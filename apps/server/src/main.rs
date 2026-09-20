use std::{
    io::{self, Read},
    path::PathBuf,
};

use anyhow::Context;
use clap::{Parser, Subcommand};
use tokio::{net::TcpListener, signal};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
use youyou_server::{
    api::build_router, audit, auth, backup, config, db, initialize, media_time, runtime::ServerLock,
};

#[derive(Debug, Parser)]
#[command(
    name = "youyou-server",
    version,
    about = "Self-hosted server for youyou"
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,

    /// TCP listen port (API and embedded admin share this port).
    #[arg(long, env = "YOUYOU_SERVER_PORT", default_value_t = 8989)]
    port: u16,

    /// Runtime directory; data lives in `<dir>/data`, media in `<dir>/media`.
    #[arg(long, env = "YOUYOU_SERVER_DIR")]
    server_dir: Option<PathBuf>,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Run a local administrative operation without starting HTTP.
    Admin {
        #[command(subcommand)]
        command: AdminCommand,
    },
    /// Create, verify, or restore a consistent database backup.
    Backup {
        #[command(subcommand)]
        command: BackupCommand,
    },
}

#[derive(Debug, Subcommand)]
enum AdminCommand {
    /// Initialize the administrator using the one-time setup token.
    Init {
        /// Read the administrator password from stdin.
        #[arg(long)]
        password_stdin: bool,
    },
}

#[derive(Debug, Subcommand)]
enum BackupCommand {
    /// Create a consistent SQLite backup and manifest.
    Create {
        /// Directory for backup artifacts; defaults to <server-dir>/data/backups.
        #[arg(long)]
        output_dir: Option<PathBuf>,
    },
    /// Verify a backup directory, its manifest, and SQLite integrity.
    Verify {
        /// Backup directory containing manifest.json and youyou.db.
        backup_dir: PathBuf,
    },
    /// Restore a verified database backup while the server is stopped.
    Restore {
        /// Backup directory containing manifest.json and youyou.db.
        backup_dir: PathBuf,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    config::load_dotenv();

    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::from_default_env())
        .with(tracing_subscriber::fmt::layer())
        .init();

    let cli = Cli::parse();
    let server_dir = config::resolve_server_dir(cli.server_dir);
    let data_dir = config::data_dir(&server_dir);
    let media_root = config::media_root(&server_dir);

    match cli.command {
        Some(Command::Admin {
            command: AdminCommand::Init { password_stdin },
        }) => run_admin_init(password_stdin, data_dir, media_root).await,
        Some(Command::Backup { command }) => run_backup(command, data_dir).await,
        None => run_server(cli.port, data_dir, media_root).await,
    }
}

async fn run_admin_init(
    password_stdin: bool,
    data_dir: PathBuf,
    media_root: PathBuf,
) -> anyhow::Result<()> {
    if !password_stdin {
        anyhow::bail!("admin init requires --password-stdin");
    }
    let mut password = String::new();
    io::stdin()
        .read_to_string(&mut password)
        .context("read administrator password from stdin")?;
    let password = password.trim_end_matches(['\r', '\n']);
    if password.is_empty() {
        anyhow::bail!("administrator password from stdin is empty");
    }

    let _lock = ServerLock::acquire(&data_dir).await?;
    let state = initialize(&data_dir, &media_root)
        .await
        .context("initialize youyou server for admin init")?;
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .context("read one-time setup token")?;
    auth::initialize_admin(
        &state.db,
        &state.setup_token_path,
        setup_token.trim(),
        password,
    )
    .await
    .context("initialize administrator")?;
    println!("administrator initialized");
    Ok(())
}

async fn run_backup(command: BackupCommand, data_dir: PathBuf) -> anyhow::Result<()> {
    match command {
        BackupCommand::Create { output_dir } => {
            let _lock = ServerLock::acquire(&data_dir).await?;
            let output_dir = output_dir.unwrap_or_else(|| data_dir.join("backups"));
            let backup_dir = backup::create(&data_dir, &output_dir).await?;
            println!("backup created: {}", backup_dir.display());
            Ok(())
        }
        BackupCommand::Verify { backup_dir } => {
            let manifest = backup::verify(&backup_dir).await?;
            println!(
                "backup verified: {} (schema {}, sha256 {})",
                backup_dir.display(),
                manifest.schema_version,
                manifest.sha256
            );
            Ok(())
        }
        BackupCommand::Restore { backup_dir } => {
            let _lock = ServerLock::acquire(&data_dir).await?;
            let safety_backup = backup::restore(&data_dir, &backup_dir).await?;
            if let Ok(pool) = db::connect(&data_dir).await {
                let _ =
                    audit::record(&pool, "admin", "backup.restore", "backup", audit::SUCCESS).await;
                pool.close().await;
            }
            if let Some(safety_backup) = safety_backup {
                println!(
                    "database restored; current database saved at {}",
                    safety_backup.display()
                );
            } else {
                println!("database restored");
            }
            Ok(())
        }
    }
}

async fn run_server(port: u16, data_dir: PathBuf, media_root: PathBuf) -> anyhow::Result<()> {
    let bind = config::bind_addr(port);
    let _lock = ServerLock::acquire(&data_dir).await?;
    let state = initialize(&data_dir, &media_root)
        .await
        .context("initialize youyou server")?;
    let listener = TcpListener::bind(bind)
        .await
        .with_context(|| format!("bind {}", bind))?;
    tracing::info!(
        address = %bind,
        data_dir = %data_dir.display(),
        media_root = %media_root.display(),
        "youyou-server started"
    );

    // 历史时间回填不放在 initialize 里：它要遍历全部 time_version=0 的行，十万条量级
    // 下会让端口迟迟不监听，容器健康检查直接判 unhealthy。回填本身是分批次、可中断
    // 续跑的，并为每条纠正过的行发普通变更事件，因此放到监听之后后台执行。
    let backfill_pool = state.db.clone();
    tokio::spawn(async move {
        match media_time::backfill(&backfill_pool).await {
            Ok(()) => tracing::info!("historical media time backfill finished"),
            Err(error) => tracing::error!(
                error = ?error,
                "historical media time backfill stopped; uncorrected rows stay pending"
            ),
        }
    });

    axum::serve(
        listener,
        build_router(state).into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await
    .context("serve HTTP requests")?;
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        signal::unix::signal(signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}

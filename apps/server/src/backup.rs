use std::{
    collections::HashSet,
    path::{Path, PathBuf},
};

use anyhow::{Context, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqlitePoolOptions},
};
use tokio::{
    fs,
    io::{AsyncReadExt, AsyncWriteExt},
};
use uuid::Uuid;

use crate::db;

const DATABASE_FILE: &str = "youyou.db";
const MANIFEST_FILE: &str = "manifest.json";
const FORMAT_VERSION: i64 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupManifest {
    pub format_version: i64,
    pub schema_version: i64,
    pub server_version: String,
    pub database_file: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub created_at: i64,
}

pub async fn create(data_dir: &Path, output_dir: &Path) -> anyhow::Result<PathBuf> {
    let pool = db::connect(data_dir).await?;
    let result = create_with_pool(&pool, output_dir).await;
    pool.close().await;
    result
}

pub async fn create_with_pool(pool: &SqlitePool, output_dir: &Path) -> anyhow::Result<PathBuf> {
    let schema_version = current_schema_version(pool).await?;
    fs::create_dir_all(output_dir)
        .await
        .with_context(|| format!("create backup directory {}", output_dir.display()))?;

    let backup_dir = output_dir.join(format!("{}-{}", db::now_millis(), Uuid::new_v4()));
    fs::create_dir(&backup_dir)
        .await
        .with_context(|| format!("create backup {}", backup_dir.display()))?;

    let result = create_inner(pool, schema_version, &backup_dir).await;
    if result.is_err() {
        let _ = fs::remove_dir_all(&backup_dir).await;
    }
    result.map(|()| backup_dir)
}

async fn create_inner(
    pool: &SqlitePool,
    schema_version: i64,
    backup_dir: &Path,
) -> anyhow::Result<()> {
    let database_path = backup_dir.join(DATABASE_FILE);
    sqlx::query("VACUUM INTO ?1")
        .bind(database_path.to_string_lossy().into_owned())
        .execute(pool)
        .await
        .context("create consistent SQLite backup")?;
    set_private_file(&database_path).await?;

    let (size_bytes, sha256) = hash_file(&database_path).await?;
    let manifest = BackupManifest {
        format_version: FORMAT_VERSION,
        schema_version,
        server_version: env!("CARGO_PKG_VERSION").to_owned(),
        database_file: DATABASE_FILE.to_owned(),
        size_bytes,
        sha256,
        created_at: db::now_millis(),
    };
    let manifest_bytes =
        serde_json::to_vec_pretty(&manifest).context("serialize backup manifest")?;
    let mut file = fs::File::create(backup_dir.join(MANIFEST_FILE))
        .await
        .context("create backup manifest")?;
    file.write_all(&manifest_bytes)
        .await
        .context("write backup manifest")?;
    file.write_all(b"\n")
        .await
        .context("finish backup manifest")?;
    set_private_file(&backup_dir.join(MANIFEST_FILE)).await?;
    Ok(())
}

pub async fn verify(backup_dir: &Path) -> anyhow::Result<BackupManifest> {
    let manifest_path = backup_dir.join(MANIFEST_FILE);
    let manifest_bytes = fs::read(&manifest_path)
        .await
        .with_context(|| format!("read backup manifest {}", manifest_path.display()))?;
    let manifest: BackupManifest =
        serde_json::from_slice(&manifest_bytes).context("parse backup manifest")?;
    if manifest.format_version != FORMAT_VERSION {
        bail!(
            "unsupported backup format {}, expected {}",
            manifest.format_version,
            FORMAT_VERSION
        );
    }
    if manifest.database_file != DATABASE_FILE {
        bail!("backup database_file must be {DATABASE_FILE}");
    }

    let database_path = backup_dir.join(DATABASE_FILE);
    let metadata = fs::metadata(&database_path)
        .await
        .with_context(|| format!("read backup database {}", database_path.display()))?;
    if metadata.len() != manifest.size_bytes {
        bail!(
            "backup database size mismatch: manifest {}, actual {}",
            manifest.size_bytes,
            metadata.len()
        );
    }
    let (size_bytes, sha256) = hash_file(&database_path).await?;
    if size_bytes != manifest.size_bytes || sha256 != manifest.sha256 {
        bail!("backup database SHA-256 does not match manifest");
    }

    let pool = open_read_only(&database_path).await?;
    let quick_check: String = sqlx::query_scalar("PRAGMA quick_check")
        .fetch_one(&pool)
        .await
        .context("run SQLite quick_check on backup")?;
    if quick_check != "ok" {
        bail!("SQLite quick_check failed: {quick_check}");
    }
    let schema_version = current_schema_version(&pool).await?;
    pool.close().await;
    if schema_version != manifest.schema_version {
        bail!(
            "backup schema mismatch: manifest {}, actual {}",
            manifest.schema_version,
            schema_version
        );
    }
    Ok(manifest)
}

pub async fn cleanup_orphan_artifacts(pool: &SqlitePool, data_dir: &Path) -> anyhow::Result<()> {
    let backup_dir = data_dir.join("backups");
    let referenced = sqlx::query_scalar::<_, String>(
        "SELECT path FROM backups WHERE status = 'succeeded' AND path IS NOT NULL",
    )
    .fetch_all(pool)
    .await?
    .into_iter()
    .filter_map(|path| {
        Path::new(&path)
            .file_name()
            .and_then(|name| name.to_str())
            .map(str::to_owned)
    })
    .collect::<HashSet<_>>();

    if let Ok(mut entries) = fs::read_dir(&backup_dir).await {
        while let Some(entry) = entries.next_entry().await? {
            let file_type = entry.file_type().await?;
            if file_type.is_dir()
                && entry
                    .file_name()
                    .to_str()
                    .is_some_and(|name| !referenced.contains(name))
            {
                let _ = fs::remove_dir_all(entry.path()).await;
            }
        }
    }

    let Ok(mut entries) = fs::read_dir(data_dir).await else {
        return Ok(());
    };
    while let Some(entry) = entries.next_entry().await? {
        let file_type = entry.file_type().await?;
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if file_type.is_file() && name.starts_with(&format!(".{DATABASE_FILE}.restore-")) {
            let _ = fs::remove_file(entry.path()).await;
        }
    }
    Ok(())
}

pub async fn restore(data_dir: &Path, backup_dir: &Path) -> anyhow::Result<Option<PathBuf>> {
    let manifest = verify(backup_dir).await?;
    if manifest.server_version != env!("CARGO_PKG_VERSION") {
        bail!(
            "backup was created by youyou-server {}, current binary is {}",
            manifest.server_version,
            env!("CARGO_PKG_VERSION")
        );
    }

    fs::create_dir_all(data_dir)
        .await
        .with_context(|| format!("create data directory {}", data_dir.display()))?;
    let current_database = data_dir.join(DATABASE_FILE);
    let safety_backup = if fs::try_exists(&current_database).await.unwrap_or(false) {
        Some(create(data_dir, &data_dir.join("backups")).await?)
    } else {
        None
    };

    let temporary_database = data_dir.join(format!(".{DATABASE_FILE}.restore-{}", Uuid::new_v4()));
    let source_database = backup_dir.join(DATABASE_FILE);
    if let Err(error) = fs::copy(&source_database, &temporary_database).await {
        let _ = fs::remove_file(&temporary_database).await;
        return Err(error)
            .with_context(|| format!("stage restored database {}", temporary_database.display()));
    }
    set_private_file(&temporary_database).await?;

    for suffix in ["-wal", "-shm"] {
        let _ = fs::remove_file(current_database.with_extension(format!("db{suffix}"))).await;
    }
    if let Err(error) = fs::rename(&temporary_database, &current_database).await {
        let _ = fs::remove_file(&temporary_database).await;
        return Err(error).with_context(|| {
            format!(
                "replace database {} with restored backup",
                current_database.display()
            )
        });
    }
    Ok(safety_backup)
}

async fn current_schema_version(pool: &SqlitePool) -> anyhow::Result<i64> {
    sqlx::query_scalar::<_, i64>("SELECT COALESCE(MAX(version), 0) FROM _sqlx_migrations")
        .fetch_one(pool)
        .await
        .context("read SQLite schema version")
}

async fn open_read_only(path: &Path) -> anyhow::Result<SqlitePool> {
    SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(
            SqliteConnectOptions::new()
                .filename(path)
                .read_only(true)
                .foreign_keys(true),
        )
        .await
        .with_context(|| format!("open backup database {}", path.display()))
}

async fn hash_file(path: &Path) -> anyhow::Result<(u64, String)> {
    let mut file = fs::File::open(path)
        .await
        .with_context(|| format!("open file for SHA-256 {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut size = 0_u64;
    let mut buffer = vec![0_u8; 1024 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .await
            .with_context(|| format!("read file for SHA-256 {}", path.display()))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
        size = size
            .checked_add(u64::try_from(read).expect("read length fits in u64"))
            .context("backup file size overflow")?;
    }
    Ok((size, hex::encode(hasher.finalize())))
}

async fn set_private_file(path: &Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    {
        let permissions = std::os::unix::fs::PermissionsExt::from_mode(0o600);
        fs::set_permissions(path, permissions)
            .await
            .with_context(|| format!("restrict permissions for {}", path.display()))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[tokio::test]
    async fn creates_verifies_and_restores_a_consistent_database() {
        let data_dir = tempdir().expect("data directory");
        let pool = db::connect(data_dir.path()).await.expect("database");
        sqlx::query("CREATE TABLE backup_fixture (value TEXT NOT NULL)")
            .execute(&pool)
            .await
            .expect("fixture table");
        sqlx::query("INSERT INTO backup_fixture (value) VALUES ('before')")
            .execute(&pool)
            .await
            .expect("fixture value");
        pool.close().await;

        let output_dir = data_dir.path().join("backups");
        let backup_dir = create(data_dir.path(), &output_dir)
            .await
            .expect("create backup");
        let manifest = verify(&backup_dir).await.expect("verify backup");
        assert_eq!(manifest.database_file, DATABASE_FILE);
        assert!(manifest.size_bytes > 0);

        let pool = db::connect(data_dir.path()).await.expect("database");
        sqlx::query("UPDATE backup_fixture SET value = 'after'")
            .execute(&pool)
            .await
            .expect("change fixture");
        pool.close().await;

        let safety_backup = restore(data_dir.path(), &backup_dir)
            .await
            .expect("restore backup");
        assert!(safety_backup.is_some());

        let pool = db::connect(data_dir.path())
            .await
            .expect("restored database");
        let value: String = sqlx::query_scalar("SELECT value FROM backup_fixture")
            .fetch_one(&pool)
            .await
            .expect("restored value");
        assert_eq!(value, "before");
    }
}

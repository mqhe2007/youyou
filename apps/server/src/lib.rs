pub mod api;
pub mod audit;
pub mod auth;
pub mod backup;
pub mod config;
pub mod db;
pub mod error;
pub mod heif;
pub mod live_photo;
pub mod media_format;
pub mod media_library;
pub mod metadata;
pub mod runtime;
pub mod scan;
pub mod storage;
pub mod sync;
pub mod trash;
pub mod uploads;
pub mod users;

use std::{path::Path, sync::Arc};

use anyhow::Context;
use tokio::{
    process::Command,
    sync::{Mutex, Notify},
};

use crate::{
    api::AppState,
    db::{connect, ensure_local_storage, recover_running_jobs, server_instance_id},
    storage::{LocalFilesystemStorageDriver, StorageDriver, StorageRuntime},
};

pub async fn initialize(
    data_dir: impl AsRef<Path>,
    media_root: impl AsRef<Path>,
) -> anyhow::Result<AppState> {
    let data_dir = data_dir.as_ref();
    let default_media_root = media_root.as_ref();
    let tmp_dir = data_dir.join("tmp");
    tokio::fs::create_dir_all(&tmp_dir)
        .await
        .with_context(|| format!("create temporary directory {}", tmp_dir.display()))?;
    ensure_media_tools().await?;
    let db = connect(data_dir).await?;
    let media_root = db::local_storage_root(&db)
        .await?
        .unwrap_or_else(|| default_media_root.to_owned());
    tokio::fs::create_dir_all(&media_root)
        .await
        .with_context(|| format!("create media root {}", media_root.display()))?;
    let storage_driver = Arc::new(LocalFilesystemStorageDriver::new(&media_root)?);
    let server_instance_id = server_instance_id(&db).await?;
    recover_running_jobs(&db).await?;
    if let Err(error) = storage_driver.cleanup_orphan_temporary_files().await {
        tracing::error!(error = ?error, "temporary storage artifact cleanup failed during startup");
    }
    if let Err(error) = backup::cleanup_orphan_artifacts(&db, data_dir).await {
        tracing::error!(error = ?error, "backup artifact cleanup failed during startup");
    }
    match crate::heif::tool() {
        Some(tool) => tracing::info!(tool = ?tool, "HEIC/HEIF 解码器可用"),
        None => tracing::warn!(
            "未找到 HEIC/HEIF 解码器（heif-convert/sips）：HEIC 可被索引，缩略图将使用占位图"
        ),
    }
    let health = storage_driver.health_check().await?;
    ensure_local_storage(&db, storage_driver.root(), health.read_only).await?;
    let setup_token_path = auth::prepare_setup_token(data_dir, &db).await?;

    // 实况照片历史收敛（FR-7）：对已分别索引的成对文件补配对并清理失效配对。
    // 只做库内可判定的部分，有界且幂等；失败不阻断启动（下次启动或扫描继续收敛）。
    match crate::live_photo::backfill_pairs(&db).await {
        Ok(0) => {}
        Ok(count) => tracing::info!(count, "实况照片历史配对已收敛"),
        Err(error) => tracing::error!(error = ?error, "实况照片历史配对收敛失败"),
    }

    // 回收站：先校验配置（同文件系统 + 位于扫描树之外），再恢复未完成文件操作，
    // 最后补一次到期清理。扫描任务在 HTTP 服务起来之后才可能被触发，因此
    // 「先恢复操作、再允许扫描」在这里得到保证。
    let server_dir = config::infer_server_dir(data_dir);
    let trash_config = trash::prepare(&server_dir, storage_driver.root()).await;
    let trash_runtime = trash::TrashRuntime::new(trash_config);
    if let Err(error) =
        trash::recover_pending_operations(&db, storage_driver.as_ref(), &trash_runtime).await
    {
        tracing::error!(error = ?error, "启动恢复未完成文件操作失败");
    }
    if let Err(error) = trash::run_expiry_cleanup(&db, &trash_runtime).await {
        tracing::error!(error = ?error, "启动回收站到期清理失败");
    }

    Ok(AppState {
        db,
        storage: Arc::new(StorageRuntime::new(storage_driver)),
        data_dir: data_dir.to_owned(),
        server_dir,
        trash: trash_runtime,
        tmp_dir,
        setup_token_path,
        server_instance_id,
        job_notify: Arc::new(Notify::new()),
        storage_update_lock: Arc::new(Mutex::new(())),
        auth_rate_limiter: auth::AuthRateLimiter::default(),
    })
}

async fn ensure_media_tools() -> anyhow::Result<()> {
    for command in ["ffmpeg", "ffprobe"] {
        let output = Command::new(command)
            .arg("-version")
            .output()
            .await
            .with_context(|| format!("{command} is required for media metadata and thumbnails"))?;
        if !output.status.success() {
            anyhow::bail!("{command} failed its startup self-check");
        }
    }
    Ok(())
}

pub mod media_time;

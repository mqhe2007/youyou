use std::{collections::HashSet, io::Cursor, sync::Arc};

use anyhow::Context;
use futures_util::StreamExt;
use image::ImageReader;
use mime_guess::MimeGuess;
use serde::Serialize;
use sqlx::{FromRow, SqlitePool};
use tokio::{
    process::Command,
    time::{Duration, timeout},
};
use uuid::Uuid;

use crate::{
    db::now_millis,
    metadata,
    storage::{LocalFilesystemStorageDriver, StorageDriver, StorageEntry, StorageRuntime},
    sync,
};

#[derive(Debug, Default, Clone, Serialize)]
pub struct ScanSummary {
    pub discovered: u64,
    pub indexed: u64,
    pub failed: u64,
    pub cancelled: bool,
}

#[derive(Debug, FromRow)]
struct ExistingLocation {
    media_asset_id: String,
    size: i64,
    modified_at: Option<i64>,
    hash_state: String,
    owner_user_id: Option<i64>,
}

struct PendingIndex {
    pending_media_id: Option<String>,
    media_id_hint: Option<String>,
    reconcile_media_id: Option<String>,
    previous_owner: Option<Option<i64>>,
    skip_hash: bool,
}

#[derive(Debug, Default)]
struct ExtractedMetadata {
    duration_ms: Option<i64>,
    width: Option<i64>,
    height: Option<i64>,
    taken_at: Option<i64>,
    video_codec: Option<String>,
}

pub async fn scan_directory(
    pool: &SqlitePool,
    storage: Arc<StorageRuntime>,
    job_id: &str,
) -> anyhow::Result<ScanSummary> {
    scan_directory_with_lease(pool, storage.snapshot().await, job_id, None).await
}

pub(crate) async fn scan_directory_with_lease(
    pool: &SqlitePool,
    storage: Arc<LocalFilesystemStorageDriver>,
    job_id: &str,
    lease_owner: Option<&str>,
) -> anyhow::Result<ScanSummary> {
    let mut summary = ScanSummary::default();
    let checkpoint =
        sqlx::query_scalar::<_, Option<String>>("SELECT checkpoint FROM jobs WHERE id = ?1")
            .bind(job_id)
            .fetch_optional(pool)
            .await?
            .flatten();
    let checkpoint: Option<serde_json::Value> = checkpoint
        .map(|value| serde_json::from_str(&value))
        .transpose()?;
    let scope_path = LocalFilesystemStorageDriver::normalize_relative(
        checkpoint
            .as_ref()
            .and_then(|value| value.get("scopePath"))
            .and_then(|value| value.as_str())
            .unwrap_or(""),
    )?;
    let mut directories = vec![scope_path.clone()];
    let mut seen_directories = HashSet::new();
    let mut seen_paths = HashSet::new();

    while let Some(directory) = directories.pop() {
        if is_cancel_requested(pool, job_id).await? {
            summary.cancelled = true;
            return Ok(summary);
        }
        if !seen_directories.insert(directory.clone()) {
            continue;
        }
        let mut entries = storage
            .list(&directory)
            .await
            .with_context(|| format!("list directory {directory:?}"))?;
        while let Some(entry) = entries.next().await {
            let entry = entry?;
            if entry.is_directory {
                directories.push(entry.path);
                continue;
            }
            if !is_supported_media(&entry) {
                continue;
            }
            seen_paths.insert(entry.path.clone());

            if is_cancel_requested(pool, job_id).await? {
                summary.cancelled = true;
                return Ok(summary);
            }
            summary.discovered += 1;
            match index_media(pool, &storage, &entry).await {
                Ok(()) => summary.indexed += 1,
                Err(error) => {
                    summary.failed += 1;
                    tracing::warn!(
                        job_id,
                        path = %entry.path,
                        error = ?error,
                        "failed to index media"
                    );
                }
            }
            let checkpoint = serde_json::json!({
                "version": 1,
                "kind": "scan",
                "scopePath": scope_path,
                "lastPath": entry.path,
            })
            .to_string();
            let progress_saved = update_scan_progress(
                pool,
                job_id,
                summary.discovered,
                summary.indexed,
                &checkpoint,
                lease_owner,
            )
            .await?;
            if lease_owner.is_some() && !progress_saved {
                summary.cancelled = true;
                return Ok(summary);
            }
        }
    }

    if is_cancel_requested(pool, job_id).await? {
        summary.cancelled = true;
        return Ok(summary);
    }
    reconcile_missing_locations(pool, &seen_paths, &scope_path).await?;
    if let Some(lease_owner) = lease_owner {
        let checkpoint = serde_json::json!({
            "version": 1,
            "kind": "scan",
            "scopePath": scope_path,
            "phase": "completed",
        })
        .to_string();
        if !update_scan_checkpoint(pool, job_id, &checkpoint, lease_owner).await? {
            return Err(anyhow::anyhow!("scan job lease was lost"));
        }
    }
    Ok(summary)
}

async fn reconcile_missing_locations(
    pool: &SqlitePool,
    seen_paths: &HashSet<String>,
    scope_path: &str,
) -> anyhow::Result<()> {
    let scope_prefix = format!("{scope_path}/");
    // 未完成的文件操作（删除/恢复）占用的路径本轮不参与对账，避免与正在进行的
    // 文件移动互相覆盖；操作落地后由下一轮扫描接管。
    let pending_paths =
        sqlx::query_scalar::<_, String>("SELECT normalized_path FROM media_pending_paths")
            .fetch_all(pool)
            .await?
            .into_iter()
            .collect::<HashSet<String>>();
    let locations = sqlx::query_as::<_, (String, String)>(
        "SELECT id, media_asset_id FROM media_locations WHERE storage_id = 'local'",
    )
    .fetch_all(pool)
    .await?;
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    for (location_id, media_asset_id) in locations {
        let path = sqlx::query_scalar::<_, String>(
            "SELECT normalized_path FROM media_locations WHERE id = ?1",
        )
        .bind(&location_id)
        .fetch_optional(&mut *transaction)
        .await?;
        let Some(path) = path else {
            continue;
        };
        if (!scope_path.is_empty() && !path.starts_with(&scope_prefix))
            || seen_paths.contains(&path)
            || pending_paths.contains(&path)
        {
            continue;
        }
        sqlx::query("DELETE FROM media_locations WHERE id = ?1")
            .bind(&location_id)
            .execute(&mut *transaction)
            .await?;
        let remaining = sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_locations WHERE media_asset_id = ?1",
        )
        .bind(&media_asset_id)
        .fetch_one(&mut *transaction)
        .await?;
        if remaining != 0 {
            continue;
        }
        metadata::tombstone_media_tx(
            &mut transaction,
            &media_asset_id,
            "source_missing",
            now_millis(),
            revision,
        )
        .await?;
    }
    transaction.commit().await?;
    Ok(())
}

async fn update_scan_progress(
    pool: &SqlitePool,
    job_id: &str,
    discovered: u64,
    indexed: u64,
    checkpoint: &str,
    lease_owner: Option<&str>,
) -> Result<bool, sqlx::Error> {
    let now = now_millis();
    let message = format!("discovered={discovered}, indexed={indexed}");
    let result = if let Some(lease_owner) = lease_owner {
        sqlx::query(
            "UPDATE jobs SET current = ?1, message = ?2, checkpoint = ?3, updated_at = ?4, heartbeat_at = ?4, lease_until = ?4 + 60000 WHERE id = ?5 AND status = 'running' AND lease_owner = ?6",
        )
        .bind(i64::try_from(indexed).unwrap_or(i64::MAX))
        .bind(message)
        .bind(checkpoint)
        .bind(now)
        .bind(job_id)
        .bind(lease_owner)
        .execute(pool)
        .await?
    } else {
        sqlx::query(
            "UPDATE jobs SET current = ?1, message = ?2, checkpoint = ?3, updated_at = ?4, heartbeat_at = ?4, lease_until = ?4 + 60000 WHERE id = ?5",
        )
        .bind(i64::try_from(indexed).unwrap_or(i64::MAX))
        .bind(message)
        .bind(checkpoint)
        .bind(now)
        .bind(job_id)
        .execute(pool)
        .await?
    };
    Ok(result.rows_affected() == 1)
}

async fn update_scan_checkpoint(
    pool: &SqlitePool,
    job_id: &str,
    checkpoint: &str,
    lease_owner: &str,
) -> Result<bool, sqlx::Error> {
    let now = now_millis();
    let result = sqlx::query(
        "UPDATE jobs SET checkpoint = ?1, updated_at = ?2, heartbeat_at = ?2, lease_until = ?2 + 60000 WHERE id = ?3 AND status = 'running' AND lease_owner = ?4",
    )
    .bind(checkpoint)
    .bind(now)
    .bind(job_id)
    .bind(lease_owner)
    .execute(pool)
    .await?;
    Ok(result.rows_affected() == 1)
}

async fn is_cancel_requested(pool: &SqlitePool, job_id: &str) -> Result<bool, sqlx::Error> {
    Ok(
        sqlx::query_scalar::<_, i64>("SELECT cancel_requested FROM jobs WHERE id = ?1")
            .bind(job_id)
            .fetch_optional(pool)
            .await?
            .unwrap_or_default()
            == 1,
    )
}

async fn prepare_pending_index(
    pool: &SqlitePool,
    entry: &StorageEntry,
    mime_type: &Option<String>,
    is_video: bool,
    owner_user_id: Option<i64>,
) -> anyhow::Result<PendingIndex> {
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let previous_location = sqlx::query_as::<_, ExistingLocation>(
        r#"
        SELECT l.media_asset_id, l.size, l.modified_at, l.hash_state, a.owner_user_id
        FROM media_locations l
        INNER JOIN media_assets a ON a.id = l.media_asset_id
        WHERE l.storage_id = 'local' AND l.normalized_path = ?1
        "#,
    )
    .bind(&entry.path)
    .fetch_optional(&mut *transaction)
    .await?;

    let pending_media_id = if let Some(previous) = previous_location.as_ref() {
        let metadata_changed = previous.size
            != i64::try_from(entry.size.unwrap_or_default()).unwrap_or(i64::MAX)
            || previous.modified_at != entry.modified_at
            || previous.hash_state != "verified";
        if metadata_changed {
            sqlx::query(
                r#"
                UPDATE media_locations
                SET file_name = ?1, size = ?2, modified_at = ?3,
                    hash_state = 'pending', observed_size = ?2, observed_mtime = ?3,
                    updated_at = ?4
                WHERE storage_id = 'local' AND normalized_path = ?5
                "#,
            )
            .bind(&entry.name)
            .bind(i64::try_from(entry.size.unwrap_or_default()).unwrap_or(i64::MAX))
            .bind(entry.modified_at)
            .bind(now)
            .bind(&entry.path)
            .execute(&mut *transaction)
            .await?;
        }
        None
    } else {
        let media_id = Uuid::new_v4().to_string();
        sqlx::query(
            r#"
            INSERT INTO media_assets
                (id, blob_id, identity_state, name, mime_type, is_video, sort_at, owner_user_id, created_at, updated_at)
            VALUES (?1, NULL, 'pending', ?2, ?3, ?4, ?5, ?6, ?7, ?7)
            "#,
        )
        .bind(&media_id)
        .bind(&entry.name)
        .bind(mime_type)
        .bind(if is_video { 1_i64 } else { 0_i64 })
        .bind(entry.modified_at)
        .bind(owner_user_id)
        .bind(now)
        .execute(&mut *transaction)
        .await?;
        sqlx::query(
            r#"
            INSERT INTO media_locations
                (id, media_asset_id, storage_id, normalized_path, file_name, size,
                 modified_at, hash_state, observed_size, observed_mtime, created_at, updated_at)
            VALUES (?1, ?2, 'local', ?3, ?4, ?5, ?6, 'pending', ?5, ?6, ?7, ?7)
            "#,
        )
        .bind(Uuid::new_v4().to_string())
        .bind(&media_id)
        .bind(&entry.path)
        .bind(&entry.name)
        .bind(i64::try_from(entry.size.unwrap_or_default()).unwrap_or(i64::MAX))
        .bind(entry.modified_at)
        .bind(now)
        .execute(&mut *transaction)
        .await?;
        Some(media_id)
    };

    transaction.commit().await?;
    Ok(PendingIndex {
        pending_media_id,
        media_id_hint: previous_location
            .as_ref()
            .map(|previous| previous.media_asset_id.clone()),
        reconcile_media_id: previous_location.as_ref().and_then(|previous| {
            let metadata_changed = previous.size
                != i64::try_from(entry.size.unwrap_or_default()).unwrap_or(i64::MAX)
                || previous.modified_at != entry.modified_at
                || previous.hash_state != "verified";
            (metadata_changed && previous.hash_state == "verified")
                .then(|| previous.media_asset_id.clone())
        }),
        previous_owner: previous_location
            .as_ref()
            .map(|previous| previous.owner_user_id),
        skip_hash: previous_location.as_ref().is_some_and(|previous| {
            previous.size == i64::try_from(entry.size.unwrap_or_default()).unwrap_or(i64::MAX)
                && previous.modified_at == entry.modified_at
                && previous.hash_state == "verified"
        }),
    })
}

async fn mark_location_hash_state(
    pool: &SqlitePool,
    path: &str,
    state: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "UPDATE media_locations SET hash_state = ?1, updated_at = ?2 WHERE storage_id = 'local' AND normalized_path = ?3",
    )
    .bind(state)
    .bind(now_millis())
    .bind(path)
    .execute(pool)
    .await?;
    Ok(())
}

pub(crate) async fn index_media(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    entry: &StorageEntry,
) -> anyhow::Result<()> {
    index_media_with_metadata(pool, storage, entry, None, None).await
}

pub(crate) async fn index_media_with_metadata(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    entry: &StorageEntry,
    mime_type_override: Option<Option<&str>>,
    taken_at_override: Option<Option<i64>>,
) -> anyhow::Result<()> {
    index_media_with_time(
        pool,
        storage,
        entry,
        mime_type_override,
        taken_at_override,
        None,
        None,
    )
    .await
}

pub(crate) async fn index_media_with_time(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    entry: &StorageEntry,
    mime_type_override: Option<Option<&str>>,
    taken_at_override: Option<Option<i64>>,
    timeline: Option<crate::media_time::MediaTime>,
    original_name: Option<&str>,
) -> anyhow::Result<()> {
    let mime_type = MimeGuess::from_path(&entry.path)
        .first_raw()
        .map(str::to_owned);
    let effective_mime_type = mime_type_override
        .map(|value| value.map(str::to_owned))
        .unwrap_or_else(|| mime_type.clone());
    let metadata_override_requested =
        mime_type_override.is_some() || taken_at_override.is_some() || timeline.is_some();
    // 删除/恢复正在处理的路径跳过；显式覆盖（上传、元数据修正）则报错让调用方重试，
    // 避免静默丢弃用户可见的写入结果。
    if crate::trash::path_has_pending_operation(pool, &entry.path).await? {
        if metadata_override_requested {
            anyhow::bail!(
                "path {} has a pending delete or restore operation; retry later",
                entry.path
            );
        }
        return Ok(());
    }
    let is_video = mime_type
        .as_deref()
        .map(|mime| mime.starts_with("video/"))
        .unwrap_or_else(|| {
            entry
                .name
                .rsplit_once('.')
                .map(|(_, extension)| {
                    matches!(
                        extension.to_ascii_lowercase().as_str(),
                        "avi" | "mkv" | "mov" | "mp4" | "webm"
                    )
                })
                .unwrap_or(false)
        });
    let owner_user_id = crate::users::resolve_owner_for_path(pool, &entry.path).await?;
    let pending =
        prepare_pending_index(pool, entry, &effective_mime_type, is_video, owner_user_id).await?;
    let owner_changed = pending
        .previous_owner
        .is_some_and(|previous| previous != owner_user_id);
    let needs_time_verification = if let Some(id) = pending.media_id_hint.as_deref() {
        sqlx::query_scalar::<_, i64>("SELECT time_version FROM media_assets WHERE id=?1")
            .bind(id)
            .fetch_optional(pool)
            .await?
            .is_some_and(|v| v != 1)
    } else {
        true
    };
    if pending.skip_hash
        && !metadata_override_requested
        && !owner_changed
        && !needs_time_verification
    {
        return Ok(());
    }
    let (content_hash, content_size) = match storage.hash_sha256(&entry.path).await {
        Ok(value) => value,
        Err(error) => {
            mark_location_hash_state(pool, &entry.path, "failed").await?;
            if let Some(media_id) = pending.reconcile_media_id.as_deref() {
                emit_reconcile_change(pool, media_id).await?;
            }
            return Err(error.into());
        }
    };
    let current_stat = match storage.stat(&entry.path).await {
        Ok(stat) => stat,
        Err(error) => {
            mark_location_hash_state(pool, &entry.path, "failed").await?;
            if let Some(media_id) = pending.reconcile_media_id.as_deref() {
                emit_reconcile_change(pool, media_id).await?;
            }
            return Err(error.into());
        }
    };
    if entry.size != Some(current_stat.size) || entry.modified_at != current_stat.modified_at {
        mark_location_hash_state(pool, &entry.path, "stale").await?;
        if let Some(media_id) = pending.reconcile_media_id.as_deref() {
            emit_reconcile_change(pool, media_id).await?;
        }
        return Err(anyhow::anyhow!(
            "media changed while hashing: {}",
            entry.path
        ));
    }
    let extracted = if is_video {
        match extract_video_metadata(storage, &entry.path).await {
            Ok(metadata) => metadata,
            Err(error) => {
                tracing::debug!(
                    path = %entry.path,
                    error = ?error,
                    "video metadata extraction failed"
                );
                mark_location_hash_state(pool, &entry.path, "failed").await?;
                if let Some(media_id) = pending.reconcile_media_id.as_deref() {
                    emit_reconcile_change(pool, media_id).await?;
                }
                return Err(error);
            }
        }
    } else {
        match extract_image_metadata(storage, &entry.path).await {
            Ok(metadata) => metadata,
            Err(error) => {
                tracing::debug!(
                    path = %entry.path,
                    error = ?error,
                    "media metadata extraction failed"
                );
                ExtractedMetadata::default()
            }
        }
    };
    let content_size = i64::try_from(content_size).context("media file is too large")?;
    let modified_at = entry.modified_at;
    let taken_at = crate::media_time::valid(extracted.taken_at).or_else(|| {
        crate::media_time::valid(taken_at_override.flatten())
            .filter(|_| timeline.as_ref().is_none_or(|v| v.source != "legacy"))
    });
    let is_upload = original_name.is_some();
    let original_name = original_name.unwrap_or(&entry.name);

    let now = now_millis();

    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    let existing_location = sqlx::query_as::<_, ExistingLocation>(
        r#"
        SELECT l.media_asset_id, l.size, l.modified_at, l.hash_state, a.owner_user_id
        FROM media_locations l
        INNER JOIN media_assets a ON a.id = l.media_asset_id
        WHERE l.storage_id = 'local' AND l.normalized_path = ?1
        "#,
    )
    .bind(&entry.path)
    .fetch_optional(&mut *transaction)
    .await?;

    let content_blob_id = sqlx::query_scalar::<_, String>(
        "SELECT id FROM content_blobs WHERE hash_algorithm = 'sha256' AND content_hash = ?1",
    )
    .bind(&content_hash)
    .fetch_optional(&mut *transaction)
    .await?;

    let content_blob_id = match content_blob_id {
        Some(id) => id,
        None => {
            let id = Uuid::new_v4().to_string();
            sqlx::query(
                r#"
                INSERT OR IGNORE INTO content_blobs
                    (id, hash_algorithm, content_hash, size, created_at)
                VALUES (?1, 'sha256', ?2, ?3, ?4)
                "#,
            )
            .bind(&id)
            .bind(&content_hash)
            .bind(content_size)
            .bind(now)
            .execute(&mut *transaction)
            .await?;
            sqlx::query_scalar::<_, String>(
                "SELECT id FROM content_blobs WHERE hash_algorithm = 'sha256' AND content_hash = ?1",
            )
            .bind(&content_hash)
            .fetch_one(&mut *transaction)
            .await?
        }
    };

    let fallback_media_id = if owner_changed {
        // 跨用户改属：不能复用旧用户的媒体身份，回落到本次预建的 pending 资产。
        pending.pending_media_id.clone()
    } else {
        pending
            .pending_media_id
            .clone()
            .or_else(|| pending.media_id_hint.clone())
    };
    let media_id = sqlx::query_scalar::<_, String>(
        r#"
        SELECT id
        FROM media_assets
        WHERE blob_id = ?1 AND owner_user_id IS ?2
        ORDER BY CASE WHEN identity_state = 'verified' THEN 0 ELSE 1 END,
                 updated_at DESC, id ASC
        LIMIT 1
        "#,
    )
    .bind(&content_blob_id)
    .bind(owner_user_id)
    .fetch_optional(&mut *transaction)
    .await?
    .unwrap_or_else(|| fallback_media_id.unwrap_or_else(|| Uuid::new_v4().to_string()));

    let media_exists = sqlx::query_scalar::<_, i64>("SELECT 1 FROM media_assets WHERE id = ?1")
        .bind(&media_id)
        .fetch_optional(&mut *transaction)
        .await?
        .is_some();

    let old_time = sqlx::query_as::<_,(Option<i64>,String,Option<i64>,i64,Option<String>,Option<String>)>(
        "SELECT sort_at,sort_source,taken_at,time_version,original_name,blob_id FROM media_assets WHERE id=?1")
        .bind(&media_id).fetch_optional(&mut *transaction).await?;
    let same_content = old_time
        .as_ref()
        .is_some_and(|v| v.5.as_deref() == Some(content_blob_id.as_str()));
    let original_name = old_time
        .as_ref()
        .filter(|_| same_content)
        .and_then(|v| v.4.as_deref())
        .unwrap_or(original_name);
    let candidate = crate::media_time::resolve(
        original_name,
        taken_at,
        modified_at.filter(|_| {
            !(is_upload || (same_content && old_time.as_ref().is_some_and(|v| v.3 != 0)))
        }),
    );
    let candidate = timeline
        .map(|t| crate::media_time::choose(t, candidate.clone()))
        .unwrap_or(candidate);
    let time = old_time
        .as_ref()
        .filter(|v| v.3 != 0 && same_content)
        .map(|v| {
            crate::media_time::choose(
                crate::media_time::MediaTime {
                    at: v.0,
                    source: v.1.clone(),
                },
                candidate.clone(),
            )
        })
        .unwrap_or(candidate);
    let sort_at = time.at;
    let taken_at = if time.source == "capture" {
        time.at
    } else {
        None
    };
    let time_changed = old_time
        .as_ref()
        .is_none_or(|v| v.0 != sort_at || v.1 != time.source || v.3 != 1 || v.2 != taken_at);
    let location_changed = existing_location
        .as_ref()
        .map(|location| {
            location.media_asset_id != media_id
                || location.size != content_size
                || location.modified_at != modified_at
                || location.hash_state != "verified"
        })
        .unwrap_or(true);

    if media_exists {
        if location_changed || metadata_override_requested || time_changed {
            sqlx::query(
                r#"
                UPDATE media_assets
                SET blob_id = ?1, identity_state = 'verified', name = ?2,
                    mime_type = ?3, is_video = ?4,
                    duration_ms = ?5, video_codec = ?6,
                    width = ?7, height = ?8,
                    taken_at = ?9, sort_at = ?10,
                    version = CASE
                        WHEN identity_state = 'pending' AND ?13 = 0 THEN version
                        ELSE version + 1
                    END,
                    updated_at = ?11
                WHERE id = ?12
                "#,
            )
            .bind(&content_blob_id)
            .bind(&entry.name)
            .bind(&effective_mime_type)
            .bind(if is_video { 1_i64 } else { 0_i64 })
            .bind(extracted.duration_ms)
            .bind(&extracted.video_codec)
            .bind(extracted.width)
            .bind(extracted.height)
            .bind(taken_at)
            .bind(sort_at)
            .bind(now)
            .bind(&media_id)
            .bind(if metadata_override_requested {
                1_i64
            } else {
                0_i64
            })
            .execute(&mut *transaction)
            .await?;
        }
    } else {
        sqlx::query(
            r#"
            INSERT INTO media_assets
                (id, blob_id, identity_state, name, mime_type, is_video,
                 duration_ms, video_codec, width, height, taken_at, sort_at,
                 owner_user_id, version, created_at, updated_at)
            VALUES (?1, ?2, 'verified', ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?14)
            "#,
        )
        .bind(&media_id)
        .bind(&content_blob_id)
        .bind(&entry.name)
        .bind(&effective_mime_type)
        .bind(if is_video { 1_i64 } else { 0_i64 })
        .bind(extracted.duration_ms)
        .bind(&extracted.video_codec)
        .bind(extracted.width)
        .bind(extracted.height)
        .bind(taken_at)
        .bind(sort_at)
        .bind(owner_user_id)
        .bind(if metadata_override_requested {
            2_i64
        } else {
            1_i64
        })
        .bind(now)
        .execute(&mut *transaction)
        .await?;
    }

    if existing_location.is_some() {
        if location_changed {
            sqlx::query(
                r#"
                UPDATE media_locations
                SET media_asset_id = ?1, file_name = ?2, size = ?3, modified_at = ?4,
                    hash_state = 'verified', observed_size = ?3, observed_mtime = ?4,
                    updated_at = ?5
                WHERE storage_id = 'local' AND normalized_path = ?6
                "#,
            )
            .bind(&media_id)
            .bind(&entry.name)
            .bind(content_size)
            .bind(modified_at)
            .bind(now)
            .bind(&entry.path)
            .execute(&mut *transaction)
            .await?;
        }
    } else {
        sqlx::query(
            r#"
            INSERT INTO media_locations
                (id, media_asset_id, storage_id, normalized_path, file_name, size,
                 modified_at, hash_state, observed_size, observed_mtime, created_at, updated_at)
            VALUES (?1, ?2, 'local', ?3, ?4, ?5, ?6, 'verified', ?5, ?6, ?7, ?7)
            "#,
        )
        .bind(Uuid::new_v4().to_string())
        .bind(&media_id)
        .bind(&entry.path)
        .bind(&entry.name)
        .bind(content_size)
        .bind(modified_at)
        .bind(now)
        .execute(&mut *transaction)
        .await?;
    }

    if let Some(previous) = existing_location.as_ref() {
        if previous.media_asset_id != media_id {
            let still_referenced = sqlx::query_scalar::<_, i64>(
                r#"
                SELECT 1
                FROM media_locations
                WHERE media_asset_id = ?1
                  AND NOT (storage_id = 'local' AND normalized_path = ?2)
                LIMIT 1
                "#,
            )
            .bind(&previous.media_asset_id)
            .bind(&entry.path)
            .fetch_optional(&mut *transaction)
            .await?
            .is_some();
            if !still_referenced {
                if owner_changed {
                    // 跨用户改属：旧用户的标签关系不随内容迁移到新用户，
                    // 旧资产按其属主的变更流下墓碑，由新资产承接该路径。
                    metadata::tombstone_media_tx(
                        &mut transaction,
                        &previous.media_asset_id,
                        "library_rebound",
                        now,
                        revision,
                    )
                    .await?;
                } else {
                    metadata::move_media_relations_tx(
                        &mut transaction,
                        &previous.media_asset_id,
                        &media_id,
                        now,
                        revision,
                    )
                    .await?;
                    metadata::tombstone_media_tx(
                        &mut transaction,
                        &previous.media_asset_id,
                        "source_content_changed",
                        now,
                        revision,
                    )
                    .await?;
                }
            }
        }
    }

    if let Some(pending_media_id) = pending.pending_media_id.as_deref()
        && pending_media_id != media_id
    {
        sqlx::query(
            r#"
            DELETE FROM media_assets
            WHERE id = ?1
              AND identity_state = 'pending'
              AND NOT EXISTS (
                  SELECT 1 FROM media_locations WHERE media_asset_id = ?1
              )
            "#,
        )
        .bind(pending_media_id)
        .execute(&mut *transaction)
        .await?;
    }

    sqlx::query(
        "UPDATE media_assets SET sort_source=?1,time_version=1,original_name=?2 WHERE id=?3",
    )
    .bind(&time.source)
    .bind(original_name)
    .bind(&media_id)
    .execute(&mut *transaction)
    .await?;
    if location_changed || metadata_override_requested || time_changed {
        let media_version =
            sqlx::query_scalar::<_, i64>("SELECT version FROM media_assets WHERE id = ?1")
                .bind(&media_id)
                .fetch_one(&mut *transaction)
                .await?;
        let payload = serde_json::json!({
            "id": media_id,
            "name": entry.name,
            "path": entry.path,
            "size": content_size,
            "contentHash": content_hash,
            "mimeType": effective_mime_type,
            "isVideo": is_video,
            "storageId": "local",
            "identityState": "verified",
            "hashState": "verified",
            "durationMs": extracted.duration_ms,
            "videoCodec": extracted.video_codec,
            "width": extracted.width,
            "height": extracted.height,
            "takenAt": taken_at,
            "sortAt": sort_at,
            "sortSource": time.source, "timeVersion": 1, "originalName": original_name,
        });
        sqlx::query(
            r#"
            INSERT INTO change_log
                (revision, event_id, entity, operation, entity_id, version, payload, created_at, owner_user_id)
            VALUES (?1, ?2, 'media', 'upsert', ?3, ?4, ?5, ?6, ?7)
            "#,
        )
        .bind(revision)
        .bind(Uuid::new_v4().to_string())
        .bind(&media_id)
        .bind(media_version)
        .bind(serde_json::to_string(&payload)?)
        .bind(now)
        .bind(owner_user_id)
        .execute(&mut *transaction)
        .await?;
    }

    transaction.commit().await?;
    Ok(())
}

async fn emit_reconcile_change(pool: &SqlitePool, media_id: &str) -> anyhow::Result<()> {
    let Some((identity_state, version, owner_user_id)) =
        sqlx::query_as::<_, (String, i64, Option<i64>)>(
            "SELECT identity_state, version, owner_user_id FROM media_assets WHERE id = ?1",
        )
        .bind(media_id)
        .fetch_optional(pool)
        .await?
    else {
        return Ok(());
    };
    if identity_state != "verified" {
        return Ok(());
    }
    let has_verified_location = sqlx::query_scalar::<_, i64>(
        "SELECT EXISTS(SELECT 1 FROM media_locations WHERE media_asset_id = ?1 AND hash_state = 'verified')",
    )
    .bind(media_id)
    .fetch_one(pool)
    .await?
        == 1;
    if has_verified_location {
        return Ok(());
    }

    let already_pending = sqlx::query_scalar::<_, String>(
        "SELECT payload FROM change_log WHERE entity = 'media' AND entity_id = ?1 ORDER BY revision DESC LIMIT 1",
    )
    .bind(media_id)
    .fetch_optional(pool)
    .await?
    .is_some_and(|payload| payload.contains("\"reconcile_required\""));
    if already_pending {
        return Ok(());
    }

    let payload = serde_json::json!({
        "id": media_id,
        "version": version,
        "reason": "reconcile_required",
    });
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    sqlx::query(
        r#"
        INSERT INTO change_log
            (revision, event_id, entity, operation, entity_id, version, payload, created_at, owner_user_id)
        VALUES (?1, ?2, 'media', 'delete', ?3, ?4, ?5, ?6, ?7)
        "#,
    )
    .bind(revision)
    .bind(Uuid::new_v4().to_string())
    .bind(media_id)
    .bind(version)
    .bind(serde_json::to_string(&payload)?)
    .bind(now_millis())
    .bind(owner_user_id)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    Ok(())
}

async fn extract_image_metadata(
    storage: &LocalFilesystemStorageDriver,
    path: &str,
) -> anyhow::Result<ExtractedMetadata> {
    let bytes = storage.read_all(path, None).await?;
    let reader = ImageReader::new(Cursor::new(&bytes)).with_guessed_format()?;
    let (width, height) = reader.into_dimensions()?;
    Ok(ExtractedMetadata {
        duration_ms: None,
        width: Some(i64::from(width)),
        height: Some(i64::from(height)),
        taken_at: extract_exif_taken_at(&bytes),
        video_codec: None,
    })
}

async fn extract_video_metadata(
    storage: &LocalFilesystemStorageDriver,
    path: &str,
) -> anyhow::Result<ExtractedMetadata> {
    let output = timeout(
        Duration::from_secs(10),
        Command::new("ffprobe")
            .args([
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=codec_name,width,height,duration:stream_tags=creation_time:format=duration:format_tags=creation_time",
                "-of",
                "json",
            ])
            .arg(storage.root().join(path))
            .output(),
    )
    .await
    .context("ffprobe timed out")??;
    if !output.status.success() {
        anyhow::bail!("ffprobe exited with status {}", output.status);
    }

    let document: serde_json::Value = serde_json::from_slice(&output.stdout)?;
    let stream = document
        .get("streams")
        .and_then(|streams| streams.as_array())
        .and_then(|streams| streams.first())
        .ok_or_else(|| anyhow::anyhow!("ffprobe returned no video stream"))?;
    let video_codec = stream
        .get("codec_name")
        .and_then(|value| value.as_str())
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .ok_or_else(|| anyhow::anyhow!("ffprobe returned no video codec"))?;
    let duration = stream
        .get("duration")
        .and_then(json_number_as_f64)
        .or_else(|| {
            document
                .get("format")
                .and_then(|format| format.get("duration"))
                .and_then(json_number_as_f64)
        })
        .and_then(|seconds| {
            if seconds.is_finite() && seconds >= 0.0 {
                Some((seconds * 1000.0).round() as i64)
            } else {
                None
            }
        });
    let width = stream.get("width").and_then(|value| value.as_i64());
    let height = stream.get("height").and_then(|value| value.as_i64());
    if width.is_none() || height.is_none() || duration.is_none() {
        anyhow::bail!("ffprobe returned incomplete video metadata");
    }
    Ok(ExtractedMetadata {
        duration_ms: duration,
        width,
        height,
        taken_at: stream
            .get("tags")
            .and_then(|v| v.get("creation_time"))
            .and_then(|v| v.as_str())
            .or_else(|| {
                document
                    .get("format")
                    .and_then(|v| v.get("tags"))
                    .and_then(|v| v.get("creation_time"))
                    .and_then(|v| v.as_str())
            })
            .and_then(crate::media_time::iso_capture),
        video_codec: Some(video_codec),
    })
}

fn json_number_as_f64(value: &serde_json::Value) -> Option<f64> {
    value
        .as_f64()
        .or_else(|| value.as_str().and_then(|value| value.parse().ok()))
}

fn extract_exif_taken_at(bytes: &[u8]) -> Option<i64> {
    let mut cursor = Cursor::new(bytes);
    let exif = exif::Reader::new().read_from_container(&mut cursor).ok()?;
    for (tag, offset_tag, subsec_tag) in [
        (
            exif::Tag::DateTimeOriginal,
            exif::Tag::OffsetTimeOriginal,
            exif::Tag::SubSecTimeOriginal,
        ),
        (
            exif::Tag::DateTimeDigitized,
            exif::Tag::OffsetTimeDigitized,
            exif::Tag::SubSecTimeDigitized,
        ),
    ] {
        if let Some(field) = exif.get_field(tag, exif::In::PRIMARY) {
            let value = field.display_value().to_string();
            if let Some(timestamp) = parse_exif_datetime(&value) {
                let offset = exif
                    .get_field(offset_tag, exif::In::PRIMARY)
                    .map(|v| v.display_value().to_string());
                let offset = offset
                    .as_deref()
                    .map(|v| crate::media_time::offset_minutes(v.trim_matches('"')))
                    .unwrap_or(Some(0))?;
                let sub = exif
                    .get_field(subsec_tag, exif::In::PRIMARY)
                    .map(|v| v.display_value().to_string())
                    .unwrap_or_default();
                let digits = sub.trim_matches('"');
                let ms = if !digits.is_empty() && digits.bytes().all(|v| v.is_ascii_digit()) {
                    format!("{digits:0<3}")[..3].parse::<i64>().unwrap_or(0)
                } else {
                    0
                };
                return crate::media_time::valid(Some(timestamp - offset * 60000 + ms));
            }
        }
    }
    None
}

fn parse_exif_datetime(value: &str) -> Option<i64> {
    let value = value.trim().trim_matches('"');
    let (date, time) = value.split_once(' ')?;
    crate::media_time::filename(&format!(
        "IMG_{}_{}",
        date.replace([':', '-'], ""),
        time.replace(':', "")
    ))
}

fn is_supported_media(entry: &StorageEntry) -> bool {
    let extension = entry
        .name
        .rsplit_once('.')
        .map(|(_, extension)| extension.to_ascii_lowercase());
    matches!(
        extension.as_deref(),
        Some("gif" | "jpeg" | "jpg" | "mkv" | "mov" | "mp4" | "png" | "webm" | "webp" | "avi")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn real_exif_capture_offset_and_subseconds() {
        let fields = [
            exif::Field {
                tag: exif::Tag::DateTimeOriginal,
                ifd_num: exif::In::PRIMARY,
                value: exif::Value::Ascii(vec![b"2024:01:01 00:00:00".to_vec()]),
            },
            exif::Field {
                tag: exif::Tag::OffsetTimeOriginal,
                ifd_num: exif::In::PRIMARY,
                value: exif::Value::Ascii(vec![b"+08:00".to_vec()]),
            },
            exif::Field {
                tag: exif::Tag::SubSecTimeOriginal,
                ifd_num: exif::In::PRIMARY,
                value: exif::Value::Ascii(vec![b"123".to_vec()]),
            },
        ];
        let mut writer = exif::experimental::Writer::new();
        for field in &fields {
            writer.push_field(field);
        }
        let mut bytes = std::io::Cursor::new(Vec::new());
        writer.write(&mut bytes, false).unwrap();
        assert_eq!(extract_exif_taken_at(bytes.get_ref()), Some(1704038400123));
    }

    #[tokio::test]
    async fn media_time_survives_rescan_and_upload_rename() -> anyhow::Result<()> {
        let root = tempfile::tempdir()?;
        let state =
            crate::initialize(&root.path().join("data"), &root.path().join("media")).await?;
        let storage = state.storage.snapshot().await;
        tokio::fs::write(
            storage.root().join("IMG_20240101_000000.jpg"),
            b"image-time-test",
        )
        .await?;
        let stat = storage.stat("IMG_20240101_000000.jpg").await?;
        let entry = StorageEntry {
            name: "IMG_20240101_000000.jpg".into(),
            path: "IMG_20240101_000000.jpg".into(),
            is_directory: false,
            size: Some(stat.size),
            modified_at: stat.modified_at,
        };
        index_media(&state.db, &storage, &entry).await?;
        let time = sqlx::query_as::<_, (Option<i64>, String)>(
            "SELECT sort_at,sort_source FROM media_assets WHERE identity_state='verified'",
        )
        .fetch_one(&state.db)
        .await?;
        assert_eq!(time, (Some(1704067200000), "filename".into()));
        index_media_with_time(
            &state.db,
            &storage,
            &entry,
            None,
            None,
            Some(crate::media_time::MediaTime {
                at: Some(crate::db::now_millis()),
                source: "modified".into(),
            }),
            None,
        )
        .await?;
        let time2 = sqlx::query_as::<_, (Option<i64>, String)>(
            "SELECT sort_at,sort_source FROM media_assets WHERE identity_state='verified'",
        )
        .fetch_one(&state.db)
        .await?;
        assert_eq!(time, time2);
        // Generated upload name is not evidence: keep original unknown filename and inherited fallback.
        tokio::fs::write(
            storage.root().join("IMG_20250101_000000.jpg"),
            b"second-content",
        )
        .await?;
        let stat = storage.stat("IMG_20250101_000000.jpg").await?;
        let generated = StorageEntry {
            name: "IMG_20250101_000000.jpg".into(),
            path: "IMG_20250101_000000.jpg".into(),
            is_directory: false,
            size: Some(stat.size),
            modified_at: stat.modified_at,
        };
        index_media_with_time(
            &state.db,
            &storage,
            &generated,
            None,
            None,
            Some(crate::media_time::MediaTime {
                at: Some(1609459200000),
                source: "modified".into(),
            }),
            Some("plain.jpg"),
        )
        .await?;
        index_media_with_metadata(&state.db, &storage, &generated, Some(None), None).await?;
        let time = sqlx::query_as::<_, (Option<i64>, String)>(
            "SELECT sort_at,sort_source FROM media_assets WHERE name='IMG_20250101_000000.jpg'",
        )
        .fetch_one(&state.db)
        .await?;
        assert_eq!(time, (Some(1609459200000), "modified".into()));
        Ok(())
    }

    #[tokio::test]
    async fn hash_failure_emits_reconcile_change_for_verified_media() -> anyhow::Result<()> {
        let root = tempfile::tempdir()?;
        let data_dir = root.path().join("data");
        let media_root = root.path().join("media");
        tokio::fs::create_dir_all(&media_root).await?;
        let state = crate::initialize(&data_dir, &media_root).await?;
        let storage = state.storage.snapshot().await;
        let path = media_root.join("photo.jpg");
        tokio::fs::write(&path, b"not-a-real-image").await?;
        let stat = storage.stat("photo.jpg").await?;
        let entry = StorageEntry {
            name: "photo.jpg".to_owned(),
            path: "photo.jpg".to_owned(),
            is_directory: false,
            size: Some(stat.size),
            modified_at: stat.modified_at,
        };

        index_media(&state.db, &storage, &entry).await?;
        let media_id =
            sqlx::query_scalar::<_, String>("SELECT media_asset_id FROM media_locations LIMIT 1")
                .fetch_one(&state.db)
                .await?;
        tokio::fs::remove_file(&path).await?;

        let changed_entry = StorageEntry {
            size: Some(stat.size.saturating_add(1)),
            ..entry
        };
        assert!(
            index_media(&state.db, &storage, &changed_entry)
                .await
                .is_err()
        );

        let hash_state = sqlx::query_scalar::<_, String>(
            "SELECT hash_state FROM media_locations WHERE media_asset_id = ?1",
        )
        .bind(&media_id)
        .fetch_one(&state.db)
        .await?;
        assert_eq!(hash_state, "failed");
        let (operation, payload) = sqlx::query_as::<_, (String, String)>(
            r#"
            SELECT operation, payload FROM change_log
            WHERE entity = 'media' AND entity_id = ?1
            ORDER BY revision DESC LIMIT 1
            "#,
        )
        .bind(media_id)
        .fetch_one(&state.db)
        .await?;
        assert_eq!(operation, "delete");
        assert!(payload.contains("\"reconcile_required\""));
        Ok(())
    }
}

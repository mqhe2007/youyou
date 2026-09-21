use std::{collections::HashSet, io::Cursor, sync::Arc, time::Instant};

use anyhow::Context;
use futures_util::StreamExt;
use image::ImageReader;
use serde::{Deserialize, Serialize};
use sqlx::{FromRow, SqlitePool};
use tokio::{
    process::Command,
    time::{Duration, sleep, timeout},
};
use uuid::Uuid;

use crate::{
    db::{begin_write, now_millis},
    metadata,
    storage::{LocalFilesystemStorageDriver, StorageDriver, StorageEntry, StorageRuntime},
    sync,
};

/// 进度落库节流间隔：每文件一次 UPDATE 会与客户端写入争抢写锁。
const PROGRESS_UPDATE_INTERVAL: Duration = Duration::from_millis(1000);
/// 即使扫描极快，也至少每 50 个目录项保存一次可恢复的游标。
/// 这沿用 P0 的写入节流上限，不会为每个媒体文件额外写一行 job。
const PROGRESS_UPDATE_ENTRY_INTERVAL: u64 = 50;
/// 单文件索引的写锁重试退避（SQLITE_BUSY 类错误）。
const INDEX_RETRY_BACKOFF_MS: [u64; 3] = [50, 150, 400];
/// 作业检查点里保留的失败明细上限（避免长目录把 checkpoint 撑大）。
const MAX_RECORDED_FAILURES: usize = 100;
const MAX_FAILURE_REASON_CHARS: usize = 300;

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanSummary {
    pub discovered: u64,
    pub indexed: u64,
    pub failed: u64,
    /// 扩展名暂不可索引（仍在磁盘上，重扫会重新尝试）。
    pub skipped_unsupported: u64,
    /// 垃圾文件/目录与 0 字节文件（不进入媒体库，历史条目会被对账清理）。
    pub skipped_ignored: u64,
    /// 已知内容失败且文件未变更（未启用重试时跳过，避免重复哈希）。
    pub skipped_failed: u64,
    /// 因写锁竞争重试后成功的次数。
    pub retried: u64,
    pub failures: Vec<ScanFailure>,
    pub cancelled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanFailure {
    pub path: String,
    pub reason: String,
}

/// 可持久化的目录遍历游标。`pending_directories` 按栈顺序保存，恢复时先回到
/// `current_directory` 并跳过已处理的条目，再按原有的深度优先顺序继续。
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScanCursor {
    current_directory: Option<String>,
    last_entry_name: Option<String>,
    #[serde(default)]
    pending_directories: Vec<String>,
}

#[derive(Debug, Clone)]
struct ScanResumeMetadata {
    source_job_id: String,
    discovered: u64,
    indexed: u64,
}

impl ScanSummary {
    pub fn skipped_total(&self) -> u64 {
        self.skipped_unsupported + self.skipped_ignored + self.skipped_failed
    }

    fn record_failure(&mut self, path: &str, error: &anyhow::Error) {
        if self.failures.len() >= MAX_RECORDED_FAILURES {
            return;
        }
        let mut reason = format!("{error:#}");
        if reason.chars().count() > MAX_FAILURE_REASON_CHARS {
            reason = reason.chars().take(MAX_FAILURE_REASON_CHARS).collect();
            reason.push('…');
        }
        self.failures.push(ScanFailure {
            path: path.to_owned(),
            reason,
        });
    }
}

/// 扫描时应跳过的垃圾条目：隐藏文件（含 macOS `._*` 与 AppleDouble）、
/// 系统/同步工具产生的元数据目录等。跳过即不进入媒体库，历史条目由对账清理。
fn is_ignored_name(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    name.starts_with('.')
        || matches!(
            lower.as_str(),
            "thumbs.db"
                | "desktop.ini"
                | "@eadir"
                | "__macosx"
                | "#recycle"
                | "lost+found"
                | "$recycle.bin"
                | "system volume information"
        )
}

/// 内容解析失败且文件未变更时跳过重复哈希（重试入口见 start_scan 的 `retry_failed`）。
async fn is_unchanged_terminal_failure(
    pool: &SqlitePool,
    entry: &StorageEntry,
) -> Result<bool, sqlx::Error> {
    let row = sqlx::query_as::<_, (i64, Option<i64>)>(
        "SELECT size, modified_at FROM media_index_failures WHERE storage_id = 'local' AND normalized_path = ?1",
    )
    .bind(&entry.path)
    .fetch_optional(pool)
    .await?;
    Ok(row.is_some_and(|(size, modified_at)| {
        size == i64::try_from(entry.size.unwrap_or_default()).unwrap_or(i64::MAX)
            && modified_at == entry.modified_at
    }))
}

/// 记录内容级失败（损坏/不可解码）：持久化原因与指纹，重扫不再重复哈希。
pub(crate) async fn record_index_failure(
    pool: &SqlitePool,
    entry: &StorageEntry,
    reason: &str,
) -> anyhow::Result<()> {
    let now = now_millis();
    let size = i64::try_from(entry.size.unwrap_or_default()).unwrap_or(i64::MAX);
    sqlx::query(
        r#"
        INSERT INTO media_index_failures
            (storage_id, normalized_path, file_name, size, modified_at, reason, attempts,
             first_failed_at, last_failed_at)
        VALUES ('local', ?1, ?2, ?3, ?4, ?5, 1, ?6, ?6)
        ON CONFLICT(storage_id, normalized_path) DO UPDATE SET
            file_name = excluded.file_name,
            size = excluded.size,
            modified_at = excluded.modified_at,
            reason = excluded.reason,
            attempts = media_index_failures.attempts + 1,
            last_failed_at = excluded.last_failed_at
        "#,
    )
    .bind(&entry.path)
    .bind(&entry.name)
    .bind(size)
    .bind(entry.modified_at)
    .bind(reason)
    .bind(now)
    .execute(pool)
    .await?;
    Ok(())
}

/// 内容级失败时丢弃本次预建的 pending 资产与位置行（仅限新文件首次索引），
/// 失败本身由 `media_index_failures` 留痕，不再留下无说明的 pending 残留。
async fn discard_failed_pending(
    pool: &SqlitePool,
    entry: &StorageEntry,
    pending_media_id: Option<&str>,
) -> anyhow::Result<()> {
    let Some(media_id) = pending_media_id else {
        return Ok(());
    };
    let mut transaction = begin_write(pool).await?;
    sqlx::query("DELETE FROM media_locations WHERE storage_id = 'local' AND normalized_path = ?1")
        .bind(&entry.path)
        .execute(&mut *transaction)
        .await?;
    sqlx::query(
        "DELETE FROM media_assets WHERE id = ?1 AND identity_state = 'pending' AND NOT EXISTS (SELECT 1 FROM media_locations WHERE media_asset_id = ?1)",
    )
    .bind(media_id)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    Ok(())
}

/// 内容级失败（文件损坏或无法解码）：持久化原因与指纹（重扫不重复哈希），
/// 清理本次预建的 pending 残留，并返回带原因的终结错误。
async fn record_terminal_failure(
    pool: &SqlitePool,
    entry: &StorageEntry,
    pending: &PendingIndex,
    label: &str,
    error: anyhow::Error,
) -> anyhow::Error {
    tracing::debug!(path = %entry.path, error = ?error, label, "media content could not be decoded");
    let reason = format!("{label}：{error:#}");
    if let Err(record_error) = record_index_failure(pool, entry, &reason).await {
        tracing::warn!(
            path = %entry.path,
            error = ?record_error,
            "failed to record index failure"
        );
    }
    if pending.pending_media_id.is_none()
        && let Err(mark_error) = mark_location_hash_state(pool, &entry.path, "failed").await
    {
        tracing::warn!(path = %entry.path, error = ?mark_error, "failed to mark location as failed");
    }
    if let Some(media_id) = pending.reconcile_media_id.as_deref()
        && let Err(reconcile_error) = emit_reconcile_change(pool, media_id).await
    {
        tracing::warn!(path = %entry.path, error = ?reconcile_error, "failed to emit reconcile change");
    }
    if let Err(discard_error) =
        discard_failed_pending(pool, entry, pending.pending_media_id.as_deref()).await
    {
        tracing::warn!(
            path = %entry.path,
            error = ?discard_error,
            "failed to discard pending media after index failure"
        );
    }
    anyhow::anyhow!(reason)
}

/// 单文件索引：SQLITE_BUSY 类错误退避重试，其余错误直接上抛。
async fn index_with_retry(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    entry: &StorageEntry,
    summary: &mut ScanSummary,
) -> anyhow::Result<()> {
    let mut attempt = 0_usize;
    loop {
        match index_media(pool, storage, entry).await {
            Ok(()) => return Ok(()),
            Err(error) => {
                if attempt >= INDEX_RETRY_BACKOFF_MS.len() || !crate::db::is_busy_anyhow(&error) {
                    return Err(error);
                }
                let backoff = INDEX_RETRY_BACKOFF_MS[attempt];
                attempt += 1;
                summary.retried += 1;
                tracing::debug!(
                    path = %entry.path,
                    attempt,
                    backoff_ms = backoff,
                    "retrying media index after lock contention"
                );
                sleep(Duration::from_millis(backoff)).await;
            }
        }
    }
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
    let retry_failed = checkpoint
        .as_ref()
        .and_then(|value| value.get("retryFailed"))
        .and_then(|value| value.as_bool())
        .unwrap_or(false);
    let resume_cursor = checkpoint
        .as_ref()
        .and_then(|value| value.get("resume"))
        .and_then(|value| serde_json::from_value::<ScanCursor>(value.clone()).ok());
    let resume_metadata = checkpoint.as_ref().and_then(|value| {
        Some(ScanResumeMetadata {
            source_job_id: value.get("resumedFromJobId")?.as_str()?.to_owned(),
            discovered: value
                .get("resumedAtDiscovered")
                .and_then(|value| value.as_u64())
                .unwrap_or(0),
            indexed: value
                .get("resumedAtIndexed")
                .and_then(|value| value.as_u64())
                .unwrap_or(0),
        })
    });
    // 只有携带游标的检查点才代表一次真正的续跑；普通运行中的检查点不能把
    // 历史统计带进新扫描。
    let resumed = resume_cursor.is_some();
    let mut summary = if resumed {
        scan_summary_from_checkpoint(checkpoint.as_ref())
    } else {
        ScanSummary::default()
    };
    let mut directories = match resume_cursor.as_ref() {
        Some(cursor) => {
            let mut pending = cursor.pending_directories.clone();
            if let Some(current) = &cursor.current_directory {
                pending.push(current.clone());
            }
            pending
        }
        None => vec![scope_path.clone()],
    };
    let mut seen_directories = HashSet::new();
    let mut seen_paths = HashSet::new();
    let mut last_path: Option<String> = None;
    let mut last_progress_at = Instant::now();
    let mut entries_since_progress = 0_u64;
    let mut cursor_to_resume = resume_cursor;

    while let Some(directory) = directories.pop() {
        if is_cancel_requested(pool, job_id).await? {
            let cursor = ScanCursor {
                current_directory: None,
                last_entry_name: None,
                pending_directories: directories.clone(),
            };
            save_interrupted_checkpoint(
                pool,
                job_id,
                &scope_path,
                retry_failed,
                &summary,
                &cursor,
                last_path.as_deref(),
                resume_metadata.as_ref(),
                lease_owner,
            )
            .await?;
            summary.cancelled = true;
            return Ok(summary);
        }
        if !seen_directories.insert(directory.clone()) {
            continue;
        }
        let entries = sorted_entries(&storage, &directory)
            .await
            // 作业终态会把 `Error::to_string()` 持久化为 lastError；把完整错误链
            // 展开到顶层，既保留目录上下文，也保留诸如 symlink escape 的原因。
            .map_err(|error| anyhow::anyhow!("list directory {directory:?}: {error:#}"))?;
        let resume_entry_name = cursor_to_resume
            .as_ref()
            .filter(|cursor| cursor.current_directory.as_deref() == Some(directory.as_str()))
            .and_then(|cursor| cursor.last_entry_name.clone());
        let mut last_entry_name = resume_entry_name.clone();

        for entry in entries {
            // 恢复到同一目录时，仅跳过已经处理并落到检查点的前缀。排序保证
            // 这个比较跨进程重启仍是确定的；子目录已在 pending 栈中，无需重走。
            if resume_entry_name
                .as_ref()
                .is_some_and(|last| entry.name <= *last)
            {
                continue;
            }
            if is_cancel_requested(pool, job_id).await? {
                let cursor = ScanCursor {
                    current_directory: Some(directory.clone()),
                    last_entry_name,
                    pending_directories: directories.clone(),
                };
                save_interrupted_checkpoint(
                    pool,
                    job_id,
                    &scope_path,
                    retry_failed,
                    &summary,
                    &cursor,
                    last_path.as_deref(),
                    resume_metadata.as_ref(),
                    lease_owner,
                )
                .await?;
                summary.cancelled = true;
                return Ok(summary);
            }
            if entry.is_directory {
                // 垃圾目录（.Trash/@eaDir/__MACOSX 等）不遍历；库内历史条目由对账清理。
                if !is_ignored_name(&entry.name) {
                    directories.push(entry.path.clone());
                }
            } else if is_ignored_name(&entry.name) || entry.size == Some(0) {
                summary.skipped_ignored += 1;
            } else {
                // 文件确实存在：先登记，保证对账不会把「存在但暂不支持索引」的媒体
                // 误判为已移除（例如上传入库的 HEIC）。
                seen_paths.insert(entry.path.clone());
                if !is_supported_media(&entry) {
                    summary.skipped_unsupported += 1;
                } else if !retry_failed && is_unchanged_terminal_failure(pool, &entry).await? {
                    summary.skipped_failed += 1;
                } else {
                    summary.discovered += 1;
                    match index_with_retry(pool, &storage, &entry, &mut summary).await {
                        Ok(()) => summary.indexed += 1,
                        Err(error) => {
                            summary.failed += 1;
                            tracing::warn!(
                                job_id,
                                path = %entry.path,
                                error = ?error,
                                "failed to index media"
                            );
                            summary.record_failure(&entry.path, &error);
                        }
                    }
                }
            }
            last_entry_name = Some(entry.name);
            last_path = Some(entry.path.clone());
            entries_since_progress += 1;
            if last_progress_at.elapsed() >= PROGRESS_UPDATE_INTERVAL
                || entries_since_progress >= PROGRESS_UPDATE_ENTRY_INTERVAL
            {
                let cursor = ScanCursor {
                    current_directory: Some(directory.clone()),
                    last_entry_name: last_entry_name.clone(),
                    pending_directories: directories.clone(),
                };
                let checkpoint = scan_checkpoint(
                    &scope_path,
                    retry_failed,
                    "running",
                    &summary,
                    Some(&cursor),
                    last_path.as_deref(),
                    resume_metadata.as_ref(),
                );
                last_progress_at = Instant::now();
                entries_since_progress = 0;
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
        // 首次恢复所在的目录已处理完；后续目录不能继续使用其 entry 游标。
        cursor_to_resume = None;
    }

    if is_cancel_requested(pool, job_id).await? {
        let cursor = ScanCursor {
            current_directory: None,
            last_entry_name: None,
            pending_directories: Vec::new(),
        };
        save_interrupted_checkpoint(
            pool,
            job_id,
            &scope_path,
            retry_failed,
            &summary,
            &cursor,
            last_path.as_deref(),
            resume_metadata.as_ref(),
            lease_owner,
        )
        .await?;
        summary.cancelled = true;
        return Ok(summary);
    }
    // 收尾前落一次最终进度：节流窗口内的计数与 lastPath 不能丢。
    let final_progress = scan_checkpoint(
        &scope_path,
        retry_failed,
        "running",
        &summary,
        None,
        last_path.as_deref(),
        resume_metadata.as_ref(),
    );
    let progress_saved = update_scan_progress(
        pool,
        job_id,
        summary.discovered,
        summary.indexed,
        &final_progress,
        lease_owner,
    )
    .await?;
    if lease_owner.is_some() && !progress_saved {
        return Err(anyhow::anyhow!("scan job lease was lost"));
    }
    // 续跑时内存里只保留本段已走过的路径。对账前无哈希地重收集整个范围，
    // 避免把取消前已处理、恢复后未再次枚举的路径误判为缺失。
    let reconciliation_paths = if resumed {
        collect_reconciliation_paths(&storage, &scope_path).await?
    } else {
        seen_paths
    };
    reconcile_missing_locations(pool, &reconciliation_paths, &scope_path).await?;
    if let Some(lease_owner) = lease_owner {
        let checkpoint = scan_checkpoint(
            &scope_path,
            retry_failed,
            "completed",
            &summary,
            None,
            last_path.as_deref(),
            resume_metadata.as_ref(),
        );
        if !update_scan_checkpoint(pool, job_id, &checkpoint, lease_owner).await? {
            return Err(anyhow::anyhow!("scan job lease was lost"));
        }
    }
    Ok(summary)
}

async fn sorted_entries(
    storage: &LocalFilesystemStorageDriver,
    directory: &str,
) -> anyhow::Result<Vec<StorageEntry>> {
    let mut stream = storage.list(directory).await?;
    let mut entries = Vec::new();
    while let Some(entry) = stream.next().await {
        entries.push(entry?);
    }
    entries.sort_by(|left, right| {
        left.name
            .cmp(&right.name)
            .then_with(|| left.path.cmp(&right.path))
    });
    Ok(entries)
}

/// 扫描恢复后用于最终对账的轻量遍历：只识别「仍存在且不是垃圾」的路径，
/// 从不读取内容、不提取元数据、不计算哈希。
async fn collect_reconciliation_paths(
    storage: &LocalFilesystemStorageDriver,
    scope_path: &str,
) -> anyhow::Result<HashSet<String>> {
    let mut directories = vec![scope_path.to_owned()];
    let mut seen_directories = HashSet::new();
    let mut paths = HashSet::new();
    while let Some(directory) = directories.pop() {
        if !seen_directories.insert(directory.clone()) {
            continue;
        }
        for entry in sorted_entries(storage, &directory)
            .await
            .with_context(|| format!("list directory {directory:?} for reconciliation"))?
        {
            if entry.is_directory {
                if !is_ignored_name(&entry.name) {
                    directories.push(entry.path);
                }
            } else if !is_ignored_name(&entry.name) && entry.size != Some(0) {
                paths.insert(entry.path);
            }
        }
    }
    Ok(paths)
}

fn scan_summary_from_checkpoint(checkpoint: Option<&serde_json::Value>) -> ScanSummary {
    let Some(value) = checkpoint else {
        return ScanSummary::default();
    };
    ScanSummary {
        discovered: value
            .get("discovered")
            .and_then(|value| value.as_u64())
            .unwrap_or(0),
        indexed: value
            .get("indexed")
            .and_then(|value| value.as_u64())
            .unwrap_or(0),
        failed: value
            .get("failed")
            .and_then(|value| value.as_u64())
            .unwrap_or(0),
        skipped_unsupported: value
            .get("skippedUnsupported")
            .and_then(|value| value.as_u64())
            .unwrap_or(0),
        skipped_ignored: value
            .get("skippedIgnored")
            .and_then(|value| value.as_u64())
            .unwrap_or(0),
        skipped_failed: value
            .get("skippedFailed")
            .and_then(|value| value.as_u64())
            .unwrap_or(0),
        retried: value
            .get("retried")
            .and_then(|value| value.as_u64())
            .unwrap_or(0),
        failures: value
            .get("failures")
            .cloned()
            .and_then(|value| serde_json::from_value(value).ok())
            .unwrap_or_default(),
        cancelled: false,
    }
}

async fn save_interrupted_checkpoint(
    pool: &SqlitePool,
    job_id: &str,
    scope_path: &str,
    retry_failed: bool,
    summary: &ScanSummary,
    cursor: &ScanCursor,
    last_path: Option<&str>,
    resume_metadata: Option<&ScanResumeMetadata>,
    lease_owner: Option<&str>,
) -> anyhow::Result<()> {
    let checkpoint = scan_checkpoint(
        scope_path,
        retry_failed,
        "interrupted",
        summary,
        Some(cursor),
        last_path,
        resume_metadata,
    );
    let saved = update_scan_progress(
        pool,
        job_id,
        summary.discovered,
        summary.indexed,
        &checkpoint,
        lease_owner,
    )
    .await?;
    if lease_owner.is_some() && !saved {
        anyhow::bail!("scan job lease was lost");
    }
    Ok(())
}

/// 扫描检查点 JSON：运行/取消阶段带可恢复的目录游标，完成阶段带分类统计与失败明细。
fn scan_checkpoint(
    scope_path: &str,
    retry_failed: bool,
    phase: &str,
    summary: &ScanSummary,
    cursor: Option<&ScanCursor>,
    last_path: Option<&str>,
    resume_metadata: Option<&ScanResumeMetadata>,
) -> String {
    let mut value = serde_json::json!({
        "version": 2,
        "kind": "scan",
        "scopePath": scope_path,
        "retryFailed": retry_failed,
        "phase": phase,
        "discovered": summary.discovered,
        "indexed": summary.indexed,
        "failed": summary.failed,
        "skippedUnsupported": summary.skipped_unsupported,
        "skippedIgnored": summary.skipped_ignored,
        "skippedFailed": summary.skipped_failed,
        "retried": summary.retried,
        "failures": summary.failures,
    });
    if let Some(cursor) = cursor {
        value["resume"] = serde_json::json!(cursor);
    }
    if let Some(metadata) = resume_metadata {
        value["resumedFromJobId"] = serde_json::json!(metadata.source_job_id);
        value["resumedAtDiscovered"] = serde_json::json!(metadata.discovered);
        value["resumedAtIndexed"] = serde_json::json!(metadata.indexed);
    }
    if let Some(last_path) = last_path {
        value["lastPath"] = serde_json::json!(last_path);
    }
    value.to_string()
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
    let mut transaction = begin_write(pool).await?;
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
    // 已消失文件的失败记录一并清理：文件回来时会重新走一次索引。
    let recorded_failures = sqlx::query_scalar::<_, String>(
        "SELECT normalized_path FROM media_index_failures WHERE storage_id = 'local'",
    )
    .fetch_all(&mut *transaction)
    .await?;
    for path in recorded_failures {
        if (!scope_path.is_empty() && !path.starts_with(&scope_prefix))
            || seen_paths.contains(&path)
            || pending_paths.contains(&path)
        {
            continue;
        }
        sqlx::query(
            "DELETE FROM media_index_failures WHERE storage_id = 'local' AND normalized_path = ?1",
        )
        .bind(&path)
        .execute(&mut *transaction)
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
    let mut transaction = begin_write(pool).await?;
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
    let media_format = crate::media_format::from_path(&entry.path);
    let mime_type = crate::media_format::mime_for_path(&entry.path);
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
    // 显式覆盖（上传）优先信任调用方给出的 MIME；否则以格式注册表为准。
    let is_video = match mime_type_override.flatten() {
        Some(mime) => mime.starts_with("video/"),
        None => {
            media_format.is_some_and(|format| format.kind == crate::media_format::MediaKind::Video)
                || mime_type
                    .as_deref()
                    .is_some_and(|mime| mime.starts_with("video/"))
        }
    };
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
                return Err(record_terminal_failure(
                    pool,
                    entry,
                    &pending,
                    "视频元数据解析失败",
                    error,
                )
                .await);
            }
        }
    } else if media_format
        .is_some_and(|format| format.kind == crate::media_format::MediaKind::Image)
    {
        match extract_image_metadata(storage, &entry.path).await {
            Ok(metadata) => metadata,
            Err(error) => {
                // 注册表内的图片格式必须能解析出尺寸才算入库：解码失败按内容级失败
                // 处理，避免产生 0×0 的 verified 资产（其缩略图必然失败）。
                return Err(
                    record_terminal_failure(pool, entry, &pending, "图片解码失败", error).await,
                );
            }
        }
    } else {
        // 注册表之外的格式（上传兜底）：元数据不可得不算失败，按未知尺寸入库。
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

    let mut transaction = begin_write(pool).await?;
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

    // 索引成功：清掉历史失败记录（含本次修复的损坏文件）。
    sqlx::query(
        "DELETE FROM media_index_failures WHERE storage_id = 'local' AND normalized_path = ?1",
    )
    .bind(&entry.path)
    .execute(&mut *transaction)
    .await?;

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
    let mut transaction = begin_write(pool).await?;
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
    if crate::media_format::from_path(path)
        .is_some_and(|format| format.decoder == crate::media_format::Decoder::Heif)
    {
        // HEIC/HEIF：尺寸从 ISOBMFF 容器解析（不依赖解码器），
        // EXIF（含拍摄时间）走 kamadak-exif 的 HEIF 支持。
        let (width, height) = crate::heif::dimensions(&bytes)
            .ok_or_else(|| anyhow::anyhow!("HEIF 容器未找到图像尺寸（ispe）"))?;
        return Ok(ExtractedMetadata {
            duration_ms: None,
            width: Some(i64::from(width)),
            height: Some(i64::from(height)),
            taken_at: extract_exif_taken_at(&bytes),
            video_codec: None,
        });
    }
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
    crate::media_format::is_supported(&entry.name)
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
        tokio::fs::write(storage.root().join("IMG_20240101_000000.jpg"), tiny_jpeg()).await?;
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
        tokio::fs::write(storage.root().join("IMG_20250101_000000.jpg"), tiny_png()).await?;
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
        tokio::fs::write(&path, tiny_jpeg()).await?;
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

    /// 最小可用 JPEG：图片必须能解析出尺寸才算入库，测试夹具不能用任意字节。
    fn tiny_jpeg() -> Vec<u8> {
        let image = image::RgbImage::from_pixel(2, 2, image::Rgb([120, 80, 40]));
        let mut bytes = std::io::Cursor::new(Vec::new());
        image::DynamicImage::ImageRgb8(image)
            .write_to(&mut bytes, image::ImageFormat::Jpeg)
            .expect("encode tiny jpeg");
        bytes.into_inner()
    }

    /// 最小可用 PNG（内容与扩展名可不一致，用于「存在但不支持」的场景）。
    fn tiny_png() -> Vec<u8> {
        let image = image::RgbImage::from_pixel(2, 2, image::Rgb([10, 200, 30]));
        let mut bytes = std::io::Cursor::new(Vec::new());
        image::DynamicImage::ImageRgb8(image)
            .write_to(&mut bytes, image::ImageFormat::Png)
            .expect("encode tiny png");
        bytes.into_inner()
    }

    async fn write_test_entry(
        storage: &LocalFilesystemStorageDriver,
        path: &str,
        bytes: &[u8],
    ) -> anyhow::Result<StorageEntry> {
        tokio::fs::write(storage.root().join(path), bytes).await?;
        let stat = storage.stat(path).await?;
        Ok(StorageEntry {
            name: path.rsplit('/').next().unwrap_or(path).to_owned(),
            path: path.to_owned(),
            is_directory: false,
            size: Some(stat.size),
            modified_at: stat.modified_at,
        })
    }

    #[tokio::test]
    async fn scan_skips_junk_files_and_cleans_up_indexed_junk() -> anyhow::Result<()> {
        let root = tempfile::tempdir()?;
        let media_root = root.path().join("media");
        tokio::fs::create_dir_all(&media_root).await?;
        let state = crate::initialize(&root.path().join("data"), &media_root).await?;
        let storage = state.storage.snapshot().await;

        write_test_entry(&storage, "IMG_20240101_000000.jpg", &tiny_jpeg()).await?;
        // 旧版本可能已收录的 AppleDouble 垃圾：本次扫描应对账墓碑。
        let legacy_junk = write_test_entry(&storage, "._1708264084494.jpg", &tiny_jpeg()).await?;
        index_media(&state.db, &storage, &legacy_junk).await?;
        // 新增垃圾：隐藏文件、同步缩略图目录、0 字节文件。
        write_test_entry(&storage, ".DS_Store", b"ds").await?;
        tokio::fs::create_dir_all(media_root.join("@eaDir")).await?;
        write_test_entry(&storage, "@eaDir/thumb.jpg", b"thumb").await?;
        write_test_entry(&storage, "empty.jpg", b"").await?;

        let summary = scan_directory(&state.db, state.storage.clone(), "scan").await?;
        assert_eq!(summary.discovered, 1);
        assert_eq!(summary.indexed, 1);
        assert_eq!(summary.skipped_ignored, 3);

        let locations: Vec<String> = sqlx::query_scalar(
            "SELECT normalized_path FROM media_locations ORDER BY normalized_path",
        )
        .fetch_all(&state.db)
        .await?;
        assert_eq!(locations, vec!["IMG_20240101_000000.jpg".to_owned()]);
        let tombstoned = sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_assets WHERE identity_state = 'tombstoned'",
        )
        .fetch_one(&state.db)
        .await?;
        assert_eq!(tombstoned, 1);
        Ok(())
    }

    #[tokio::test]
    async fn scan_does_not_tombstone_present_unsupported_files() -> anyhow::Result<()> {
        // 上传路径可写入扫描白名单之外的格式（如 HEIC）；重扫必须按「文件仍在」
        // 对待，否则这些媒体会在扫描后凭空消失（source_missing）。
        let root = tempfile::tempdir()?;
        let media_root = root.path().join("media");
        tokio::fs::create_dir_all(&media_root).await?;
        let state = crate::initialize(&root.path().join("data"), &media_root).await?;
        let storage = state.storage.snapshot().await;
        // 上传/旧版本可写入注册表之外的格式；这里用「可用图片 + 未登记扩展名」模拟，
        // 表示文件确实存在且已入库，只是当前格式不在扫描白名单里。
        let unsupported =
            write_test_entry(&storage, "IMG_20240102_000000.tiff", &tiny_png()).await?;
        index_media(&state.db, &storage, &unsupported).await?;

        let summary = scan_directory(&state.db, state.storage.clone(), "scan").await?;
        assert_eq!(summary.skipped_unsupported, 1);
        assert_eq!(summary.discovered, 0);
        let location_count = sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_locations WHERE normalized_path = 'IMG_20240102_000000.tiff'",
        )
        .fetch_one(&state.db)
        .await?;
        assert_eq!(location_count, 1);
        let identity = sqlx::query_scalar::<_, String>("SELECT identity_state FROM media_assets")
            .fetch_one(&state.db)
            .await?;
        assert_eq!(identity, "verified");
        Ok(())
    }

    #[tokio::test]
    async fn corrupt_video_failure_is_recorded_once_and_retried_only_on_demand()
    -> anyhow::Result<()> {
        let root = tempfile::tempdir()?;
        let media_root = root.path().join("media");
        tokio::fs::create_dir_all(&media_root).await?;
        let state = crate::initialize(&root.path().join("data"), &media_root).await?;
        let storage = state.storage.snapshot().await;
        write_test_entry(&storage, "MVI_0466.MOV", &[0_u8; 4096]).await?;

        let first = scan_directory(&state.db, state.storage.clone(), "scan").await?;
        assert_eq!(first.failed, 1);
        assert_eq!(first.failures.len(), 1);
        assert!(
            first.failures[0].reason.contains("视频元数据解析失败"),
            "unexpected reason: {}",
            first.failures[0].reason
        );
        // 不再残留无说明的 pending 资产与位置行。
        assert_eq!(media_asset_count(&state.db).await?, 0);
        assert_eq!(location_count(&state.db).await?, 0);
        assert_eq!(failure_attempts(&state.db).await?, 1);
        let recorded: String = sqlx::query_scalar(
            "SELECT reason FROM media_index_failures WHERE normalized_path = 'MVI_0466.MOV'",
        )
        .fetch_one(&state.db)
        .await?;
        assert!(recorded.contains("视频元数据解析失败"), "{recorded}");

        // 重扫：已知失败且文件未变更 → 跳过，不重复哈希大文件。
        let second = scan_directory(&state.db, state.storage.clone(), "scan-again").await?;
        assert_eq!(second.skipped_failed, 1);
        assert_eq!(second.failed, 0);
        assert_eq!(failure_attempts(&state.db).await?, 1);

        // 显式重试（retryFailed）才重新索引。
        sqlx::query(
            "INSERT INTO jobs (id, kind, status, created_at, updated_at, checkpoint) VALUES ('scan-retry', 'scan', 'running', 1, 1, ?1)",
        )
        .bind(
            serde_json::json!({"version": 1, "kind": "scan", "scopePath": "", "retryFailed": true})
                .to_string(),
        )
        .execute(&state.db)
        .await?;
        let third = scan_directory(&state.db, state.storage.clone(), "scan-retry").await?;
        assert_eq!(third.failed, 1);
        assert_eq!(failure_attempts(&state.db).await?, 2);
        Ok(())
    }

    async fn media_asset_count(pool: &SqlitePool) -> Result<i64, sqlx::Error> {
        sqlx::query_scalar("SELECT COUNT(*) FROM media_assets")
            .fetch_one(pool)
            .await
    }

    async fn location_count(pool: &SqlitePool) -> Result<i64, sqlx::Error> {
        sqlx::query_scalar("SELECT COUNT(*) FROM media_locations")
            .fetch_one(pool)
            .await
    }

    async fn failure_attempts(pool: &SqlitePool) -> Result<i64, sqlx::Error> {
        sqlx::query_scalar(
            "SELECT attempts FROM media_index_failures WHERE normalized_path = 'MVI_0466.MOV'",
        )
        .fetch_one(pool)
        .await
    }
}

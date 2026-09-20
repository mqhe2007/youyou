//! 服务端回收站：原件真删除、保留期回收、恢复与到期清理。
//!
//! 设计约束（需求 UoN5J--JHK_R §5/§6）：
//!
//! * 回收站位于全部可扫描用户库树之外，且与媒体源同文件系统；不满足则在启动/
//!   配置校验阶段禁用删除能力，绝不隐式 `copy + delete`。
//! * 文件系统与 SQLite 不能共用原子事务，因此以 `media_operations` 持久化操作
//!   阶段（`initiated` / `files_moved` / `committed`），崩溃后按阶段与文件系统
//!   实际状态做补偿：文件已移动则前滚提交，未移动则回退为失败。
//! * 同盘 rename + 不覆盖目标；并发占位同时约束文件系统与数据库，不只依赖 SQL
//!   唯一约束。

use std::{
    io::ErrorKind,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use serde::{Deserialize, Serialize};
use sqlx::{FromRow, Sqlite, SqlitePool, Transaction};
use tokio::sync::{Mutex, RwLock};
use uuid::Uuid;

use crate::{
    audit,
    db::now_millis,
    error::{AppError, AppResult},
    metadata,
    storage::LocalFilesystemStorageDriver,
    sync, users,
};

/// 服务端回收站保留期（固定 30 天，不做可配置项）。
pub const TRASH_RETENTION_MS: i64 = 30 * 24 * 60 * 60 * 1000;

const CLEANUP_INTERVAL: Duration = Duration::from_secs(60 * 60);
const TRASH_DIR_ENV: &str = "YOUYOU_TRASH_DIR";

#[derive(Debug, Clone)]
pub struct TrashConfig {
    pub root: PathBuf,
    pub configured: String,
    pub enabled: bool,
    pub blocked_reason: Option<String>,
}

impl TrashConfig {
    pub fn require_enabled(&self) -> AppResult<&Path> {
        if self.enabled {
            Ok(self.root.as_path())
        } else {
            Err(AppError::Conflict(format!(
                "服务端删除能力未启用：{}",
                self.blocked_reason.as_deref().unwrap_or("回收站配置无效")
            )))
        }
    }
}

/// 回收站运行时：配置快照 + 串行化锁。
///
/// 锁保证「恢复与过期清理、重复恢复、删除与扫描占位」之间的串行协调。
#[derive(Clone)]
pub struct TrashRuntime {
    config: Arc<RwLock<TrashConfig>>,
    lock: Arc<Mutex<()>>,
}

impl TrashRuntime {
    pub fn new(config: TrashConfig) -> Self {
        Self {
            config: Arc::new(RwLock::new(config)),
            lock: Arc::new(Mutex::new(())),
        }
    }

    pub async fn snapshot(&self) -> TrashConfig {
        self.config.read().await.clone()
    }

    /// 存储根变更后重新校验回收站配置。
    pub async fn reconfigure(&self, server_dir: &Path, storage_root: &Path) -> TrashConfig {
        let config = prepare(server_dir, storage_root).await;
        *self.config.write().await = config.clone();
        config
    }
}

/// 解析回收站目录：`YOUYOU_TRASH_DIR` 优先，缺省为服务端目录下的 `trash/`。
pub fn resolve_trash_dir(server_dir: &Path) -> PathBuf {
    match std::env::var_os(TRASH_DIR_ENV) {
        Some(value) if !value.is_empty() => {
            let path = PathBuf::from(value);
            if path.is_absolute() {
                path
            } else {
                server_dir.join(path)
            }
        }
        _ => server_dir.join("trash"),
    }
}

/// 校验并准备回收站目录。任一项不满足都返回禁用原因，由调用方禁用删除能力。
pub async fn prepare(server_dir: &Path, storage_root: &Path) -> TrashConfig {
    let root = resolve_trash_dir(server_dir);
    let configured = root.display().to_string();
    match inspect(&root, storage_root).await {
        Ok(canonical) => TrashConfig {
            root: canonical,
            configured,
            enabled: true,
            blocked_reason: None,
        },
        Err(reason) => {
            tracing::error!(
                trash_dir = %configured,
                storage_root = %storage_root.display(),
                reason = %reason,
                "回收站目录校验失败；服务端删除能力已禁用"
            );
            TrashConfig {
                root,
                configured,
                enabled: false,
                blocked_reason: Some(reason),
            }
        }
    }
}

async fn inspect(root: &Path, storage_root: &Path) -> Result<PathBuf, String> {
    tokio::fs::create_dir_all(root)
        .await
        .map_err(|error| format!("无法创建回收站目录 {}：{error}", root.display()))?;
    let canonical = tokio::fs::canonicalize(root)
        .await
        .map_err(|error| format!("无法解析回收站目录 {}：{error}", root.display()))?;
    let storage = tokio::fs::canonicalize(storage_root)
        .await
        .map_err(|error| format!("无法解析媒体存储根 {}：{error}", storage_root.display()))?;
    if canonical == storage || canonical.starts_with(&storage) {
        return Err(format!(
            "回收站目录 {} 位于媒体存储根 {} 之内（属于可扫描树），请配置到存储根之外（环境变量 {TRASH_DIR_ENV}）",
            canonical.display(),
            storage.display()
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let trash_device = tokio::fs::metadata(&canonical)
            .await
            .map_err(|error| format!("无法读取回收站目录元信息：{error}"))?
            .dev();
        let storage_device = tokio::fs::metadata(&storage)
            .await
            .map_err(|error| format!("无法读取媒体存储根元信息：{error}"))?
            .dev();
        if trash_device != storage_device {
            return Err(format!(
                "回收站目录 {} 与媒体存储根 {} 不在同一文件系统，无法保证原子 rename；请把 {TRASH_DIR_ENV} 配置到同一文件系统",
                canonical.display(),
                storage.display()
            ));
        }
    }
    Ok(canonical)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    Initiated,
    FilesMoved,
    Committed,
}

impl Phase {
    fn as_str(self) -> &'static str {
        match self {
            Phase::Initiated => "initiated",
            Phase::FilesMoved => "files_moved",
            Phase::Committed => "committed",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OperationState {
    InProgress,
    Succeeded,
    Failed,
    Conflict,
    NotFound,
}

impl OperationState {
    pub fn as_str(self) -> &'static str {
        match self {
            OperationState::InProgress => "in_progress",
            OperationState::Succeeded => "succeeded",
            OperationState::Failed => "failed",
            OperationState::Conflict => "conflict",
            OperationState::NotFound => "not_found",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TagSnapshot {
    pub tag_id: String,
    pub tag_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaSnapshot {
    pub media_id: String,
    pub owner_user_id: Option<i64>,
    pub name: String,
    pub mime_type: Option<String>,
    pub is_video: bool,
    pub duration_ms: Option<i64>,
    pub video_codec: Option<String>,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub taken_at: Option<i64>,
    pub sort_at: Option<i64>,
    pub sort_source: String,
    pub time_version: i64,
    pub original_name: Option<String>,
    pub is_favorite: bool,
    pub blob_id: Option<String>,
    pub content_hash: Option<String>,
    pub asset_version: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PayloadEntry {
    pub entry_id: String,
    pub location_id: String,
    pub original_path: String,
    pub file_name: String,
    pub trash_path: String,
    pub size: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeletePayload {
    pub media: MediaSnapshot,
    pub deleted_by: String,
    pub tags: Vec<TagSnapshot>,
    /// 已成功移入回收站的原件。
    pub entries: Vec<PayloadEntry>,
    /// 移入前已不在磁盘上的原件路径（仅删除索引，不产生回收条目）。
    pub missing_paths: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreMove {
    pub entry_id: String,
    pub trash_path: String,
    pub target_path: String,
    #[serde(default)]
    pub modified_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestorePayload {
    pub media_id: String,
    pub owner_user_id: Option<i64>,
    pub moves: Vec<RestoreMove>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PurgePayload {
    pub entry_id: String,
    pub trash_path: String,
    pub media_id: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OperationRecord {
    pub id: String,
    pub media_id: String,
    pub kind: String,
    pub state: String,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
    pub created_at: i64,
    pub finished_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteOutcome {
    pub media_id: String,
    /// `succeeded` / `failed` / `conflict` / `not_found`
    pub state: String,
    pub trashed: Vec<String>,
    pub missing: Vec<String>,
    pub message: Option<String>,
    pub current_version: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreOutcome {
    pub media_id: String,
    pub restored: Vec<String>,
    pub renamed: Vec<String>,
    pub message: Option<String>,
}

#[derive(Debug, FromRow)]
struct AssetRow {
    id: String,
    blob_id: Option<String>,
    identity_state: String,
    name: String,
    mime_type: Option<String>,
    is_video: i64,
    duration_ms: Option<i64>,
    video_codec: Option<String>,
    width: Option<i64>,
    height: Option<i64>,
    taken_at: Option<i64>,
    sort_at: Option<i64>,
    sort_source: String,
    time_version: i64,
    original_name: Option<String>,
    is_favorite: i64,
    version: i64,
    owner_user_id: Option<i64>,
    content_hash: Option<String>,
}

#[derive(Debug, Clone, FromRow)]
struct LocationRow {
    id: String,
    normalized_path: String,
    file_name: String,
    size: i64,
    storage_id: String,
}

#[derive(Debug, Clone, FromRow)]
pub struct TrashEntryRow {
    pub id: String,
    pub media_id: String,
    pub owner_user_id: Option<i64>,
    pub file_name: String,
    pub original_path: String,
    pub trash_path: String,
    pub size: i64,
}

#[derive(Debug, Clone, Serialize, FromRow)]
#[serde(rename_all = "camelCase")]
pub struct TrashEntryView {
    pub id: String,
    pub media_id: String,
    pub owner_user_id: Option<i64>,
    pub name: String,
    pub original_path: String,
    pub trash_path: String,
    pub size: i64,
    pub mime_type: Option<String>,
    pub is_video: i64,
    pub sort_at: Option<i64>,
    pub deleted_by: String,
    pub deleted_at: i64,
    pub expires_at: i64,
    pub state: String,
}

#[derive(Debug)]
enum MoveError {
    Missing,
    Other(String),
}

/// 设备侧/管理端删除：把该媒体在当前用户库内的全部原件移入回收站，然后墓碑并写变更。
#[allow(clippy::too_many_arguments)]
pub async fn delete_media(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    runtime: &TrashRuntime,
    media_id: &str,
    caller_user_id: Option<i64>,
    deleted_by: &str,
    scope_key: &str,
    operation_id: &str,
    expected_version: Option<i64>,
    device_id: Option<&str>,
) -> AppResult<DeleteOutcome> {
    let _guard = runtime.lock.lock().await;
    let config = runtime.snapshot().await;
    let trash_root = config.require_enabled()?.to_path_buf();

    if let Some(existing) = get_operation(pool, scope_key, operation_id).await? {
        return Ok(outcome_from_operation(existing));
    }

    let load_failure = |outcome: DeleteOutcome| async { Ok::<_, AppError>(outcome) };

    let Some(asset) = load_asset(pool, media_id).await? else {
        return finalize_early(
            pool,
            scope_key,
            operation_id,
            media_id,
            caller_user_id,
            device_id,
            expected_version,
            OperationState::NotFound,
            None,
            "媒体不存在",
        )
        .await;
    };

    // 所有权：设备端只能删自己库内的媒体；越权按「不存在」处理，不泄露结果。
    if caller_user_id.is_some() && asset.owner_user_id != caller_user_id {
        return finalize_early(
            pool,
            scope_key,
            operation_id,
            media_id,
            caller_user_id,
            device_id,
            expected_version,
            OperationState::NotFound,
            None,
            "媒体不存在",
        )
        .await;
    }

    if asset.identity_state != "verified" {
        return finalize_early(
            pool,
            scope_key,
            operation_id,
            media_id,
            caller_user_id,
            device_id,
            expected_version,
            OperationState::NotFound,
            Some(asset.version),
            "媒体已删除",
        )
        .await;
    }

    if let Some(expected) = expected_version
        && expected != asset.version
    {
        return finalize_early(
            pool,
            scope_key,
            operation_id,
            media_id,
            caller_user_id,
            device_id,
            expected_version,
            OperationState::Conflict,
            Some(asset.version),
            "媒体版本已变化，请重新确认后使用新的操作 ID 删除",
        )
        .await;
    }

    let locations = load_locations(pool, media_id).await?;
    if locations.is_empty() {
        return Err(AppError::NotFound("media not found".to_owned()));
    }
    // 本期仅支持本地存储；出现其它存储归属时必须在动作前拒绝。
    if let Some(unsupported) = locations.iter().find(|item| item.storage_id != "local") {
        return Err(AppError::Conflict(format!(
            "媒体存在本期不支持的存储归属 {}，已拒绝删除",
            unsupported.storage_id
        )));
    }

    let tags = load_tag_snapshot(pool, media_id).await?;
    let now = now_millis();
    let mut payload = DeletePayload {
        media: MediaSnapshot {
            media_id: asset.id.clone(),
            owner_user_id: asset.owner_user_id,
            name: asset.name.clone(),
            mime_type: asset.mime_type.clone(),
            is_video: asset.is_video == 1,
            duration_ms: asset.duration_ms,
            video_codec: asset.video_codec.clone(),
            width: asset.width,
            height: asset.height,
            taken_at: asset.taken_at,
            sort_at: asset.sort_at,
            sort_source: asset.sort_source.clone(),
            time_version: asset.time_version,
            original_name: asset.original_name.clone(),
            is_favorite: asset.is_favorite == 1,
            blob_id: asset.blob_id.clone(),
            content_hash: asset.content_hash.clone(),
            asset_version: asset.version,
        },
        deleted_by: deleted_by.to_owned(),
        tags,
        entries: locations
            .iter()
            .map(|location| PayloadEntry {
                entry_id: Uuid::new_v4().to_string(),
                location_id: location.id.clone(),
                original_path: location.normalized_path.clone(),
                file_name: location.file_name.clone(),
                trash_path: trash_relative(media_id, &location.file_name),
                size: location.size,
            })
            .collect(),
        missing_paths: Vec::new(),
    };

    insert_operation(
        pool,
        scope_key,
        operation_id,
        "delete",
        media_id,
        caller_user_id,
        device_id,
        expected_version,
        Phase::Initiated,
        Some(&to_value(&payload)?),
    )
    .await?;

    let mut moved: Vec<PayloadEntry> = Vec::new();
    let mut missing: Vec<String> = Vec::new();
    for entry in payload.entries.clone() {
        let source = storage.root().join(&entry.original_path);
        let target = trash_root.join(&entry.trash_path);
        let outcome = match move_same_device(&source, &target).await {
            Ok(()) => {
                mark_pending_path(pool, &entry.original_path, operation_id).await?;
                moved.push(entry);
                None
            }
            Err(MoveError::Missing) => {
                missing.push(entry.original_path.clone());
                None
            }
            Err(MoveError::Other(message)) => Some(message),
        };
        if let Some(message) = outcome {
            // 回滚已移动文件，保持「全部目标移出后才算删除」的语义。
            for done in moved.iter().rev() {
                let back_source = trash_root.join(&done.trash_path);
                let back_target = storage.root().join(&done.original_path);
                let _ = move_same_device(&back_source, &back_target).await;
            }
            clear_operation_pending_paths(pool, operation_id).await?;
            fail_operation(pool, scope_key, operation_id, &message).await?;
            return Err(AppError::Conflict(format!(
                "移动原件到回收站失败：{message}；本次未删除任何原件"
            )));
        }
    }

    payload.entries = moved.clone();
    payload.missing_paths = missing.clone();
    let payload_value = to_value(&payload)?;

    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    commit_delete_tx(&mut transaction, &payload, now, revision).await?;
    transaction.commit().await?;

    let result = serde_json::json!({
        "mediaId": media_id,
        "state": "succeeded",
        "trashed": moved.iter().map(|e| e.original_path.clone()).collect::<Vec<_>>(),
        "missing": missing.clone(),
        "retentionDays": 30,
    });
    let _ = audit::record(
        pool,
        if caller_user_id.is_some() {
            "device"
        } else {
            "admin"
        },
        "media.trash",
        &format!(
            "{media_id}:{}",
            moved
                .iter()
                .map(|e| e.original_path.as_str())
                .collect::<Vec<_>>()
                .join(",")
        ),
        audit::SUCCESS,
    )
    .await;

    finish_operation(
        pool,
        scope_key,
        operation_id,
        OperationState::Succeeded,
        Phase::Committed,
        Some(&payload_value),
        Some(&result),
        None,
    )
    .await?;
    clear_operation_pending_paths(pool, operation_id).await?;
    let _ = load_failure;

    Ok(DeleteOutcome {
        media_id: media_id.to_owned(),
        state: "succeeded".to_owned(),
        trashed: moved.into_iter().map(|entry| entry.original_path).collect(),
        missing,
        message: None,
        current_version: None,
    })
}

#[allow(clippy::too_many_arguments)]
async fn finalize_early(
    pool: &SqlitePool,
    scope_key: &str,
    operation_id: &str,
    media_id: &str,
    caller_user_id: Option<i64>,
    device_id: Option<&str>,
    expected_version: Option<i64>,
    state: OperationState,
    current_version: Option<i64>,
    message: &str,
) -> AppResult<DeleteOutcome> {
    insert_operation(
        pool,
        scope_key,
        operation_id,
        "delete",
        media_id,
        caller_user_id,
        device_id,
        expected_version,
        Phase::Committed,
        None,
    )
    .await?;
    let result = serde_json::json!({
        "mediaId": media_id,
        "state": state.as_str(),
        "currentVersion": current_version,
    });
    finish_operation(
        pool,
        scope_key,
        operation_id,
        state,
        Phase::Committed,
        None,
        Some(&result),
        Some(message),
    )
    .await?;
    Ok(DeleteOutcome {
        media_id: media_id.to_owned(),
        state: state.as_str().to_owned(),
        trashed: Vec::new(),
        missing: Vec::new(),
        message: Some(message.to_owned()),
        current_version,
    })
}

/// 管理端恢复：按 media 归组还原全部回收条目，保留 media id 与排序时间。
pub async fn restore_media(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    runtime: &TrashRuntime,
    media_id: &str,
) -> AppResult<RestoreOutcome> {
    let _guard = runtime.lock.lock().await;
    let config = runtime.snapshot().await;
    let trash_root = config.require_enabled()?.to_path_buf();

    let entries = load_active_entries(pool, media_id).await?;
    if entries.is_empty() {
        return Err(AppError::NotFound(
            "回收站中没有该媒体的可恢复原件".to_owned(),
        ));
    }

    let Some(asset) = load_asset(pool, media_id).await? else {
        return Err(AppError::Conflict(
            "媒体索引已不存在，无法恢复；回收条目已保留".to_owned(),
        ));
    };

    // 已被另一有效 asset 占据（保留期内同内容被重新写入并重新索引）时拒绝恢复。
    if asset.identity_state == "verified" {
        let live = sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_locations WHERE media_asset_id = ?1 AND hash_state = 'verified'",
        )
        .bind(media_id)
        .fetch_one(pool)
        .await?;
        if live > 0 {
            return Err(AppError::Conflict(
                "相同内容已由另一有效媒体占据，已拒绝恢复并保留回收条目".to_owned(),
            ));
        }
    }

    // owner 校验：owner 已不存在或原路径不归属原 owner 时拒绝。
    let owner = entries[0].owner_user_id;
    if let Some(owner_id) = owner
        && !users::user_exists(pool, owner_id).await?
    {
        return Err(AppError::Conflict(
            "媒体原属主已不存在，已拒绝恢复并保留回收条目".to_owned(),
        ));
    }
    for entry in &entries {
        let resolved = users::resolve_owner_for_path(pool, &entry.original_path).await?;
        if resolved != owner {
            return Err(AppError::Conflict(format!(
                "原路径 {} 不再归属原属主，已拒绝恢复并保留回收条目",
                entry.original_path
            )));
        }
    }

    let mut moves: Vec<RestoreMove> = Vec::new();
    for entry in &entries {
        let target_path = allocate_target_path(pool, storage, &entry.original_path).await?;
        verify_parent_within_storage(storage, &target_path).await?;
        moves.push(RestoreMove {
            entry_id: entry.id.clone(),
            trash_path: entry.trash_path.clone(),
            target_path,
            modified_at: None,
        });
    }

    let operation_id = Uuid::new_v4().to_string();
    let mut payload = RestorePayload {
        media_id: media_id.to_owned(),
        owner_user_id: owner,
        moves: moves.clone(),
    };
    let payload_value = to_value(&payload)?;
    insert_operation(
        pool,
        "admin",
        &operation_id,
        "restore",
        media_id,
        None,
        None,
        None,
        Phase::Initiated,
        Some(&payload_value),
    )
    .await?;

    let mut done: Vec<RestoreMove> = Vec::new();
    for item in &moves {
        let source = trash_root.join(&item.trash_path);
        let target = storage.root().join(&item.target_path);
        if let Some(parent) = target.parent()
            && let Err(error) = tokio::fs::create_dir_all(parent).await
        {
            rollback_restore(trash_root.as_path(), storage, &done).await;
            fail_operation(pool, "admin", &operation_id, &error.to_string()).await?;
            return Err(AppError::Internal(error.into()));
        }
        let failure = match move_same_device(&source, &target).await {
            Ok(()) => {
                mark_pending_path(pool, &item.target_path, &operation_id).await?;
                let mut restored_item = item.clone();
                restored_item.modified_at = file_mtime(&target).await;
                done.push(restored_item);
                None
            }
            Err(MoveError::Missing) => Some("回收站原件缺失".to_owned()),
            Err(MoveError::Other(message)) => Some(message),
        };
        if let Some(message) = failure {
            rollback_restore(trash_root.as_path(), storage, &done).await;
            clear_operation_pending_paths(pool, &operation_id).await?;
            fail_operation(pool, "admin", &operation_id, &message).await?;
            return Err(AppError::Conflict(format!(
                "恢复原件失败：{message}；本次未恢复任何原件"
            )));
        }
    }

    // 目标路径占用以数据库占位为准，配合文件系统已就位的事实。
    for item in &moves {
        occupy_path(pool, &item.target_path, &operation_id).await?;
    }

    payload.moves = done.clone();
    let payload_value = to_value(&payload)?;
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    commit_restore_tx(&mut transaction, &payload, &asset, now, revision).await?;
    transaction.commit().await?;

    let _ = audit::record(pool, "admin", "trash.restore", media_id, audit::SUCCESS).await;

    let renamed = moves
        .iter()
        .filter(|item| {
            entries
                .iter()
                .find(|entry| entry.id == item.entry_id)
                .is_some_and(|entry| entry.original_path != item.target_path)
        })
        .map(|item| item.target_path.clone())
        .collect::<Vec<_>>();
    let result = serde_json::json!({
        "mediaId": media_id,
        "state": "succeeded",
        "restored": moves.iter().map(|m| m.target_path.clone()).collect::<Vec<_>>(),
        "renamed": renamed.clone(),
    });
    finish_operation(
        pool,
        "admin",
        &operation_id,
        OperationState::Succeeded,
        Phase::Committed,
        Some(&payload_value),
        Some(&result),
        None,
    )
    .await?;
    clear_operation_pending_paths(pool, &operation_id).await?;

    Ok(RestoreOutcome {
        media_id: media_id.to_owned(),
        restored: moves.into_iter().map(|item| item.target_path).collect(),
        renamed,
        message: None,
    })
}

/// 管理端彻底删除 / 清空：先删文件，再置条目为 `purged`。
pub async fn purge_entries(
    pool: &SqlitePool,
    runtime: &TrashRuntime,
    entry_ids: &[String],
) -> AppResult<u64> {
    let _guard = runtime.lock.lock().await;
    let config = runtime.snapshot().await;
    let trash_root = config.require_enabled()?.to_path_buf();
    let mut purged = 0_u64;
    for entry_id in entry_ids {
        let row = sqlx::query_as::<_, (String, String, String)>(
            "SELECT id, trash_path, media_id FROM trash_entries WHERE id = ?1 AND state = 'active'",
        )
        .bind(entry_id)
        .fetch_optional(pool)
        .await?;
        let Some((id, trash_path, media_id)) = row else {
            continue;
        };
        let operation_id = Uuid::new_v4().to_string();
        let payload = PurgePayload {
            entry_id: id.clone(),
            trash_path: trash_path.clone(),
            media_id: media_id.clone(),
        };
        let payload_value = to_value(&payload)?;
        insert_operation(
            pool,
            "admin",
            &operation_id,
            "purge",
            &media_id,
            None,
            None,
            None,
            Phase::Initiated,
            Some(&payload_value),
        )
        .await?;
        let absolute = trash_root.join(&trash_path);
        match tokio::fs::remove_file(&absolute).await {
            Ok(()) => {}
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) if error.kind() == ErrorKind::IsADirectory => {
                let _ = tokio::fs::remove_dir_all(&absolute).await;
            }
            Err(error) => {
                fail_operation(pool, "admin", &operation_id, &error.to_string()).await?;
                return Err(AppError::Internal(error.into()));
            }
        }
        // 清理回收站内的空目录（容量管理，失败不影响结果）。
        if let Some(parent) = absolute.parent() {
            let _ = tokio::fs::remove_dir(parent).await;
        }
        mark_purged(pool, &id).await?;
        finish_operation(
            pool,
            "admin",
            &operation_id,
            OperationState::Succeeded,
            Phase::Committed,
            Some(&payload_value),
            Some(&serde_json::json!({"entryId": id, "state": "purged"})),
            None,
        )
        .await?;
        purged += 1;
    }
    if purged > 0 {
        let _ = audit::record(
            pool,
            "admin",
            "trash.purge",
            &format!("{purged} 项"),
            audit::SUCCESS,
        )
        .await;
    }
    Ok(purged)
}

pub async fn list_entries(pool: &SqlitePool) -> AppResult<Vec<TrashEntryView>> {
    let rows = sqlx::query_as::<_, TrashEntryView>(
        r#"
        SELECT t.id, t.media_id, t.owner_user_id, COALESCE(a.name, t.file_name) AS name,
               t.original_path, t.trash_path, t.size, t.mime_type, t.is_video,
               t.sort_at, t.deleted_by, t.deleted_at, t.expires_at, t.state
        FROM trash_entries t
        LEFT JOIN media_assets a ON a.id = t.media_id
        WHERE t.state = 'active'
        ORDER BY t.deleted_at DESC, t.id ASC
        "#,
    )
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

pub async fn list_all_active_entry_ids(pool: &SqlitePool) -> AppResult<Vec<String>> {
    Ok(sqlx::query_scalar::<_, String>(
        "SELECT id FROM trash_entries WHERE state = 'active' ORDER BY deleted_at ASC",
    )
    .fetch_all(pool)
    .await?)
}

/// 到期清理：停机期间无法保证精确时刻清理，启动后与每小时各补一次。
pub async fn run_expiry_cleanup(pool: &SqlitePool, runtime: &TrashRuntime) -> AppResult<u64> {
    let config = runtime.snapshot().await;
    if !config.enabled {
        return Ok(0);
    }
    let expired = sqlx::query_scalar::<_, String>(
        "SELECT id FROM trash_entries WHERE state = 'active' AND expires_at <= ?1 ORDER BY expires_at ASC",
    )
    .bind(now_millis())
    .fetch_all(pool)
    .await?;
    if expired.is_empty() {
        return Ok(0);
    }
    purge_entries(pool, runtime, &expired).await
}

pub fn spawn_cleanup_worker(pool: SqlitePool, runtime: TrashRuntime) {
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(CLEANUP_INTERVAL).await;
            if let Err(error) = run_expiry_cleanup(&pool, &runtime).await {
                tracing::error!(error = ?error, "回收站到期清理失败");
            }
        }
    });
}

/// 启动恢复协调：进程崩溃后先恢复未完成文件操作，再允许扫描。
pub async fn recover_pending_operations(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    runtime: &TrashRuntime,
) -> AppResult<()> {
    let config = runtime.snapshot().await;
    let pending = sqlx::query_as::<_, (String, String, String, Option<String>)>(
        r#"
        SELECT scope_key, id, kind, payload
        FROM media_operations
        WHERE finished_at IS NULL
        ORDER BY created_at ASC
        "#,
    )
    .fetch_all(pool)
    .await?;
    for (scope_key, operation_id, kind, payload) in pending {
        let payload = payload.unwrap_or_default();
        let outcome = match kind.as_str() {
            "delete" => {
                recover_delete(pool, storage, &config, &scope_key, &operation_id, &payload).await
            }
            "restore" => {
                recover_restore(pool, storage, &config, &scope_key, &operation_id, &payload).await
            }
            "purge" => recover_purge(pool, &config, &scope_key, &operation_id, &payload).await,
            other => {
                tracing::warn!(kind = %other, operation_id = %operation_id, "未知文件操作类型，忽略");
                Ok(())
            }
        };
        if let Err(error) = outcome {
            tracing::error!(operation_id = %operation_id, error = ?error, "恢复未完成文件操作失败");
        }
    }
    clear_all_pending_paths(pool).await?;
    Ok(())
}

async fn recover_delete(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    config: &TrashConfig,
    scope_key: &str,
    operation_id: &str,
    payload: &str,
) -> AppResult<()> {
    let payload: DeletePayload = serde_json::from_str(payload)
        .map_err(|error| AppError::Internal(anyhow::anyhow!("解析删除操作载荷失败：{error}")))?;
    if !config.enabled {
        return Err(AppError::Conflict(
            "回收站未启用，无法前滚删除操作".to_owned(),
        ));
    }
    let trash_root = config.root.as_path();
    let mut committed: Vec<PayloadEntry> = Vec::new();
    let mut missing = payload.missing_paths.clone();
    for entry in &payload.entries {
        let source = storage.root().join(&entry.original_path);
        let target = trash_root.join(&entry.trash_path);
        let source_exists = tokio::fs::symlink_metadata(&source).await.is_ok();
        let target_exists = tokio::fs::symlink_metadata(&target).await.is_ok();
        if target_exists {
            committed.push(entry.clone());
        } else if source_exists {
            match move_same_device(&source, &target).await {
                Ok(()) => committed.push(entry.clone()),
                Err(MoveError::Missing) => missing.push(entry.original_path.clone()),
                Err(MoveError::Other(message)) => {
                    return Err(AppError::Conflict(format!("前滚删除操作失败：{message}")));
                }
            }
        } else {
            missing.push(entry.original_path.clone());
        }
    }
    let rolled = DeletePayload {
        entries: committed,
        missing_paths: missing,
        ..payload
    };
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    commit_delete_tx(&mut transaction, &rolled, now, revision).await?;
    transaction.commit().await?;
    let result = serde_json::json!({
        "mediaId": rolled.media.media_id,
        "state": "succeeded",
        "recovered": true,
        "trashed": rolled.entries.iter().map(|e| e.original_path.clone()).collect::<Vec<_>>(),
        "missing": rolled.missing_paths.clone(),
    });
    finish_operation(
        pool,
        scope_key,
        operation_id,
        OperationState::Succeeded,
        Phase::Committed,
        Some(&to_value(&rolled)?),
        Some(&result),
        None,
    )
    .await?;
    Ok(())
}

async fn recover_restore(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    config: &TrashConfig,
    scope_key: &str,
    operation_id: &str,
    payload: &str,
) -> AppResult<()> {
    let payload: RestorePayload = serde_json::from_str(payload)
        .map_err(|error| AppError::Internal(anyhow::anyhow!("解析恢复操作载荷失败：{error}")))?;
    if !config.enabled {
        return Err(AppError::Conflict(
            "回收站未启用，无法前滚恢复操作".to_owned(),
        ));
    }
    let trash_root = config.root.as_path();
    for item in &payload.moves {
        let source = trash_root.join(&item.trash_path);
        let target = storage.root().join(&item.target_path);
        let source_exists = tokio::fs::symlink_metadata(&source).await.is_ok();
        let target_exists = tokio::fs::symlink_metadata(&target).await.is_ok();
        if target_exists || !source_exists {
            continue;
        }
        if let Some(parent) = target.parent() {
            tokio::fs::create_dir_all(parent)
                .await
                .map_err(|error| AppError::Internal(error.into()))?;
        }
        if let Err(MoveError::Other(message)) = move_same_device(&source, &target).await {
            return Err(AppError::Conflict(format!("前滚恢复操作失败：{message}")));
        }
    }
    let Some(asset) = load_asset(pool, &payload.media_id).await? else {
        return Err(AppError::Conflict(
            "媒体索引已不存在，无法前滚恢复".to_owned(),
        ));
    };
    for item in &payload.moves {
        occupy_path(pool, &item.target_path, operation_id).await?;
    }
    let mut payload = payload;
    for item in &mut payload.moves {
        if item.modified_at.is_none() {
            item.modified_at = file_mtime(&storage.root().join(&item.target_path)).await;
        }
    }
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    commit_restore_tx(&mut transaction, &payload, &asset, now, revision).await?;
    transaction.commit().await?;
    finish_operation(
        pool,
        scope_key,
        operation_id,
        OperationState::Succeeded,
        Phase::Committed,
        Some(&to_value(&payload)?),
        Some(&serde_json::json!({
            "mediaId": payload.media_id,
            "state": "succeeded",
            "recovered": true,
            "restored": payload.moves.iter().map(|m| m.target_path.clone()).collect::<Vec<_>>(),
        })),
        None,
    )
    .await?;
    Ok(())
}

async fn recover_purge(
    pool: &SqlitePool,
    config: &TrashConfig,
    scope_key: &str,
    operation_id: &str,
    payload: &str,
) -> AppResult<()> {
    let payload: PurgePayload = serde_json::from_str(payload)
        .map_err(|error| AppError::Internal(anyhow::anyhow!("解析清理操作载荷失败：{error}")))?;
    if config.enabled {
        let absolute = config.root.join(&payload.trash_path);
        match tokio::fs::remove_file(&absolute).await {
            Ok(()) => {}
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) if error.kind() == ErrorKind::IsADirectory => {
                let _ = tokio::fs::remove_dir_all(&absolute).await;
            }
            Err(error) => return Err(AppError::Internal(error.into())),
        }
        if let Some(parent) = absolute.parent() {
            let _ = tokio::fs::remove_dir(parent).await;
        }
    }
    mark_purged(pool, &payload.entry_id).await?;
    finish_operation(
        pool,
        scope_key,
        operation_id,
        OperationState::Succeeded,
        Phase::Committed,
        Some(&to_value(&payload)?),
        Some(
            &serde_json::json!({"entryId": payload.entry_id, "state": "purged", "recovered": true}),
        ),
        None,
    )
    .await?;
    Ok(())
}

// ---------------------------------------------------------------------------
// 数据库补偿：把一次删除/恢复的最终落库动作收敛成可重复执行的函数。
// ---------------------------------------------------------------------------

async fn commit_delete_tx(
    transaction: &mut Transaction<'_, Sqlite>,
    payload: &DeletePayload,
    now: i64,
    revision: i64,
) -> AppResult<()> {
    let media = &payload.media;
    for entry in &payload.entries {
        sqlx::query(
            r#"
            INSERT OR IGNORE INTO trash_entries
                (id, media_id, owner_user_id, storage_id, original_path, file_name, trash_path,
                 size, blob_id, content_hash, mime_type, is_video, duration_ms, video_codec,
                 width, height, taken_at, sort_at, sort_source, time_version, original_name,
                 is_favorite, asset_version, deleted_by, operation_id, state, deleted_at,
                 expires_at, created_at, updated_at)
            VALUES (?1, ?2, ?3, 'local', ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13,
                    ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22, ?23, ?24, 'active', ?24, ?25, ?24, ?24)
            "#,
        )
        .bind(&entry.entry_id)
        .bind(&media.media_id)
        .bind(media.owner_user_id)
        .bind(&entry.original_path)
        .bind(&entry.file_name)
        .bind(&entry.trash_path)
        .bind(entry.size)
        .bind(&media.blob_id)
        .bind(&media.content_hash)
        .bind(&media.mime_type)
        .bind(if media.is_video { 1_i64 } else { 0_i64 })
        .bind(media.duration_ms)
        .bind(&media.video_codec)
        .bind(media.width)
        .bind(media.height)
        .bind(media.taken_at)
        .bind(media.sort_at)
        .bind(&media.sort_source)
        .bind(media.time_version)
        .bind(&media.original_name)
        .bind(if media.is_favorite { 1_i64 } else { 0_i64 })
        .bind(media.asset_version)
        .bind(&payload.deleted_by)
        .bind(now)
        .bind(now + TRASH_RETENTION_MS)
        .execute(&mut **transaction)
        .await?;
        for tag in &payload.tags {
            sqlx::query(
                "INSERT OR IGNORE INTO trash_entry_tags (trash_entry_id, tag_id, tag_name) VALUES (?1, ?2, ?3)",
            )
            .bind(&entry.entry_id)
            .bind(&tag.tag_id)
            .bind(&tag.tag_name)
            .execute(&mut **transaction)
            .await?;
        }
    }

    // 该媒体在当前库内的全部 location 一并移出，不可只移走一份。
    sqlx::query("DELETE FROM media_locations WHERE media_asset_id = ?1 AND storage_id = 'local'")
        .bind(&media.media_id)
        .execute(&mut **transaction)
        .await?;
    let remaining = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM media_locations WHERE media_asset_id = ?1",
    )
    .bind(&media.media_id)
    .fetch_one(&mut **transaction)
    .await?;
    if remaining == 0 {
        metadata::tombstone_media_tx(
            transaction,
            &media.media_id,
            &payload.deleted_by,
            now,
            revision,
        )
        .await?;
    }
    Ok(())
}

async fn commit_restore_tx(
    transaction: &mut Transaction<'_, Sqlite>,
    payload: &RestorePayload,
    asset: &AssetRow,
    now: i64,
    revision: i64,
) -> AppResult<()> {
    for item in &payload.moves {
        let snapshot = sqlx::query_as::<_, (String, i64)>(
            "SELECT file_name, size FROM trash_entries WHERE id = ?1",
        )
        .bind(&item.entry_id)
        .fetch_optional(&mut **transaction)
        .await?;
        let Some((file_name, size)) = snapshot else {
            continue;
        };
        let exists = sqlx::query_scalar::<_, i64>(
            "SELECT 1 FROM media_locations WHERE storage_id = 'local' AND normalized_path = ?1",
        )
        .bind(&item.target_path)
        .fetch_optional(&mut **transaction)
        .await?;
        if exists.is_some() {
            continue;
        }
        sqlx::query(
            r#"
            INSERT INTO media_locations
                (id, media_asset_id, storage_id, normalized_path, file_name, size,
                 modified_at, hash_state, observed_size, observed_mtime, created_at, updated_at)
            VALUES (?1, ?2, 'local', ?3, ?4, ?5, ?6, 'verified', ?5, ?6, ?6, ?6)
            "#,
        )
        .bind(Uuid::new_v4().to_string())
        .bind(&payload.media_id)
        .bind(&item.target_path)
        .bind(&file_name)
        .bind(size)
        .bind(now)
        .execute(&mut **transaction)
        .await?;
    }

    sqlx::query(
        r#"
        UPDATE media_assets
        SET identity_state = 'verified',
            is_favorite = ?1,
            version = version + 1,
            updated_at = ?2
        WHERE id = ?3
        "#,
    )
    .bind(if asset.is_favorite == 1 { 1_i64 } else { 0_i64 })
    .bind(now)
    .bind(&payload.media_id)
    .execute(&mut **transaction)
    .await?;

    // 标签关系随回收保存，恢复时按 tag_id 重新关联；已删除的标签跳过而不重建。
    let tags = sqlx::query_as::<_, (String, Option<String>)>(
        r#"
        SELECT DISTINCT tag_id, tag_name
        FROM trash_entry_tags
        WHERE trash_entry_id IN (SELECT id FROM trash_entries WHERE media_id = ?1)
          AND EXISTS (SELECT 1 FROM tags WHERE tags.id = trash_entry_tags.tag_id AND tags.deleted_at IS NULL)
        "#,
    )
    .bind(&payload.media_id)
    .fetch_all(&mut **transaction)
    .await?;
    for (tag_id, _) in tags {
        sqlx::query(
            "INSERT OR IGNORE INTO media_tags (tag_id, media_asset_id, version, created_at, updated_at) VALUES (?1, ?2, 1, ?3, ?3)",
        )
        .bind(&tag_id)
        .bind(&payload.media_id)
        .bind(now)
        .execute(&mut **transaction)
        .await?;
    }

    sqlx::query(
        "UPDATE trash_entries SET state = 'restored', restored_at = ?1, updated_at = ?1 WHERE media_id = ?2 AND state IN ('active', 'restoring')",
    )
    .bind(now)
    .bind(&payload.media_id)
    .execute(&mut **transaction)
    .await?;

    users::append_media_upsert_change(
        transaction,
        revision,
        &payload.media_id,
        asset.owner_user_id,
        now,
    )
    .await?;
    Ok(())
}

// ---------------------------------------------------------------------------
// 移动原语
// ---------------------------------------------------------------------------

/// 同盘 rename：目标已存在则失败，源缺失则报 `Missing`，绝不覆盖。
async fn move_same_device(source: &Path, target: &Path) -> Result<(), MoveError> {
    if let Some(parent) = target.parent()
        && let Err(error) = tokio::fs::create_dir_all(parent).await
    {
        return Err(MoveError::Other(format!("创建目标目录失败：{error}")));
    }
    if tokio::fs::symlink_metadata(target).await.is_ok() {
        return Err(MoveError::Other(format!(
            "目标路径已存在：{}",
            target.display()
        )));
    }
    match tokio::fs::symlink_metadata(source).await {
        Ok(_) => {}
        Err(error) if error.kind() == ErrorKind::NotFound => return Err(MoveError::Missing),
        Err(error) => return Err(MoveError::Other(format!("读取源文件失败：{error}"))),
    }
    let source_owned = source.to_path_buf();
    let target_owned = target.to_path_buf();
    let moved =
        tokio::task::spawn_blocking(move || rename_without_replace(&source_owned, &target_owned))
            .await
            .map_err(|error| MoveError::Other(format!("移动任务失败：{error}")))?;
    match moved {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == ErrorKind::NotFound => Err(MoveError::Missing),
        Err(error) => Err(MoveError::Other(format!(
            "移动 {} 失败：{error}",
            source.display()
        ))),
    }
}

/// 由内核原子保证“不覆盖”，检查存在后调用普通 rename 存在竞争窗口。
#[cfg(any(target_os = "linux", target_os = "macos"))]
fn rename_without_replace(source: &Path, target: &Path) -> std::io::Result<()> {
    use std::{ffi::CString, os::unix::ffi::OsStrExt};
    let source = CString::new(source.as_os_str().as_bytes())?;
    let target = CString::new(target.as_os_str().as_bytes())?;
    // SAFETY: 两个 CString 在调用期间有效、NUL 结尾；不把裸指针保留到调用之外。
    #[cfg(target_os = "macos")]
    let result = unsafe { libc::renamex_np(source.as_ptr(), target.as_ptr(), libc::RENAME_EXCL) };
    #[cfg(target_os = "linux")]
    let result = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            libc::AT_FDCWD,
            source.as_ptr(),
            libc::AT_FDCWD,
            target.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
fn rename_without_replace(_source: &Path, _target: &Path) -> std::io::Result<()> {
    Err(std::io::Error::new(
        ErrorKind::Unsupported,
        "当前平台不支持原子不覆盖移动",
    ))
}

async fn rollback_restore(
    trash_root: &Path,
    storage: &LocalFilesystemStorageDriver,
    done: &[RestoreMove],
) {
    for item in done.iter().rev() {
        let back_source = storage.root().join(&item.target_path);
        let back_target = trash_root.join(&item.trash_path);
        let _ = move_same_device(&back_source, &back_target).await;
    }
}

/// 读取文件修改时间（毫秒）。恢复后的 location 直接落真实 mtime，
/// 避免下一轮扫描因 mtime 不符而重算哈希。
async fn file_mtime(path: &Path) -> Option<i64> {
    let metadata = tokio::fs::metadata(path).await.ok()?;
    let modified = metadata.modified().ok()?;
    modified
        .duration_since(std::time::UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

/// 生成回收站内相对路径：`<media_id>/<uuid>_<file_name>`。
fn trash_relative(media_id: &str, file_name: &str) -> String {
    let safe = file_name
        .rsplit('/')
        .next()
        .unwrap_or(file_name)
        .trim_start_matches('.');
    let safe = if safe.is_empty() { "file" } else { safe };
    format!("{media_id}/{}_{}", Uuid::new_v4().simple(), safe)
}

fn split_parent(path: &str) -> (&str, &str) {
    match path.rfind('/') {
        Some(index) => (&path[..index], &path[index + 1..]),
        None => ("", path),
    }
}

/// 路径占用自动生成唯一名，同时约束文件系统与数据库占位。
async fn allocate_target_path(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    original: &str,
) -> AppResult<String> {
    let (directory, name) = split_parent(original);
    for attempt in 0..10_000_u32 {
        let candidate_name = if attempt == 0 {
            name.to_owned()
        } else {
            unique_name(name, attempt)
        };
        let candidate = if directory.is_empty() {
            candidate_name
        } else {
            format!("{directory}/{candidate_name}")
        };
        let on_disk = tokio::fs::symlink_metadata(storage.root().join(&candidate))
            .await
            .is_ok();
        let in_db = path_occupied(pool, &candidate).await?;
        if !on_disk && !in_db {
            return Ok(candidate);
        }
    }
    Err(AppError::Conflict(
        "无法为恢复的原件生成唯一文件名".to_owned(),
    ))
}

fn unique_name(base: &str, index: u32) -> String {
    let (stem, extension) = match base.rfind('.') {
        Some(position) if position > 0 => (&base[..position], &base[position..]),
        _ => (base, ""),
    };
    format!("{stem} ({index}){extension}")
}

async fn path_occupied(pool: &SqlitePool, normalized_path: &str) -> AppResult<bool> {
    let in_db = sqlx::query_scalar::<_, i64>(
        "SELECT 1 FROM media_locations WHERE storage_id = 'local' AND normalized_path = ?1",
    )
    .bind(normalized_path)
    .fetch_optional(pool)
    .await?
    .is_some();
    if in_db {
        return Ok(true);
    }
    Ok(
        sqlx::query_scalar::<_, i64>(
            "SELECT 1 FROM media_pending_paths WHERE normalized_path = ?1",
        )
        .bind(normalized_path)
        .fetch_optional(pool)
        .await?
        .is_some(),
    )
}

/// 验证目标路径的已存在祖先解析后仍位于存储根内（防符号链接越界）。
async fn verify_parent_within_storage(
    storage: &LocalFilesystemStorageDriver,
    target_path: &str,
) -> AppResult<()> {
    // 存储根自身可能包含符号链接（例如 macOS 的 /var → /private/var），
    // 必须与目标一起规范化后再比较，否则合法路径会被误判为越界。
    let canonical_root = tokio::fs::canonicalize(storage.root())
        .await
        .map_err(|error| AppError::Internal(error.into()))?;
    let (mut probe, _) = split_parent(target_path);
    loop {
        let absolute = if probe.is_empty() {
            storage.root().to_path_buf()
        } else {
            storage.root().join(probe)
        };
        if tokio::fs::symlink_metadata(&absolute).await.is_ok() {
            let canonical = tokio::fs::canonicalize(&absolute)
                .await
                .map_err(|error| AppError::Internal(error.into()))?;
            if !canonical.starts_with(&canonical_root) {
                return Err(AppError::Conflict(format!(
                    "恢复目标目录解析后越出存储根：{probe}"
                )));
            }
            return Ok(());
        }
        let (parent, _) = split_parent(probe);
        if parent == probe {
            return Ok(());
        }
        probe = parent;
    }
}

// ---------------------------------------------------------------------------
// 数据库辅助
// ---------------------------------------------------------------------------

async fn load_asset(pool: &SqlitePool, media_id: &str) -> AppResult<Option<AssetRow>> {
    Ok(sqlx::query_as::<_, AssetRow>(
        r#"
        SELECT a.id, a.blob_id, a.identity_state, a.name, a.mime_type, a.is_video,
               a.duration_ms, a.video_codec, a.width, a.height, a.taken_at, a.sort_at,
               a.sort_source, a.time_version, a.original_name, a.is_favorite, a.version,
               a.owner_user_id, b.content_hash
        FROM media_assets a
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE a.id = ?1
        "#,
    )
    .bind(media_id)
    .fetch_optional(pool)
    .await?)
}

async fn load_locations(pool: &SqlitePool, media_id: &str) -> AppResult<Vec<LocationRow>> {
    Ok(sqlx::query_as::<_, LocationRow>(
        r#"
        SELECT id, normalized_path, file_name, size, storage_id
        FROM media_locations
        WHERE media_asset_id = ?1
        ORDER BY storage_id ASC, normalized_path ASC, id ASC
        "#,
    )
    .bind(media_id)
    .fetch_all(pool)
    .await?)
}

async fn load_tag_snapshot(pool: &SqlitePool, media_id: &str) -> AppResult<Vec<TagSnapshot>> {
    let rows = sqlx::query_as::<_, (String, Option<String>)>(
        r#"
        SELECT t.id, t.name
        FROM media_tags mt
        INNER JOIN tags t ON t.id = mt.tag_id
        WHERE mt.media_asset_id = ?1
        "#,
    )
    .bind(media_id)
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(tag_id, tag_name)| TagSnapshot { tag_id, tag_name })
        .collect())
}

async fn load_active_entries(pool: &SqlitePool, media_id: &str) -> AppResult<Vec<TrashEntryRow>> {
    Ok(sqlx::query_as::<_, TrashEntryRow>(
        r#"
        SELECT id, media_id, owner_user_id, file_name, original_path, trash_path, size
        FROM trash_entries
        WHERE media_id = ?1 AND state = 'active'
        ORDER BY original_path ASC, id ASC
        "#,
    )
    .bind(media_id)
    .fetch_all(pool)
    .await?)
}

#[allow(clippy::too_many_arguments)]
async fn insert_operation(
    pool: &SqlitePool,
    scope_key: &str,
    operation_id: &str,
    kind: &str,
    media_id: &str,
    owner_user_id: Option<i64>,
    device_id: Option<&str>,
    expected_version: Option<i64>,
    phase: Phase,
    payload: Option<&serde_json::Value>,
) -> AppResult<()> {
    let now = now_millis();
    let payload_text = payload
        .map(serde_json::to_string)
        .transpose()
        .map_err(|error| AppError::Internal(error.into()))?;
    sqlx::query(
        r#"
        INSERT INTO media_operations
            (id, scope_key, kind, media_id, owner_user_id, device_id, expected_version,
             state, phase, payload, created_at, updated_at)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'in_progress', ?8, ?9, ?10, ?10)
        "#,
    )
    .bind(operation_id)
    .bind(scope_key)
    .bind(kind)
    .bind(media_id)
    .bind(owner_user_id)
    .bind(device_id)
    .bind(expected_version)
    .bind(phase.as_str())
    .bind(payload_text)
    .bind(now)
    .execute(pool)
    .await?;
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn finish_operation(
    pool: &SqlitePool,
    scope_key: &str,
    operation_id: &str,
    state: OperationState,
    phase: Phase,
    payload: Option<&serde_json::Value>,
    result: Option<&serde_json::Value>,
    error: Option<&str>,
) -> AppResult<()> {
    let now = now_millis();
    let payload_text = payload
        .map(serde_json::to_string)
        .transpose()
        .map_err(|error| AppError::Internal(error.into()))?;
    let result_text = result
        .map(serde_json::to_string)
        .transpose()
        .map_err(|error| AppError::Internal(error.into()))?;
    sqlx::query(
        r#"
        UPDATE media_operations
        SET state = ?1, phase = ?2,
            payload = COALESCE(?3, payload),
            result = ?4, error = ?5, updated_at = ?6, finished_at = ?6
        WHERE scope_key = ?7 AND id = ?8
        "#,
    )
    .bind(state.as_str())
    .bind(phase.as_str())
    .bind(payload_text)
    .bind(result_text)
    .bind(error)
    .bind(now)
    .bind(scope_key)
    .bind(operation_id)
    .execute(pool)
    .await?;
    Ok(())
}

async fn fail_operation(
    pool: &SqlitePool,
    scope_key: &str,
    operation_id: &str,
    error: &str,
) -> AppResult<()> {
    sqlx::query(
        "UPDATE media_operations SET state = 'failed', error = ?1, updated_at = ?2, finished_at = ?2 WHERE scope_key = ?3 AND id = ?4",
    )
    .bind(error)
    .bind(now_millis())
    .bind(scope_key)
    .bind(operation_id)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn get_operation(
    pool: &SqlitePool,
    scope_key: &str,
    operation_id: &str,
) -> AppResult<Option<OperationRecord>> {
    let row = sqlx::query_as::<
        _,
        (
            String,
            String,
            String,
            String,
            Option<String>,
            Option<String>,
            i64,
            Option<i64>,
        ),
    >(
        r#"
        SELECT id, media_id, kind, state, result, error, created_at, finished_at
        FROM media_operations
        WHERE scope_key = ?1 AND id = ?2
        "#,
    )
    .bind(scope_key)
    .bind(operation_id)
    .fetch_optional(pool)
    .await?;
    Ok(row.map(
        |(id, media_id, kind, state, result, error, created_at, finished_at)| OperationRecord {
            id,
            media_id,
            kind,
            state,
            result: result.and_then(|value| serde_json::from_str(&value).ok()),
            error,
            created_at,
            finished_at,
        },
    ))
}

fn outcome_from_operation(record: OperationRecord) -> DeleteOutcome {
    let trashed = record
        .result
        .as_ref()
        .and_then(|value| value.get("trashed"))
        .and_then(|value| value.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.as_str().map(str::to_owned))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let missing = record
        .result
        .as_ref()
        .and_then(|value| value.get("missing"))
        .and_then(|value| value.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.as_str().map(str::to_owned))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let current_version = record
        .result
        .as_ref()
        .and_then(|value| value.get("currentVersion"))
        .and_then(|value| value.as_i64());
    DeleteOutcome {
        media_id: record.media_id,
        state: record.state,
        trashed,
        missing,
        message: record.error,
        current_version,
    }
}

async fn mark_pending_path(
    pool: &SqlitePool,
    normalized_path: &str,
    operation_id: &str,
) -> AppResult<()> {
    sqlx::query(
        "INSERT OR REPLACE INTO media_pending_paths (normalized_path, operation_id, created_at) VALUES (?1, ?2, ?3)",
    )
    .bind(normalized_path)
    .bind(operation_id)
    .bind(now_millis())
    .execute(pool)
    .await?;
    Ok(())
}

async fn occupy_path(
    pool: &SqlitePool,
    normalized_path: &str,
    operation_id: &str,
) -> AppResult<()> {
    // 数据库占位：并发恢复同一路径时先到先得，不依赖文件系统竞态。
    sqlx::query(
        "INSERT OR IGNORE INTO media_pending_paths (normalized_path, operation_id, created_at) VALUES (?1, ?2, ?3)",
    )
    .bind(normalized_path)
    .bind(operation_id)
    .bind(now_millis())
    .execute(pool)
    .await?;
    Ok(())
}

async fn clear_operation_pending_paths(pool: &SqlitePool, operation_id: &str) -> AppResult<()> {
    sqlx::query("DELETE FROM media_pending_paths WHERE operation_id = ?1")
        .bind(operation_id)
        .execute(pool)
        .await?;
    Ok(())
}

async fn clear_all_pending_paths(pool: &SqlitePool) -> AppResult<()> {
    sqlx::query("DELETE FROM media_pending_paths")
        .execute(pool)
        .await?;
    Ok(())
}

async fn mark_purged(pool: &SqlitePool, entry_id: &str) -> AppResult<()> {
    let now = now_millis();
    sqlx::query(
        "UPDATE trash_entries SET state = 'purged', purged_at = ?1, updated_at = ?1 WHERE id = ?2",
    )
    .bind(now)
    .bind(entry_id)
    .execute(pool)
    .await?;
    Ok(())
}

fn to_value<T: Serialize>(value: &T) -> AppResult<serde_json::Value> {
    serde_json::to_value(value)
        .map_err(|error| AppError::Internal(anyhow::anyhow!("序列化操作载荷失败：{error}")))
}

/// 扫描守卫：某路径是否正处于未完成的文件操作中。
pub async fn path_has_pending_operation(
    pool: &SqlitePool,
    normalized_path: &str,
) -> Result<bool, sqlx::Error> {
    Ok(
        sqlx::query_scalar::<_, i64>(
            "SELECT 1 FROM media_pending_paths WHERE normalized_path = ?1",
        )
        .bind(normalized_path)
        .fetch_optional(pool)
        .await?
        .is_some(),
    )
}

#[cfg(test)]
mod exclusive_move_tests {
    use super::*;

    #[tokio::test]
    async fn occupied_target_preserves_both_files() {
        let dir = tempfile::tempdir().unwrap();
        let source = dir.path().join("source");
        let target = dir.path().join("target");
        tokio::fs::write(&source, b"original").await.unwrap();
        tokio::fs::write(&target, b"external occupant")
            .await
            .unwrap();
        assert!(move_same_device(&source, &target).await.is_err());
        assert_eq!(tokio::fs::read(&source).await.unwrap(), b"original");
        assert_eq!(
            tokio::fs::read(&target).await.unwrap(),
            b"external occupant"
        );
    }

    #[tokio::test]
    async fn concurrent_moves_have_one_winner_without_losing_other_source() {
        let dir = tempfile::tempdir().unwrap();
        let first = dir.path().join("first");
        let second = dir.path().join("second");
        let target = dir.path().join("target");
        tokio::fs::write(&first, b"first").await.unwrap();
        tokio::fs::write(&second, b"second").await.unwrap();
        let (a, b) = tokio::join!(
            move_same_device(&first, &target),
            move_same_device(&second, &target)
        );
        assert_ne!(a.is_ok(), b.is_ok());
        if a.is_ok() {
            assert_eq!(tokio::fs::read(&target).await.unwrap(), b"first");
            assert_eq!(tokio::fs::read(&second).await.unwrap(), b"second");
        } else {
            assert_eq!(tokio::fs::read(&target).await.unwrap(), b"second");
            assert_eq!(tokio::fs::read(&first).await.unwrap(), b"first");
        }
    }
}

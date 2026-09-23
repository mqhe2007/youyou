//! 删除与回收站相关接口：设备端删除/结果查询，管理端回收站运维。

use axum::{
    Json,
    extract::{Path, State},
    http::HeaderMap,
};
use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use crate::{
    api::AppState,
    auth::{self, Principal},
    error::{AppError, AppResult},
    trash::{self, DeleteOutcome, OperationRecord, TRASH_RETENTION_MS, TrashEntryView},
};

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct MediaDeleteRequest {
    pub operation_id: String,
    #[serde(default)]
    pub expected_version: Option<i64>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct OperationQueryResponse {
    pub media_id: String,
    pub kind: String,
    pub state: String,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
    pub created_at: i64,
    pub finished_at: Option<i64>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct MediaDeleteResponse {
    pub media_id: String,
    pub state: String,
    pub trashed: Vec<String>,
    pub missing: Vec<String>,
    pub message: Option<String>,
    pub current_version: Option<i64>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct TrashCapability {
    pub enabled: bool,
    pub directory: String,
    pub blocked_reason: Option<String>,
    pub retention_days: u32,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct TrashListResponse {
    pub capability: TrashCapability,
    pub entries: Vec<TrashEntryView>,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct TrashRestoreRequest {
    pub media_ids: Vec<String>,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct TrashPurgeRequest {
    pub entry_ids: Vec<String>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct TrashRestoreResponse {
    pub restored: Vec<String>,
    pub renamed: Vec<String>,
    pub failures: Vec<TrashFailure>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct TrashFailure {
    pub target: String,
    pub message: String,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct TrashPurgeResponse {
    pub purged: u64,
}

fn capability_of(config: &trash::TrashConfig) -> TrashCapability {
    TrashCapability {
        enabled: config.enabled,
        directory: config.configured.clone(),
        blocked_reason: config.blocked_reason.clone(),
        retention_days: u32::try_from(TRASH_RETENTION_MS / (24 * 60 * 60 * 1000)).unwrap_or(30),
    }
}

/// 设备侧删除：携带操作 ID 与媒体预期版本，删除当前用户库内的全部 location。
#[utoipa::path(
    delete,
    path = "/api/v1/media/{id}",
    tag = "media",
    params(("id" = String, Path, description = "Media ID")),
    request_body = crate::api::MediaDeleteRequest,
    responses(
        (status = 200, description = "删除结果（含部分失败与冲突）", body = MediaDeleteResponse),
        (status = 401, description = "Unauthorized"),
        (status = 403, description = "Forbidden"),
        (status = 404, description = "Not found"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn delete_media(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(request): Json<MediaDeleteRequest>,
) -> AppResult<Json<MediaDeleteResponse>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let device_id = match &principal {
        Principal::Device(identity) => Some(identity.device_id.clone()),
        Principal::Admin => None,
    };
    if request.operation_id.trim().is_empty() || request.operation_id.len() > 128 {
        return Err(AppError::BadRequest(
            "operationId must contain 1-128 characters".to_owned(),
        ));
    }
    let storage = state.storage.snapshot().await;
    let scope_key = format!("user:{user_id}");
    let deleted_by = device_id
        .as_deref()
        .map(|value| format!("device:{value}"))
        .unwrap_or_else(|| "device".to_owned());
    // FR-5 实况整体删除：删静态帧时级联删掉配对动态部分。若让客户端分两次删，
    // 客户端投影滞后就拿不到动态部分的媒体 id，会留下半张实况。
    if let Some(motion_id) =
        crate::live_photo::paired_motion_id(&state.db, &id, Some(user_id)).await?
    {
        // 操作 id 必须逐次唯一：固定 id 会让第二次删除被当成重放而静默跳过级联。
        let parent_operation = request.operation_id.trim();
        let cascade_operation = format!(
            "{}:motion",
            &parent_operation[..parent_operation.len().min(120)]
        );
        if let Err(error) = trash::delete_media(
            &state.db,
            storage.as_ref(),
            &state.trash,
            &motion_id,
            Some(user_id),
            &deleted_by,
            &scope_key,
            &cascade_operation,
            None,
            device_id.as_deref(),
        )
        .await
        {
            tracing::warn!(media_id = %motion_id, error = ?error, "级联删除实况动态部分失败，静态帧删除继续");
        }
    }
    let outcome: DeleteOutcome = trash::delete_media(
        &state.db,
        storage.as_ref(),
        &state.trash,
        &id,
        Some(user_id),
        &deleted_by,
        &scope_key,
        request.operation_id.trim(),
        request.expected_version,
        device_id.as_deref(),
    )
    .await?;
    Ok(Json(MediaDeleteResponse {
        media_id: outcome.media_id,
        state: outcome.state,
        trashed: outcome.trashed,
        missing: outcome.missing,
        message: outcome.message,
        current_version: outcome.current_version,
    }))
}

/// 删除操作结果只读查询：查询不到记录不是成功证明，也不触发自动重放。
#[utoipa::path(
    get,
    path = "/api/v1/media/operations/{operation_id}",
    tag = "media",
    params(("operation_id" = String, Path, description = "Client supplied operation ID")),
    responses(
        (status = 200, description = "操作结果", body = OperationQueryResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Not found")
    )
)]
pub(crate) async fn get_media_operation(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(operation_id): Path<String>,
) -> AppResult<Json<OperationQueryResponse>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let scope_key = format!("user:{user_id}");
    let record = trash::get_operation(&state.db, &scope_key, &operation_id)
        .await?
        .ok_or_else(|| AppError::NotFound("operation record not found".to_owned()))?;
    Ok(Json(operation_response(record)))
}

fn operation_response(record: OperationRecord) -> OperationQueryResponse {
    OperationQueryResponse {
        media_id: record.media_id,
        kind: record.kind,
        state: record.state,
        result: record.result,
        error: record.error,
        created_at: record.created_at,
        finished_at: record.finished_at,
    }
}

/// 管理端回收站列表（含删除能力状态）。
#[utoipa::path(
    get,
    path = "/api/v1/admin/trash",
    tag = "administration",
    responses(
        (status = 200, description = "回收站条目", body = TrashListResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_trash(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<TrashListResponse>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let capability = capability_of(&state.trash.snapshot().await);
    let entries = trash::list_entries(&state.db).await?;
    Ok(Json(TrashListResponse {
        capability,
        entries,
    }))
}

/// 管理端批量恢复：按 media 归组还原，失败项逐条返回。
#[utoipa::path(
    post,
    path = "/api/v1/admin/trash/restore",
    tag = "administration",
    request_body = TrashRestoreRequest,
    responses(
        (status = 200, description = "恢复结果", body = TrashRestoreResponse),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn restore_trash(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<TrashRestoreRequest>,
) -> AppResult<Json<TrashRestoreResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let storage = state.storage.snapshot().await;
    let mut restored = Vec::new();
    let mut renamed = Vec::new();
    let mut failures = Vec::new();
    for media_id in &request.media_ids {
        match trash::restore_media(&state.db, storage.as_ref(), &state.trash, media_id).await {
            Ok(outcome) => {
                restored.push(outcome.media_id);
                renamed.extend(outcome.renamed);
            }
            Err(error) => failures.push(TrashFailure {
                target: media_id.clone(),
                message: error.to_string(),
            }),
        }
    }
    Ok(Json(TrashRestoreResponse {
        restored,
        renamed,
        failures,
    }))
}

/// 管理端彻底删除指定条目（不可恢复）。
#[utoipa::path(
    post,
    path = "/api/v1/admin/trash/purge",
    tag = "administration",
    request_body = TrashPurgeRequest,
    responses(
        (status = 200, description = "彻底删除结果", body = TrashPurgeResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn purge_trash(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<TrashPurgeRequest>,
) -> AppResult<Json<TrashPurgeResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let purged = trash::purge_entries(&state.db, &state.trash, &request.entry_ids).await?;
    Ok(Json(TrashPurgeResponse { purged }))
}

/// 管理端清空回收站（不可恢复）。
#[utoipa::path(
    delete,
    path = "/api/v1/admin/trash",
    tag = "administration",
    responses(
        (status = 200, description = "清空结果", body = TrashPurgeResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn purge_all_trash(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<TrashPurgeResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let entry_ids = trash::list_all_active_entry_ids(&state.db).await?;
    let purged = trash::purge_entries(&state.db, &state.trash, &entry_ids).await?;
    let _ = crate::audit::record(
        &state.db,
        "admin",
        "trash.purge_all",
        &format!("{purged} 项"),
        crate::audit::SUCCESS,
    )
    .await;
    Ok(Json(TrashPurgeResponse { purged }))
}

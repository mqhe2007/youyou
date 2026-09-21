use axum::{
    Json,
    http::{HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use serde::Serialize;
use thiserror::Error;
use uuid::Uuid;

use crate::storage::StorageError;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("{0}")]
    BadRequest(String),

    #[error("{0}")]
    NotFound(String),

    #[error("{0}")]
    Conflict(String),

    #[error("{0}")]
    Unauthorized(String),

    #[error("{0}")]
    Forbidden(String),

    #[error("client upgrade required")]
    UpgradeRequired,

    #[error("too many authentication failures; try again later")]
    RateLimited,

    #[error("snapshot is still preparing")]
    SnapshotPreparing,

    #[error("snapshot has expired")]
    SnapshotExpired,

    #[error("changes cursor is too old; create a new bootstrap snapshot")]
    ResyncRequired,

    #[error("{0}")]
    InvalidUpload(String),

    #[error("temporary storage is full")]
    DiskFull,

    /// 服务端缺少处理该媒体类型的能力（例如未安装 HEIC 解码器）。
    /// 客户端可据此回退到原始内容（由设备本地解码）。
    #[error("{0}")]
    Unavailable(String),

    #[error(transparent)]
    Storage(#[from] StorageError),

    #[error(transparent)]
    Database(#[from] sqlx::Error),

    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl AppError {
    fn response_details_field(&self) -> Option<serde_json::Value> {
        match self {
            Self::UpgradeRequired => Some(serde_json::json!({
                "minClientVersion": crate::auth::MIN_CLIENT_VERSION,
            })),
            _ => None,
        }
    }

    fn response_details(&self) -> (StatusCode, &'static str, bool, String) {
        match self {
            Self::BadRequest(message) => (
                StatusCode::BAD_REQUEST,
                "invalid_request",
                false,
                message.clone(),
            ),
            Self::NotFound(message) => (StatusCode::NOT_FOUND, "not_found", false, message.clone()),
            Self::Conflict(message) => (StatusCode::CONFLICT, "conflict", false, message.clone()),
            Self::Unauthorized(message) => (
                StatusCode::UNAUTHORIZED,
                "unauthorized",
                false,
                message.clone(),
            ),
            Self::Forbidden(message) => {
                (StatusCode::FORBIDDEN, "forbidden", false, message.clone())
            }
            Self::UpgradeRequired => (
                StatusCode::UPGRADE_REQUIRED,
                "upgrade_required",
                false,
                "client upgrade required".to_owned(),
            ),
            Self::RateLimited => (
                StatusCode::TOO_MANY_REQUESTS,
                "rate_limited",
                true,
                "too many authentication failures; try again later".to_owned(),
            ),
            Self::SnapshotPreparing => (
                StatusCode::CONFLICT,
                "snapshot_preparing",
                true,
                "snapshot is still preparing".to_owned(),
            ),
            Self::SnapshotExpired => (
                StatusCode::GONE,
                "snapshot_expired",
                false,
                "snapshot has expired; create a new bootstrap snapshot".to_owned(),
            ),
            Self::ResyncRequired => (
                StatusCode::CONFLICT,
                "resync_required",
                true,
                "changes cursor is too old; create a new bootstrap snapshot".to_owned(),
            ),
            Self::InvalidUpload(message) => (
                StatusCode::BAD_REQUEST,
                "invalid_upload",
                false,
                message.clone(),
            ),
            Self::DiskFull => (
                StatusCode::INSUFFICIENT_STORAGE,
                "disk_full",
                true,
                "temporary storage is full".to_owned(),
            ),
            Self::Unavailable(message) => (
                StatusCode::SERVICE_UNAVAILABLE,
                "unavailable",
                true,
                message.clone(),
            ),
            Self::Storage(StorageError::InvalidPath(message)) => (
                StatusCode::BAD_REQUEST,
                "invalid_path",
                false,
                message.clone(),
            ),
            Self::Storage(StorageError::OutsideRoot) => (
                StatusCode::BAD_REQUEST,
                "path_outside_root",
                false,
                "path resolves outside the configured storage root".to_owned(),
            ),
            Self::Storage(StorageError::NotFound(path)) => (
                StatusCode::NOT_FOUND,
                "file_not_found",
                false,
                format!("file not found: {path}"),
            ),
            Self::Storage(StorageError::NotDirectory(path)) => (
                StatusCode::BAD_REQUEST,
                "not_a_directory",
                false,
                format!("not a directory: {path}"),
            ),
            Self::Storage(StorageError::ReadOnly) => (
                StatusCode::CONFLICT,
                "storage_read_only",
                false,
                "the configured storage directory is read-only".to_owned(),
            ),
            Self::Storage(StorageError::NotEmpty(path)) => (
                StatusCode::CONFLICT,
                "directory_not_empty",
                false,
                format!("directory is not empty: {path}"),
            ),
            Self::Storage(StorageError::Io(_)) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "storage_unavailable",
                true,
                "the configured storage directory is unavailable".to_owned(),
            ),
            Self::Database(_) | Self::Internal(_) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                true,
                "the server could not complete the request".to_owned(),
            ),
        }
    }
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    code: &'static str,
    message: String,
    request_id: String,
    retryable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    details: Option<serde_json::Value>,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, code, retryable, message) = self.response_details();
        let details = self.response_details_field();
        let body = Json(ErrorResponse {
            code,
            message,
            request_id: Uuid::new_v4().to_string(),
            retryable,
            details,
        });
        let mut response = (status, body).into_response();
        if matches!(self, Self::RateLimited) {
            response
                .headers_mut()
                .insert(header::RETRY_AFTER, HeaderValue::from_static("900"));
        }
        response
    }
}

pub type AppResult<T> = Result<T, AppError>;

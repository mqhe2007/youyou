use std::{
    collections::BTreeMap, future::Future, io::Cursor, net::SocketAddr, path::Path as FsPath,
    sync::Arc, time::Duration as StdDuration,
};

use axum::{
    Json, Router,
    body::{Body, Bytes},
    extract::{ConnectInfo, DefaultBodyLimit, Extension, Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{Html, IntoResponse, Response},
    routing::{delete, get, patch, post},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use futures_util::StreamExt;
use image::ImageFormat;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use sqlx::{FromRow, SqlitePool};
use tokio::{
    process::Command,
    sync::{Mutex, Notify},
    time::Duration,
};
use tower_http::{timeout::TimeoutLayer, trace::TraceLayer};
use utoipa::{OpenApi, ToSchema};
use uuid::Uuid;

use crate::{
    audit,
    auth::{self, DeviceTokens, PairingCodeResponse},
    backup,
    db::now_millis,
    error::{AppError, AppResult},
    metadata::{self, Tag},
    scan::{ScanSummary, scan_directory_with_lease},
    storage::{LocalFilesystemStorageDriver, StorageDriver, StorageRuntime},
    sync::{self, SnapshotItemRow, SnapshotRow},
    uploads::{self},
    users,
};

const SERVER_VERSION: &str = env!("CARGO_PKG_VERSION");
const CHANGE_RETENTION_MS: i64 = 30 * 24 * 60 * 60 * 1000;
const AUTH_RATE_WINDOW: StdDuration = StdDuration::from_secs(15 * 60);
const SETUP_MAX_FAILURES: u32 = 5;
const LOGIN_MAX_FAILURES: u32 = 10;
const PAIRING_MAX_FAILURES: u32 = 10;

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub storage: Arc<StorageRuntime>,
    pub data_dir: std::path::PathBuf,
    pub server_dir: std::path::PathBuf,
    pub trash: crate::trash::TrashRuntime,
    pub tmp_dir: std::path::PathBuf,
    pub setup_token_path: std::path::PathBuf,
    pub server_instance_id: String,
    pub job_notify: Arc<Notify>,
    pub storage_update_lock: Arc<Mutex<()>>,
    pub auth_rate_limiter: auth::AuthRateLimiter,
}

fn peer_key(peer: Option<&Extension<ConnectInfo<SocketAddr>>>) -> Option<String> {
    peer.map(|extension| extension.0.0.ip().to_string())
}

async fn check_auth_rate_limit(
    state: &AppState,
    scope: &str,
    peer: Option<&Extension<ConnectInfo<SocketAddr>>>,
    max_failures: u32,
) -> AppResult<Option<String>> {
    let key = peer_key(peer).map(|peer| format!("{scope}:{peer}"));
    if let Some(key) = &key {
        if !state
            .auth_rate_limiter
            .reserve(key, max_failures, AUTH_RATE_WINDOW)
            .await
        {
            return Err(AppError::RateLimited);
        }
    }
    Ok(key)
}

async fn complete_auth_attempt(state: &AppState, key: Option<&str>, failed: bool) {
    if let Some(key) = key {
        state
            .auth_rate_limiter
            .complete(key, AUTH_RATE_WINDOW, failed)
            .await;
    }
}

#[derive(OpenApi)]
#[openapi(
    info(
        title = "youyou Server API",
        version = "0.1.0",
        description = "API for a user-operated youyou media server."
    ),
    tags(
        (name = "system", description = "System, health, and authentication endpoints"),
        (name = "administration", description = "Administrative endpoints"),
        (name = "sync", description = "Client sync and bootstrap endpoints"),
        (name = "media", description = "Media library, tags, and uploads"),
        (name = "changes", description = "Change log endpoints"),
        (name = "jobs", description = "Job management endpoints")
    ),
    paths(
        health,
        get_setup_status,
        setup_admin,
        get_admin_session,
        create_admin_session,
        revoke_admin_session,
        pair_device,
        revoke_self_device,
        server_info,
        admin_status,
        get_admin_storage,
        update_admin_storage,
        test_admin_storage,
        list_users,
        create_user,
        delete_user,
        list_user_favorites,
        create_pairing_code,
        list_devices,
        revoke_device,
        rotate_device,
        list_admin_jobs,
        start_scan,
        list_backups,
        start_backup,
        get_backup,
        list_audit_log,
        admin_diagnostics,
        get_admin_job,
        start_bootstrap,
        get_bootstrap,
        list_snapshot_items,
        list_media,
        list_media_folders,
        get_media,
        media_content,
        media_thumbnail,
        set_media_favorite,
        delete_media,
        get_media_operation,
        list_trash,
        restore_trash,
        purge_trash,
        purge_all_trash,
        list_tags,
        create_tag,
        update_tag,
        delete_tag,
        add_tag_media,
        remove_tag_media,
        stream_upload,
        list_changes,
        get_job,
        cancel_job,
        retry_job,
        crate::media_library::list_library,
        crate::media_library::mkdir_library,
        crate::media_library::move_library,
        crate::media_library::delete_library_entry,
        crate::media_library::upload_library,
        crate::media_library::library_media_content,
        crate::media_library::library_media_thumbnail
    ),
    components(schemas(
        HealthResponse,
        SetupStatusResponse,
        SetupRequest,
        LoginRequest,
        PairingRequest,
        ServerResponse,
        ServerCapabilities,
        StorageSummary,
        AdminStorageResponse,
        StorageCapabilitiesResponse,
        StorageUpdateRequest,
        StorageTestResponse,
        BootstrapStartResponse,
        BootstrapStatusResponse,
        BootstrapCursors,
        SnapshotEntity,
        PageQuery,
        MediaItem,
        MediaFolder,
        MediaDeleteRequest,
        MediaDeleteResponse,
        OperationQueryResponse,
        TrashCapability,
        TrashListResponse,
        TrashRestoreRequest,
        TrashRestoreResponse,
        TrashPurgeRequest,
        TrashPurgeResponse,
        TrashFailure,
        ThumbnailQuery,
        FavoriteUpdateRequest,
        MetadataNameRequest,
        ChangeQuery,
        ChangeItem,
        ChangesResponse,
        JobResponse,
        BackupResponse,
        AuditLogEntry,
        DiagnosticsResponse,
        auth::SessionTokens,
        auth::PairingCodeResponse,
        auth::DeviceTokens,
        auth::DeviceSummary,
        users::UserSummary,
        UserFavoriteItem,
        users::CreateUserRequest,
        crate::media_library::LibraryListResponse,
        crate::media_library::LibraryFolder,
        crate::media_library::LibraryMedia,
        metadata::Tag,
        metadata::Relation,
        uploads::StreamUploadResponse
    ))
)]
pub struct ApiDoc;

async fn openapi_json() -> Json<utoipa::openapi::OpenApi> {
    Json(ApiDoc::openapi())
}

pub fn build_router(state: AppState) -> Router {
    spawn_pending_job_worker(
        state.db.clone(),
        state.storage.clone(),
        state.data_dir.clone(),
        state.job_notify.clone(),
    );
    spawn_job_lease_watchdog(state.db.clone());
    crate::trash::spawn_cleanup_worker(state.db.clone(), state.trash.clone());
    let timed_routes = Router::new()
        .route("/api-docs/openapi.json", get(openapi_json))
        .route("/admin", get(admin_index))
        .route("/admin/", get(admin_index))
        .route("/admin/admin.js", get(admin_script))
        .route("/admin/admin.css", get(admin_styles))
        .route("/admin/logo_mark.png", get(admin_logo))
        .route("/admin/login-background.jpg", get(admin_login_background))
        .route("/api/v1/health", get(health))
        .route(
            "/api/v1/admin/setup",
            get(get_setup_status).post(setup_admin),
        )
        .route(
            "/api/v1/admin/session",
            get(get_admin_session)
                .post(create_admin_session)
                .delete(revoke_admin_session),
        )
        .route("/api/v1/admin/status", get(admin_status))
        .route(
            "/api/v1/admin/storage",
            get(get_admin_storage).patch(update_admin_storage),
        )
        .route("/api/v1/admin/storage/test", post(test_admin_storage))
        .route("/api/v1/admin/users", get(list_users).post(create_user))
        .route("/api/v1/admin/users/{user_id}", delete(delete_user))
        .route(
            "/api/v1/admin/users/{user_id}/favorites",
            get(list_user_favorites),
        )
        .route(
            "/api/v1/admin/users/{user_id}/pairing-codes",
            post(create_pairing_code),
        )
        .route("/api/v1/admin/devices", get(list_devices))
        .route("/api/v1/admin/devices/{id}/revoke", post(revoke_device))
        .route("/api/v1/admin/devices/{id}/rotate", post(rotate_device))
        .route("/api/v1/admin/jobs", get(list_admin_jobs))
        .route("/api/v1/pairing", post(pair_device))
        .route("/api/v1/devices/me/revoke", post(revoke_self_device))
        .route("/api/v1/server", get(server_info))
        .route("/api/v1/sync/bootstrap", post(start_bootstrap))
        .route("/api/v1/sync/bootstrap/{snapshot_id}", get(get_bootstrap))
        .route(
            "/api/v1/sync/bootstrap/{snapshot_id}/{entity}",
            get(list_snapshot_items),
        )
        .route("/api/v1/media", get(list_media))
        .route("/api/v1/media/folders", get(list_media_folders))
        .route("/api/v1/media/{id}", get(get_media).delete(delete_media))
        .route(
            "/api/v1/media/operations/{operation_id}",
            get(get_media_operation),
        )
        .route("/api/v1/media/{id}/content", get(media_content))
        .route("/api/v1/media/{id}/thumbnail", get(media_thumbnail))
        .route("/api/v1/media/{id}/favorite", post(set_media_favorite))
        .route("/api/v1/tags", get(list_tags).post(create_tag))
        .route("/api/v1/tags/{id}", patch(update_tag).delete(delete_tag))
        .route(
            "/api/v1/tags/{id}/media/{media_id}",
            post(add_tag_media).delete(remove_tag_media),
        )
        .route("/api/v1/changes", get(list_changes))
        .route("/api/v1/admin/jobs/scan", post(start_scan))
        .route(
            "/api/v1/admin/backups",
            get(list_backups).post(start_backup),
        )
        .route("/api/v1/admin/backups/{id}", get(get_backup))
        .route("/api/v1/admin/audit-log", get(list_audit_log))
        .route(
            "/api/v1/admin/trash",
            get(list_trash).delete(purge_all_trash),
        )
        .route("/api/v1/admin/trash/restore", post(restore_trash))
        .route("/api/v1/admin/trash/purge", post(purge_trash))
        .route("/api/v1/admin/diagnostics", get(admin_diagnostics))
        .route("/api/v1/admin/jobs/{id}", get(get_admin_job))
        .route("/api/v1/jobs/{id}", get(get_job))
        .route("/api/v1/jobs/{id}/cancel", post(cancel_job))
        .route("/api/v1/jobs/{id}/retry", post(retry_job))
        .merge(crate::media_library::routes())
        .layer((
            TraceLayer::new_for_http(),
            TimeoutLayer::with_status_code(StatusCode::REQUEST_TIMEOUT, Duration::from_secs(30)),
        ));

    // Uploads (and only uploads) bypass the short request timeout so large
    // files can finish hashing and streaming without a 30s cutoff.
    let upload_routes = Router::new()
        .route("/api/v1/media/upload", post(stream_upload))
        .merge(crate::media_library::upload_routes())
        .layer(TraceLayer::new_for_http());

    timed_routes
        .merge(upload_routes)
        .layer(DefaultBodyLimit::max(
            usize::try_from(uploads::MAX_UPLOAD_BYTES).expect("max upload size fits in usize"),
        ))
        .with_state(state)
}

const ADMIN_SECURITY_POLICY: &str = concat!(
    "default-src 'self'; ",
    "script-src 'self'; ",
    "style-src 'self'; ",
    "connect-src 'self'; ",
    "img-src 'self' data:; ",
    "object-src 'none'; ",
    "base-uri 'none'; ",
    "form-action 'self'; ",
    "frame-ancestors 'none'"
);

// Domain modules keep the public HTTP surface in one router while isolating
// handlers and their local DTOs by business responsibility.
#[path = "api_admin.rs"]
mod api_admin;
#[path = "api_auth.rs"]
mod api_auth;
#[path = "api_jobs.rs"]
mod api_jobs;
#[path = "api_media.rs"]
mod api_media;
#[path = "api_sync.rs"]
mod api_sync;
#[path = "api_trash.rs"]
mod api_trash;
#[path = "api_users.rs"]
mod api_users;

pub(crate) use api_admin::*;
pub(crate) use api_auth::*;
pub(crate) use api_jobs::*;
pub(crate) use api_media::*;
pub(crate) use api_sync::*;
pub(crate) use api_trash::*;
pub(crate) use api_users::*;

use super::*;

pub(crate) fn admin_page_response(content_type: &'static str, body: impl IntoResponse) -> Response {
    let mut response = body.into_response();
    response
        .headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    response.headers_mut().insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(ADMIN_SECURITY_POLICY),
    );
    response.headers_mut().insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    response
        .headers_mut()
        .insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    response.headers_mut().insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    response.headers_mut().insert(
        "permissions-policy",
        HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
    );
    response
}

pub(crate) async fn admin_index() -> Response {
    admin_page_response(
        "text/html; charset=utf-8",
        Html(include_str!("../web/dist/index.html")),
    )
}

pub(crate) async fn admin_script() -> Response {
    admin_page_response(
        "text/javascript; charset=utf-8",
        include_str!("../web/dist/admin.js"),
    )
}

pub(crate) async fn admin_styles() -> Response {
    admin_page_response(
        "text/css; charset=utf-8",
        include_str!("../web/dist/admin.css"),
    )
}

pub(crate) async fn admin_logo() -> Response {
    admin_page_response(
        "image/png",
        Bytes::from_static(include_bytes!("../../client/assets/icons/logo_mark.png")),
    )
}

pub(crate) async fn admin_login_background() -> Response {
    admin_page_response(
        "image/jpeg",
        Bytes::from_static(include_bytes!("../web/public/login-background.jpg")),
    )
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ServerResponse {
    server_version: &'static str,
    api_version: &'static str,
    min_client_version: &'static str,
    server_instance_id: String,
    user_id: Option<String>,
    capabilities: ServerCapabilities,
    storage: StorageSummary,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ServerCapabilities {
    storage_driver: &'static str,
    supports_scan: bool,
    supports_range_read: bool,
    supports_atomic_write: bool,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StorageSummary {
    read_only: bool,
    writable: bool,
    free_bytes: Option<u64>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AdminStorageResponse {
    id: String,
    name: String,
    root_path: String,
    read_only: bool,
    writable: bool,
    free_bytes: Option<u64>,
    total_bytes: Option<u64>,
    updated_at: i64,
    capabilities: StorageCapabilitiesResponse,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StorageCapabilitiesResponse {
    read_only_scan: bool,
    read_write_atomic: bool,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StorageUpdateRequest {
    root_path: String,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StorageTestResponse {
    passed: bool,
    root_path: String,
    read_only: bool,
    writable: bool,
    free_bytes: Option<u64>,
    error: Option<&'static str>,
}

#[utoipa::path(
    get,
    path = "/api/v1/server",
    tag = "system",
    responses(
        (status = 200, description = "Server information", body = ServerResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn server_info(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<ServerResponse>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let mut response = server_info_response(&state).await?;
    response.0.user_id = match principal {
        auth::Principal::Device(identity) => identity.user_id.map(|id| id.to_string()),
        auth::Principal::Admin => None,
    };
    Ok(response)
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/status",
    tag = "administration",
    responses(
        (status = 200, description = "Server status", body = ServerResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn admin_status(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<ServerResponse>> {
    auth::require_admin(&state.db, &headers, false).await?;
    server_info_response(&state).await
}

pub(crate) async fn admin_storage_response(state: &AppState) -> AppResult<AdminStorageResponse> {
    let health = state.storage.health_check().await?;
    let row = sqlx::query_as::<_, (String, String, String, i64, i64)>(
        "SELECT id, name, root_path, read_only, updated_at FROM storages WHERE id = 'local'",
    )
    .fetch_one(&state.db)
    .await?;
    Ok(AdminStorageResponse {
        id: row.0,
        name: row.1,
        root_path: health.root_path,
        read_only: health.read_only || row.3 == 1,
        writable: health.writable && row.3 == 0,
        free_bytes: health.free_bytes,
        total_bytes: health.total_bytes,
        updated_at: row.4,
        capabilities: StorageCapabilitiesResponse {
            read_only_scan: true,
            read_write_atomic: true,
        },
    })
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/storage",
    tag = "administration",
    responses(
        (status = 200, description = "Storage configuration", body = AdminStorageResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn get_admin_storage(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<AdminStorageResponse>> {
    auth::require_admin(&state.db, &headers, false).await?;
    Ok(Json(admin_storage_response(&state).await?))
}

#[utoipa::path(
    patch,
    path = "/api/v1/admin/storage",
    tag = "administration",
    request_body = StorageUpdateRequest,
    responses(
        (status = 200, description = "Storage updated", body = AdminStorageResponse),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn update_admin_storage(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<StorageUpdateRequest>,
) -> AppResult<Json<AdminStorageResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let _update_guard = state.storage_update_lock.lock().await;
    let root_path = request.root_path.trim();
    if root_path.is_empty() || root_path.len() > 4096 {
        return Err(AppError::BadRequest(
            "rootPath must contain 1-4096 bytes".to_owned(),
        ));
    }
    validate_storage_root(root_path, &state.data_dir)?;
    let active = sqlx::query_scalar::<_, i64>(
        r#"
        SELECT EXISTS(
            SELECT 1 FROM jobs
            WHERE (kind = 'scan' AND status IN ('queued', 'running', 'interrupted'))
               OR kind = 'upload' AND status IN ('queued', 'running', 'interrupted')
        )
        OR EXISTS(
            SELECT 1 FROM uploads
            WHERE status IN ('active', 'completing')
        )
        "#,
    )
    .fetch_one(&state.db)
    .await?;
    if active == 1 {
        return Err(AppError::Conflict(
            "storage cannot be changed while a scan or upload is active".to_owned(),
        ));
    }

    let (previous, health) = state.storage.replace_root(root_path).await?;
    if health.writable {
        let candidate = state.storage.snapshot().await;
        if let Err(error) = storage_write_probe(&candidate).await {
            state.storage.restore(previous).await;
            return Err(AppError::Conflict(error.to_owned()));
        }
    }
    let root_changed = previous.root() != FsPath::new(&health.root_path);
    let mut transaction = match state.db.begin().await {
        Ok(transaction) => transaction,
        Err(error) => {
            state.storage.restore(previous).await;
            return Err(error.into());
        }
    };
    if let Err(error) = sqlx::query(
        "UPDATE storages SET root_path = ?1, read_only = ?2, updated_at = ?3 WHERE id = 'local'",
    )
    .bind(&health.root_path)
    .bind(if health.read_only { 1_i64 } else { 0_i64 })
    .bind(now_millis())
    .execute(&mut *transaction)
    .await
    {
        state.storage.restore(previous).await;
        return Err(error.into());
    }
    if root_changed {
        if let Err(error) = sqlx::query(
            "UPDATE media_locations SET hash_state = 'stale', updated_at = ?1 WHERE storage_id = 'local'",
        )
        .bind(now_millis())
        .execute(&mut *transaction)
        .await
        {
            state.storage.restore(previous).await;
            return Err(error.into());
        }
    }
    if let Err(error) = transaction.commit().await {
        state.storage.restore(previous).await;
        return Err(error.into());
    }
    // 存储根变了，回收站的「同文件系统 / 不在扫描树内」校验必须重做；
    // 不满足则本机删除能力自动禁用，并在回收站接口上明确暴露原因。
    let current_storage_root = state.storage.snapshot().await.root().to_path_buf();
    let trash_config = state
        .trash
        .reconfigure(&state.server_dir, current_storage_root.as_path())
        .await;
    if !trash_config.enabled {
        tracing::error!(
            trash_dir = %trash_config.configured,
            reason = ?trash_config.blocked_reason,
            "存储根变更后回收站校验失败；服务端删除能力已禁用"
        );
    }
    let _ = audit::record(
        &state.db,
        "admin",
        "storage.update",
        "local",
        audit::SUCCESS,
    )
    .await;
    Ok(Json(admin_storage_response(&state).await?))
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/storage/test",
    tag = "administration",
    responses(
        (status = 200, description = "Storage test result", body = StorageTestResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn test_admin_storage(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<StorageTestResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let driver = state.storage.snapshot().await;
    let health = driver.health_check().await?;
    let (passed, error) = match storage_write_probe(&driver).await {
        Ok(()) => (true, None),
        Err(error) => (false, Some(error)),
    };
    let _ = audit::record(
        &state.db,
        "admin",
        "storage.test",
        "local",
        if passed {
            audit::SUCCESS
        } else {
            audit::FAILURE
        },
    )
    .await;
    Ok(Json(StorageTestResponse {
        passed,
        root_path: health.root_path,
        read_only: health.read_only,
        writable: health.writable,
        free_bytes: health.free_bytes,
        error,
    }))
}

pub(crate) async fn storage_write_probe(
    driver: &LocalFilesystemStorageDriver,
) -> Result<(), &'static str> {
    let probe_path = format!(".youyou-storage-probe-{}.tmp", Uuid::new_v4());
    let expected = b"youyou-storage-probe";
    let result = async {
        driver
            .write_atomic(&probe_path, Box::new(Cursor::new(expected.to_vec())))
            .await
            .map_err(|_| "storage write probe failed")?;
        let actual = driver
            .read_all(&probe_path, None)
            .await
            .map_err(|_| "storage read probe failed")?;
        if actual != expected {
            return Err("storage read probe returned unexpected data");
        }
        driver
            .delete(&probe_path)
            .await
            .map_err(|_| "storage probe cleanup failed")?;
        Ok(())
    }
    .await;
    if result.is_err() {
        let _ = driver.delete(&probe_path).await;
    }
    result
}

pub(crate) fn validate_storage_root(root_path: &str, data_dir: &FsPath) -> AppResult<()> {
    let root = std::fs::canonicalize(root_path).map_err(|error| {
        AppError::BadRequest(format!("rootPath must be an existing directory: {error}"))
    })?;
    if !std::fs::metadata(&root)
        .map_err(|error| AppError::BadRequest(format!("rootPath cannot be inspected: {error}")))?
        .is_dir()
    {
        return Err(AppError::BadRequest(
            "rootPath must point to a directory".to_owned(),
        ));
    }
    let data_dir = std::fs::canonicalize(data_dir).map_err(|error| {
        AppError::Internal(anyhow::anyhow!(
            "cannot canonicalize data directory: {error}"
        ))
    })?;
    if root == FsPath::new("/") || root.starts_with(&data_dir) || data_dir.starts_with(&root) {
        return Err(AppError::BadRequest(
            "rootPath cannot overlap the server data directory".to_owned(),
        ));
    }
    Ok(())
}

pub(crate) async fn server_info_response(state: &AppState) -> AppResult<Json<ServerResponse>> {
    let health = state.storage.health_check().await?;
    Ok(Json(ServerResponse {
        server_version: SERVER_VERSION,
        api_version: "v1",
        min_client_version: auth::MIN_CLIENT_VERSION,
        server_instance_id: state.server_instance_id.clone(),
        user_id: None,
        capabilities: ServerCapabilities {
            storage_driver: "local_filesystem",
            supports_scan: true,
            supports_range_read: true,
            supports_atomic_write: true,
        },
        storage: StorageSummary {
            read_only: health.read_only,
            writable: health.writable,
            free_bytes: health.free_bytes,
        },
    }))
}

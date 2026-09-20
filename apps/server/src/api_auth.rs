use super::*;

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct HealthResponse {
    status: &'static str,
    service: &'static str,
}

#[utoipa::path(
    get,
    path = "/api/v1/health",
    tag = "system",
    responses(
        (status = 200, description = "Process is alive", body = HealthResponse)
    )
)]
pub(crate) async fn health() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        service: "youyou-server",
    })
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SetupStatusResponse {
    initialized: bool,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SetupRequest {
    setup_token: String,
    password: String,
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/setup",
    tag = "system",
    responses(
        (status = 200, description = "Setup status", body = SetupStatusResponse)
    )
)]
pub(crate) async fn get_setup_status(
    State(state): State<AppState>,
) -> AppResult<Json<SetupStatusResponse>> {
    Ok(Json(SetupStatusResponse {
        initialized: auth::is_initialized(&state.db).await?,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/setup",
    tag = "system",
    request_body = SetupRequest,
    responses(
        (status = 204, description = "Admin initialized"),
        (status = 400, description = "Bad request"),
        (status = 429, description = "Too many attempts")
    )
)]
pub(crate) async fn setup_admin(
    State(state): State<AppState>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    Json(request): Json<SetupRequest>,
) -> AppResult<StatusCode> {
    let rate_key =
        check_auth_rate_limit(&state, "setup", peer.as_ref(), SETUP_MAX_FAILURES).await?;
    let result = auth::initialize_admin(
        &state.db,
        &state.setup_token_path,
        &request.setup_token,
        &request.password,
    )
    .await;
    complete_auth_attempt(&state, rate_key.as_deref(), result.is_err()).await;
    let _ = audit::record(
        &state.db,
        "setup",
        "admin.setup",
        "admin",
        if result.is_ok() {
            audit::SUCCESS
        } else {
            audit::FAILURE
        },
    )
    .await;
    result?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LoginRequest {
    password: String,
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/session",
    tag = "system",
    responses(
        (status = 204, description = "Session is valid"),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn get_admin_session(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<StatusCode> {
    auth::require_admin(&state.db, &headers, false).await?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/session",
    tag = "system",
    request_body = LoginRequest,
    responses(
        (status = 200, description = "Session created", body = SessionTokens),
        (status = 401, description = "Invalid password"),
        (status = 429, description = "Too many attempts")
    )
)]
pub(crate) async fn create_admin_session(
    State(state): State<AppState>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    Json(request): Json<LoginRequest>,
) -> AppResult<Response> {
    let rate_key =
        check_auth_rate_limit(&state, "login", peer.as_ref(), LOGIN_MAX_FAILURES).await?;
    let result = auth::create_admin_session(&state.db, &request.password).await;
    complete_auth_attempt(&state, rate_key.as_deref(), result.is_err()).await;
    let _ = audit::record(
        &state.db,
        "admin",
        "admin.login",
        "session",
        if result.is_ok() {
            audit::SUCCESS
        } else {
            audit::FAILURE
        },
    )
    .await;
    let session = result?;
    let mut response = Json(&session).into_response();
    response.headers_mut().append(
        header::SET_COOKIE,
        HeaderValue::from_str(&format!(
            "{}={}; HttpOnly; SameSite=Strict; Path=/",
            auth::ADMIN_SESSION_COOKIE,
            session.token
        ))
        .expect("session token is valid cookie data"),
    );
    response.headers_mut().append(
        header::SET_COOKIE,
        HeaderValue::from_str(&format!(
            "{}={}; SameSite=Strict; Path=/",
            auth::ADMIN_CSRF_COOKIE,
            session.csrf_token
        ))
        .expect("csrf token is valid cookie data"),
    );
    Ok(response)
}

#[utoipa::path(
    delete,
    path = "/api/v1/admin/session",
    tag = "system",
    responses(
        (status = 204, description = "Session revoked"),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn revoke_admin_session(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Response> {
    auth::require_admin(&state.db, &headers, true).await?;
    auth::revoke_admin_session(&state.db, &headers).await?;
    let _ = audit::record(
        &state.db,
        "admin",
        "admin.logout",
        "session",
        audit::SUCCESS,
    )
    .await;
    let mut response = StatusCode::NO_CONTENT.into_response();
    response.headers_mut().append(
        header::SET_COOKIE,
        HeaderValue::from_str(&format!(
            "{}=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/",
            auth::ADMIN_SESSION_COOKIE
        ))
        .expect("cookie name is valid"),
    );
    response.headers_mut().append(
        header::SET_COOKIE,
        HeaderValue::from_str(&format!(
            "{}=; Max-Age=0; SameSite=Strict; Path=/",
            auth::ADMIN_CSRF_COOKIE
        ))
        .expect("cookie name is valid"),
    );
    Ok(response)
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PairingRequest {
    code: String,
    device_name: String,
}

#[utoipa::path(
    post,
    path = "/api/v1/pairing",
    tag = "system",
    request_body = PairingRequest,
    responses(
        (status = 200, description = "Device paired", body = DeviceTokens),
        (status = 400, description = "Bad request"),
        (status = 429, description = "Too many attempts")
    )
)]
pub(crate) async fn pair_device(
    State(state): State<AppState>,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    Json(request): Json<PairingRequest>,
) -> AppResult<Json<DeviceTokens>> {
    let rate_key =
        check_auth_rate_limit(&state, "pairing", peer.as_ref(), PAIRING_MAX_FAILURES).await?;
    let result = auth::pair_device(&state.db, &request.code, &request.device_name).await;
    complete_auth_attempt(&state, rate_key.as_deref(), result.is_err()).await;
    let _ = audit::record(
        &state.db,
        "device",
        "device.pair",
        "device",
        if result.is_ok() {
            audit::SUCCESS
        } else {
            audit::FAILURE
        },
    )
    .await;
    Ok(Json(result?))
}

#[utoipa::path(
    post,
    path = "/api/v1/devices/me/revoke",
    tag = "system",
    responses(
        (status = 204, description = "Self device revoked"),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn revoke_self_device(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<StatusCode> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let device_id = match principal {
        auth::Principal::Device(identity) => identity.device_id,
        auth::Principal::Admin => {
            return Err(AppError::BadRequest(
                "admin session cannot revoke itself as device".to_owned(),
            ));
        }
    };
    auth::revoke_device(&state.db, &device_id).await?;
    let _ = audit::record(
        &state.db,
        "device",
        "device.revoke_self",
        &device_id,
        audit::SUCCESS,
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}

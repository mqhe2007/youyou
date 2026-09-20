use super::*;

#[utoipa::path(
    post,
    path = "/api/v1/admin/users/{user_id}/pairing-codes",
    tag = "administration",
    params(
        ("user_id" = i64, Path, description = "User ID")
    ),
    responses(
        (status = 200, description = "Pairing code created", body = PairingCodeResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "User not found")
    )
)]
pub(crate) async fn create_pairing_code(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(user_id): Path<i64>,
) -> AppResult<Json<PairingCodeResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let result = auth::create_pairing_code(&state.db, user_id).await;
    let _ = audit::record(
        &state.db,
        "admin",
        "pairing-code.create",
        &format!("user:{user_id}"),
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
    get,
    path = "/api/v1/admin/devices",
    tag = "administration",
    responses(
        (status = 200, description = "List of devices", body = [DeviceSummary]),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_devices(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<Vec<auth::DeviceSummary>>> {
    auth::require_admin(&state.db, &headers, false).await?;
    Ok(Json(auth::list_devices(&state.db).await?))
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/devices/{id}/revoke",
    tag = "administration",
    params(
        ("id" = String, Path, description = "Device ID")
    ),
    responses(
        (status = 204, description = "Device revoked"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Device not found")
    )
)]
pub(crate) async fn revoke_device(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<StatusCode> {
    auth::require_admin(&state.db, &headers, true).await?;
    let result = auth::revoke_device(&state.db, &id).await;
    let _ = audit::record(
        &state.db,
        "admin",
        "device.revoke",
        "device",
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

#[utoipa::path(
    post,
    path = "/api/v1/admin/devices/{id}/rotate",
    tag = "administration",
    params(
        ("id" = String, Path, description = "Device ID")
    ),
    responses(
        (status = 200, description = "Device tokens rotated", body = DeviceTokens),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Device not found")
    )
)]
pub(crate) async fn rotate_device(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<auth::DeviceTokens>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let result = auth::rotate_device(&state.db, &id).await;
    let _ = audit::record(
        &state.db,
        "admin",
        "device.rotate",
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

// ── 用户与媒体库绑定管理 ─────────────────────────────────────

#[utoipa::path(
    get,
    path = "/api/v1/admin/users",
    tag = "administration",
    responses(
        (status = 200, description = "List of users", body = [users::UserSummary]),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_users(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<Vec<users::UserSummary>>> {
    auth::require_admin(&state.db, &headers, false).await?;
    Ok(Json(users::list_users(&state.db).await?))
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/users",
    tag = "administration",
    request_body = users::CreateUserRequest,
    responses(
        (status = 201, description = "User created", body = users::UserSummary),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn create_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<users::CreateUserRequest>,
) -> AppResult<(StatusCode, Json<users::UserSummary>)> {
    auth::require_admin(&state.db, &headers, true).await?;
    let storage = state.storage.snapshot().await;
    let result = users::create_user(&state.db, storage.root(), request).await;
    let _ = audit::record(
        &state.db,
        "admin",
        "user.create",
        "user",
        if result.is_ok() {
            audit::SUCCESS
        } else {
            audit::FAILURE
        },
    )
    .await;
    let user = result?;
    Ok((StatusCode::CREATED, Json(user)))
}

#[utoipa::path(
    delete,
    path = "/api/v1/admin/users/{user_id}",
    tag = "administration",
    params(
        ("user_id" = i64, Path, description = "User ID")
    ),
    responses(
        (status = 204, description = "User deleted"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "User not found")
    )
)]
pub(crate) async fn delete_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(user_id): Path<i64>,
) -> AppResult<StatusCode> {
    auth::require_admin(&state.db, &headers, true).await?;
    let result = users::delete_user(&state.db, user_id).await;
    let _ = audit::record(
        &state.db,
        "admin",
        "user.delete",
        &format!("user:{user_id}"),
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

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UserFavoriteItem {
    id: String,
    name: String,
    is_video: bool,
    taken_at: Option<i64>,
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/users/{user_id}/favorites",
    tag = "administration",
    params(
        ("user_id" = i64, Path, description = "User ID")
    ),
    responses(
        (status = 200, description = "User's favorite media", body = [UserFavoriteItem]),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "User not found")
    )
)]
pub(crate) async fn list_user_favorites(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(user_id): Path<i64>,
) -> AppResult<Json<Vec<UserFavoriteItem>>> {
    auth::require_admin(&state.db, &headers, false).await?;
    if !users::user_exists(&state.db, user_id).await? {
        return Err(AppError::NotFound("user not found".to_owned()));
    }
    let rows = sqlx::query_as::<_, (String, String, i64, Option<i64>)>(
        r#"
        SELECT id, name, is_video, taken_at
        FROM media_assets
        WHERE owner_user_id = ?1 AND is_favorite = 1 AND identity_state = 'verified'
        ORDER BY (sort_at IS NULL) ASC, sort_at DESC, id DESC
        LIMIT 500
        "#,
    )
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;
    Ok(Json(
        rows.into_iter()
            .map(|(id, name, is_video, taken_at)| UserFavoriteItem {
                id,
                name,
                is_video: is_video == 1,
                taken_at,
            })
            .collect(),
    ))
}

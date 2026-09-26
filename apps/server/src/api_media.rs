use super::*;

#[derive(Debug, Deserialize, ToSchema)]
pub(crate) struct PageQuery {
    pub(crate) cursor: Option<String>,
    pub(crate) limit: Option<u32>,
}

#[derive(Debug, FromRow)]
pub(crate) struct MediaRow {
    id: String,
    name: String,
    normalized_path: String,
    storage_id: String,
    size: i64,
    mime_type: Option<String>,
    is_video: i64,
    duration_ms: Option<i64>,
    video_codec: Option<String>,
    content_hash: Option<String>,
    identity_state: String,
    hash_state: String,
    width: Option<i64>,
    height: Option<i64>,
    taken_at: Option<i64>,
    sort_at: Option<i64>,
    sort_source: String,
    time_version: i64,
    original_name: Option<String>,
    version: i64,
    is_favorite: i64,
    live_role: String,
    live_embedded: i64,
    live_group_key: Option<String>,
    live_partner_id: Option<String>,
    live_partner_hash: Option<String>,
    live_motion_duration_ms: Option<i64>,
}

/// 实况照片标记（服务端投影即权威）。
///
/// `role = "motion"` 表示该行只是某段实况的动态部分，不作为独立媒体项展示；
/// `role = "still"` 且 `partnerMediaId` 为空表示动态部分尚未入库（半态）。
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LivePhotoItem {
    role: String,
    embedded: bool,
    group_key: Option<String>,
    partner_media_id: Option<String>,
    partner_content_hash: Option<String>,
    motion_duration_ms: Option<i64>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MediaItem {
    id: String,
    name: String,
    path: String,
    storage_id: String,
    size: u64,
    mime_type: Option<String>,
    is_video: bool,
    duration_ms: Option<u64>,
    video_codec: Option<String>,
    content_hash: Option<String>,
    identity_state: String,
    hash_state: String,
    width: Option<u64>,
    height: Option<u64>,
    taken_at: Option<i64>,
    sort_at: Option<i64>,
    sort_source: String,
    time_version: i64,
    original_name: Option<String>,
    version: i64,
    is_favorite: bool,
    live_photo: Option<LivePhotoItem>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Page<T> {
    pub(crate) items: Vec<T>,
    pub(crate) next_cursor: Option<String>,
    pub(crate) has_more: bool,
}

#[derive(Debug, sqlx::FromRow)]
pub(crate) struct IdempotencyRecordRow {
    status_code: i64,
    response_json: Option<String>,
    updated_at: i64,
    request_hash: Option<String>,
    claim_token: Option<String>,
}

#[derive(Clone)]
pub(crate) struct IdempotencyClaim {
    principal_id: String,
    operation: String,
    key: String,
    claim_token: String,
}

pub(crate) enum IdempotencyDecision {
    Replay {
        status: StatusCode,
        body: Option<Value>,
    },
    Execute(IdempotencyClaim),
}

pub(crate) fn idempotency_key(headers: &HeaderMap) -> AppResult<String> {
    let key = headers
        .get("Idempotency-Key")
        .ok_or_else(|| AppError::BadRequest("Idempotency-Key is required".to_owned()))?
        .to_str()
        .map_err(|_| AppError::BadRequest("Idempotency-Key is invalid".to_owned()))?
        .to_owned();
    if key.is_empty() || key.len() > 200 {
        return Err(AppError::BadRequest(
            "Idempotency-Key must contain 1-200 bytes".to_owned(),
        ));
    }
    Ok(key)
}

pub(crate) fn principal_id(principal: &auth::Principal) -> String {
    match principal {
        auth::Principal::Admin => "admin".to_owned(),
        auth::Principal::Device(identity) => format!("device:{}", identity.device_id),
    }
}

pub(crate) fn idempotency_request_hash<T: Serialize>(request: &T) -> AppResult<String> {
    let encoded =
        serde_json::to_vec(request).map_err(|error| AppError::Internal(anyhow::anyhow!(error)))?;
    Ok(hex::encode(Sha256::digest(encoded)))
}

pub(crate) async fn claim_idempotency(
    pool: &SqlitePool,
    principal: &auth::Principal,
    operation: &str,
    key: &str,
    request_hash: &str,
) -> AppResult<IdempotencyDecision> {
    let principal_id = principal_id(principal);
    let now = now_millis();
    let claim_token = Uuid::new_v4().to_string();
    let inserted = sqlx::query(
        r#"
        INSERT INTO idempotency_records
            (principal_id, operation, idempotency_key, status_code, response_json,
             created_at, updated_at, request_hash, claim_token)
        VALUES (?1, ?2, ?3, 0, NULL, ?4, ?4, ?5, ?6)
        ON CONFLICT(principal_id, operation, idempotency_key) DO NOTHING
        "#,
    )
    .bind(&principal_id)
    .bind(operation)
    .bind(key)
    .bind(now)
    .bind(request_hash)
    .bind(&claim_token)
    .execute(pool)
    .await?;
    if inserted.rows_affected() == 1 {
        return Ok(IdempotencyDecision::Execute(IdempotencyClaim {
            principal_id,
            operation: operation.to_owned(),
            key: key.to_owned(),
            claim_token,
        }));
    }

    let row = sqlx::query_as::<_, IdempotencyRecordRow>(
        r#"
        SELECT status_code, response_json, updated_at, request_hash, claim_token
        FROM idempotency_records
        WHERE principal_id = ?1 AND operation = ?2 AND idempotency_key = ?3
        "#,
    )
    .bind(&principal_id)
    .bind(operation)
    .bind(key)
    .fetch_one(pool)
    .await?;

    if let Some(stored_hash) = row.request_hash.as_deref()
        && stored_hash != request_hash
    {
        return Err(AppError::Conflict(
            "the same idempotency key was used with a different request".to_owned(),
        ));
    }

    if row.status_code > 0 {
        let status =
            StatusCode::from_u16(u16::try_from(row.status_code).map_err(|_| {
                AppError::Internal(anyhow::anyhow!("invalid idempotency status code"))
            })?)
            .map_err(|error| AppError::Internal(anyhow::anyhow!(error)))?;
        let body = row
            .response_json
            .as_deref()
            .map(serde_json::from_str)
            .transpose()
            .map_err(|error| {
                AppError::Internal(anyhow::anyhow!("decode idempotency response: {error}"))
            })?;
        return Ok(IdempotencyDecision::Replay { status, body });
    }

    const CLAIM_TTL_MS: i64 = 10 * 60 * 1000;
    if row.updated_at > now - CLAIM_TTL_MS {
        return Err(AppError::Conflict(
            "the same idempotency key is already in progress".to_owned(),
        ));
    }
    let refreshed = sqlx::query(
        r#"
        UPDATE idempotency_records
        SET status_code = 0, response_json = NULL, created_at = ?1, updated_at = ?1,
            request_hash = ?2, claim_token = ?3
        WHERE principal_id = ?4 AND operation = ?5 AND idempotency_key = ?6
          AND status_code = 0 AND updated_at <= ?7
          AND claim_token IS ?8
        "#,
    )
    .bind(now)
    .bind(request_hash)
    .bind(&claim_token)
    .bind(&principal_id)
    .bind(operation)
    .bind(key)
    .bind(now - CLAIM_TTL_MS)
    .bind(row.claim_token.as_deref())
    .execute(pool)
    .await?;
    if refreshed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "the same idempotency key is already in progress".to_owned(),
        ));
    }
    Ok(IdempotencyDecision::Execute(IdempotencyClaim {
        principal_id,
        operation: operation.to_owned(),
        key: key.to_owned(),
        claim_token,
    }))
}

pub(crate) async fn finish_idempotency(
    pool: &SqlitePool,
    claim: &IdempotencyClaim,
    status: StatusCode,
    body: Option<&str>,
) -> AppResult<()> {
    let result = sqlx::query(
        r#"
        UPDATE idempotency_records
        SET status_code = ?1, response_json = ?2, updated_at = ?3
        WHERE principal_id = ?4 AND operation = ?5 AND idempotency_key = ?6
          AND status_code = 0 AND claim_token = ?7
        "#,
    )
    .bind(i64::from(status.as_u16()))
    .bind(body)
    .bind(now_millis())
    .bind(&claim.principal_id)
    .bind(&claim.operation)
    .bind(&claim.key)
    .bind(&claim.claim_token)
    .execute(pool)
    .await?
    .rows_affected();
    if result != 1 {
        return Err(AppError::Conflict(
            "the idempotency claim was lost before completion".to_owned(),
        ));
    }
    Ok(())
}

pub(crate) async fn abort_idempotency(pool: &SqlitePool, claim: &IdempotencyClaim) {
    let _ = sqlx::query(
        "DELETE FROM idempotency_records WHERE principal_id = ?1 AND operation = ?2 AND idempotency_key = ?3 AND status_code = 0 AND claim_token = ?4",
    )
    .bind(&claim.principal_id)
    .bind(&claim.operation)
    .bind(&claim.key)
    .bind(&claim.claim_token)
    .execute(pool)
    .await;
}

pub(crate) fn spawn_idempotency_heartbeat(
    pool: SqlitePool,
    claim: IdempotencyClaim,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(StdDuration::from_secs(15)).await;
            let result = sqlx::query(
                r#"
                UPDATE idempotency_records
                SET updated_at = ?1
                WHERE principal_id = ?2 AND operation = ?3 AND idempotency_key = ?4
                  AND status_code = 0 AND claim_token = ?5
                "#,
            )
            .bind(now_millis())
            .bind(&claim.principal_id)
            .bind(&claim.operation)
            .bind(&claim.key)
            .bind(&claim.claim_token)
            .execute(&pool)
            .await;
            match result {
                Ok(result) if result.rows_affected() == 1 => {}
                Ok(_) => break,
                Err(error) => {
                    tracing::warn!(
                        operation = %claim.operation,
                        key = %claim.key,
                        error = ?error,
                        "idempotency claim heartbeat failed"
                    );
                    break;
                }
            }
        }
    })
}

pub(crate) async fn run_idempotent_json<T, F, Fut>(
    pool: &SqlitePool,
    principal: auth::Principal,
    operation: &str,
    key: String,
    request_hash: String,
    success_status: StatusCode,
    action: F,
) -> AppResult<Response>
where
    T: Serialize,
    F: FnOnce() -> Fut,
    Fut: Future<Output = AppResult<T>>,
{
    let decision = claim_idempotency(pool, &principal, operation, &key, &request_hash).await?;
    let claim = match decision {
        IdempotencyDecision::Replay { status, body } => {
            let mut response = match body {
                Some(body) => Json(body).into_response(),
                None => StatusCode::NO_CONTENT.into_response(),
            };
            *response.status_mut() = status;
            return Ok(response);
        }
        IdempotencyDecision::Execute(claim) => claim,
    };
    let heartbeat = spawn_idempotency_heartbeat(pool.clone(), claim.clone());
    let action_result = action().await;
    heartbeat.abort();
    let _ = heartbeat.await;
    let value = match action_result {
        Ok(value) => value,
        Err(error) => {
            abort_idempotency(pool, &claim).await;
            return Err(error);
        }
    };
    let body = match serde_json::to_string(&value) {
        Ok(body) => body,
        Err(error) => {
            abort_idempotency(pool, &claim).await;
            return Err(AppError::Internal(anyhow::anyhow!(error)));
        }
    };
    if let Err(error) = finish_idempotency(pool, &claim, success_status, Some(&body)).await {
        abort_idempotency(pool, &claim).await;
        return Err(error);
    }
    Ok((success_status, Json(value)).into_response())
}

pub(crate) async fn run_idempotent_empty<F, Fut>(
    pool: &SqlitePool,
    principal: auth::Principal,
    operation: &str,
    key: String,
    request_hash: String,
    action: F,
) -> AppResult<Response>
where
    F: FnOnce() -> Fut,
    Fut: Future<Output = AppResult<()>>,
{
    let decision = claim_idempotency(pool, &principal, operation, &key, &request_hash).await?;
    let claim = match decision {
        IdempotencyDecision::Replay { status, .. } => return Ok(status.into_response()),
        IdempotencyDecision::Execute(claim) => claim,
    };
    let heartbeat = spawn_idempotency_heartbeat(pool.clone(), claim.clone());
    let action_result = action().await;
    heartbeat.abort();
    let _ = heartbeat.await;
    if let Err(error) = action_result {
        abort_idempotency(pool, &claim).await;
        return Err(error);
    }
    if let Err(error) = finish_idempotency(pool, &claim, StatusCode::NO_CONTENT, None).await {
        abort_idempotency(pool, &claim).await;
        return Err(error);
    }
    Ok(StatusCode::NO_CONTENT.into_response())
}

#[utoipa::path(
    get,
    path = "/api/v1/media",
    tag = "media",
    params(
        ("cursor" = Option<String>, Query, description = "Pagination cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "List of media items", body = inline(Page<MediaItem>)),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_media(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<PageQuery>,
) -> AppResult<Json<Page<MediaItem>>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100) as i64;
    let offset = parse_offset(query.cursor.as_deref())?;
    let rows = sqlx::query_as::<_, MediaRow>(
        r#"
        WITH ranked_locations AS (
            SELECT
                l.*,
                ROW_NUMBER() OVER (
                    PARTITION BY l.media_asset_id
                    ORDER BY l.storage_id ASC, l.normalized_path ASC, l.id ASC
                ) AS location_rank
            FROM media_locations l
            WHERE l.hash_state = 'verified'
        )
        SELECT
            a.id,
            a.name,
            l.normalized_path,
            l.storage_id,
            l.size,
            a.mime_type,
            a.is_video,
            a.duration_ms,
            a.video_codec,
            b.content_hash,
            a.identity_state,
            l.hash_state,
            a.width,
            a.height,
            a.taken_at,
            a.sort_at, a.sort_source, a.time_version, a.original_name,
            a.version,
            a.is_favorite,
            a.live_role, a.live_embedded, a.live_group_key, a.live_partner_id,
            a.live_partner_hash, a.live_motion_duration_ms
        FROM media_assets a
        INNER JOIN ranked_locations l
            ON l.media_asset_id = a.id AND l.location_rank = 1
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE a.identity_state = 'verified' AND a.owner_user_id = ?3
          AND a.live_role != 'motion'
        ORDER BY (a.sort_at IS NULL) ASC, a.sort_at DESC, a.id DESC
        LIMIT ?1 OFFSET ?2
        "#,
    )
    .bind(limit + 1)
    .bind(offset)
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;

    let has_more = rows.len() > limit as usize;
    let items = rows
        .into_iter()
        .take(limit as usize)
        .map(media_item)
        .collect::<AppResult<Vec<_>>>()?;
    let next_cursor = Some(encode_page_cursor(offset + items.len() as i64));
    Ok(Json(Page {
        items,
        next_cursor,
        has_more,
    }))
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MediaFolder {
    path: String,
    media_count: u64,
}

#[utoipa::path(
    get,
    path = "/api/v1/media/folders",
    tag = "media",
    params(
        ("cursor" = Option<String>, Query, description = "Pagination cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "List of media folders", body = inline(Page<MediaFolder>)),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_media_folders(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<PageQuery>,
) -> AppResult<Json<Page<MediaFolder>>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100) as usize;
    let offset = usize::try_from(parse_offset(query.cursor.as_deref())?)
        .map_err(|_| AppError::BadRequest("cursor is too large".to_owned()))?;
    let paths = sqlx::query_scalar::<_, String>(
        r#"
        SELECT l.normalized_path
        FROM media_locations l
        INNER JOIN media_assets a ON a.id = l.media_asset_id
        WHERE l.storage_id = 'local'
          AND l.hash_state = 'verified'
          AND a.identity_state = 'verified'
          AND a.owner_user_id = ?1
        "#,
    )
    .bind(user_id)
    .fetch_all(&state.db)
    .await?;
    let mut folders = BTreeMap::<String, u64>::new();
    for path in paths {
        let mut folder = path.rsplit_once('/').map(|(parent, _)| parent);
        while let Some(path) = folder {
            *folders.entry(path.to_owned()).or_default() += 1;
            folder = path.rsplit_once('/').map(|(parent, _)| parent);
        }
    }
    let items = folders
        .into_iter()
        .skip(offset)
        .take(limit + 1)
        .map(|(path, media_count)| MediaFolder { path, media_count })
        .collect::<Vec<_>>();
    let has_more = items.len() > limit;
    let items = items.into_iter().take(limit).collect::<Vec<_>>();
    let next_cursor = Some(encode_page_cursor(
        i64::try_from(offset + items.len()).unwrap_or(i64::MAX),
    ));
    Ok(Json(Page {
        items,
        next_cursor,
        has_more,
    }))
}

#[utoipa::path(
    get,
    path = "/api/v1/media/{id}",
    tag = "media",
    params(
        ("id" = String, Path, description = "Media ID")
    ),
    responses(
        (status = 200, description = "Media item", body = MediaItem),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Media not found")
    )
)]
pub(crate) async fn get_media(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<MediaItem>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let row = find_media(&state.db, user_id, &id).await?;
    Ok(Json(media_item(row)?))
}

pub(crate) async fn find_media(pool: &SqlitePool, user_id: i64, id: &str) -> AppResult<MediaRow> {
    sqlx::query_as::<_, MediaRow>(
        r#"
        WITH ranked_locations AS (
            SELECT
                l.*,
                ROW_NUMBER() OVER (
                    PARTITION BY l.media_asset_id
                    ORDER BY l.storage_id ASC, l.normalized_path ASC, l.id ASC
                ) AS location_rank
            FROM media_locations l
            WHERE l.hash_state = 'verified'
        )
        SELECT
            a.id,
            a.name,
            l.normalized_path,
            l.storage_id,
            l.size,
            a.mime_type,
            a.is_video,
            a.duration_ms,
            a.video_codec,
            b.content_hash,
            a.identity_state,
            l.hash_state,
            a.width,
            a.height,
            a.taken_at,
            a.sort_at, a.sort_source, a.time_version, a.original_name,
            a.version,
            a.is_favorite,
            a.live_role, a.live_embedded, a.live_group_key, a.live_partner_id,
            a.live_partner_hash, a.live_motion_duration_ms
        FROM media_assets a
        INNER JOIN ranked_locations l
            ON l.media_asset_id = a.id AND l.location_rank = 1
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE a.id = ?1 AND a.identity_state = 'verified' AND a.owner_user_id = ?2
        LIMIT 1
        "#,
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| AppError::NotFound(format!("media not found: {id}")))
}

pub(crate) fn media_item(row: MediaRow) -> AppResult<MediaItem> {
    let size = u64::try_from(row.size)
        .map_err(|_| AppError::Internal(anyhow::anyhow!("media size is negative")))?;
    let live_photo = if row.live_role == "none" {
        None
    } else {
        Some(LivePhotoItem {
            role: row.live_role,
            embedded: row.live_embedded == 1,
            group_key: row.live_group_key,
            partner_media_id: row.live_partner_id,
            partner_content_hash: row.live_partner_hash,
            motion_duration_ms: row.live_motion_duration_ms,
        })
    };
    Ok(MediaItem {
        id: row.id,
        name: row.name,
        path: row.normalized_path,
        storage_id: row.storage_id,
        size,
        mime_type: row.mime_type,
        is_video: row.is_video == 1,
        duration_ms: row.duration_ms.and_then(|value| u64::try_from(value).ok()),
        video_codec: row.video_codec,
        content_hash: row.content_hash,
        identity_state: row.identity_state,
        hash_state: row.hash_state,
        width: row.width.and_then(|value| u64::try_from(value).ok()),
        height: row.height.and_then(|value| u64::try_from(value).ok()),
        taken_at: row.taken_at,
        sort_at: row.sort_at,
        sort_source: row.sort_source,
        time_version: row.time_version,
        original_name: row.original_name,
        version: row.version,
        is_favorite: row.is_favorite == 1,
        live_photo,
    })
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct FavoriteUpdateRequest {
    is_favorite: bool,
}

#[utoipa::path(
    post,
    path = "/api/v1/media/{id}/favorite",
    tag = "media",
    params(
        ("id" = String, Path, description = "Media ID")
    ),
    request_body = FavoriteUpdateRequest,
    responses(
        (status = 200, description = "Favorite state updated", body = MediaItem),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Media not found")
    )
)]
pub(crate) async fn set_media_favorite(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(request): Json<FavoriteUpdateRequest>,
) -> AppResult<Json<MediaItem>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    // 归属校验：不属于该用户的媒体按 404 处理
    find_media(&state.db, user_id, &id).await?;
    let now = now_millis();
    let mut transaction = state.db.begin().await?;
    sqlx::query(
        "UPDATE media_assets SET is_favorite = ?1, version = version + 1, updated_at = ?2 WHERE id = ?3",
    )
    .bind(if request.is_favorite { 1_i64 } else { 0_i64 })
    .bind(now)
    .bind(&id)
    .execute(&mut *transaction)
    .await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    // 收藏状态以完整媒体 upsert 进入该用户的变更流，各端据此同步。
    users::append_media_upsert_change(&mut transaction, revision, &id, Some(user_id), now).await?;
    transaction.commit().await?;
    let row = find_media(&state.db, user_id, &id).await?;
    Ok(Json(media_item(row)?))
}

#[utoipa::path(
    get,
    path = "/api/v1/media/{id}/content",
    tag = "media",
    params(
        ("id" = String, Path, description = "Media ID"),
        ("Range" = Option<String>, Header, description = "Byte range for partial content")
    ),
    responses(
        (status = 200, description = "Media content", content_type = "application/octet-stream", body = [u8]),
        (status = 206, description = "Partial content", content_type = "application/octet-stream", body = [u8]),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Media not found")
    )
)]
pub(crate) async fn media_content(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Response> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let row = find_media(&state.db, user_id, &id).await?;
    let requested_range = parse_range(headers.get(header::RANGE))?;
    let (stat, stream) = state
        .storage
        .read_stream(&row.normalized_path, requested_range)
        .await?;
    let content_length = requested_range
        .map(|(start, end)| {
            if stat.size == 0 {
                0
            } else {
                end.unwrap_or(stat.size - 1).min(stat.size - 1) - start + 1
            }
        })
        .unwrap_or(stat.size);
    let body_stream =
        stream.map(|chunk| chunk.map_err(|error| std::io::Error::other(error.to_string())));
    let mut response = Response::new(Body::from_stream(body_stream));
    *response.status_mut() = if requested_range.is_some() {
        StatusCode::PARTIAL_CONTENT
    } else {
        StatusCode::OK
    };
    let content_type = row
        .mime_type
        .or_else(|| {
            mime_guess::from_path(&row.normalized_path)
                .first_raw()
                .map(str::to_owned)
        })
        .unwrap_or_else(|| "application/octet-stream".to_owned());
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&content_type)
            .unwrap_or_else(|_| HeaderValue::from_static("application/octet-stream")),
    );
    response.headers_mut().insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(&content_length.to_string())
            .expect("content length is always an ASCII integer"),
    );
    response
        .headers_mut()
        .insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    if let Some((start, end)) = requested_range {
        let end = if stat.size == 0 {
            0
        } else {
            end.unwrap_or(stat.size - 1).min(stat.size - 1)
        };
        let content_range = format!("bytes {start}-{end}/{}", stat.size);
        response.headers_mut().insert(
            header::CONTENT_RANGE,
            HeaderValue::from_str(&content_range).expect("content range is always valid ASCII"),
        );
    }
    Ok(response)
}

#[derive(Debug, Deserialize, ToSchema)]
pub(crate) struct ThumbnailQuery {
    size: Option<u32>,
}

#[utoipa::path(
    get,
    path = "/api/v1/media/{id}/thumbnail",
    tag = "media",
    params(
        ("id" = String, Path, description = "Media ID"),
        ("size" = Option<u32>, Query, description = "Thumbnail size (64-1024)")
    ),
    responses(
        (status = 200, description = "Media thumbnail", content_type = "image/jpeg", body = [u8]),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Media not found")
    )
)]
pub(crate) async fn media_thumbnail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Query(query): Query<ThumbnailQuery>,
) -> AppResult<Response> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let row = find_media(&state.db, user_id, &id).await?;
    let size = query.size.unwrap_or(512).clamp(64, 1024);
    let cache_dir = state.data_dir.join("thumbnails");
    let cache_key = Sha256::digest(
        format!(
            "{}:{}:{}:{}",
            row.id,
            row.version,
            row.content_hash.as_deref().unwrap_or(""),
            size
        )
        .as_bytes(),
    );
    let cache_path = cache_dir.join(format!("{}.jpg", hex::encode(cache_key)));
    if let Ok(bytes) = tokio::fs::read(&cache_path).await
        && !bytes.is_empty()
    {
        return Ok(image_response(bytes));
    }

    let storage = state.storage.snapshot().await;
    let render = render_thumbnail(
        &storage,
        &row.id,
        &row.normalized_path,
        row.is_video == 1,
        size,
    )
    .await?;
    let bytes = match render {
        ThumbnailRender::Rendered(bytes) => {
            tokio::fs::create_dir_all(&cache_dir)
                .await
                .map_err(|error| AppError::Internal(error.into()))?;
            let temporary = cache_dir.join(format!(".{}.tmp-{}", row.id, Uuid::new_v4()));
            tokio::fs::write(&temporary, &bytes)
                .await
                .map_err(|error| AppError::Internal(error.into()))?;
            if let Err(error) = tokio::fs::rename(&temporary, &cache_path).await {
                let _ = tokio::fs::remove_file(&temporary).await;
                return Err(AppError::Internal(error.into()));
            }
            bytes
        }
        // 占位图不落缓存：文件修复/补上解码器后能自然恢复为真实缩略图。
        ThumbnailRender::Placeholder(bytes) => bytes,
    };
    Ok(image_response(bytes))
}

/// 缩略图渲染结果：正常渲染进入磁盘缓存，占位图不缓存。
pub(crate) enum ThumbnailRender {
    Rendered(Vec<u8>),
    Placeholder(Vec<u8>),
}

/// 渲染缩略图。解码失败（文件损坏或缺少解码器）时返回中性占位图并记录告警，
/// 避免客户端因 500 出现破图（媒体本身仍是已索引状态）。
pub(crate) async fn render_thumbnail(
    storage: &LocalFilesystemStorageDriver,
    media_id: &str,
    normalized_path: &str,
    is_video: bool,
    size: u32,
) -> AppResult<ThumbnailRender> {
    if is_video {
        return match generate_video_thumbnail(storage, normalized_path, size).await {
            Ok(bytes) => Ok(ThumbnailRender::Rendered(bytes)),
            Err(error) => {
                tracing::warn!(
                    media_id,
                    path = %normalized_path,
                    error = ?error,
                    "video thumbnail unavailable; serving placeholder"
                );
                Ok(ThumbnailRender::Placeholder(placeholder_thumbnail(size)?))
            }
        };
    }
    // HEIC/HEIF：image crate 不支持该容器，走 heif-convert / sips；解码器缺失
    // 或解码失败时返回占位图。
    let head = storage
        .read_all(normalized_path, Some((0, Some(63))))
        .await?;
    if crate::heif::is_heif(&head) {
        if crate::heif::tool().is_none() {
            // 服务端缺少 HEIF 解码器：明确告知调用方（而非返回占位图），
            // 客户端可回退到原始内容并用设备解码（Android 12 原生支持 HEIF）。
            return Err(AppError::Unavailable(
                "HEIC/HEIF 缩略图需要服务端 HEIF 解码器（heif-convert 或 sips）".to_owned(),
            ));
        }
        let source = storage.root().join(normalized_path);
        return match crate::heif::decode_thumbnail(&source, size).await {
            Ok(jpeg) => match downscale_jpeg(&jpeg, size) {
                Ok(bytes) => Ok(ThumbnailRender::Rendered(bytes)),
                Err(error) => {
                    tracing::warn!(media_id, path = %normalized_path, error = ?error,
                        "HEIC 缩略图重编码失败；使用占位图");
                    Ok(ThumbnailRender::Placeholder(placeholder_thumbnail(size)?))
                }
            },
            Err(error) => {
                tracing::warn!(
                    media_id,
                    path = %normalized_path,
                    error = ?error,
                    "HEIC 缩略图解码不可用；使用占位图"
                );
                Ok(ThumbnailRender::Placeholder(placeholder_thumbnail(size)?))
            }
        };
    }
    // 源文件读不到是真实错误（仍报错）；能读到但解不开则给占位图。
    let source = storage.read_all(normalized_path, None).await?;
    match image::load_from_memory(&source) {
        Ok(decoded) => {
            let thumbnail = decoded.thumbnail(size, size);
            let mut bytes = Cursor::new(Vec::new());
            thumbnail
                .write_to(&mut bytes, ImageFormat::Jpeg)
                .map_err(|error| {
                    AppError::Internal(anyhow::anyhow!("encode thumbnail: {error}"))
                })?;
            Ok(ThumbnailRender::Rendered(bytes.into_inner()))
        }
        Err(error) => {
            tracing::warn!(
                media_id,
                path = %normalized_path,
                error = %error,
                "image thumbnail decode failed; serving placeholder"
            );
            Ok(ThumbnailRender::Placeholder(placeholder_thumbnail(size)?))
        }
    }
}

/// 把任意 JPEG 解码并缩放到 `size` 以内再编码（HEIC 解码器输出的图可能很大）。
fn downscale_jpeg(bytes: &[u8], size: u32) -> anyhow::Result<Vec<u8>> {
    let decoded = image::load_from_memory(bytes)
        .map_err(|error| anyhow::anyhow!("decode heic output: {error}"))?;
    let thumbnail = decoded.thumbnail(size, size);
    let mut encoded = Cursor::new(Vec::new());
    thumbnail
        .write_to(&mut encoded, ImageFormat::Jpeg)
        .map_err(|error| anyhow::anyhow!("encode heic thumbnail: {error}"))?;
    Ok(encoded.into_inner())
}

/// 中性占位缩略图（浅灰底 JPEG）。
pub(crate) fn placeholder_thumbnail(size: u32) -> AppResult<Vec<u8>> {
    let image = image::RgbImage::from_pixel(size, size, image::Rgb([232_u8, 234_u8, 238_u8]));
    let mut bytes = Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image)
        .write_to(&mut bytes, ImageFormat::Jpeg)
        .map_err(|error| AppError::Internal(anyhow::anyhow!("encode placeholder: {error}")))?;
    Ok(bytes.into_inner())
}

pub(crate) async fn generate_video_thumbnail(
    storage: &LocalFilesystemStorageDriver,
    normalized_path: &str,
    size: u32,
) -> AppResult<Vec<u8>> {
    let filter = format!("scale={size}:{size}:force_original_aspect_ratio=decrease");
    let output = tokio::time::timeout(
        Duration::from_secs(15),
        Command::new("ffmpeg")
            .args(["-v", "error", "-ss", "0", "-i"])
            .arg(storage.root().join(normalized_path))
            .args([
                "-frames:v",
                "1",
                "-vf",
                &filter,
                "-f",
                "image2pipe",
                "-vcodec",
                "mjpeg",
                "pipe:1",
            ])
            .output(),
    )
    .await
    .map_err(|_| AppError::Internal(anyhow::anyhow!("video thumbnail generation timed out")))?
    .map_err(|error| AppError::Internal(anyhow::anyhow!("start ffmpeg: {error}")))?;
    if !output.status.success() || output.stdout.is_empty() {
        return Err(AppError::Internal(anyhow::anyhow!(
            "video thumbnail generation failed"
        )));
    }
    Ok(output.stdout)
}

pub(crate) fn image_response(bytes: Vec<u8>) -> Response {
    let mut response = Response::new(Body::from(bytes));
    response
        .headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static("image/jpeg"));
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("private, max-age=31536000, immutable"),
    );
    response
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MetadataNameRequest {
    name: String,
}

#[utoipa::path(
    get,
    path = "/api/v1/tags",
    tag = "media",
    params(
        ("cursor" = Option<String>, Query, description = "Pagination cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "List of tags", body = inline(Page<Tag>)),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_tags(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<PageQuery>,
) -> AppResult<Json<Page<Tag>>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100) as i64;
    let offset = parse_offset(query.cursor.as_deref())?;
    let (items, has_more) = metadata::list_tags(&state.db, user_id, offset, limit).await?;
    let next_cursor = Some(encode_page_cursor(offset + items.len() as i64));
    Ok(Json(Page {
        items,
        next_cursor,
        has_more,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v1/tags",
    tag = "media",
    request_body = MetadataNameRequest,
    responses(
        (status = 201, description = "Tag created", body = Tag),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn create_tag(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<MetadataNameRequest>,
) -> AppResult<Response> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let key = idempotency_key(&headers)?;
    let request_hash = idempotency_request_hash(&request)?;
    run_idempotent_json(
        &state.db,
        principal,
        "tag.create",
        key,
        request_hash,
        StatusCode::CREATED,
        || async { metadata::create_tag(&state.db, user_id, &request.name).await },
    )
    .await
}

#[utoipa::path(
    patch,
    path = "/api/v1/tags/{id}",
    tag = "media",
    params(
        ("id" = String, Path, description = "Tag ID"),
        ("If-Match" = String, Header, description = "Expected version for optimistic concurrency")
    ),
    request_body = MetadataNameRequest,
    responses(
        (status = 200, description = "Tag updated", body = Tag),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Tag not found"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn update_tag(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(request): Json<MetadataNameRequest>,
) -> AppResult<Response> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let key = idempotency_key(&headers)?;
    let version = parse_if_match(&headers)?;
    let request_hash = idempotency_request_hash(&(&request, version))?;
    let operation = format!("tag.update:{id}");
    run_idempotent_json(
        &state.db,
        principal,
        &operation,
        key,
        request_hash,
        StatusCode::OK,
        || async { metadata::update_tag(&state.db, user_id, &id, &request.name, version).await },
    )
    .await
}

#[utoipa::path(
    delete,
    path = "/api/v1/tags/{id}",
    tag = "media",
    params(
        ("id" = String, Path, description = "Tag ID"),
        ("If-Match" = String, Header, description = "Expected version for optimistic concurrency")
    ),
    responses(
        (status = 200, description = "Tag deleted", body = Tag),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Tag not found"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn delete_tag(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Response> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let key = idempotency_key(&headers)?;
    let version = parse_if_match(&headers)?;
    let request_hash = idempotency_request_hash(&version)?;
    let operation = format!("tag.delete:{id}");
    run_idempotent_json(
        &state.db,
        principal,
        &operation,
        key,
        request_hash,
        StatusCode::OK,
        || async { metadata::delete_tag(&state.db, user_id, &id, version).await },
    )
    .await
}

#[utoipa::path(
    post,
    path = "/api/v1/tags/{id}/media/{media_id}",
    tag = "media",
    params(
        ("id" = String, Path, description = "Tag ID"),
        ("media_id" = String, Path, description = "Media ID"),
        ("Idempotency-Key" = String, Header, description = "Idempotency key")
    ),
    responses(
        (status = 200, description = "Media added to tag", body = Relation),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Not found"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn add_tag_media(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((id, media_id)): Path<(String, String)>,
) -> AppResult<Response> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let key = idempotency_key(&headers)?;
    let request_hash = idempotency_request_hash(&())?;
    let operation = format!("tag.relation.add:{id}:{media_id}");
    run_idempotent_json(
        &state.db,
        principal,
        &operation,
        key,
        request_hash,
        StatusCode::OK,
        || async { metadata::add_tag_media(&state.db, user_id, &id, &media_id).await },
    )
    .await
}

#[utoipa::path(
    delete,
    path = "/api/v1/tags/{id}/media/{media_id}",
    tag = "media",
    params(
        ("id" = String, Path, description = "Tag ID"),
        ("media_id" = String, Path, description = "Media ID"),
        ("Idempotency-Key" = String, Header, description = "Idempotency key")
    ),
    responses(
        (status = 204, description = "Media removed from tag"),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Not found"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn remove_tag_media(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((id, media_id)): Path<(String, String)>,
) -> AppResult<Response> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let key = idempotency_key(&headers)?;
    let request_hash = idempotency_request_hash(&())?;
    let operation = format!("tag.relation.remove:{id}:{media_id}");
    run_idempotent_empty(
        &state.db,
        principal,
        &operation,
        key,
        request_hash,
        || async { metadata::remove_tag_media(&state.db, user_id, &id, &media_id).await },
    )
    .await
}

/// 流式整文件上传：客户端一次 HTTP POST 把文件字节流传上来。
///
/// Header:
/// - X-Expected-Size: 文件大小（必填）
/// - X-Expected-SHA256: 预期 SHA-256（必填，64位hex）
/// - X-File-Name: 文件名（必填）
/// - X-Mime-Type: MIME 类型（可选）
/// - X-Taken-At: 拍摄时间，毫秒时间戳（可选）
#[utoipa::path(
    post,
    path = "/api/v1/media/upload",
    tag = "media",
    params(
        ("X-Expected-Size" = u64, Header, description = "Expected file size in bytes"),
        ("X-Expected-SHA256" = String, Header, description = "Expected SHA-256 hash (64 hex chars)"),
        ("X-File-Name" = String, Header, description = "File name"),
        ("X-Mime-Type" = Option<String>, Header, description = "MIME type"),
        ("X-Taken-At" = Option<i64>, Header, description = "Capture timestamp in milliseconds; legacy clients treated as fallback"),
        ("X-Time-Version" = Option<i32>, Header, description = "Media time contract version, currently 1"),
        ("X-Sort-At" = Option<i64>, Header, description = "Stable media timestamp in milliseconds"),
        ("X-Sort-Source" = Option<String>, Header, description = "capture, filename, added, modified, legacy or unknown"),
        ("X-Original-Name" = Option<String>, Header, description = "Original source filename; empty means unrecoverable"),
        ("X-Live-Photo-Role" = Option<String>, Header, description = "still or motion; declares the file as part of a live photo"),
        ("X-Live-Photo-Group" = Option<String>, Header, description = "Opaque live photo group key shared by both parts"),
        ("X-Live-Photo-Embedded" = Option<String>, Header, description = "1 when the still carries an embedded motion part"),
        ("X-Live-Photo-Motion-Duration-Ms" = Option<i64>, Header, description = "Declared duration of the motion part in milliseconds")
    ),
    request_body(content_type = "application/octet-stream", content = [u8], description = "File binary stream"),
    responses(
        (status = 200, description = "Upload completed", body = StreamUploadResponse),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn stream_upload(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Body,
) -> AppResult<Json<uploads::StreamUploadResponse>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    // 上传固定落入用户绑定的媒体库目录；未绑定媒体库则拒绝。
    let library_root = users::require_user_library_root(&state.db, user_id).await?;

    let expected_size = headers
        .get("x-expected-size")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .ok_or_else(|| AppError::InvalidUpload("X-Expected-Size is required".to_owned()))?;
    let expected_sha256 = headers
        .get("x-expected-sha256")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| AppError::InvalidUpload("X-Expected-SHA256 is required".to_owned()))?;
    let file_name = headers
        .get("x-file-name")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| AppError::InvalidUpload("X-File-Name is required".to_owned()))?;
    let mime_type = headers.get("x-mime-type").and_then(|v| v.to_str().ok());
    let taken_at = headers
        .get("x-taken-at")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<i64>().ok());

    let storage = state.storage.snapshot().await;
    let result = uploads::stream_upload_for_user(
        &state.db,
        &state.tmp_dir,
        &storage,
        &library_root,
        expected_size,
        expected_sha256,
        file_name,
        mime_type,
        taken_at,
        headers
            .get("x-time-version")
            .filter(|v| *v == "1")
            .map(|_| crate::media_time::MediaTime {
                at: crate::media_time::valid(
                    headers
                        .get("x-sort-at")
                        .and_then(|v| v.to_str().ok())
                        .and_then(|v| v.parse().ok()),
                ),
                source: headers
                    .get("x-sort-source")
                    .and_then(|v| v.to_str().ok())
                    .filter(|v| crate::media_time::rank(v) > 0)
                    .unwrap_or("unknown")
                    .to_owned(),
            })
            .or_else(|| {
                taken_at.map(|at| crate::media_time::MediaTime {
                    at: crate::media_time::valid(Some(at)),
                    source: "legacy".into(),
                })
            }),
        headers.get("x-original-name").and_then(|v| v.to_str().ok()),
        parse_live_photo_headers(&headers),
        body,
    )
    .await?;
    Ok(Json(result))
}

/// 实况照片声明：客户端在上传时把本地识别结果交给服务端（上传会改名，基名配对不可用）。
///
/// 服务端不因此免于自证：单文件动态照片仍要由服务端确认视频字节真实存在，内容标识
/// 仍以服务端读取为准。这里只是让「静态帧先到、动态部分后到」也能立即配对。
fn parse_live_photo_headers(headers: &HeaderMap) -> crate::live_photo::DeclaredLive {
    let role = headers
        .get("x-live-photo-role")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|value| matches!(*value, "still" | "motion"))
        .map(str::to_owned);
    let group_key = headers
        .get("x-live-photo-group")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|value| !value.is_empty() && value.len() <= 128)
        .map(str::to_owned);
    let embedded = headers
        .get("x-live-photo-embedded")
        .and_then(|v| v.to_str().ok())
        .is_some_and(|value| value.trim() == "1");
    let motion_duration_ms = headers
        .get("x-live-photo-motion-duration-ms")
        .and_then(|v| v.to_str().ok())
        .and_then(|value| value.parse::<i64>().ok())
        .filter(|value| *value > 0);
    crate::live_photo::DeclaredLive {
        role,
        group_key,
        embedded,
        motion_duration_ms,
    }
}

pub(crate) fn parse_range(value: Option<&HeaderValue>) -> AppResult<Option<(u64, Option<u64>)>> {
    let Some(value) = value else {
        return Ok(None);
    };
    let value = value
        .to_str()
        .map_err(|_| AppError::BadRequest("invalid Range header".to_owned()))?;
    let range = value
        .strip_prefix("bytes=")
        .ok_or_else(|| AppError::BadRequest("only byte ranges are supported".to_owned()))?;
    if range.contains(',') {
        return Err(AppError::BadRequest(
            "multiple byte ranges are not supported".to_owned(),
        ));
    }
    let (start, end) = range
        .split_once('-')
        .ok_or_else(|| AppError::BadRequest("invalid byte range".to_owned()))?;
    if start.is_empty() {
        return Err(AppError::BadRequest(
            "suffix byte ranges are not supported".to_owned(),
        ));
    }
    let start = start
        .parse::<u64>()
        .map_err(|_| AppError::BadRequest("invalid byte range start".to_owned()))?;
    let end = if end.is_empty() {
        None
    } else {
        Some(
            end.parse::<u64>()
                .map_err(|_| AppError::BadRequest("invalid byte range end".to_owned()))?,
        )
    };
    Ok(Some((start, end)))
}

#[derive(Debug, Deserialize, ToSchema)]
pub(crate) struct ChangeQuery {
    cursor: Option<String>,
    limit: Option<u32>,
}

#[derive(Debug, FromRow)]
pub(crate) struct ChangeRow {
    revision: i64,
    event_id: String,
    entity: String,
    operation: String,
    entity_id: String,
    version: i64,
    payload: String,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ChangeItem {
    event_id: String,
    revision: i64,
    entity: String,
    operation: String,
    entity_id: String,
    version: i64,
    data: Value,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ChangesResponse {
    items: Vec<ChangeItem>,
    next_cursor: Option<String>,
    has_more: bool,
}

#[derive(Debug)]
pub(crate) struct ChangesCursor {
    pub(crate) revision: i64,
    pub(crate) event_id: String,
}

#[utoipa::path(
    get,
    path = "/api/v1/changes",
    tag = "changes",
    params(
        ("cursor" = Option<String>, Query, description = "Changes cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "List of changes", body = ChangesResponse),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Resync required")
    )
)]
pub(crate) async fn list_changes(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ChangeQuery>,
) -> AppResult<Json<ChangesResponse>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let limit = query.limit.unwrap_or(200).clamp(1, 500) as i64;
    let explicit_cursor = query.cursor.is_some();
    let cursor = parse_changes_cursor(query.cursor.as_deref())?;
    let retention_cutoff = now_millis().saturating_sub(CHANGE_RETENTION_MS);
    let mut retention_transaction = state.db.begin().await?;
    let pruned_boundary = sqlx::query_as::<_, (i64, String)>(
        "SELECT revision, event_id FROM change_log WHERE created_at < ?1 ORDER BY revision DESC, event_id DESC LIMIT 1",
    )
    .bind(retention_cutoff)
    .fetch_optional(&mut *retention_transaction)
    .await?;
    if let Some((revision, event_id)) = pruned_boundary {
        sqlx::query(
            r#"
            UPDATE change_log_meta
            SET pruned_through_revision = ?1, pruned_through_event_id = ?2
            WHERE id = 1
              AND (
                  pruned_through_revision < ?1
                  OR (
                      pruned_through_revision = ?1
                      AND pruned_through_event_id < ?2
                  )
              )
            "#,
        )
        .bind(revision)
        .bind(event_id)
        .execute(&mut *retention_transaction)
        .await?;
    }
    sqlx::query("DELETE FROM change_log WHERE created_at < ?1")
        .bind(retention_cutoff)
        .execute(&mut *retention_transaction)
        .await?;
    retention_transaction.commit().await?;
    let pruned_boundary = sqlx::query_as::<_, (i64, String)>(
        "SELECT pruned_through_revision, pruned_through_event_id FROM change_log_meta WHERE id = 1",
    )
    .fetch_one(&state.db)
    .await?;
    if explicit_cursor
        && (cursor.revision < pruned_boundary.0
            || (cursor.revision == pruned_boundary.0 && cursor.event_id < pruned_boundary.1))
    {
        return Err(AppError::ResyncRequired);
    }
    let rows = sqlx::query_as::<_, ChangeRow>(
        r#"
        SELECT revision, event_id, entity, operation, entity_id, version, payload
        FROM change_log
        WHERE owner_user_id = ?3
          AND (revision > ?1 OR (revision = ?1 AND event_id > ?2))
        ORDER BY revision ASC, event_id ASC
        LIMIT ?4
        "#,
    )
    .bind(cursor.revision)
    .bind(&cursor.event_id)
    .bind(user_id)
    .bind(limit + 1)
    .fetch_all(&state.db)
    .await?;

    let has_more = rows.len() > limit as usize;
    let items = rows
        .into_iter()
        .take(limit as usize)
        .map(|row| {
            let data = serde_json::from_str(&row.payload).map_err(|error| {
                AppError::Internal(anyhow::anyhow!(
                    "decode change payload {}: {error}",
                    row.event_id
                ))
            })?;
            Ok(ChangeItem {
                event_id: row.event_id,
                revision: row.revision,
                entity: row.entity,
                operation: row.operation,
                entity_id: row.entity_id,
                version: row.version,
                data,
            })
        })
        .collect::<AppResult<Vec<_>>>()?;
    let next_cursor = Some(match items.last() {
        Some(item) => sync::encode_changes_cursor(item.revision, &item.event_id),
        None => {
            let end_revision = sqlx::query_scalar::<_, i64>(
                r#"
                SELECT MAX(revision)
                FROM (
                    SELECT COALESCE(MAX(revision), 0) AS revision
                    FROM change_log
                    UNION ALL
                    SELECT pruned_through_revision AS revision
                    FROM change_log_meta
                    WHERE id = 1
                )
                "#,
            )
            .fetch_one(&state.db)
            .await?;
            if explicit_cursor && cursor.revision > end_revision {
                sync::encode_changes_cursor(cursor.revision, &cursor.event_id)
            } else {
                sync::encode_changes_cursor(end_revision, sync::CHANGES_CURSOR_END)
            }
        }
    });
    Ok(Json(ChangesResponse {
        items,
        next_cursor,
        has_more,
    }))
}

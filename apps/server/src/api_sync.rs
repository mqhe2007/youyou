use super::*;

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BootstrapStartResponse {
    snapshot_id: String,
    job_id: String,
    state: &'static str,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BootstrapStatusResponse {
    server_version: &'static str,
    snapshot_id: String,
    job_id: String,
    state: String,
    snapshot_revision: Option<i64>,
    expires_at: i64,
    changes_cursor: Option<String>,
    cursors: BootstrapCursors,
    last_error: Option<String>,
}

#[derive(Debug, Serialize, ToSchema)]
pub(crate) struct BootstrapCursors {
    media: String,
    tags: String,
    relations: String,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SnapshotEntity {
    entity_id: String,
    entity_version: i64,
    sort_at: Option<i64>,
    data: Value,
}

#[utoipa::path(
    post,
    path = "/api/v1/sync/bootstrap",
    tag = "sync",
    responses(
        (status = 202, description = "Bootstrap started", body = BootstrapStartResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn start_bootstrap(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<(StatusCode, Json<BootstrapStartResponse>)> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let snapshot_id = Uuid::new_v4().to_string();
    let job_id = Uuid::new_v4().to_string();
    let now = now_millis();
    let mut transaction = state.db.begin().await?;
    sqlx::query(
        "INSERT INTO jobs (id, kind, status, user_id, created_at, updated_at) VALUES (?1, 'bootstrap', 'queued', ?3, ?2, ?2)",
    )
    .bind(&job_id)
    .bind(now)
    .bind(user_id)
    .execute(&mut *transaction)
    .await?;
    sqlx::query(
        r#"
        INSERT INTO sync_snapshots
            (id, job_id, state, user_id, expires_at, created_at, updated_at)
        VALUES (?1, ?2, 'preparing', ?3, ?4, ?5, ?5)
        "#,
    )
    .bind(&snapshot_id)
    .bind(&job_id)
    .bind(user_id)
    .bind(now + 24 * 60 * 60 * 1000)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    state.job_notify.notify_one();

    Ok((
        StatusCode::ACCEPTED,
        Json(BootstrapStartResponse {
            snapshot_id,
            job_id,
            state: "preparing",
        }),
    ))
}

#[utoipa::path(
    get,
    path = "/api/v1/sync/bootstrap/{snapshot_id}",
    tag = "sync",
    params(
        ("snapshot_id" = String, Path, description = "Snapshot ID")
    ),
    responses(
        (status = 200, description = "Bootstrap status", body = BootstrapStatusResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Snapshot not found")
    )
)]
pub(crate) async fn get_bootstrap(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(snapshot_id): Path<String>,
) -> AppResult<Json<BootstrapStatusResponse>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let snapshot = load_snapshot(&state.db, &snapshot_id).await?;
    ensure_snapshot_access(&snapshot, user_id)?;
    let snapshot = expire_snapshot_if_needed(&state.db, snapshot).await?;
    Ok(Json(bootstrap_status(snapshot)))
}

#[utoipa::path(
    get,
    path = "/api/v1/sync/bootstrap/{snapshot_id}/{entity}",
    tag = "sync",
    params(
        ("snapshot_id" = String, Path, description = "Snapshot ID"),
        ("entity" = String, Path, description = "Entity type (media, tags, relations)"),
        ("cursor" = Option<String>, Query, description = "Pagination cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "Snapshot items", body = inline(Page<SnapshotEntity>)),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Snapshot not found")
    )
)]
pub(crate) async fn list_snapshot_items(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((snapshot_id, entity)): Path<(String, String)>,
    Query(query): Query<PageQuery>,
) -> AppResult<Json<Page<SnapshotEntity>>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    if !matches!(entity.as_str(), "media" | "tags" | "relations") {
        return Err(AppError::BadRequest(format!(
            "unsupported bootstrap entity: {entity}"
        )));
    }
    let snapshot = load_snapshot(&state.db, &snapshot_id).await?;
    ensure_snapshot_access(&snapshot, user_id)?;
    let snapshot = expire_snapshot_if_needed(&state.db, snapshot).await?;
    if snapshot.state == "preparing" {
        return Err(AppError::SnapshotPreparing);
    }
    if snapshot.state == "expired" {
        return Err(AppError::SnapshotExpired);
    }
    if snapshot.state != "ready" {
        return Err(AppError::Internal(anyhow::anyhow!(
            "bootstrap snapshot failed: {}",
            snapshot
                .last_error
                .unwrap_or_else(|| "unknown error".to_owned())
        )));
    }

    let limit = query.limit.unwrap_or(100).clamp(1, 100) as i64;
    let offset = parse_offset(query.cursor.as_deref())?;
    let rows = sqlx::query_as::<_, SnapshotItemRow>(
        r#"
        SELECT entity_id, entity_version, sort_at, payload
        FROM sync_snapshot_items
        WHERE snapshot_id = ?1 AND entity_type = ?2
        ORDER BY (sort_at IS NULL) ASC, sort_at DESC, entity_id DESC
        LIMIT ?3 OFFSET ?4
        "#,
    )
    .bind(&snapshot_id)
    .bind(&entity)
    .bind(limit + 1)
    .bind(offset)
    .fetch_all(&state.db)
    .await?;
    let has_more = rows.len() > limit as usize;
    let items = rows.into_iter().take(limit as usize).collect::<Vec<_>>();
    let next_cursor = Some(encode_page_cursor(offset + items.len() as i64));
    let items = items
        .into_iter()
        .map(snapshot_entity)
        .collect::<AppResult<Vec<_>>>()?;
    Ok(Json(Page {
        items,
        next_cursor,
        has_more,
    }))
}

pub(crate) async fn load_snapshot(pool: &SqlitePool, id: &str) -> AppResult<SnapshotRow> {
    sqlx::query_as::<_, SnapshotRow>("SELECT * FROM sync_snapshots WHERE id = ?1")
        .bind(id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("bootstrap snapshot not found: {id}")))
}

/// 快照按用户隔离：设备只能读取自己用户名下的 bootstrap 快照。
pub(crate) fn ensure_snapshot_access(snapshot: &SnapshotRow, user_id: i64) -> AppResult<()> {
    if snapshot.user_id != Some(user_id) {
        return Err(AppError::NotFound(
            "bootstrap snapshot not found".to_owned(),
        ));
    }
    Ok(())
}

pub(crate) async fn expire_snapshot_if_needed(
    pool: &SqlitePool,
    mut snapshot: SnapshotRow,
) -> AppResult<SnapshotRow> {
    if snapshot.state == "ready" && snapshot.expires_at <= now_millis() {
        sqlx::query(
            "UPDATE sync_snapshots SET state = 'expired', updated_at = ?1 WHERE id = ?2 AND state = 'ready'",
        )
        .bind(now_millis())
        .bind(&snapshot.id)
        .execute(pool)
        .await?;
        snapshot.state = "expired".to_owned();
    }
    Ok(snapshot)
}

pub(crate) fn bootstrap_status(snapshot: SnapshotRow) -> BootstrapStatusResponse {
    BootstrapStatusResponse {
        server_version: SERVER_VERSION,
        snapshot_id: snapshot.id,
        job_id: snapshot.job_id,
        state: snapshot.state,
        snapshot_revision: snapshot.snapshot_revision,
        expires_at: snapshot.expires_at,
        changes_cursor: snapshot.changes_cursor.clone(),
        cursors: BootstrapCursors {
            media: encode_page_cursor(0),
            tags: encode_page_cursor(0),
            relations: encode_page_cursor(0),
        },
        last_error: snapshot.last_error,
    }
}

pub(crate) fn snapshot_entity(row: SnapshotItemRow) -> AppResult<SnapshotEntity> {
    Ok(SnapshotEntity {
        entity_id: row.entity_id,
        entity_version: row.entity_version,
        sort_at: row.sort_at,
        data: serde_json::from_str(&row.payload).map_err(|error| {
            AppError::Internal(anyhow::anyhow!("decode snapshot payload: {error}"))
        })?,
    })
}

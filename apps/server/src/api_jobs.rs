use super::*;

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct JobResponse {
    id: String,
    kind: String,
    status: String,
    current: i64,
    total: Option<i64>,
    retry_count: i64,
    max_attempts: i64,
    cancel_requested: bool,
    checkpoint: Option<Value>,
    message: Option<String>,
    last_error: Option<String>,
    created_at: i64,
    updated_at: i64,
    finished_at: Option<i64>,
}

#[derive(Debug, FromRow)]
pub(crate) struct JobRow {
    id: String,
    kind: String,
    status: String,
    user_id: Option<i64>,
    current: i64,
    total: Option<i64>,
    retry_count: i64,
    max_attempts: i64,
    cancel_requested: i64,
    checkpoint: Option<String>,
    message: Option<String>,
    last_error: Option<String>,
    created_at: i64,
    updated_at: i64,
    finished_at: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ScanQuery {
    path: Option<String>,
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/jobs/scan",
    params(("path" = Option<String>, Query, description = "Folder to scan recursively, relative to storage root; omitted scans all")),
    tag = "administration",
    responses(
        (status = 202, description = "Scan job started", body = JobResponse),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Scan already running")
    )
)]
pub(crate) async fn start_scan(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ScanQuery>,
) -> AppResult<(StatusCode, Json<JobResponse>)> {
    auth::require_admin(&state.db, &headers, true).await?;
    let path =
        LocalFilesystemStorageDriver::normalize_relative(query.path.as_deref().unwrap_or(""))?;
    let storage = state.storage.snapshot().await;
    if !storage.stat(&path).await?.is_directory {
        return Err(AppError::BadRequest(
            "scan path must be a directory".to_owned(),
        ));
    }
    let checkpoint =
        serde_json::json!({"version": 1, "kind": "scan", "scopePath": path}).to_string();

    let scan_active = sqlx::query_scalar::<_, i64>(
        "SELECT EXISTS(SELECT 1 FROM jobs WHERE kind = 'scan' AND status IN ('queued', 'running', 'interrupted'))",
    )
    .fetch_one(&state.db)
    .await?;
    if scan_active == 1 {
        return Err(AppError::Conflict(
            "a scan job is already queued or running".to_owned(),
        ));
    }
    let id = Uuid::new_v4().to_string();
    let now = now_millis();
    let insert_result = sqlx::query(
        "INSERT INTO jobs (id, kind, status, created_at, updated_at, checkpoint) VALUES (?1, 'scan', 'queued', ?2, ?2, ?3)",
    )
    .bind(&id)
    .bind(now)
    .bind(checkpoint)
    .execute(&state.db)
    .await;
    if let Err(error) = insert_result {
        if matches!(&error, sqlx::Error::Database(database_error) if database_error.is_unique_violation())
        {
            return Err(AppError::Conflict(
                "a scan job is already queued or running".to_owned(),
            ));
        }
        return Err(error.into());
    }
    state.job_notify.notify_one();
    let _ = audit::record(&state.db, "admin", "scan.start", "scan", audit::SUCCESS).await;

    Ok((
        StatusCode::ACCEPTED,
        Json(job_response(
            sqlx::query_as::<_, JobRow>("SELECT * FROM jobs WHERE id = ?1")
                .bind(&id)
                .fetch_one(&state.db)
                .await?,
        )),
    ))
}

pub(crate) async fn run_scan_job(
    pool: SqlitePool,
    storage: Arc<LocalFilesystemStorageDriver>,
    job_id: String,
) {
    let lease_owner = match set_job_running(&pool, &job_id).await {
        Ok(Some(owner)) => owner,
        Ok(None) => return,
        Err(error) => {
            tracing::error!(job_id = %job_id, error = ?error, "failed to start scan job");
            return;
        }
    };
    let lease_heartbeat =
        spawn_job_lease_heartbeat(pool.clone(), job_id.clone(), lease_owner.clone());
    let summary_result =
        scan_directory_with_lease(&pool, storage, &job_id, Some(&lease_owner)).await;
    lease_heartbeat.abort();
    let _ = lease_heartbeat.await;
    let summary = match summary_result {
        Ok(summary) => summary,
        Err(error) => {
            tracing::error!(job_id = %job_id, error = ?error, "scan job failed");
            let _ = audit::record(&pool, "system", "scan.complete", "scan", audit::FAILURE).await;
            if let Err(update_error) =
                set_job_failed(&pool, &job_id, &error.to_string(), Some(&lease_owner)).await
            {
                tracing::error!(
                    job_id = %job_id,
                    error = ?update_error,
                    "failed to persist scan job error"
                );
            }
            return;
        }
    };
    let result = if summary.cancelled {
        set_job_cancelled(&pool, &job_id, &summary, &lease_owner).await
    } else {
        set_job_succeeded(&pool, &job_id, &summary, &lease_owner).await
    };
    if let Err(error) = result {
        tracing::error!(job_id = %job_id, error = ?error, "failed to finish scan job");
        let _ = audit::record(&pool, "system", "scan.complete", "scan", audit::FAILURE).await;
    } else {
        let _ = audit::record(&pool, "system", "scan.complete", "scan", audit::SUCCESS).await;
    }
}

pub(crate) async fn run_bootstrap_job(pool: SqlitePool, snapshot_id: String, job_id: String) {
    let user_id: i64 = match sqlx::query_scalar::<_, Option<i64>>(
        "SELECT user_id FROM sync_snapshots WHERE id = ?1",
    )
    .bind(&snapshot_id)
    .fetch_one(&pool)
    .await
    {
        Ok(Some(user_id)) => user_id,
        _ => {
            tracing::error!(
                snapshot_id = %snapshot_id,
                "bootstrap snapshot has no bound user; aborting"
            );
            return;
        }
    };
    let lease_owner = match set_job_running(&pool, &job_id).await {
        Ok(Some(owner)) => owner,
        Ok(None) => return,
        Err(error) => {
            tracing::error!(
                snapshot_id = %snapshot_id,
                job_id = %job_id,
                error = ?error,
                "failed to start bootstrap job"
            );
            return;
        }
    };
    let lease_heartbeat =
        spawn_job_lease_heartbeat(pool.clone(), job_id.clone(), lease_owner.clone());
    if let Err(error) =
        sync::build_snapshot_with_lease(&pool, &snapshot_id, &job_id, user_id, Some(&lease_owner))
            .await
    {
        if error.to_string() == "bootstrap cancelled" {
            let now = now_millis();
            let _ = sqlx::query(
                "UPDATE sync_snapshots SET state = 'failed', last_error = 'bootstrap cancelled', updated_at = ?1 WHERE id = ?2 AND state = 'preparing'",
            )
            .bind(now)
            .bind(&snapshot_id)
            .execute(&pool)
            .await;
            if let Err(cancel_error) =
                set_job_cancelled_message(&pool, &job_id, "bootstrap cancelled", &lease_owner).await
            {
                tracing::warn!(
                    job_id = %job_id,
                    error = ?cancel_error,
                    "failed to mark bootstrap job cancelled"
                );
            }
            lease_heartbeat.abort();
            let _ = lease_heartbeat.await;
            return;
        }
        tracing::error!(
            snapshot_id = %snapshot_id,
            job_id = %job_id,
            error = ?error,
            "bootstrap snapshot failed"
        );
        let now = now_millis();
        let _ = sqlx::query(
            r#"
            UPDATE sync_snapshots
            SET state = 'failed', last_error = ?1, updated_at = ?2
            WHERE id = ?3
              AND EXISTS (
                  SELECT 1 FROM jobs
                  WHERE id = ?4 AND status = 'running' AND lease_owner = ?5
              )
            "#,
        )
        .bind(error.to_string())
        .bind(now)
        .bind(&snapshot_id)
        .bind(&job_id)
        .bind(&lease_owner)
        .execute(&pool)
        .await;
        let _ = set_job_failed(&pool, &job_id, &error.to_string(), Some(&lease_owner)).await;
    }
    lease_heartbeat.abort();
    let _ = lease_heartbeat.await;
}

pub(crate) async fn run_backup_job(pool: SqlitePool, data_dir: std::path::PathBuf, job_id: String) {
    let lease_owner = match set_job_running(&pool, &job_id).await {
        Ok(Some(owner)) => owner,
        Ok(None) => return,
        Err(error) => {
            tracing::error!(job_id = %job_id, error = ?error, "failed to start backup job");
            return;
        }
    };
    let Some(backup_id) = backup_id_for_job(&pool, &job_id).await.ok().flatten() else {
        let _ = set_job_failed(
            &pool,
            &job_id,
            "backup record is missing",
            Some(&lease_owner),
        )
        .await;
        return;
    };
    if is_job_cancel_requested(&pool, &job_id)
        .await
        .unwrap_or(false)
    {
        let now = now_millis();
        let _ = sqlx::query(
            r#"
            UPDATE backups
            SET status = 'cancelled', last_error = 'backup cancelled',
                updated_at = ?1, completed_at = ?1
            WHERE id = ?2
              AND EXISTS (
                  SELECT 1 FROM jobs
                  WHERE id = ?3 AND status = 'running' AND lease_owner = ?4
              )
            "#,
        )
        .bind(now)
        .bind(&backup_id)
        .bind(&job_id)
        .bind(&lease_owner)
        .execute(&pool)
        .await;
        let _ = set_job_cancelled_message(&pool, &job_id, "backup cancelled", &lease_owner).await;
        return;
    }
    let _ = sqlx::query(
        r#"
        UPDATE backups
        SET status = 'running', updated_at = ?1
        WHERE id = ?2
          AND status = 'queued'
          AND EXISTS (
              SELECT 1 FROM jobs
              WHERE id = ?3 AND status = 'running' AND lease_owner = ?4
          )
        "#,
    )
    .bind(now_millis())
    .bind(&backup_id)
    .bind(&job_id)
    .bind(&lease_owner)
    .execute(&pool)
    .await;
    let lease_heartbeat =
        spawn_job_lease_heartbeat(pool.clone(), job_id.clone(), lease_owner.clone());
    let result = backup::create_with_pool(&pool, &data_dir.join("backups")).await;
    let (result, backup_path) = match result {
        Ok(path) => match backup::verify(&path).await {
            Ok(manifest) => {
                let relative_path = path
                    .strip_prefix(&data_dir)
                    .ok()
                    .map(|value| value.to_string_lossy().replace('\\', "/"));
                let update = sqlx::query(
                    r#"
                    UPDATE backups
                    SET status = 'succeeded', path = ?1, format_version = ?2,
                        schema_version = ?3, server_version = ?4, size_bytes = ?5,
                        sha256 = ?6, updated_at = ?7, completed_at = ?7, last_error = NULL
                    WHERE id = ?8
                      AND status = 'running'
                      AND EXISTS (
                          SELECT 1 FROM jobs
                          WHERE id = ?9 AND status = 'running' AND lease_owner = ?10
                      )
                    "#,
                )
                .bind(relative_path)
                .bind(manifest.format_version)
                .bind(manifest.schema_version)
                .bind(manifest.server_version)
                .bind(i64::try_from(manifest.size_bytes).unwrap_or(i64::MAX))
                .bind(manifest.sha256)
                .bind(now_millis())
                .bind(&backup_id)
                .bind(&job_id)
                .bind(&lease_owner)
                .execute(&pool)
                .await;
                let result = match update {
                    Ok(result) if result.rows_affected() == 1 => Ok(()),
                    Ok(_) => Err(anyhow::anyhow!("backup job lease was lost")),
                    Err(error) => Err(anyhow::Error::from(error)),
                };
                (result, Some(path))
            }
            Err(error) => {
                let _ = tokio::fs::remove_dir_all(&path).await;
                (Err(error), None)
            }
        },
        Err(error) => (Err(error), None),
    };
    lease_heartbeat.abort();
    let _ = lease_heartbeat.await;
    if is_job_cancel_requested(&pool, &job_id)
        .await
        .unwrap_or(false)
    {
        if let Some(path) = backup_path.as_ref() {
            let _ = tokio::fs::remove_dir_all(path).await;
        }
        let now = now_millis();
        let _ = sqlx::query(
            r#"
            UPDATE backups
            SET status = 'cancelled', last_error = 'backup cancelled',
                updated_at = ?1, completed_at = ?1
            WHERE id = ?2
              AND EXISTS (
                  SELECT 1 FROM jobs
                  WHERE id = ?3 AND status = 'running' AND lease_owner = ?4
              )
            "#,
        )
        .bind(now)
        .bind(&backup_id)
        .bind(&job_id)
        .bind(&lease_owner)
        .execute(&pool)
        .await;
        let _ = set_job_cancelled_message(&pool, &job_id, "backup cancelled", &lease_owner).await;
        return;
    }
    match result {
        Ok(()) => {
            let _ = set_job_succeeded_message(
                &pool,
                &job_id,
                "backup created and verified",
                &lease_owner,
            )
            .await;
            let _ =
                audit::record(&pool, "admin", "backup.complete", "backup", audit::SUCCESS).await;
        }
        Err(error) => {
            if let Some(path) = backup_path.as_ref() {
                let _ = tokio::fs::remove_dir_all(path).await;
            }
            tracing::error!(job_id = %job_id, error = ?error, "backup job failed");
            let now = now_millis();
            let _ = sqlx::query(
                r#"
                UPDATE backups
                SET status = 'failed',
                    last_error = 'backup creation or verification failed',
                    updated_at = ?1,
                    completed_at = ?1
                WHERE id = ?2
                  AND status = 'running'
                  AND EXISTS (
                      SELECT 1 FROM jobs
                      WHERE id = ?3 AND status = 'running' AND lease_owner = ?4
                  )
                "#,
            )
            .bind(now)
            .bind(&backup_id)
            .bind(&job_id)
            .bind(&lease_owner)
            .execute(&pool)
            .await;
            let _ = set_job_failed(
                &pool,
                &job_id,
                "backup creation or verification failed",
                Some(&lease_owner),
            )
            .await;
            let _ =
                audit::record(&pool, "admin", "backup.complete", "backup", audit::FAILURE).await;
        }
    }
}

pub(crate) fn spawn_pending_job_worker(
    pool: SqlitePool,
    storage: Arc<StorageRuntime>,
    data_dir: std::path::PathBuf,
    notify: Arc<Notify>,
) {
    tokio::spawn(async move {
        loop {
            let next_job = match next_runnable_job(&pool).await {
                Ok(job) => job,
                Err(error) => {
                    tracing::error!(error = ?error, "job worker query failed");
                    None
                }
            };
            match next_job {
                Some((job_id, kind)) => match kind.as_str() {
                    "scan" => {
                        let driver = storage.snapshot().await;
                        run_scan_job(pool.clone(), driver, job_id).await;
                    }
                    "backup" => run_backup_job(pool.clone(), data_dir.clone(), job_id).await,
                    "bootstrap" => match snapshot_id_for_job(&pool, &job_id).await {
                        Ok(Some(snapshot_id)) => {
                            run_bootstrap_job(pool.clone(), snapshot_id, job_id).await;
                        }
                        Ok(None) => {
                            let _ = set_job_failed(
                                &pool,
                                &job_id,
                                "bootstrap snapshot not found",
                                None,
                            )
                            .await;
                        }
                        Err(error) => {
                            tracing::error!(
                                job_id = %job_id,
                                error = ?error,
                                "failed to resolve bootstrap snapshot"
                            );
                            let _ = set_job_failed(
                                &pool,
                                &job_id,
                                "failed to resolve bootstrap snapshot",
                                None,
                            )
                            .await;
                        }
                    },
                    _ => {
                        let _ =
                            set_job_failed(&pool, &job_id, "unsupported persisted job kind", None)
                                .await;
                    }
                },
                None => {
                    tokio::select! {
                        _ = notify.notified() => {}
                        _ = tokio::time::sleep(StdDuration::from_millis(250)) => {}
                    }
                }
            }
        }
    });
}

pub(crate) fn spawn_job_lease_watchdog(pool: SqlitePool) {
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(StdDuration::from_secs(5)).await;
            let now = now_millis();
            match sqlx::query(
                r#"
                UPDATE jobs
                SET status = CASE
                        WHEN cancel_requested = 1 THEN 'cancelled'
                        WHEN retry_count + 1 >= max_attempts THEN 'failed'
                        ELSE 'interrupted'
                    END,
                    message = CASE
                        WHEN cancel_requested = 1 THEN 'cancelled after lease expired'
                        WHEN retry_count + 1 >= max_attempts THEN 'lease expired; retry limit reached'
                        ELSE 'lease expired; queued for recovery'
                    END,
                    last_error = 'job lease expired',
                    retry_count = retry_count + 1,
                    run_after = CASE
                        WHEN cancel_requested = 1 OR retry_count + 1 >= max_attempts THEN 0
                        WHEN retry_count = 0 THEN ?1 + 5000
                        WHEN retry_count = 1 THEN ?1 + 30000
                        WHEN retry_count = 2 THEN ?1 + 300000
                        WHEN retry_count = 3 THEN ?1 + 1800000
                        ELSE ?1 + 7200000
                    END,
                    updated_at = ?1,
                    finished_at = CASE
                        WHEN cancel_requested = 1 OR retry_count + 1 >= max_attempts THEN ?1
                        ELSE NULL
                    END,
                    lease_owner = NULL,
                    lease_until = NULL
                WHERE status = 'running'
                  AND lease_until IS NOT NULL
                  AND lease_until < ?1
                "#,
            )
            .bind(now)
            .execute(&pool)
            .await
            {
                Ok(result) if result.rows_affected() > 0 => {
                    tracing::warn!(
                        jobs = result.rows_affected(),
                        "recovered jobs with expired leases"
                    );
                }
                Ok(_) => {}
                Err(error) => {
                    tracing::error!(error = ?error, "job lease watchdog query failed");
                }
            }
        }
    });
}

pub(crate) fn spawn_job_lease_heartbeat(
    pool: SqlitePool,
    job_id: String,
    lease_owner: String,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(StdDuration::from_secs(15)).await;
            let now = now_millis();
            let result = sqlx::query(
                "UPDATE jobs SET heartbeat_at = ?1, updated_at = ?1, lease_until = ?1 + 60000 WHERE id = ?2 AND status = 'running' AND lease_owner = ?3",
            )
            .bind(now)
            .bind(&job_id)
            .bind(&lease_owner)
            .execute(&pool)
            .await;
            match result {
                Ok(result) if result.rows_affected() == 1 => {}
                Ok(_) => break,
                Err(error) => {
                    tracing::warn!(job_id = %job_id, error = ?error, "job lease heartbeat failed");
                }
            }
        }
    })
}

pub(crate) async fn next_runnable_job(
    pool: &SqlitePool,
) -> Result<Option<(String, String)>, sqlx::Error> {
    sqlx::query_as::<_, (String, String)>(
        r#"
        SELECT id, kind
        FROM jobs
        WHERE status IN ('queued', 'interrupted')
          AND COALESCE(run_after, 0) <= ?1
        ORDER BY created_at ASC
        LIMIT 1
        "#,
    )
    .bind(now_millis())
    .fetch_optional(pool)
    .await
}

pub(crate) async fn snapshot_id_for_job(
    pool: &SqlitePool,
    job_id: &str,
) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar::<_, String>(
        "SELECT id FROM sync_snapshots WHERE job_id = ?1 ORDER BY created_at DESC LIMIT 1",
    )
    .bind(job_id)
    .fetch_optional(pool)
    .await
}

pub(crate) async fn backup_id_for_job(
    pool: &SqlitePool,
    job_id: &str,
) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar::<_, String>("SELECT id FROM backups WHERE job_id = ?1")
        .bind(job_id)
        .fetch_optional(pool)
        .await
}

#[utoipa::path(
    get,
    path = "/api/v1/jobs/{id}",
    tag = "jobs",
    params(
        ("id" = String, Path, description = "Job ID")
    ),
    responses(
        (status = 200, description = "Job details", body = JobResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Job not found")
    )
)]
pub(crate) async fn get_job(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<JobResponse>> {
    let principal = auth::require_client(&state.db, &headers).await?;
    let user_id = principal.device_user()?;
    let job = load_job(&state.db, &id).await?;
    if job.user_id != Some(user_id) {
        return Err(AppError::NotFound(format!("job not found: {id}")));
    }
    Ok(Json(job_response(job)))
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/jobs/{id}",
    tag = "administration",
    params(
        ("id" = String, Path, description = "Job ID")
    ),
    responses(
        (status = 200, description = "Job details", body = JobResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Job not found")
    )
)]
pub(crate) async fn get_admin_job(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<JobResponse>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let job = load_job(&state.db, &id).await?;
    Ok(Json(job_response(job)))
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/jobs",
    tag = "administration",
    params(
        ("cursor" = Option<String>, Query, description = "Pagination cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "List of jobs", body = inline(Page<JobResponse>)),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_admin_jobs(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<PageQuery>,
) -> AppResult<Json<Page<JobResponse>>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100) as i64;
    let offset = parse_offset(query.cursor.as_deref())?;
    let rows = sqlx::query_as::<_, JobRow>(
        "SELECT * FROM jobs ORDER BY created_at DESC, id DESC LIMIT ?1 OFFSET ?2",
    )
    .bind(limit + 1)
    .bind(offset)
    .fetch_all(&state.db)
    .await?;
    let has_more = rows.len() > limit as usize;
    let items = rows
        .into_iter()
        .take(limit as usize)
        .map(job_response)
        .collect::<Vec<_>>();
    Ok(Json(Page {
        next_cursor: Some(encode_page_cursor(offset + items.len() as i64)),
        has_more,
        items,
    }))
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BackupResponse {
    id: String,
    job_id: String,
    status: String,
    path: Option<String>,
    format_version: Option<i64>,
    schema_version: Option<i64>,
    server_version: Option<String>,
    size_bytes: Option<u64>,
    sha256: Option<String>,
    created_at: i64,
    updated_at: i64,
    completed_at: Option<i64>,
    last_error: Option<String>,
}

#[derive(Debug, FromRow)]
pub(crate) struct BackupRow {
    id: String,
    job_id: String,
    status: String,
    path: Option<String>,
    format_version: Option<i64>,
    schema_version: Option<i64>,
    server_version: Option<String>,
    size_bytes: Option<i64>,
    sha256: Option<String>,
    created_at: i64,
    updated_at: i64,
    completed_at: Option<i64>,
    last_error: Option<String>,
}

pub(crate) fn backup_response(row: BackupRow) -> AppResult<BackupResponse> {
    Ok(BackupResponse {
        id: row.id,
        job_id: row.job_id,
        status: row.status,
        path: row.path,
        format_version: row.format_version,
        schema_version: row.schema_version,
        server_version: row.server_version,
        size_bytes: row.size_bytes.and_then(|value| u64::try_from(value).ok()),
        sha256: row.sha256,
        created_at: row.created_at,
        updated_at: row.updated_at,
        completed_at: row.completed_at,
        last_error: row.last_error,
    })
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/backups",
    tag = "administration",
    responses(
        (status = 202, description = "Backup job started", body = BackupResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn start_backup(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<(StatusCode, Json<BackupResponse>)> {
    auth::require_admin(&state.db, &headers, true).await?;
    let backup_id = Uuid::new_v4().to_string();
    let job_id = Uuid::new_v4().to_string();
    let now = now_millis();
    let mut transaction = state.db.begin().await?;
    sqlx::query(
        "INSERT INTO jobs (id, kind, status, message, created_at, updated_at) VALUES (?1, 'backup', 'queued', 'backup queued', ?2, ?2)",
    )
    .bind(&job_id)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    sqlx::query(
        "INSERT INTO backups (id, job_id, status, created_at, updated_at) VALUES (?1, ?2, 'queued', ?3, ?3)",
    )
    .bind(&backup_id)
    .bind(&job_id)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    let _ = audit::record(
        &state.db,
        "admin",
        "backup.create",
        "backup",
        audit::SUCCESS,
    )
    .await;
    state.job_notify.notify_one();
    let backup = load_backup(&state.db, &backup_id).await?;
    Ok((StatusCode::ACCEPTED, Json(backup_response(backup)?)))
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/backups",
    tag = "administration",
    params(
        ("cursor" = Option<String>, Query, description = "Pagination cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "List of backups", body = inline(Page<BackupResponse>)),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_backups(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<PageQuery>,
) -> AppResult<Json<Page<BackupResponse>>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100) as i64;
    let offset = parse_offset(query.cursor.as_deref())?;
    let rows = sqlx::query_as::<_, BackupRow>(
        "SELECT * FROM backups ORDER BY created_at DESC, id DESC LIMIT ?1 OFFSET ?2",
    )
    .bind(limit + 1)
    .bind(offset)
    .fetch_all(&state.db)
    .await?;
    let has_more = rows.len() > limit as usize;
    let items = rows
        .into_iter()
        .take(limit as usize)
        .map(backup_response)
        .collect::<AppResult<Vec<_>>>()?;
    let next_cursor = Some(encode_page_cursor(
        offset + i64::try_from(items.len()).unwrap_or(0),
    ));
    Ok(Json(Page {
        items,
        next_cursor,
        has_more,
    }))
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/backups/{id}",
    tag = "administration",
    params(
        ("id" = String, Path, description = "Backup ID")
    ),
    responses(
        (status = 200, description = "Backup details", body = BackupResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Backup not found")
    )
)]
pub(crate) async fn get_backup(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<BackupResponse>> {
    auth::require_admin(&state.db, &headers, false).await?;
    Ok(Json(backup_response(load_backup(&state.db, &id).await?)?))
}

pub(crate) async fn load_backup(pool: &SqlitePool, id: &str) -> AppResult<BackupRow> {
    sqlx::query_as::<_, BackupRow>("SELECT * FROM backups WHERE id = ?1")
        .bind(id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("backup not found: {id}")))
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AuditLogEntry {
    id: i64,
    actor: String,
    action: String,
    target: String,
    result: String,
    created_at: i64,
}

#[derive(Debug, FromRow)]
pub(crate) struct AuditLogRow {
    id: i64,
    actor: String,
    action: String,
    target: String,
    result: String,
    created_at: i64,
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/audit-log",
    tag = "administration",
    params(
        ("cursor" = Option<String>, Query, description = "Pagination cursor"),
        ("limit" = Option<u32>, Query, description = "Page size")
    ),
    responses(
        (status = 200, description = "Audit log entries", body = inline(Page<AuditLogEntry>)),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_audit_log(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<PageQuery>,
) -> AppResult<Json<Page<AuditLogEntry>>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100) as i64;
    let offset = parse_offset(query.cursor.as_deref())?;
    let rows = sqlx::query_as::<_, AuditLogRow>(
        "SELECT id, actor, action, target, result, created_at FROM audit_log ORDER BY id DESC LIMIT ?1 OFFSET ?2",
    )
    .bind(limit + 1)
    .bind(offset)
    .fetch_all(&state.db)
    .await?;
    let has_more = rows.len() > limit as usize;
    let items: Vec<AuditLogEntry> = rows
        .into_iter()
        .take(limit as usize)
        .map(|row| AuditLogEntry {
            id: row.id,
            actor: row.actor,
            action: row.action,
            target: row.target,
            result: row.result,
            created_at: row.created_at,
        })
        .collect();
    let next_cursor = Some(encode_page_cursor(
        offset + i64::try_from(items.len()).unwrap_or(0),
    ));
    Ok(Json(Page {
        items,
        next_cursor,
        has_more,
    }))
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DiagnosticsResponse {
    server_version: &'static str,
    api_version: &'static str,
    schema_version: i64,
    database_quick_check: String,
    media_count: i64,
    active_job_count: i64,
    storage: AdminStorageResponse,
    ffmpeg_version: Option<String>,
    ffprobe_version: Option<String>,
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/diagnostics",
    tag = "administration",
    responses(
        (status = 200, description = "Server diagnostics", body = DiagnosticsResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn admin_diagnostics(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> AppResult<Json<DiagnosticsResponse>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let schema_version =
        sqlx::query_scalar::<_, i64>("SELECT COALESCE(MAX(version), 0) FROM _sqlx_migrations")
            .fetch_one(&state.db)
            .await?;
    let database_quick_check = sqlx::query_scalar::<_, String>("PRAGMA quick_check")
        .fetch_one(&state.db)
        .await?;
    let media_count = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM media_assets WHERE identity_state != 'tombstoned'",
    )
    .fetch_one(&state.db)
    .await?;
    let active_job_count = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM jobs WHERE status IN ('queued', 'running', 'interrupted')",
    )
    .fetch_one(&state.db)
    .await?;
    let storage = admin_storage_response(&state).await?;
    let (ffmpeg_version, ffprobe_version) =
        tokio::join!(tool_version("ffmpeg"), tool_version("ffprobe"));
    Ok(Json(DiagnosticsResponse {
        server_version: SERVER_VERSION,
        api_version: "v1",
        schema_version,
        database_quick_check,
        media_count,
        active_job_count,
        storage,
        ffmpeg_version,
        ffprobe_version,
    }))
}

pub(crate) async fn tool_version(command: &'static str) -> Option<String> {
    let output = tokio::time::timeout(
        Duration::from_secs(3),
        Command::new(command).arg("-version").output(),
    )
    .await
    .ok()?
    .ok()?;
    let line = output
        .stdout
        .split(|byte| *byte == b'\n')
        .next()
        .and_then(|line| std::str::from_utf8(line).ok())?
        .trim();
    (!line.is_empty()).then(|| line.chars().take(160).collect())
}

#[utoipa::path(
    post,
    path = "/api/v1/jobs/{id}/cancel",
    tag = "jobs",
    params(
        ("id" = String, Path, description = "Job ID")
    ),
    responses(
        (status = 200, description = "Job cancelled", body = JobResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Job not found"),
        (status = 409, description = "Job is in terminal state")
    )
)]
pub(crate) async fn cancel_job(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<JobResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let job = load_job(&state.db, &id).await?;
    if matches!(job.status.as_str(), "succeeded" | "failed" | "cancelled") {
        return Err(AppError::Conflict(
            "terminal jobs cannot be cancelled".to_owned(),
        ));
    }
    let now = now_millis();
    if job.status == "queued" || job.status == "interrupted" {
        sqlx::query(
            r#"
            UPDATE jobs
            SET status = 'cancelled', cancel_requested = 1,
                updated_at = ?1, finished_at = ?1, lease_owner = NULL, lease_until = NULL
            WHERE id = ?2
            "#,
        )
        .bind(now)
        .bind(&id)
        .execute(&state.db)
        .await?;
    } else {
        sqlx::query("UPDATE jobs SET cancel_requested = 1, updated_at = ?1 WHERE id = ?2")
            .bind(now)
            .bind(&id)
            .execute(&state.db)
            .await?;
    }
    if job.kind == "backup" {
        sqlx::query(
            "UPDATE backups SET status = CASE WHEN ?1 IN ('queued', 'interrupted') THEN 'cancelled' ELSE status END, last_error = CASE WHEN ?1 IN ('queued', 'interrupted') THEN 'backup cancelled' ELSE last_error END, updated_at = ?2, completed_at = CASE WHEN ?1 IN ('queued', 'interrupted') THEN ?2 ELSE completed_at END WHERE job_id = ?3",
        )
        .bind(&job.status)
        .bind(now)
        .bind(&id)
        .execute(&state.db)
        .await?;
    }
    Ok(Json(job_response(load_job(&state.db, &id).await?)))
}

#[utoipa::path(
    post,
    path = "/api/v1/jobs/{id}/retry",
    tag = "jobs",
    params(
        ("id" = String, Path, description = "Job ID")
    ),
    responses(
        (status = 200, description = "Job retried", body = JobResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Job not found"),
        (status = 409, description = "Job cannot be retried")
    )
)]
pub(crate) async fn retry_job(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<JobResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let job = load_job(&state.db, &id).await?;
    if !matches!(job.status.as_str(), "failed" | "interrupted") {
        return Err(AppError::Conflict(
            "only failed or interrupted jobs can be retried".to_owned(),
        ));
    }
    if job.retry_count >= job.max_attempts {
        return Err(AppError::Conflict(
            "job retry limit has been reached".to_owned(),
        ));
    }
    let bootstrap_snapshot_id = if job.kind == "bootstrap" {
        Some(
            snapshot_id_for_job(&state.db, &id)
                .await?
                .ok_or_else(|| AppError::Conflict("bootstrap snapshot is missing".to_owned()))?,
        )
    } else {
        None
    };
    if job.kind != "scan" && job.kind != "bootstrap" && job.kind != "backup" {
        return Err(AppError::Conflict(
            "this job kind does not support retry".to_owned(),
        ));
    }
    let previous_backup_path = if job.kind == "backup" {
        sqlx::query_scalar::<_, Option<String>>("SELECT path FROM backups WHERE job_id = ?1")
            .bind(&id)
            .fetch_optional(&state.db)
            .await?
            .flatten()
    } else {
        None
    };
    let retry_number = job.retry_count + 1;
    let retry_delay = match retry_number {
        1 => StdDuration::from_secs(5),
        2 => StdDuration::from_secs(30),
        3 => StdDuration::from_secs(5 * 60),
        4 => StdDuration::from_secs(30 * 60),
        _ => StdDuration::from_secs(2 * 60 * 60),
    };
    let now = now_millis();
    let run_after = now + i64::try_from(retry_delay.as_millis()).unwrap_or(i64::MAX);
    if let Some(snapshot_id) = &bootstrap_snapshot_id {
        sqlx::query(
            r#"
            UPDATE sync_snapshots
            SET state = 'preparing', snapshot_revision = NULL, changes_cursor = NULL,
                last_error = NULL, updated_at = ?1
            WHERE id = ?2 AND state IN ('failed', 'preparing')
            "#,
        )
        .bind(now)
        .bind(snapshot_id)
        .execute(&state.db)
        .await?;
    }
    let mut transaction = state.db.begin().await?;
    if job.kind == "backup" {
        sqlx::query(
            r#"
            UPDATE backups
            SET status = 'queued', path = NULL, format_version = NULL,
                schema_version = NULL, server_version = NULL, size_bytes = NULL,
                sha256 = NULL, last_error = NULL, updated_at = ?1, completed_at = NULL
            WHERE job_id = ?2
            "#,
        )
        .bind(now)
        .bind(&id)
        .execute(&mut *transaction)
        .await?;
    }
    sqlx::query(
        r#"
        UPDATE jobs
        SET status = 'queued', current = 0, total = NULL, checkpoint = CASE WHEN kind = 'scan' THEN checkpoint ELSE NULL END, message = 'retry queued',
            retry_count = retry_count + 1, run_after = ?1,
            cancel_requested = 0, updated_at = ?1, finished_at = NULL,
            lease_owner = NULL, lease_until = NULL
        WHERE id = ?2
        "#,
    )
    .bind(run_after)
    .bind(&id)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    if let Some(path) = previous_backup_path {
        let relative_path = FsPath::new(&path);
        if relative_path.is_relative() {
            let backup_path = state.data_dir.join(relative_path);
            if backup_path.starts_with(&state.data_dir) {
                let _ = tokio::fs::remove_dir_all(backup_path).await;
            }
        }
    }
    state.job_notify.notify_one();
    Ok(Json(job_response(load_job(&state.db, &id).await?)))
}

pub(crate) async fn load_job(pool: &SqlitePool, id: &str) -> AppResult<JobRow> {
    sqlx::query_as::<_, JobRow>("SELECT * FROM jobs WHERE id = ?1")
        .bind(id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("job not found: {id}")))
}

pub(crate) fn job_response(job: JobRow) -> JobResponse {
    JobResponse {
        id: job.id,
        kind: job.kind,
        status: job.status,
        current: job.current,
        total: job.total,
        retry_count: job.retry_count,
        max_attempts: job.max_attempts,
        cancel_requested: job.cancel_requested == 1,
        checkpoint: job
            .checkpoint
            .as_deref()
            .and_then(|value| serde_json::from_str(value).ok()),
        message: job.message,
        last_error: job.last_error,
        created_at: job.created_at,
        updated_at: job.updated_at,
        finished_at: job.finished_at,
    }
}

pub(crate) async fn set_job_running(
    pool: &SqlitePool,
    id: &str,
) -> Result<Option<String>, sqlx::Error> {
    let lease_owner = Uuid::new_v4().to_string();
    let result = sqlx::query(
        r#"
        UPDATE jobs
        SET status = 'running', updated_at = ?1, heartbeat_at = ?1,
            lease_owner = ?2, lease_until = ?1 + 60000
        WHERE id = ?3 AND status IN ('queued', 'interrupted')
          AND cancel_requested = 0
          AND COALESCE(run_after, 0) <= ?1
        "#,
    )
    .bind(now_millis())
    .bind(&lease_owner)
    .bind(id)
    .execute(pool)
    .await?;
    Ok((result.rows_affected() == 1).then_some(lease_owner))
}

pub(crate) async fn set_job_succeeded(
    pool: &SqlitePool,
    id: &str,
    summary: &ScanSummary,
    lease_owner: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        r#"
        UPDATE jobs
        SET status = 'succeeded', current = ?1, total = ?2, message = ?3,
            updated_at = ?4, finished_at = ?4, heartbeat_at = ?4,
            lease_owner = NULL, lease_until = NULL
        WHERE id = ?5 AND status = 'running' AND lease_owner = ?6
        "#,
    )
    .bind(i64::try_from(summary.indexed).unwrap_or(i64::MAX))
    .bind(i64::try_from(summary.discovered).unwrap_or(i64::MAX))
    .bind(format!(
        "discovered={}, indexed={}, failed={}",
        summary.discovered, summary.indexed, summary.failed
    ))
    .bind(now_millis())
    .bind(id)
    .bind(lease_owner)
    .execute(pool)
    .await
    .and_then(|result| {
        (result.rows_affected() == 1)
            .then_some(result)
            .ok_or(sqlx::Error::RowNotFound)
    })?;
    Ok(())
}

pub(crate) async fn set_job_succeeded_message(
    pool: &SqlitePool,
    id: &str,
    message: &str,
    lease_owner: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        r#"
        UPDATE jobs
        SET status = 'succeeded', current = 1, total = 1, message = ?1,
            updated_at = ?2, finished_at = ?2, heartbeat_at = ?2,
            lease_owner = NULL, lease_until = NULL
        WHERE id = ?3 AND status = 'running' AND lease_owner = ?4
        "#,
    )
    .bind(message)
    .bind(now_millis())
    .bind(id)
    .bind(lease_owner)
    .execute(pool)
    .await
    .and_then(|result| {
        (result.rows_affected() == 1)
            .then_some(result)
            .ok_or(sqlx::Error::RowNotFound)
    })?;
    Ok(())
}

pub(crate) async fn is_job_cancel_requested(
    pool: &SqlitePool,
    id: &str,
) -> Result<bool, sqlx::Error> {
    Ok(
        sqlx::query_scalar::<_, i64>("SELECT cancel_requested FROM jobs WHERE id = ?1")
            .bind(id)
            .fetch_optional(pool)
            .await?
            .unwrap_or_default()
            == 1,
    )
}

pub(crate) async fn set_job_cancelled_message(
    pool: &SqlitePool,
    id: &str,
    message: &str,
    lease_owner: &str,
) -> Result<(), sqlx::Error> {
    let now = now_millis();
    sqlx::query(
        r#"
        UPDATE jobs
        SET status = 'cancelled', current = 1, total = 1, message = ?1,
            updated_at = ?2, finished_at = ?2, heartbeat_at = ?2,
            lease_owner = NULL, lease_until = NULL
        WHERE id = ?3 AND status = 'running' AND lease_owner = ?4
        "#,
    )
    .bind(message)
    .bind(now)
    .bind(id)
    .bind(lease_owner)
    .execute(pool)
    .await
    .and_then(|result| {
        (result.rows_affected() == 1)
            .then_some(result)
            .ok_or(sqlx::Error::RowNotFound)
    })?;
    Ok(())
}

pub(crate) async fn set_job_cancelled(
    pool: &SqlitePool,
    id: &str,
    summary: &ScanSummary,
    lease_owner: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        r#"
        UPDATE jobs
        SET status = 'cancelled', current = ?1, total = ?2, message = ?3,
            updated_at = ?4, finished_at = ?4, heartbeat_at = ?4,
            lease_owner = NULL, lease_until = NULL
        WHERE id = ?5 AND status = 'running' AND lease_owner = ?6
        "#,
    )
    .bind(i64::try_from(summary.indexed).unwrap_or(i64::MAX))
    .bind(i64::try_from(summary.discovered).unwrap_or(i64::MAX))
    .bind(format!(
        "cancelled: discovered={}, indexed={}, failed={}",
        summary.discovered, summary.indexed, summary.failed
    ))
    .bind(now_millis())
    .bind(id)
    .bind(lease_owner)
    .execute(pool)
    .await
    .and_then(|result| {
        (result.rows_affected() == 1)
            .then_some(result)
            .ok_or(sqlx::Error::RowNotFound)
    })?;
    Ok(())
}

pub(crate) async fn set_job_failed(
    pool: &SqlitePool,
    id: &str,
    error: &str,
    lease_owner: Option<&str>,
) -> Result<(), sqlx::Error> {
    let now = now_millis();
    if let Some(lease_owner) = lease_owner {
        sqlx::query(
            "UPDATE jobs SET status = 'failed', last_error = ?1, updated_at = ?2, finished_at = ?2, lease_owner = NULL, lease_until = NULL WHERE id = ?3 AND status = 'running' AND lease_owner = ?4",
        )
        .bind(error)
        .bind(now)
        .bind(id)
        .bind(lease_owner)
        .execute(pool)
        .await
        .and_then(|result| {
            (result.rows_affected() == 1)
                .then_some(result)
                .ok_or(sqlx::Error::RowNotFound)
        })?;
    } else {
        sqlx::query(
            "UPDATE jobs SET status = 'failed', last_error = ?1, updated_at = ?2, finished_at = ?2, lease_owner = NULL, lease_until = NULL WHERE id = ?3",
        )
        .bind(error)
        .bind(now)
        .bind(id)
        .execute(pool)
        .await
        .and_then(|result| {
            (result.rows_affected() == 1)
                .then_some(result)
                .ok_or(sqlx::Error::RowNotFound)
        })?;
    }
    Ok(())
}

pub(crate) fn parse_offset(cursor: Option<&str>) -> AppResult<i64> {
    let Some(cursor) = cursor else {
        return Ok(0);
    };
    let decoded = URL_SAFE_NO_PAD
        .decode(cursor)
        .map_err(|_| AppError::BadRequest("cursor must be an opaque page cursor".to_owned()))?;
    let decoded = String::from_utf8(decoded)
        .map_err(|_| AppError::BadRequest("cursor must be an opaque page cursor".to_owned()))?;
    let offset = decoded
        .strip_prefix("v1:")
        .ok_or_else(|| AppError::BadRequest("cursor must be an opaque page cursor".to_owned()))?
        .parse::<i64>()
        .map_err(|_| AppError::BadRequest("cursor must be an opaque page cursor".to_owned()))?;
    if offset < 0 {
        return Err(AppError::BadRequest(
            "cursor must not be negative".to_owned(),
        ));
    }
    Ok(offset)
}

pub(crate) fn parse_changes_cursor(cursor: Option<&str>) -> AppResult<ChangesCursor> {
    let Some(cursor) = cursor else {
        return Ok(ChangesCursor {
            revision: 0,
            event_id: String::new(),
        });
    };
    let decoded = URL_SAFE_NO_PAD
        .decode(cursor)
        .map_err(|_| AppError::BadRequest("cursor must be an opaque changes cursor".to_owned()))?;
    let decoded = String::from_utf8(decoded)
        .map_err(|_| AppError::BadRequest("cursor must be an opaque changes cursor".to_owned()))?;
    if decoded.starts_with("v1:") {
        return Err(AppError::ResyncRequired);
    }
    let mut parts = decoded.splitn(3, ':');
    let version = parts.next();
    let revision = parts.next();
    let event_id = parts.next();
    if version != Some("v2") || revision.is_none() || event_id.is_none() {
        return Err(AppError::BadRequest(
            "cursor must be an opaque changes cursor".to_owned(),
        ));
    }
    let revision = revision
        .and_then(|value| value.parse::<i64>().ok())
        .ok_or_else(|| AppError::BadRequest("cursor revision is invalid".to_owned()))?;
    if revision < 0 {
        return Err(AppError::BadRequest(
            "cursor revision must not be negative".to_owned(),
        ));
    }
    let event_id = event_id.unwrap_or_default().to_owned();
    if event_id.len() > 200 {
        return Err(AppError::BadRequest(
            "cursor event id is too long".to_owned(),
        ));
    }
    Ok(ChangesCursor { revision, event_id })
}

pub(crate) fn encode_page_cursor(offset: i64) -> String {
    URL_SAFE_NO_PAD.encode(format!("v1:{offset}"))
}

pub(crate) fn parse_if_match(headers: &HeaderMap) -> AppResult<i64> {
    let value = headers
        .get(header::IF_MATCH)
        .ok_or_else(|| AppError::BadRequest("If-Match version is required".to_owned()))?
        .to_str()
        .map_err(|_| AppError::BadRequest("invalid If-Match version".to_owned()))?;
    let value = value.trim().trim_matches('"');
    value
        .parse::<i64>()
        .map_err(|_| AppError::BadRequest("If-Match must be an integer version".to_owned()))
}

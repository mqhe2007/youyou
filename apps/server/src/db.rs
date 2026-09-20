use std::{
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions},
};

pub async fn connect(data_dir: &Path) -> anyhow::Result<SqlitePool> {
    tokio::fs::create_dir_all(data_dir).await?;
    let database_path = data_dir.join("youyou.db");
    let options = SqliteConnectOptions::new()
        .filename(database_path)
        .create_if_missing(true)
        .busy_timeout(Duration::from_secs(5))
        .journal_mode(SqliteJournalMode::Wal)
        .foreign_keys(true);
    let pool = SqlitePoolOptions::new()
        .max_connections(8)
        .connect_with(options)
        .await?;
    // Keep bootstrap scale indexes bundled with the binary's migration set.
    sqlx::migrate!("./migrations").run(&pool).await?;
    Ok(pool)
}

pub async fn ensure_local_storage(
    pool: &SqlitePool,
    root: &Path,
    read_only: bool,
) -> Result<(), sqlx::Error> {
    let root_path = root.to_string_lossy().into_owned();
    let now = now_millis();
    sqlx::query(
        r#"
        INSERT INTO storages (id, name, root_path, read_only, created_at, updated_at)
        VALUES ('local', 'Local directory', ?1, ?2, ?3, ?3)
        ON CONFLICT(id) DO UPDATE SET
            read_only = excluded.read_only,
            updated_at = excluded.updated_at
        "#,
    )
    .bind(root_path)
    .bind(if read_only { 1_i64 } else { 0_i64 })
    .bind(now)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn local_storage_root(pool: &SqlitePool) -> Result<Option<PathBuf>, sqlx::Error> {
    sqlx::query_scalar::<_, String>("SELECT root_path FROM storages WHERE id = 'local'")
        .fetch_optional(pool)
        .await
        .map(|path| path.map(PathBuf::from))
}

pub async fn server_instance_id(pool: &SqlitePool) -> Result<String, sqlx::Error> {
    sqlx::query_scalar::<_, String>("SELECT instance_id FROM server_identity WHERE id = 1")
        .fetch_one(pool)
        .await
}

pub async fn recover_running_jobs(pool: &SqlitePool) -> Result<(), sqlx::Error> {
    let now = now_millis();
    sqlx::query(
        r#"
        UPDATE jobs
        SET status = CASE
                WHEN cancel_requested = 1 THEN 'cancelled'
                WHEN retry_count + 1 >= max_attempts THEN 'failed'
                ELSE 'interrupted'
            END,
            message = CASE
                WHEN cancel_requested = 1 THEN 'cancelled before server restart recovery'
                WHEN retry_count + 1 >= max_attempts THEN 'server restarted; retry limit reached'
                ELSE 'server restarted while the job was running'
            END,
            last_error = CASE
                WHEN cancel_requested = 1 THEN last_error
                ELSE 'server restarted while the job was running'
            END,
            retry_count = CASE
                WHEN cancel_requested = 1 THEN retry_count
                ELSE retry_count + 1
            END,
            run_after = CASE
                WHEN cancel_requested = 1 OR retry_count + 1 >= max_attempts THEN 0
                ELSE 0
            END,
            updated_at = ?1,
            finished_at = CASE
                WHEN cancel_requested = 1 OR retry_count + 1 >= max_attempts THEN ?1
                ELSE NULL
            END,
            lease_owner = NULL,
            lease_until = NULL
        WHERE status = 'running'
        "#,
    )
    .bind(now)
    .execute(pool)
    .await?;
    sqlx::query(
        r#"
        UPDATE backups
        SET status = CASE
                WHEN EXISTS (
                    SELECT 1 FROM jobs
                    WHERE jobs.id = backups.job_id
                      AND jobs.status = 'cancelled'
                      AND jobs.updated_at = ?1
                ) THEN 'cancelled'
                ELSE 'failed'
            END,
            last_error = CASE
                WHEN EXISTS (
                    SELECT 1 FROM jobs
                    WHERE jobs.id = backups.job_id
                      AND jobs.status = 'cancelled'
                      AND jobs.updated_at = ?1
                ) THEN 'backup cancelled before server restart recovery'
                ELSE 'server restarted after the backup retry limit was reached'
            END,
            updated_at = ?1,
            completed_at = ?1
        WHERE status = 'running'
          AND EXISTS (
              SELECT 1 FROM jobs
              WHERE jobs.id = backups.job_id
                AND jobs.status IN ('cancelled', 'failed')
                AND jobs.updated_at = ?1
          )
        "#,
    )
    .bind(now)
    .execute(pool)
    .await?;
    Ok(())
}

pub fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
        .unwrap_or_default()
}

use std::{
    ops::{Deref, DerefMut},
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use sqlx::{
    Sqlite, SqliteConnection, SqlitePool,
    pool::PoolConnection,
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

/// 写事务守卫：以 `BEGIN IMMEDIATE` 开启，避免 WAL 下「先读后写」事务的
/// `SQLITE_BUSY_SNAPSHOT`——该错误不经过 busy_timeout，会立即以
/// `database is locked` 失败（见 2026-09-20 索引审计）。
pub struct WriteTransaction {
    connection: Option<PoolConnection<Sqlite>>,
    finished: bool,
}

impl WriteTransaction {
    pub async fn commit(mut self) -> Result<(), sqlx::Error> {
        self.finish("COMMIT").await
    }

    pub async fn rollback(mut self) -> Result<(), sqlx::Error> {
        self.finish("ROLLBACK").await
    }

    async fn finish(&mut self, statement: &str) -> Result<(), sqlx::Error> {
        let mut connection = self
            .connection
            .take()
            .expect("write transaction is already finished");
        let result = sqlx::query(statement)
            .execute(&mut *connection)
            .await
            .map(|_| ());
        self.finished = true;
        result
    }
}

impl Drop for WriteTransaction {
    fn drop(&mut self) {
        if self.finished {
            return;
        }
        let Some(mut connection) = self.connection.take() else {
            return;
        };
        // Drop 内不能 await：交给运行时异步回滚，连接归还连接池。
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            handle.spawn(async move {
                if let Err(error) = sqlx::query("ROLLBACK").execute(&mut *connection).await {
                    tracing::warn!(
                        error = ?error,
                        "failed to roll back an abandoned write transaction"
                    );
                }
            });
        }
    }
}

impl Deref for WriteTransaction {
    type Target = SqliteConnection;

    fn deref(&self) -> &Self::Target {
        self.connection
            .as_ref()
            .expect("write transaction is already finished")
    }
}

impl DerefMut for WriteTransaction {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.connection
            .as_mut()
            .expect("write transaction is already finished")
    }
}

/// 获取写事务。写锁竞争交给 busy_timeout（默认 5s）排队等待，而不是立即失败。
pub async fn begin_write(pool: &SqlitePool) -> Result<WriteTransaction, sqlx::Error> {
    let mut connection = pool.acquire().await?;
    sqlx::query("BEGIN IMMEDIATE")
        .execute(&mut *connection)
        .await?;
    Ok(WriteTransaction {
        connection: Some(connection),
        finished: false,
    })
}

/// 是否为 SQLite 的 BUSY 类错误（主码 5，含 `SQLITE_BUSY_SNAPSHOT` 等扩展码）。
pub fn is_busy_error(error: &sqlx::Error) -> bool {
    error
        .as_database_error()
        .and_then(|error| error.code())
        .and_then(|code| code.parse::<i32>().ok())
        .is_some_and(|code| code & 0xff == 5)
        || error.to_string().contains("database is locked")
}

/// `?` 传播后的 BUSY 判断：沿错误链查找 sqlx 错误。
pub fn is_busy_anyhow(error: &anyhow::Error) -> bool {
    error
        .chain()
        .filter_map(|cause| cause.downcast_ref::<sqlx::Error>())
        .any(is_busy_error)
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

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    async fn write_connection(pool: &SqlitePool) -> anyhow::Result<WriteTransaction> {
        Ok(begin_write(pool).await?)
    }

    #[tokio::test]
    async fn write_transaction_avoids_wal_snapshot_conflicts() -> anyhow::Result<()> {
        // 回归：WAL 下 deferred 事务「先读后写」在他人提交后会立即 BUSY
        // （SQLITE_BUSY_SNAPSHOT 不经过 busy_timeout）；BEGIN IMMEDIATE 则不会。
        let dir = tempdir()?;
        let pool = connect(dir.path()).await?;
        sqlx::query("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER NOT NULL)")
            .execute(&pool)
            .await?;
        sqlx::query("INSERT INTO t VALUES (1, 0)")
            .execute(&pool)
            .await?;

        let mut deferred = pool.begin().await?;
        let _ = sqlx::query_scalar::<_, i64>("SELECT v FROM t WHERE id = 1")
            .fetch_one(&mut *deferred)
            .await?;
        {
            let mut other = write_connection(&pool).await?;
            sqlx::query("UPDATE t SET v = v + 1 WHERE id = 1")
                .execute(&mut *other)
                .await?;
            other.commit().await?;
        }
        let error = sqlx::query("UPDATE t SET v = v + 1 WHERE id = 1")
            .execute(&mut *deferred)
            .await
            .unwrap_err();
        assert!(is_busy_error(&error), "expected SQLITE_BUSY, got {error:?}");
        deferred.rollback().await?;

        // BEGIN IMMEDIATE：同一路径稳定成功。
        let mut writer = write_connection(&pool).await?;
        let _ = sqlx::query_scalar::<_, i64>("SELECT v FROM t WHERE id = 1")
            .fetch_one(&mut *writer)
            .await?;
        sqlx::query("UPDATE t SET v = v + 1 WHERE id = 1")
            .execute(&mut *writer)
            .await?;
        writer.commit().await?;
        let value = sqlx::query_scalar::<_, i64>("SELECT v FROM t WHERE id = 1")
            .fetch_one(&pool)
            .await?;
        assert_eq!(value, 2);
        Ok(())
    }

    #[tokio::test]
    async fn abandoned_write_transaction_rolls_back() -> anyhow::Result<()> {
        let dir = tempdir()?;
        let pool = connect(dir.path()).await?;
        sqlx::query("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER NOT NULL)")
            .execute(&pool)
            .await?;
        sqlx::query("INSERT INTO t VALUES (1, 0)")
            .execute(&pool)
            .await?;

        {
            let mut writer = write_connection(&pool).await?;
            sqlx::query("UPDATE t SET v = v + 1 WHERE id = 1")
                .execute(&mut *writer)
                .await?;
            // 未显式 commit/rollback 直接丢弃 → Drop 异步回滚。
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
        let value = sqlx::query_scalar::<_, i64>("SELECT v FROM t WHERE id = 1")
            .fetch_one(&pool)
            .await?;
        assert_eq!(value, 0);
        Ok(())
    }
}

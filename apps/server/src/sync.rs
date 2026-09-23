use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::Serialize;
use sqlx::{FromRow, QueryBuilder, Sqlite, SqliteConnection, SqlitePool};

use crate::db::now_millis;

const SNAPSHOT_TTL_MS: i64 = 24 * 60 * 60 * 1000;
// Six bound values per row fit 4,000 rows below SQLite's default variable
// limit while keeping each write transaction short enough for other jobs.
const SNAPSHOT_INSERT_BATCH_SIZE: usize = 4_000;
pub const CHANGES_CURSOR_END: &str = "\u{10ffff}";

pub async fn allocate_revision(transaction: &mut SqliteConnection) -> Result<i64, sqlx::Error> {
    sqlx::query("UPDATE change_revision SET revision = revision + 1 WHERE id = 1")
        .execute(&mut *transaction)
        .await?;
    sqlx::query_scalar::<_, i64>("SELECT revision FROM change_revision WHERE id = 1")
        .fetch_one(&mut *transaction)
        .await
}

pub fn encode_changes_cursor(revision: i64, event_id: &str) -> String {
    URL_SAFE_NO_PAD.encode(format!("v2:{revision}:{event_id}"))
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotBuildResult {
    pub snapshot_revision: i64,
    pub changes_cursor: String,
    pub expires_at: i64,
    pub media_count: u64,
}

#[derive(Debug, FromRow)]
pub struct SnapshotRow {
    pub id: String,
    pub job_id: String,
    pub state: String,
    pub user_id: Option<i64>,
    pub snapshot_revision: Option<i64>,
    pub changes_cursor: Option<String>,
    pub expires_at: i64,
    pub created_at: i64,
    pub updated_at: i64,
    pub last_error: Option<String>,
}

#[derive(Debug, FromRow)]
pub struct SnapshotItemRow {
    pub entity_id: String,
    pub entity_version: i64,
    pub sort_at: Option<i64>,
    pub payload: String,
}

#[derive(Debug, FromRow)]
struct SnapshotPayloadRow {
    entity_id: String,
    entity_version: i64,
    sort_at: Option<i64>,
    payload: String,
}

pub async fn build_snapshot(
    pool: &SqlitePool,
    snapshot_id: &str,
    job_id: &str,
    user_id: i64,
) -> anyhow::Result<SnapshotBuildResult> {
    build_snapshot_with_lease(pool, snapshot_id, job_id, user_id, None).await
}

pub async fn build_snapshot_with_lease(
    pool: &SqlitePool,
    snapshot_id: &str,
    job_id: &str,
    user_id: i64,
    lease_owner: Option<&str>,
) -> anyhow::Result<SnapshotBuildResult> {
    if is_job_cancel_requested(pool, job_id).await? {
        return Err(anyhow::anyhow!("bootstrap cancelled"));
    }
    // Capture all source rows and the change revision from one read snapshot.
    // The payload is then immutable even if normal media/favorite writes happen
    // while the snapshot items are materialized.
    let mut read_transaction = pool.begin().await?;
    let snapshot_revision = sqlx::query_scalar::<_, i64>(
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
    .fetch_one(&mut *read_transaction)
    .await?;
    let media_rows = sqlx::query_as::<_, SnapshotPayloadRow>(&format!(
        r#"
        WITH ranked_locations AS (
            SELECT media_asset_id, storage_id, normalized_path, size, hash_state,
                   ROW_NUMBER() OVER (
                       PARTITION BY media_asset_id
                       ORDER BY storage_id ASC, normalized_path ASC, id ASC
                   ) AS location_rank
            FROM media_locations
            WHERE hash_state = 'verified'
        )
        SELECT a.id AS entity_id, a.version AS entity_version, a.sort_at,
               json_object(
                   'id', a.id,
                   'name', a.name,
                   'path', l.normalized_path,
                   'storageId', l.storage_id,
                   'size', l.size,
                   'mimeType', a.mime_type,
                   'isVideo', CASE WHEN a.is_video = 1 THEN json('true') ELSE json('false') END,
                   'durationMs', a.duration_ms,
                   'videoCodec', a.video_codec,
                   'contentHash', b.content_hash,
                   'identityState', a.identity_state,
                   'hashState', l.hash_state,
                   'width', a.width,
                   'height', a.height,
                   'takenAt', a.taken_at,
                   'sortAt', a.sort_at,
                   'sortSource', a.sort_source, 'timeVersion', a.time_version, 'originalName', a.original_name,
                   'version', a.version,
                   'isFavorite', CASE WHEN a.is_favorite = 1 THEN json('true') ELSE json('false') END,
                   {live}
               ) AS payload
        FROM media_assets a
        INNER JOIN ranked_locations l
            ON l.media_asset_id = a.id AND l.location_rank = 1
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE a.identity_state = 'verified' AND a.owner_user_id = ?1
          AND {visible}
        ORDER BY (a.sort_at IS NULL) ASC, a.sort_at DESC, a.id DESC
        "#,
        live = crate::live_photo::PAYLOAD_FRAGMENT,
        visible = crate::live_photo::VISIBLE_PREDICATE,
    ))
    .bind(user_id)
    .fetch_all(&mut *read_transaction)
    .await?;
    let tag_rows = sqlx::query_as::<_, SnapshotPayloadRow>(
        r#"
        SELECT id AS entity_id, version AS entity_version, updated_at AS sort_at,
               json_object(
                   'id', id, 'name', name, 'version', version,
                   'createdAt', created_at, 'updatedAt', updated_at
               ) AS payload
        FROM tags
        WHERE deleted_at IS NULL AND user_id = ?1
        ORDER BY name COLLATE NOCASE ASC, id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(&mut *read_transaction)
    .await?;
    let relation_rows = sqlx::query_as::<_, SnapshotPayloadRow>(
        r#"
        SELECT 'tag:' || mt.tag_id || ':' || mt.media_asset_id AS entity_id,
               mt.version AS entity_version, NULL AS sort_at,
               json_object(
                   'relationId', 'tag:' || mt.tag_id || ':' || mt.media_asset_id,
                   'albumId', NULL, 'tagId', mt.tag_id,
                   'mediaId', mt.media_asset_id, 'version', mt.version
               ) AS payload
        FROM media_tags mt
        INNER JOIN tags t ON t.id = mt.tag_id AND t.deleted_at IS NULL AND t.user_id = ?1
        INNER JOIN media_assets m ON m.id = mt.media_asset_id
            AND m.identity_state = 'verified' AND m.owner_user_id = ?1
        WHERE EXISTS (
            SELECT 1 FROM media_locations l
            WHERE l.media_asset_id = m.id AND l.hash_state = 'verified'
        )
        ORDER BY entity_id ASC
        "#,
    )
    .bind(user_id)
    .fetch_all(&mut *read_transaction)
    .await?;
    read_transaction.commit().await?;

    let now = now_millis();
    let expires_at = now + SNAPSHOT_TTL_MS;
    let mut last_heartbeat_at = now;
    let media_count = i64::try_from(media_rows.len()).unwrap_or(i64::MAX);

    // A retry/restart may leave a preparing snapshot with partial rows.  Clear
    // those rows before writing the immutable read-snapshot payloads.
    let mut cleanup = pool.begin_with("BEGIN IMMEDIATE").await?;
    sqlx::query("DELETE FROM sync_snapshot_items WHERE snapshot_id = ?1")
        .bind(snapshot_id)
        .execute(&mut *cleanup)
        .await?;
    cleanup.commit().await?;

    insert_snapshot_payloads(
        pool,
        snapshot_id,
        job_id,
        lease_owner,
        "media",
        &media_rows,
        &mut last_heartbeat_at,
    )
    .await?;

    // 相册实体已随"移除逻辑相册"决策（PRD D2）从同步信封移除。

    insert_snapshot_payloads(
        pool,
        snapshot_id,
        job_id,
        lease_owner,
        "tags",
        &tag_rows,
        &mut last_heartbeat_at,
    )
    .await?;
    insert_snapshot_payloads(
        pool,
        snapshot_id,
        job_id,
        lease_owner,
        "relations",
        &relation_rows,
        &mut last_heartbeat_at,
    )
    .await?;

    if is_job_cancel_requested(pool, job_id).await? {
        return Err(anyhow::anyhow!("bootstrap cancelled"));
    }

    let changes_cursor = encode_changes_cursor(snapshot_revision, CHANGES_CURSOR_END);
    let mut transaction = pool.begin_with("BEGIN IMMEDIATE").await?;
    let snapshot_updated = sqlx::query(
        r#"
        UPDATE sync_snapshots
        SET state = 'ready', snapshot_revision = ?1, changes_cursor = ?2,
            expires_at = ?3, updated_at = ?4, last_error = NULL
        WHERE id = ?5
          AND state = 'preparing'
          AND (
              ?6 IS NULL
              OR EXISTS (
                  SELECT 1
                  FROM jobs
                  WHERE id = ?7 AND status = 'running' AND lease_owner = ?6
              )
          )
        "#,
    )
    .bind(snapshot_revision)
    .bind(&changes_cursor)
    .bind(expires_at)
    .bind(now)
    .bind(snapshot_id)
    .bind(lease_owner)
    .bind(job_id)
    .execute(&mut *transaction)
    .await?
    .rows_affected();
    if snapshot_updated != 1 {
        return Err(anyhow::anyhow!("bootstrap job lease was lost"));
    }
    sqlx::query(
        r#"
        UPDATE jobs
        SET status = 'succeeded', current = ?1, total = ?1,
            message = ?2, updated_at = ?3, finished_at = ?3, heartbeat_at = ?3,
            lease_owner = NULL, lease_until = NULL
        WHERE id = ?4
          AND status = 'running'
          AND (?5 IS NULL OR lease_owner = ?5)
        "#,
    )
    .bind(media_count)
    .bind(format!("materialized {media_count} media items"))
    .bind(now)
    .bind(job_id)
    .bind(lease_owner)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;

    Ok(SnapshotBuildResult {
        snapshot_revision,
        changes_cursor,
        expires_at,
        media_count: u64::try_from(media_count).unwrap_or_default(),
    })
}

async fn insert_snapshot_payloads(
    pool: &SqlitePool,
    snapshot_id: &str,
    job_id: &str,
    lease_owner: Option<&str>,
    entity_type: &str,
    rows: &[SnapshotPayloadRow],
    last_heartbeat_at: &mut i64,
) -> anyhow::Result<()> {
    for batch in rows.chunks(SNAPSHOT_INSERT_BATCH_SIZE) {
        if is_job_cancel_requested(pool, job_id).await? {
            return Err(anyhow::anyhow!("bootstrap cancelled"));
        }
        let mut transaction = pool.begin_with("BEGIN IMMEDIATE").await?;
        let mut query = QueryBuilder::<Sqlite>::new(
            "INSERT INTO sync_snapshot_items (snapshot_id, entity_type, entity_id, entity_version, sort_at, payload) ",
        );
        query.push_values(batch, |mut row, item| {
            row.push_bind(snapshot_id)
                .push_bind(entity_type)
                .push_bind(&item.entity_id)
                .push_bind(item.entity_version)
                .push_bind(item.sort_at)
                .push_bind(&item.payload);
        });
        query.build().execute(&mut *transaction).await?;
        heartbeat_job(&mut transaction, job_id, lease_owner, last_heartbeat_at).await?;
        transaction.commit().await?;
    }
    Ok(())
}

async fn is_job_cancel_requested(pool: &SqlitePool, job_id: &str) -> Result<bool, sqlx::Error> {
    Ok(
        sqlx::query_scalar::<_, i64>("SELECT cancel_requested FROM jobs WHERE id = ?1")
            .bind(job_id)
            .fetch_optional(pool)
            .await?
            .unwrap_or_default()
            == 1,
    )
}

async fn heartbeat_job(
    transaction: &mut sqlx::SqliteConnection,
    job_id: &str,
    lease_owner: Option<&str>,
    last_heartbeat_at: &mut i64,
) -> anyhow::Result<()> {
    let now = now_millis();
    let Some(lease_owner) = lease_owner else {
        return Ok(());
    };
    if now.saturating_sub(*last_heartbeat_at) < 15_000 {
        return Ok(());
    }
    let updated = sqlx::query(
        "UPDATE jobs SET heartbeat_at = ?1, updated_at = ?1, lease_until = ?1 + 60000 WHERE id = ?2 AND status = 'running' AND lease_owner = ?3",
    )
    .bind(now)
    .bind(job_id)
    .bind(lease_owner)
    .execute(&mut *transaction)
    .await?
    .rows_affected();
    if updated != 1 {
        return Err(anyhow::anyhow!("bootstrap job lease was lost"));
    }
    *last_heartbeat_at = now;
    Ok(())
}

//! 多用户：用户实体、媒体库目录绑定与媒体归属。
//!
//! 归属模型：`media_assets.owner_user_id` 由文件所在目录（`user_libraries.root_path`
//! 前缀匹配）派生；未落在任何用户目录内的媒体归管理员（owner 为 NULL），仅
//! admin 媒体库可见。目录绑定/换绑时会回填归属并向受影响用户的变更流写
//! `media` upsert/delete 事件。

use std::path::Path;

use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;
use utoipa::ToSchema;
use uuid::Uuid;

use crate::{
    db::now_millis,
    error::{AppError, AppResult},
    sync,
};

/// 用户上传文件在其媒体库目录下的子目录。
pub const USER_UPLOAD_SUBDIRECTORY: &str = "uploads";

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UserSummary {
    pub id: i64,
    pub name: String,
    /// 相对存储根的媒体库目录；未绑定则为 null。
    pub library_root: Option<String>,
    pub device_count: i64,
    pub media_count: i64,
    pub created_at: i64,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateUserRequest {
    pub name: String,
    /// 媒体库目录名（仅英文字母和数字，1-64 位）。服务端在存储根下自动创建并终身绑定。
    pub library_name: String,
}

const USER_SUMMARY_SELECT: &str = r#"
    SELECT u.id, u.name, ul.root_path,
           (SELECT COUNT(*) FROM devices d
            WHERE d.user_id = u.id AND d.revoked_at IS NULL) AS device_count,
           (SELECT COUNT(*) FROM media_assets a
            WHERE a.owner_user_id = u.id AND a.identity_state = 'verified') AS media_count,
           u.created_at
    FROM users u
    LEFT JOIN user_libraries ul ON ul.user_id = u.id
"#;

pub async fn create_user(
    pool: &SqlitePool,
    storage_root: &Path,
    request: CreateUserRequest,
) -> AppResult<UserSummary> {
    let name = validate_user_name(&request.name)?;
    let library_name = validate_library_name(&request.library_name)?;
    ensure_no_active_scan(pool).await?;

    // 媒体库目录与用户绑死后随创建落盘；同名目录已存在则直接复用。
    let directory = storage_root.join(&library_name);
    tokio::fs::create_dir_all(&directory)
        .await
        .map_err(|error| {
            AppError::Conflict(format!("无法创建媒体库目录 {library_name}: {error}"))
        })?;
    // 创建/复用目录前做一次真实读写探测，避免绑定成功后扫描才暴露权限问题。
    let probe = directory.join(format!(".youyou-write-check-{}", Uuid::new_v4()));
    tokio::fs::write(&probe, b"youyou").await.map_err(|error| {
        AppError::Conflict(format!("媒体库目录 {library_name} 不可写：{error}"))
    })?;
    let probe_ok = tokio::fs::read(&probe).await.is_ok();
    let _ = tokio::fs::remove_file(&probe).await;
    if !probe_ok {
        return Err(AppError::Conflict(format!(
            "媒体库目录 {library_name} 不可读，请检查权限"
        )));
    }
    let canonical_dir = directory
        .canonicalize()
        .map_err(|error| AppError::Internal(error.into()))?;
    let canonical_root = storage_root
        .canonicalize()
        .map_err(|error| AppError::Internal(error.into()))?;
    if !canonical_dir.starts_with(&canonical_root) {
        return Err(AppError::BadRequest("目录名越出了媒体存储根".to_owned()));
    }

    let mut transaction = pool.begin().await?;
    let now = now_millis();
    ensure_library_name_free(&mut transaction, &library_name).await?;
    let result =
        sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES (?1, ?2, ?2)")
            .bind(name)
            .bind(now)
            .execute(&mut *transaction)
            .await
            .map_err(map_user_conflict)?;
    let user_id = result.last_insert_rowid();
    sqlx::query(
        "INSERT INTO user_libraries (user_id, root_path, created_at, updated_at) VALUES (?1, ?2, ?3, ?3)",
    )
    .bind(user_id)
    .bind(&library_name)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    claim_unowned_assets(&mut transaction, revision, user_id, &library_name, now).await?;
    transaction.commit().await?;
    load_user_summary(pool, user_id).await
}

/// 目录名仅允许 1-64 位英文字母和数字（非中文合法目录名规则）。
fn validate_library_name(raw: &str) -> AppResult<String> {
    let name = raw.trim();
    if !name.is_empty() && name.len() <= 64 && name.chars().all(|c| c.is_ascii_alphanumeric()) {
        Ok(name.to_owned())
    } else {
        Err(AppError::BadRequest(
            "媒体库目录名只能包含 1-64 位英文字母和数字".to_owned(),
        ))
    }
}

async fn ensure_library_name_free(
    transaction: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    library_name: &str,
) -> AppResult<()> {
    let taken = sqlx::query_scalar::<_, i64>("SELECT 1 FROM user_libraries WHERE root_path = ?1")
        .bind(library_name)
        .fetch_optional(&mut **transaction)
        .await?;
    if taken.is_some() {
        return Err(AppError::Conflict(format!(
            "媒体库目录 {library_name} 已被其他用户绑定"
        )));
    }
    Ok(())
}

/// 把目标目录下已索引的无属主媒体划归该用户（历史目录复用场景），并写变更。
async fn claim_unowned_assets(
    transaction: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    revision: i64,
    user_id: i64,
    root_relative: &str,
    now: i64,
) -> AppResult<()> {
    let claimed_ids = sqlx::query_scalar::<_, String>(
        r#"
        SELECT a.id FROM media_assets a
        WHERE a.owner_user_id IS NULL
          AND a.identity_state = 'verified'
          AND EXISTS (
              SELECT 1 FROM media_locations l
              WHERE l.media_asset_id = a.id
                AND l.hash_state = 'verified'
                AND (l.normalized_path = ?1 OR l.normalized_path LIKE ?1 || '/%')
          )
        "#,
    )
    .bind(root_relative)
    .fetch_all(&mut **transaction)
    .await?;
    if claimed_ids.is_empty() {
        return Ok(());
    }
    sqlx::query(
        "UPDATE media_assets SET owner_user_id = ?1, version = version + 1, updated_at = ?2 WHERE id IN (SELECT value FROM json_each(?3))",
    )
    .bind(user_id)
    .bind(now)
    .bind(serde_json::to_string(&claimed_ids).map_err(|e| AppError::Internal(e.into()))?)
    .execute(&mut **transaction)
    .await?;
    for media_id in &claimed_ids {
        append_media_upsert_change(transaction, revision, media_id, Some(user_id), now).await?;
    }
    Ok(())
}

pub async fn list_users(pool: &SqlitePool) -> AppResult<Vec<UserSummary>> {
    let rows = sqlx::query_as::<_, (i64, String, Option<String>, i64, i64, i64)>(&format!(
        "{USER_SUMMARY_SELECT} ORDER BY u.id ASC"
    ))
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().map(user_summary_from_row).collect())
}

pub async fn load_user_summary(pool: &SqlitePool, user_id: i64) -> AppResult<UserSummary> {
    let row = sqlx::query_as::<_, (i64, String, Option<String>, i64, i64, i64)>(&format!(
        "{USER_SUMMARY_SELECT} WHERE u.id = ?1"
    ))
    .bind(user_id)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| AppError::NotFound("user not found".to_owned()))?;
    Ok(user_summary_from_row(row))
}

fn user_summary_from_row(row: (i64, String, Option<String>, i64, i64, i64)) -> UserSummary {
    UserSummary {
        id: row.0,
        name: row.1,
        library_root: row.2,
        device_count: row.3,
        media_count: row.4,
        created_at: row.5,
    }
}

pub async fn user_exists(pool: &SqlitePool, user_id: i64) -> AppResult<bool> {
    Ok(
        sqlx::query_scalar::<_, i64>("SELECT 1 FROM users WHERE id = ?1")
            .bind(user_id)
            .fetch_optional(pool)
            .await?
            .is_some(),
    )
}

/// 删除用户：吊销其设备、作废配对码、删除其标签、媒体归还无属主池。
pub async fn delete_user(pool: &SqlitePool, user_id: i64) -> AppResult<()> {
    let mut transaction = pool.begin().await?;
    let now = now_millis();
    let revision = sync::allocate_revision(&mut transaction).await?;

    // 媒体归还无属主池，并向该用户的变更流补 delete 事件，避免在线设备残留投影。
    let owned_ids =
        sqlx::query_scalar::<_, String>("SELECT id FROM media_assets WHERE owner_user_id = ?1")
            .bind(user_id)
            .fetch_all(&mut *transaction)
            .await?;
    if !owned_ids.is_empty() {
        sqlx::query(
            "UPDATE media_assets SET owner_user_id = NULL, version = version + 1, updated_at = ?1 WHERE owner_user_id = ?2",
        )
        .bind(now)
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
        for media_id in &owned_ids {
            append_media_delete_change(&mut transaction, revision, media_id, user_id, now).await?;
        }
    }

    sqlx::query("DELETE FROM media_tags WHERE tag_id IN (SELECT id FROM tags WHERE user_id = ?1)")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query("DELETE FROM tags WHERE user_id = ?1")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query("UPDATE devices SET revoked_at = ?1, user_id = NULL WHERE user_id = ?2")
        .bind(now)
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query("DELETE FROM pairing_codes WHERE user_id = ?1")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query("UPDATE change_log SET owner_user_id = NULL WHERE owner_user_id = ?1")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query("UPDATE jobs SET user_id = NULL WHERE user_id = ?1")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query("UPDATE sync_snapshots SET user_id = NULL WHERE user_id = ?1")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query("DELETE FROM user_libraries WHERE user_id = ?1")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?;
    let deleted = sqlx::query("DELETE FROM users WHERE id = ?1")
        .bind(user_id)
        .execute(&mut *transaction)
        .await?
        .rows_affected();
    if deleted == 0 {
        return Err(AppError::NotFound("user not found".to_owned()));
    }
    transaction.commit().await?;
    Ok(())
}

/// 为已存在用户完成媒体库绑定（创建用户时的内部步骤；媒体库与用户绑死后不提供换绑）。
/// 目录在存储根下自动创建；目录内已索引的无属主媒体随之认领。
pub async fn bind_user_library(
    pool: &SqlitePool,
    storage_root: &Path,
    user_id: i64,
    library_name: &str,
) -> AppResult<UserSummary> {
    if !user_exists(pool, user_id).await? {
        return Err(AppError::NotFound("user not found".to_owned()));
    }
    let library_name = validate_library_name(library_name)?;
    ensure_no_active_scan(pool).await?;

    let directory = storage_root.join(&library_name);
    tokio::fs::create_dir_all(&directory)
        .await
        .map_err(|error| {
            AppError::Conflict(format!("无法创建媒体库目录 {library_name}: {error}"))
        })?;

    let mut transaction = pool.begin().await?;
    let now = now_millis();
    ensure_library_name_free(&mut transaction, &library_name).await?;
    sqlx::query(
        "INSERT INTO user_libraries (user_id, root_path, created_at, updated_at) VALUES (?1, ?2, ?3, ?3)",
    )
    .bind(user_id)
    .bind(&library_name)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    claim_unowned_assets(&mut transaction, revision, user_id, &library_name, now).await?;
    transaction.commit().await?;
    load_user_summary(pool, user_id).await
}

/// 用户的媒体库根（相对存储根）。未绑定返回 None。
pub async fn user_library_root(pool: &SqlitePool, user_id: i64) -> AppResult<Option<String>> {
    sqlx::query_scalar("SELECT root_path FROM user_libraries WHERE user_id = ?1")
        .bind(user_id)
        .fetch_optional(pool)
        .await
        .map_err(AppError::from)
}

pub async fn require_user_library_root(pool: &SqlitePool, user_id: i64) -> AppResult<String> {
    user_library_root(pool, user_id).await?.ok_or_else(|| {
        AppError::Conflict(
            "device user has no media library bound; ask the admin to bind one".to_owned(),
        )
    })
}

/// 按目录前缀解析媒体归属（路径为相对存储根的相对路径）。
pub async fn resolve_owner_for_path(
    pool: &SqlitePool,
    relative_path: &str,
) -> AppResult<Option<i64>> {
    sqlx::query_scalar(
        r#"
        SELECT user_id FROM user_libraries
        WHERE ?1 = root_path OR ?1 LIKE root_path || '/%'
        ORDER BY length(root_path) DESC
        LIMIT 1
        "#,
    )
    .bind(relative_path)
    .fetch_optional(pool)
    .await
    .map_err(AppError::from)
}

async fn ensure_no_active_scan(pool: &SqlitePool) -> AppResult<()> {
    let active = sqlx::query_scalar::<_, i64>(
        "SELECT 1 FROM jobs WHERE kind = 'scan' AND status IN ('queued', 'running') LIMIT 1",
    )
    .fetch_optional(pool)
    .await?;
    if active.is_some() {
        return Err(AppError::Conflict(
            "a media scan is active; retry after it finishes".to_owned(),
        ));
    }
    Ok(())
}

fn validate_user_name(raw: &str) -> AppResult<&str> {
    let name = raw.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest(
            "user name must contain 1-100 characters".to_owned(),
        ));
    }
    Ok(name)
}

fn map_user_conflict(error: sqlx::Error) -> AppError {
    if error
        .to_string()
        .to_ascii_lowercase()
        .contains("unique constraint failed")
    {
        AppError::Conflict("user name already exists".to_owned())
    } else {
        AppError::Database(error)
    }
}

/// 以与 scan.rs 一致的 payload 形状，向指定用户的变更流写入媒体 upsert。
pub(crate) async fn append_media_upsert_change(
    transaction: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    revision: i64,
    media_id: &str,
    owner_user_id: Option<i64>,
    now: i64,
) -> AppResult<()> {
    let row = sqlx::query_as::<_, MediaChangeRow>(
        r#"
        WITH ranked_locations AS (
            SELECT l.*, ROW_NUMBER() OVER (
                PARTITION BY l.media_asset_id
                ORDER BY l.storage_id ASC, l.normalized_path ASC, l.id ASC
            ) AS location_rank
            FROM media_locations l
            WHERE l.hash_state = 'verified'
        )
        SELECT a.id, a.name, l.normalized_path, l.size, a.mime_type, a.is_video,
               a.duration_ms, a.video_codec, b.content_hash, a.width, a.height,
               a.taken_at, a.sort_at, a.sort_source, a.time_version, a.original_name, a.version, a.is_favorite
        FROM media_assets a
        INNER JOIN ranked_locations l ON l.media_asset_id = a.id AND l.location_rank = 1
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE a.id = ?1
        "#,
    )
    .bind(media_id)
    .fetch_optional(&mut **transaction)
    .await?;
    let Some(row) = row else {
        return Ok(());
    };
    let payload = serde_json::json!({
        "id": row.id,
        "name": row.name,
        "path": row.normalized_path,
        "size": row.size,
        "contentHash": row.content_hash,
        "mimeType": row.mime_type,
        "isVideo": row.is_video == 1,
        "isFavorite": row.is_favorite == 1,
        "storageId": "local",
        "identityState": "verified",
        "hashState": "verified",
        "durationMs": row.duration_ms,
        "videoCodec": row.video_codec,
        "width": row.width,
        "height": row.height,
        "takenAt": row.taken_at,
        "sortAt": row.sort_at,
        "sortSource": row.sort_source, "timeVersion": row.time_version, "originalName": row.original_name,
    });
    sqlx::query(
        r#"
        INSERT INTO change_log
            (revision, event_id, entity, operation, entity_id, version, payload, created_at, owner_user_id)
        VALUES (?1, ?2, 'media', 'upsert', ?3, ?4, ?5, ?6, ?7)
        "#,
    )
    .bind(revision)
    .bind(Uuid::new_v4().to_string())
    .bind(media_id)
    .bind(row.version)
    .bind(serde_json::to_string(&payload).map_err(|e| AppError::Internal(e.into()))?)
    .bind(now)
    .bind(owner_user_id)
    .execute(&mut **transaction)
    .await?;
    Ok(())
}

/// 向指定用户的变更流写入媒体 delete（归属移除/用户删除场景，文件本身仍在磁盘）。
pub(crate) async fn append_media_delete_change(
    transaction: &mut sqlx::Transaction<'_, sqlx::Sqlite>,
    revision: i64,
    media_id: &str,
    owner_user_id: i64,
    now: i64,
) -> AppResult<()> {
    let version: Option<i64> = sqlx::query_scalar("SELECT version FROM media_assets WHERE id = ?1")
        .bind(media_id)
        .fetch_optional(&mut **transaction)
        .await?;
    let Some(version) = version else {
        return Ok(());
    };
    let payload = serde_json::json!({
        "id": media_id,
        "version": version,
        "reason": "library_rebound",
    });
    sqlx::query(
        r#"
        INSERT INTO change_log
            (revision, event_id, entity, operation, entity_id, version, payload, created_at, owner_user_id)
        VALUES (?1, ?2, 'media', 'delete', ?3, ?4, ?5, ?6, ?7)
        "#,
    )
    .bind(revision)
    .bind(Uuid::new_v4().to_string())
    .bind(media_id)
    .bind(version)
    .bind(serde_json::to_string(&payload).map_err(|e| AppError::Internal(e.into()))?)
    .bind(now)
    .bind(owner_user_id)
    .execute(&mut **transaction)
    .await?;
    Ok(())
}

#[derive(Debug, sqlx::FromRow)]
struct MediaChangeRow {
    id: String,
    name: String,
    normalized_path: String,
    size: i64,
    mime_type: Option<String>,
    is_video: i64,
    duration_ms: Option<i64>,
    video_codec: Option<String>,
    content_hash: Option<String>,
    width: Option<i64>,
    height: Option<i64>,
    taken_at: Option<i64>,
    sort_at: Option<i64>,
    sort_source: String,
    time_version: i64,
    original_name: Option<String>,
    version: i64,
    is_favorite: i64,
}

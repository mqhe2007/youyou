use std::collections::HashSet;

use serde::Serialize;
use sqlx::{FromRow, SqliteConnection, SqlitePool};
use utoipa::ToSchema;
use uuid::Uuid;

use crate::{
    db::now_millis,
    error::{AppError, AppResult},
    sync,
};

#[derive(Debug, Clone, Serialize, FromRow, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct Album {
    pub id: String,
    pub name: String,
    pub cover_media_id: Option<String>,
    pub photo_count: i64,
    pub version: i64,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, FromRow, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct Tag {
    pub id: String,
    pub name: String,
    pub version: i64,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, FromRow, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct Relation {
    pub album_id: Option<String>,
    pub tag_id: Option<String>,
    pub media_id: String,
    pub version: i64,
}

pub async fn list_tags(
    pool: &SqlitePool,
    user_id: i64,
    offset: i64,
    limit: i64,
) -> AppResult<(Vec<Tag>, bool)> {
    let rows = sqlx::query_as::<_, Tag>(
        r#"
        SELECT id, name, version, created_at, updated_at
        FROM tags
        WHERE deleted_at IS NULL AND user_id = ?1
        ORDER BY name COLLATE NOCASE ASC, id ASC
        LIMIT ?2 OFFSET ?3
        "#,
    )
    .bind(user_id)
    .bind(limit + 1)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    let has_more = rows.len() > limit as usize;
    Ok((rows.into_iter().take(limit as usize).collect(), has_more))
}

pub async fn create_tag(pool: &SqlitePool, user_id: i64, name: &str) -> AppResult<Tag> {
    let name = validate_name(name)?;
    let id = Uuid::new_v4().to_string();
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    sqlx::query(
        "INSERT INTO tags (id, name, user_id, version, created_at, updated_at) VALUES (?1, ?2, ?3, 1, ?4, ?4)",
    )
    .bind(&id)
    .bind(name)
    .bind(user_id)
    .bind(now)
    .execute(&mut *transaction)
    .await
    .map_err(map_unique_name_error)?;
    let payload = serde_json::json!({
        "id": id,
        "name": name,
        "version": 1,
        "createdAt": now,
        "updatedAt": now,
    });
    append_change(
        &mut transaction,
        revision,
        "tag",
        "upsert",
        &id,
        1,
        &payload,
        now,
        Some(user_id),
    )
    .await?;
    transaction.commit().await?;
    load_tag(pool, &id).await
}

pub async fn update_tag(
    pool: &SqlitePool,
    user_id: i64,
    id: &str,
    name: &str,
    expected_version: i64,
) -> AppResult<Tag> {
    let name = validate_name(name)?;
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    let (current, owner) = load_tag_tx(&mut transaction, id).await?;
    ensure_tag_owner(owner, user_id)?;
    ensure_version(current.version, expected_version)?;
    let result = sqlx::query(
        "UPDATE tags SET name = ?1, version = version + 1, updated_at = ?2 WHERE id = ?3 AND deleted_at IS NULL AND version = ?4",
    )
    .bind(name)
    .bind(now)
    .bind(id)
    .bind(expected_version)
    .execute(&mut *transaction)
    .await
    .map_err(map_unique_name_error)?;
    if result.rows_affected() != 1 {
        return Err(AppError::Conflict("tag changed while updating".to_owned()));
    }
    let (updated, _) = load_tag_tx(&mut transaction, id).await?;
    let payload =
        serde_json::to_value(&updated).map_err(|error| AppError::Internal(error.into()))?;
    append_change(
        &mut transaction,
        revision,
        "tag",
        "upsert",
        id,
        updated.version,
        &payload,
        now,
        Some(user_id),
    )
    .await?;
    transaction.commit().await?;
    Ok(updated)
}

pub async fn delete_tag(
    pool: &SqlitePool,
    user_id: i64,
    id: &str,
    expected_version: i64,
) -> AppResult<Tag> {
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    let (current, owner) = load_tag_tx(&mut transaction, id).await?;
    ensure_tag_owner(owner, user_id)?;
    ensure_version(current.version, expected_version)?;
    detach_tag_relations_tx(&mut transaction, id, user_id, now, revision).await?;
    let result = sqlx::query(
        "UPDATE tags SET deleted_at = ?1, version = version + 1, updated_at = ?1 WHERE id = ?2 AND deleted_at IS NULL AND version = ?3",
    )
    .bind(now)
    .bind(id)
    .bind(expected_version)
    .execute(&mut *transaction)
    .await?;
    if result.rows_affected() != 1 {
        return Err(AppError::Conflict("tag changed while deleting".to_owned()));
    }
    let deleted_version = current.version + 1;
    let payload = serde_json::json!({
        "id": id,
        "version": deleted_version,
        "reason": "tag_deleted",
    });
    append_change(
        &mut transaction,
        revision,
        "tag",
        "delete",
        id,
        deleted_version,
        &payload,
        now,
        Some(user_id),
    )
    .await?;
    transaction.commit().await?;
    Ok(Tag {
        version: deleted_version,
        updated_at: now,
        ..current
    })
}

pub async fn add_tag_media(
    pool: &SqlitePool,
    user_id: i64,
    tag_id: &str,
    media_id: &str,
) -> AppResult<Relation> {
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    ensure_tag_and_media(&mut transaction, user_id, tag_id, media_id).await?;
    let relation = relation_id(tag_id, media_id);
    let mut version = next_relation_version(&mut transaction, "tag_relation", &relation).await?;
    let result = sqlx::query(
        "INSERT INTO media_tags (tag_id, media_asset_id, version, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?4) ON CONFLICT(tag_id, media_asset_id) DO NOTHING",
    )
    .bind(tag_id)
    .bind(media_id)
    .bind(version)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    if result.rows_affected() == 1 {
        remember_relation_version(&mut transaction, "tag_relation", &relation, version, None)
            .await?;
        let payload = serde_json::json!({
            "tagId": tag_id,
            "mediaId": media_id,
            "version": version,
        });
        append_change(
            &mut transaction,
            revision,
            "tag_relation",
            "upsert",
            &relation,
            version,
            &payload,
            now,
            Some(user_id),
        )
        .await?;
    } else {
        version = sqlx::query_scalar::<_, i64>(
            "SELECT version FROM media_tags WHERE tag_id = ?1 AND media_asset_id = ?2",
        )
        .bind(tag_id)
        .bind(media_id)
        .fetch_one(&mut *transaction)
        .await?;
    }
    transaction.commit().await?;
    Ok(Relation {
        album_id: None,
        tag_id: Some(tag_id.to_owned()),
        media_id: media_id.to_owned(),
        version,
    })
}

pub async fn remove_tag_media(
    pool: &SqlitePool,
    user_id: i64,
    tag_id: &str,
    media_id: &str,
) -> AppResult<()> {
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    ensure_tag_owner_tx(&mut transaction, user_id, tag_id).await?;
    let relation = relation_id(tag_id, media_id);
    let current_version = sqlx::query_scalar::<_, i64>(
        "SELECT version FROM media_tags WHERE tag_id = ?1 AND media_asset_id = ?2",
    )
    .bind(tag_id)
    .bind(media_id)
    .fetch_optional(&mut *transaction)
    .await?
    .ok_or_else(|| AppError::NotFound("tag relation not found".to_owned()))?;
    let result = sqlx::query("DELETE FROM media_tags WHERE tag_id = ?1 AND media_asset_id = ?2")
        .bind(tag_id)
        .bind(media_id)
        .execute(&mut *transaction)
        .await?;
    debug_assert_eq!(result.rows_affected(), 1);
    remember_relation_version(
        &mut transaction,
        "tag_relation",
        &relation,
        current_version + 1,
        Some(now),
    )
    .await?;
    let payload = serde_json::json!({
        "tagId": tag_id,
        "mediaId": media_id,
        "version": current_version + 1,
        "reason": "relation_removed",
    });
    append_change(
        &mut transaction,
        revision,
        "tag_relation",
        "delete",
        &relation,
        current_version + 1,
        &payload,
        now,
        Some(user_id),
    )
    .await?;
    transaction.commit().await?;
    Ok(())
}

async fn detach_tag_relations_tx(
    transaction: &mut SqliteConnection,
    tag_id: &str,
    user_id: i64,
    now: i64,
    revision: i64,
) -> AppResult<()> {
    let relations = sqlx::query_as::<_, (String, i64)>(
        "SELECT media_asset_id, version FROM media_tags WHERE tag_id = ?1",
    )
    .bind(tag_id)
    .fetch_all(&mut *transaction)
    .await?;
    for (media_id, current_version) in relations {
        sqlx::query("DELETE FROM media_tags WHERE tag_id = ?1 AND media_asset_id = ?2")
            .bind(tag_id)
            .bind(&media_id)
            .execute(&mut *transaction)
            .await?;
        let relation = relation_id(tag_id, &media_id);
        let version = current_version + 1;
        remember_relation_version(transaction, "tag_relation", &relation, version, Some(now))
            .await?;
        append_change(
            transaction,
            revision,
            "tag_relation",
            "delete",
            &relation,
            version,
            &serde_json::json!({
                "tagId": tag_id,
                "mediaId": media_id,
                "version": version,
                "reason": "tag_deleted",
            }),
            now,
            Some(user_id),
        )
        .await?;
    }
    Ok(())
}

/// Mark a media asset unavailable and emit all projection changes in one
/// transaction. Relations are physically removed only after their durable
/// version/tombstone has been recorded in `relation_versions`.
pub(crate) async fn tombstone_media_tx(
    transaction: &mut SqliteConnection,
    media_id: &str,
    reason: &str,
    now: i64,
    revision: i64,
) -> AppResult<bool> {
    let owner_user_id: Option<i64> =
        sqlx::query_scalar("SELECT owner_user_id FROM media_assets WHERE id = ?1")
            .bind(media_id)
            .fetch_optional(&mut *transaction)
            .await?
            .flatten();
    let changed = sqlx::query(
        "UPDATE media_assets SET identity_state = 'tombstoned', version = version + 1, updated_at = ?1 WHERE id = ?2 AND identity_state != 'tombstoned'",
    )
    .bind(now)
    .bind(media_id)
    .execute(&mut *transaction)
    .await?
    .rows_affected();
    if changed != 1 {
        return Ok(false);
    }

    detach_media_relations_tx(transaction, media_id, now, revision).await?;
    // 实况配对是派生态：一侧消失即整体降级为普通媒体（FR-8），对手的投影同步更新。
    for id in crate::live_photo::degrade_pair_tx(transaction, media_id, now).await? {
        let state = sqlx::query_scalar::<_, String>(
            "SELECT identity_state FROM media_assets WHERE id = ?1",
        )
        .bind(&id)
        .fetch_one(&mut *transaction)
        .await?;
        if state == "verified" {
            crate::live_photo::emit(transaction, revision, &id, now).await?;
        }
    }
    let version = sqlx::query_scalar::<_, i64>("SELECT version FROM media_assets WHERE id = ?1")
        .bind(media_id)
        .fetch_one(&mut *transaction)
        .await?;
    let payload = serde_json::json!({
        "id": media_id,
        "version": version,
        "reason": reason,
    });
    append_change(
        transaction,
        revision,
        "media",
        "delete",
        media_id,
        version,
        &payload,
        now,
        owner_user_id,
    )
    .await?;
    Ok(true)
}

/// Remove all relations for a media asset while preserving durable deletion
/// versions. This is used when a media tombstone is created.
pub(crate) async fn detach_media_relations_tx(
    transaction: &mut SqliteConnection,
    media_id: &str,
    now: i64,
    revision: i64,
) -> AppResult<()> {
    let album_relations = sqlx::query_as::<_, (String, i64)>(
        "SELECT album_id, version FROM album_media WHERE media_asset_id = ?1",
    )
    .bind(media_id)
    .fetch_all(&mut *transaction)
    .await?;
    let mut affected_albums = HashSet::new();

    for (album_id, current_version) in album_relations {
        sqlx::query("DELETE FROM album_media WHERE album_id = ?1 AND media_asset_id = ?2")
            .bind(&album_id)
            .bind(media_id)
            .execute(&mut *transaction)
            .await?;
        let relation = relation_id(&album_id, media_id);
        let version = current_version + 1;
        remember_relation_version(transaction, "album_relation", &relation, version, Some(now))
            .await?;
        let payload = serde_json::json!({
            "albumId": album_id,
            "mediaId": media_id,
            "version": version,
            "reason": "media_tombstoned",
        });
        append_change(
            transaction,
            revision,
            "album_relation",
            "delete",
            &relation,
            version,
            &payload,
            now,
            None,
        )
        .await?;
        affected_albums.insert(album_id);
    }

    let tag_relations = sqlx::query_as::<_, (String, i64, Option<i64>)>(
        r#"
        SELECT mt.tag_id, mt.version, t.user_id
        FROM media_tags mt
        INNER JOIN tags t ON t.id = mt.tag_id
        WHERE mt.media_asset_id = ?1
        "#,
    )
    .bind(media_id)
    .fetch_all(&mut *transaction)
    .await?;

    for (tag_id, current_version, tag_owner) in tag_relations {
        sqlx::query("DELETE FROM media_tags WHERE tag_id = ?1 AND media_asset_id = ?2")
            .bind(&tag_id)
            .bind(media_id)
            .execute(&mut *transaction)
            .await?;
        let relation = relation_id(&tag_id, media_id);
        let version = current_version + 1;
        remember_relation_version(transaction, "tag_relation", &relation, version, Some(now))
            .await?;
        let payload = serde_json::json!({
            "tagId": tag_id,
            "mediaId": media_id,
            "version": version,
            "reason": "media_tombstoned",
        });
        append_change(
            transaction,
            revision,
            "tag_relation",
            "delete",
            &relation,
            version,
            &payload,
            now,
            tag_owner,
        )
        .await?;
    }

    let cover_albums = sqlx::query_scalar::<_, String>(
        "SELECT id FROM albums WHERE cover_media_id = ?1 AND deleted_at IS NULL",
    )
    .bind(media_id)
    .fetch_all(&mut *transaction)
    .await?;
    for album_id in cover_albums {
        sqlx::query("UPDATE albums SET cover_media_id = NULL WHERE id = ?1 AND deleted_at IS NULL")
            .bind(&album_id)
            .execute(&mut *transaction)
            .await?;
        affected_albums.insert(album_id);
    }

    for album_id in affected_albums {
        touch_album_tx(transaction, &album_id, now, revision).await?;
    }
    Ok(())
}

/// Move relations when a scanned location is deduplicated into another media
/// identity. Both sides receive explicit changes so existing client
/// projections cannot retain the old relation.
pub(crate) async fn move_media_relations_tx(
    transaction: &mut SqliteConnection,
    old_media_id: &str,
    new_media_id: &str,
    now: i64,
    revision: i64,
) -> AppResult<()> {
    if old_media_id == new_media_id {
        return Ok(());
    }

    let album_relations = sqlx::query_as::<_, (String, i64)>(
        "SELECT album_id, version FROM album_media WHERE media_asset_id = ?1",
    )
    .bind(old_media_id)
    .fetch_all(&mut *transaction)
    .await?;
    let tag_relations = sqlx::query_as::<_, (String, i64, Option<i64>)>(
        r#"
        SELECT mt.tag_id, mt.version, t.user_id
        FROM media_tags mt
        INNER JOIN tags t ON t.id = mt.tag_id
        WHERE mt.media_asset_id = ?1
        "#,
    )
    .bind(old_media_id)
    .fetch_all(&mut *transaction)
    .await?;
    let mut affected_albums = HashSet::new();

    for (album_id, old_version) in album_relations {
        sqlx::query("DELETE FROM album_media WHERE album_id = ?1 AND media_asset_id = ?2")
            .bind(&album_id)
            .bind(old_media_id)
            .execute(&mut *transaction)
            .await?;
        let old_relation = relation_id(&album_id, old_media_id);
        let old_delete_version = old_version + 1;
        remember_relation_version(
            transaction,
            "album_relation",
            &old_relation,
            old_delete_version,
            Some(now),
        )
        .await?;
        append_change(
            transaction,
            revision,
            "album_relation",
            "delete",
            &old_relation,
            old_delete_version,
            &serde_json::json!({
                "albumId": album_id,
                "mediaId": old_media_id,
                "version": old_delete_version,
                "reason": "media_identity_changed",
            }),
            now,
            None,
        )
        .await?;

        let new_relation = relation_id(&album_id, new_media_id);
        let new_version =
            next_relation_version(transaction, "album_relation", &new_relation).await?;
        let inserted = sqlx::query(
            "INSERT INTO album_media (album_id, media_asset_id, version, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?4) ON CONFLICT(album_id, media_asset_id) DO NOTHING",
        )
        .bind(&album_id)
        .bind(new_media_id)
        .bind(new_version)
        .bind(now)
        .execute(&mut *transaction)
        .await?
        .rows_affected();
        if inserted == 1 {
            remember_relation_version(
                transaction,
                "album_relation",
                &new_relation,
                new_version,
                None,
            )
            .await?;
            append_change(
                transaction,
                revision,
                "album_relation",
                "upsert",
                &new_relation,
                new_version,
                &serde_json::json!({
                    "albumId": album_id,
                    "mediaId": new_media_id,
                    "version": new_version,
                }),
                now,
                None,
            )
            .await?;
        }
        affected_albums.insert(album_id);
    }

    for (tag_id, old_version, tag_owner) in tag_relations {
        sqlx::query("DELETE FROM media_tags WHERE tag_id = ?1 AND media_asset_id = ?2")
            .bind(&tag_id)
            .bind(old_media_id)
            .execute(&mut *transaction)
            .await?;
        let old_relation = relation_id(&tag_id, old_media_id);
        let old_delete_version = old_version + 1;
        remember_relation_version(
            transaction,
            "tag_relation",
            &old_relation,
            old_delete_version,
            Some(now),
        )
        .await?;
        append_change(
            transaction,
            revision,
            "tag_relation",
            "delete",
            &old_relation,
            old_delete_version,
            &serde_json::json!({
                "tagId": tag_id,
                "mediaId": old_media_id,
                "version": old_delete_version,
                "reason": "media_identity_changed",
            }),
            now,
            tag_owner,
        )
        .await?;

        let new_relation = relation_id(&tag_id, new_media_id);
        let new_version = next_relation_version(transaction, "tag_relation", &new_relation).await?;
        let inserted = sqlx::query(
            "INSERT INTO media_tags (tag_id, media_asset_id, version, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?4) ON CONFLICT(tag_id, media_asset_id) DO NOTHING",
        )
        .bind(&tag_id)
        .bind(new_media_id)
        .bind(new_version)
        .bind(now)
        .execute(&mut *transaction)
        .await?
        .rows_affected();
        if inserted == 1 {
            remember_relation_version(
                transaction,
                "tag_relation",
                &new_relation,
                new_version,
                None,
            )
            .await?;
            append_change(
                transaction,
                revision,
                "tag_relation",
                "upsert",
                &new_relation,
                new_version,
                &serde_json::json!({
                    "tagId": tag_id,
                    "mediaId": new_media_id,
                    "version": new_version,
                }),
                now,
                tag_owner,
            )
            .await?;
        }
    }

    let cover_albums = sqlx::query_scalar::<_, String>(
        "SELECT id FROM albums WHERE cover_media_id = ?1 AND deleted_at IS NULL",
    )
    .bind(old_media_id)
    .fetch_all(&mut *transaction)
    .await?;
    for album_id in cover_albums {
        sqlx::query("UPDATE albums SET cover_media_id = ?1 WHERE id = ?2 AND deleted_at IS NULL")
            .bind(new_media_id)
            .bind(&album_id)
            .execute(&mut *transaction)
            .await?;
        affected_albums.insert(album_id);
    }

    for album_id in affected_albums {
        touch_album_tx(transaction, &album_id, now, revision).await?;
    }
    Ok(())
}

async fn load_album_tx(transaction: &mut SqliteConnection, id: &str) -> AppResult<Album> {
    sqlx::query_as::<_, Album>(
        r#"
        SELECT a.id, a.name, a.cover_media_id,
               (SELECT COUNT(*)
                FROM album_media am
                INNER JOIN media_assets ma ON ma.id = am.media_asset_id
                WHERE am.album_id = a.id
                  AND ma.identity_state = 'verified'
                  AND EXISTS (
                      SELECT 1 FROM media_locations ml
                      WHERE ml.media_asset_id = ma.id
                        AND ml.hash_state = 'verified'
                  )) AS photo_count,
               a.version, a.created_at, a.updated_at
        FROM albums a
        WHERE a.id = ?1 AND a.deleted_at IS NULL
        "#,
    )
    .bind(id)
    .fetch_optional(&mut *transaction)
    .await?
    .ok_or_else(|| AppError::NotFound("album not found".to_owned()))
}

async fn touch_album_tx(
    transaction: &mut SqliteConnection,
    album_id: &str,
    now: i64,
    revision: i64,
) -> AppResult<()> {
    let updated = sqlx::query(
        "UPDATE albums SET version = version + 1, updated_at = ?1 WHERE id = ?2 AND deleted_at IS NULL",
    )
    .bind(now)
    .bind(album_id)
    .execute(&mut *transaction)
    .await?
    .rows_affected();
    if updated != 1 {
        return Ok(());
    }
    let album = load_album_tx(transaction, album_id).await?;
    let payload = serde_json::to_value(&album).map_err(|error| AppError::Internal(error.into()))?;
    append_change(
        transaction,
        revision,
        "album",
        "upsert",
        album_id,
        album.version,
        &payload,
        now,
        None,
    )
    .await
}

async fn load_tag(pool: &SqlitePool, id: &str) -> AppResult<Tag> {
    sqlx::query_as::<_, Tag>(
        "SELECT id, name, version, created_at, updated_at FROM tags WHERE id = ?1 AND deleted_at IS NULL",
    )
    .bind(id)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| AppError::NotFound("tag not found".to_owned()))
}

async fn load_tag_tx(
    transaction: &mut SqliteConnection,
    id: &str,
) -> AppResult<(Tag, Option<i64>)> {
    sqlx::query_as::<_, (String, String, i64, i64, i64, Option<i64>)>(
        "SELECT id, name, version, created_at, updated_at, user_id FROM tags WHERE id = ?1 AND deleted_at IS NULL",
    )
    .bind(id)
    .fetch_optional(&mut *transaction)
    .await?
    .map(|row| {
        (
            Tag {
                id: row.0,
                name: row.1,
                version: row.2,
                created_at: row.3,
                updated_at: row.4,
            },
            row.5,
        )
    })
    .ok_or_else(|| AppError::NotFound("tag not found".to_owned()))
}

fn ensure_tag_owner(owner: Option<i64>, user_id: i64) -> AppResult<()> {
    if owner == Some(user_id) {
        Ok(())
    } else {
        Err(AppError::NotFound("tag not found".to_owned()))
    }
}

async fn ensure_tag_owner_tx(
    transaction: &mut SqliteConnection,
    user_id: i64,
    tag_id: &str,
) -> AppResult<()> {
    let owner = sqlx::query_scalar::<_, Option<i64>>(
        "SELECT user_id FROM tags WHERE id = ?1 AND deleted_at IS NULL",
    )
    .bind(tag_id)
    .fetch_optional(&mut *transaction)
    .await?
    .flatten();
    match owner {
        Some(owner) if owner == user_id => Ok(()),
        _ => Err(AppError::NotFound("tag not found".to_owned())),
    }
}

async fn ensure_tag_and_media(
    transaction: &mut SqliteConnection,
    user_id: i64,
    tag_id: &str,
    media_id: &str,
) -> AppResult<()> {
    ensure_tag_owner_tx(transaction, user_id, tag_id).await?;
    ensure_media_for_user(transaction, media_id, user_id).await
}

async fn ensure_media_for_user(
    transaction: &mut SqliteConnection,
    media_id: &str,
    user_id: i64,
) -> AppResult<()> {
    let exists = sqlx::query_scalar::<_, i64>(
        r#"
        SELECT 1
        FROM media_assets a
        WHERE a.id = ?1 AND a.identity_state = 'verified' AND a.owner_user_id = ?2
          AND EXISTS (
              SELECT 1 FROM media_locations l
              WHERE l.media_asset_id = a.id AND l.hash_state = 'verified'
          )
        "#,
    )
    .bind(media_id)
    .bind(user_id)
    .fetch_optional(&mut *transaction)
    .await?
    .is_some();
    if exists {
        Ok(())
    } else {
        Err(AppError::NotFound("media not found".to_owned()))
    }
}

#[allow(clippy::too_many_arguments)]
async fn append_change(
    transaction: &mut SqliteConnection,
    revision: i64,
    entity: &str,
    operation: &str,
    entity_id: &str,
    version: i64,
    payload: &serde_json::Value,
    now: i64,
    owner_user_id: Option<i64>,
) -> AppResult<()> {
    let payload =
        serde_json::to_string(payload).map_err(|error| AppError::Internal(error.into()))?;
    sqlx::query(
        r#"
        INSERT INTO change_log
            (revision, event_id, entity, operation, entity_id, version, payload, created_at, owner_user_id)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
        "#,
    )
    .bind(revision)
    .bind(Uuid::new_v4().to_string())
    .bind(entity)
    .bind(operation)
    .bind(entity_id)
    .bind(version)
    .bind(payload)
    .bind(now)
    .bind(owner_user_id)
    .execute(&mut *transaction)
    .await?;
    Ok(())
}

fn validate_name(value: &str) -> AppResult<&str> {
    let name = value.trim();
    if name.is_empty() || name.chars().count() > 200 {
        return Err(AppError::BadRequest(
            "metadata name must contain 1 to 200 characters".to_owned(),
        ));
    }
    Ok(name)
}

fn ensure_version(current: i64, expected: i64) -> AppResult<()> {
    if current == expected {
        Ok(())
    } else {
        Err(AppError::Conflict(format!(
            "version conflict: current={current}, expected={expected}"
        )))
    }
}

async fn next_relation_version(
    transaction: &mut SqliteConnection,
    entity: &str,
    relation: &str,
) -> AppResult<i64> {
    Ok(sqlx::query_scalar::<_, i64>(
        r#"
        SELECT COALESCE(MAX(version), 0) + 1
        FROM (
            SELECT version
            FROM relation_versions
            WHERE entity = ?1 AND relation_id = ?2
            UNION ALL
            SELECT version
            FROM change_log
            WHERE entity = ?1 AND entity_id = ?2
        )
        "#,
    )
    .bind(entity)
    .bind(relation)
    .fetch_one(&mut *transaction)
    .await?)
}

async fn remember_relation_version(
    transaction: &mut SqliteConnection,
    entity: &str,
    relation: &str,
    version: i64,
    deleted_at: Option<i64>,
) -> AppResult<()> {
    sqlx::query(
        r#"
        INSERT INTO relation_versions (entity, relation_id, version, deleted_at)
        VALUES (?1, ?2, ?3, ?4)
        ON CONFLICT(entity, relation_id) DO UPDATE SET
            version = excluded.version,
            deleted_at = excluded.deleted_at
        "#,
    )
    .bind(entity)
    .bind(relation)
    .bind(version)
    .bind(deleted_at)
    .execute(&mut *transaction)
    .await?;
    Ok(())
}

fn relation_id(left_id: &str, media_id: &str) -> String {
    format!("{left_id}:{media_id}")
}

fn map_unique_name_error(error: sqlx::Error) -> AppError {
    if error
        .to_string()
        .to_ascii_lowercase()
        .contains("unique constraint failed")
    {
        AppError::Conflict("metadata name already exists".to_owned())
    } else {
        AppError::Database(error)
    }
}

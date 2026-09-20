package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.PhotoTagEntity
import com.example.youyou_album.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags_table ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags_table WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Query("SELECT * FROM tags_table WHERE name = :name AND source_type = 'local' LIMIT 1")
    suspend fun getLocalByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Query("DELETE FROM tags_table WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 删除服务器同步生成的标签。早期版本没有写入 source_type，因此兼容清理
     * 以 t + 32 位十六进制摘要命名的历史服务端标签。
     */
    @Query("DELETE FROM tags_table WHERE source_type = 'server' OR (server_namespace IS NOT NULL) OR (length(id) = 33 AND id GLOB 't[0-9a-f]*')")
    suspend fun deleteAllServerTags(): Int

    // Junction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTagToPhoto(relation: PhotoTagEntity)

    @Query("DELETE FROM photo_tags_table WHERE tag_id = :tagId AND photo_id = :photoId")
    suspend fun removeTagFromPhoto(tagId: String, photoId: String)

    @Query("DELETE FROM photo_tags_table WHERE photo_id IN (SELECT id FROM photos_table WHERE source_type = 'server')")
    suspend fun deleteRelationsForServerPhotos(): Int

    @Query("DELETE FROM photo_tags_table WHERE tag_id IN (SELECT id FROM tags_table WHERE source_type = 'server' OR server_namespace IS NOT NULL OR (length(id) = 33 AND id GLOB 't[0-9a-f]*'))")
    suspend fun deleteRelationsForServerTags(): Int

    @Query("SELECT t.* FROM tags_table t INNER JOIN photo_tags_table pt ON t.id = pt.tag_id WHERE pt.photo_id = :photoId")
    suspend fun getTagsForPhoto(photoId: String): List<TagEntity>

    /**
     * 标签详情与时间线同源派生三态，避免同一媒体在不同入口显示成不同同步状态。
     */
    @Query(
        """
        SELECT * FROM (
            SELECT p.*,
                COALESCE(NULLIF(tt.sortKey,''), p.id) AS timelineKey,
                CASE WHEN tt.hash IS NOT NULL THEN tt.at ELSE p.sort_at END AS timelineAt,
                CASE WHEN tt.hash IS NOT NULL THEN tt.source ELSE p.sort_source END AS timelineSource,
                CASE
                WHEN p.source_type = 'server' THEN
                    CASE WHEN EXISTS(
                        SELECT 1 FROM photos_table l
                        WHERE l.content_hash IS NOT NULL
                          AND l.content_hash = p.content_hash
                          AND l.source_type != 'server'
                    ) THEN 'SYNCED' ELSE 'REMOTE_ONLY' END
                ELSE
                    CASE WHEN p.content_hash IS NULL THEN 'LOCAL_ONLY'
                         WHEN EXISTS(
                             SELECT 1 FROM photos_table s
                             INNER JOIN server_media_projection sp ON sp.local_photo_id = s.id
                             WHERE s.content_hash = p.content_hash
                               AND s.source_type = 'server'
                               AND sp.server_namespace = :namespace
                         ) THEN 'SYNCED'
                         ELSE 'LOCAL_ONLY' END
                END AS backupState
            FROM photos_table p
            INNER JOIN photo_tags_table pt ON p.id = pt.photo_id
            LEFT JOIN timeline_times tt ON tt.hash = p.content_hash AND tt.namespace = :namespace
            WHERE pt.tag_id = :tagId
        )
        ORDER BY timelineAt DESC, timelineKey ASC, id ASC
        """,
    )
    fun observePhotosByTag(tagId: String, namespace: String): Flow<List<com.example.youyou_album.data.db.dao.TimelinePhoto>>
}

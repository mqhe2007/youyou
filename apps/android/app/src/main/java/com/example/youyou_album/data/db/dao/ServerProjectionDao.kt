package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity

/** 服务端媒体 id + 它在 `photos_table` 里的已存在行（批量落库时一次取回）。 */
data class ServerMediaPhoto(
    val serverMediaId: String,
    @Embedded val photo: PhotoEntity,
)

@Dao
interface ServerProjectionDao {
    @Query("SELECT * FROM server_media_projection WHERE server_namespace = :namespace AND server_media_id = :mediaId")
    suspend fun getByMediaId(namespace: String, mediaId: String): ServerMediaProjectionEntity?

    @Query("SELECT * FROM server_media_projection WHERE local_photo_id = :localPhotoId")
    suspend fun getByLocalPhotoId(localPhotoId: String): ServerMediaProjectionEntity?

    @Query("SELECT * FROM server_media_projection WHERE local_photo_id = :localPhotoId AND server_namespace = :namespace")
    suspend fun getByLocalPhotoIdInNamespace(localPhotoId: String, namespace: String): ServerMediaProjectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(projection: ServerMediaProjectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(projections: List<ServerMediaProjectionEntity>)

    @Query("DELETE FROM server_media_projection WHERE server_namespace = :namespace AND server_media_id = :mediaId")
    suspend fun deleteByMediaId(namespace: String, mediaId: String)

    @Query("DELETE FROM server_media_projection WHERE server_namespace = :namespace")
    suspend fun deleteByNamespace(namespace: String)

    @Query("DELETE FROM server_media_projection")
    suspend fun deleteAll()

    @Query("SELECT local_photo_id FROM server_media_projection WHERE server_namespace = :namespace")
    suspend fun listLocalPhotoIds(namespace: String): List<String>

    /**
     * 本页媒体的已存在行（bootstrap 落库用）。
     *
     * 逐条 `getByServerMediaId` 时每页 100 条就是 100 次 JOIN 查询，整份清单三万条要跑
     * 三万次——真机实测 bootstrap 每页约 0.8s 主要是这个开销量级。一次 IN 取回即可。
     */
    @Query(
        """
        SELECT sp.server_media_id AS serverMediaId, p.*
        FROM server_media_projection sp
        INNER JOIN photos_table p ON p.id = sp.local_photo_id
        WHERE sp.server_namespace = :namespace AND sp.server_media_id IN (:mediaIds)
        """,
    )
    suspend fun listPhotosByMediaIds(namespace: String, mediaIds: List<String>): List<ServerMediaPhoto>

    @Query("SELECT server_media_id FROM server_media_projection WHERE server_namespace = :namespace")
    suspend fun listServerMediaIds(namespace: String): List<String>

    @Query(
        """
        SELECT local_photo_id FROM server_media_projection
        WHERE server_namespace = :namespace AND server_media_id IN (:mediaIds)
        """
    )
    suspend fun listLocalPhotoIdsIn(namespace: String, mediaIds: List<String>): List<String>

    @Query(
        """
        DELETE FROM server_media_projection
        WHERE server_namespace = :namespace AND server_media_id IN (:mediaIds)
        """
    )
    suspend fun deleteIn(namespace: String, mediaIds: List<String>)

    /** 换绑后清掉旧身份的投影行。 */
    @Query("DELETE FROM server_media_projection WHERE server_namespace != :namespace")
    suspend fun deleteProjectionsOutsideNamespace(namespace: String): Int

    /** 命名空间升级迁移：把旧式命名空间整批改名（保留投影与游标，避免全量重建）。 */
    @Query("UPDATE server_media_projection SET server_namespace = :newNamespace WHERE server_namespace = :oldNamespace")
    suspend fun renameNamespace(oldNamespace: String, newNamespace: String): Int

    /** 当前身份下的远程媒体条数（连接管理状态头展示用），随同步结果自动刷新。 */
    @Query("SELECT COUNT(*) FROM server_media_projection WHERE server_namespace = :namespace")
    fun observeCount(namespace: String): kotlinx.coroutines.flow.Flow<Int>

    @Query(
        """
        SELECT p.server_media_id FROM server_media_projection p
        INNER JOIN photos_table ph ON ph.id = p.local_photo_id
        WHERE ph.content_hash = :contentHash AND ph.source_type = 'server' AND p.server_namespace = :namespace
        LIMIT 1
        """
    )
    suspend fun getServerMediaIdForContentHash(contentHash: String, namespace: String): String?
}

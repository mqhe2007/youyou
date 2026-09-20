package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity

@Dao
interface ServerProjectionDao {
    @Query("SELECT * FROM server_media_projection WHERE server_namespace = :namespace AND server_media_id = :mediaId")
    suspend fun getByMediaId(namespace: String, mediaId: String): ServerMediaProjectionEntity?

    @Query("SELECT * FROM server_media_projection WHERE local_photo_id = :localPhotoId")
    suspend fun getByLocalPhotoId(localPhotoId: String): ServerMediaProjectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(projection: ServerMediaProjectionEntity)

    @Query("DELETE FROM server_media_projection WHERE server_namespace = :namespace AND server_media_id = :mediaId")
    suspend fun deleteByMediaId(namespace: String, mediaId: String)

    @Query("DELETE FROM server_media_projection WHERE server_namespace = :namespace")
    suspend fun deleteByNamespace(namespace: String)

    @Query("DELETE FROM server_media_projection")
    suspend fun deleteAll()

    @Query("SELECT local_photo_id FROM server_media_projection WHERE server_namespace = :namespace")
    suspend fun listLocalPhotoIds(namespace: String): List<String>

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

    /** 当前身份下的远程媒体条数（连接管理状态头展示用），随同步结果自动刷新。 */
    @Query("SELECT COUNT(*) FROM server_media_projection WHERE server_namespace = :namespace")
    fun observeCount(namespace: String): kotlinx.coroutines.flow.Flow<Int>

    @Query(
        """
        SELECT p.server_media_id FROM server_media_projection p
        INNER JOIN photos_table ph ON ph.id = p.local_photo_id
        WHERE ph.content_hash = :contentHash AND ph.source_type = 'server'
        LIMIT 1
        """
    )
    suspend fun getServerMediaIdForContentHash(contentHash: String): String?
}

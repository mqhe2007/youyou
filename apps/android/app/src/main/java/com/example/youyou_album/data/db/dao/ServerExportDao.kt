package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.ServerMediaExportEntity

@Dao
interface ServerExportDao {
    @Query("SELECT * FROM server_media_exports WHERE source_uri = :sourceUri")
    suspend fun getBySourceUri(sourceUri: String): ServerMediaExportEntity?

    @Query("SELECT * FROM server_media_exports WHERE server_namespace = :namespace AND server_media_id = :mediaId")
    suspend fun getByMediaId(namespace: String, mediaId: String): List<ServerMediaExportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(export: ServerMediaExportEntity)

    @Query("DELETE FROM server_media_exports WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM server_media_exports WHERE server_namespace = :namespace")
    suspend fun deleteByNamespace(namespace: String)

    @Query("DELETE FROM server_media_exports")
    suspend fun deleteAll()
}

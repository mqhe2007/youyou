package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.PendingDownloadEntity

@Dao
interface PendingDownloadDao {
    @Query("SELECT * FROM pending_downloads WHERE server_media_id = :serverMediaId")
    suspend fun get(serverMediaId: String): PendingDownloadEntity?

    @Query("SELECT * FROM pending_downloads")
    suspend fun listAll(): List<PendingDownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PendingDownloadEntity)

    @Query("DELETE FROM pending_downloads WHERE server_media_id = :serverMediaId")
    suspend fun delete(serverMediaId: String): Int

    @Query("DELETE FROM pending_downloads")
    suspend fun deleteAll(): Int
}

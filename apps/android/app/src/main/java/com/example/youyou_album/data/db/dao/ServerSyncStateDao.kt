package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.ServerSyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerSyncStateDao {
    @Query("SELECT * FROM server_sync_state WHERE id = 1")
    suspend fun get(): ServerSyncStateEntity?

    @Query("SELECT * FROM server_sync_state WHERE id = 1")
    fun observe(): Flow<ServerSyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ServerSyncStateEntity)

    @Query("DELETE FROM server_sync_state WHERE id = 1")
    suspend fun clear()
}

package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.youyou_album.data.db.entity.PendingLocalDeletionEntity
import com.example.youyou_album.data.db.entity.PendingMediaOperationEntity

@Dao
interface MediaOperationDao {

    // ---- 远程删除操作结果上下文 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOperation(operation: PendingMediaOperationEntity)

    @Query("SELECT * FROM pending_media_operations WHERE operation_id = :operationId")
    suspend fun getOperation(operationId: String): PendingMediaOperationEntity?

    /** 未确认结果的删除操作（`pending` / `unknown`），重连后只查询、不重放。 */
    @Query("SELECT * FROM pending_media_operations WHERE state IN ('pending', 'unknown') ORDER BY created_at ASC")
    suspend fun listUnresolved(): List<PendingMediaOperationEntity>

    @Query("UPDATE pending_media_operations SET state = :state, message = :message, updated_at = :updatedAt WHERE operation_id = :operationId")
    suspend fun updateOperationState(operationId: String, state: String, message: String?, updatedAt: Long)

    @Query("DELETE FROM pending_media_operations WHERE operation_id = :operationId")
    suspend fun deleteOperation(operationId: String)

    // ---- 本机异步删除批次 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingLocal(items: List<PendingLocalDeletionEntity>)

    @Query("SELECT * FROM pending_local_deletions WHERE batch_id = :batchId ORDER BY photo_id ASC")
    suspend fun listPendingLocalByBatch(batchId: String): List<PendingLocalDeletionEntity>

    @Query("SELECT * FROM pending_local_deletions ORDER BY batch_id ASC, photo_id ASC")
    suspend fun listAllPendingLocal(): List<PendingLocalDeletionEntity>

    @Query("DELETE FROM pending_local_deletions WHERE photo_id IN (:photoIds)")
    suspend fun clearPendingLocal(photoIds: List<String>)

    @Query("DELETE FROM pending_local_deletions")
    suspend fun clearAllPendingLocal()
}

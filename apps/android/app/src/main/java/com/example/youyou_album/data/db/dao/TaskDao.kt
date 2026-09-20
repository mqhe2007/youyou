package com.example.youyou_album.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.youyou_album.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks_table ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks_table WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks_table WHERE status IN ('pending', 'running') ORDER BY updated_at DESC")
    fun observeActive(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks_table SET status = :status, message = :message, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, message: String?, updatedAt: Long)

    @Query("UPDATE tasks_table SET current = :current, total = :total, indeterminate = :indeterminate, phase = :phase, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateProgress(
        id: String,
        current: Int,
        total: Int?,
        indeterminate: Boolean,
        phase: String?,
        updatedAt: Long
    )

    @Query("DELETE FROM tasks_table WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks_table WHERE status = 'completed' AND finished_at < :before")
    suspend fun cleanupCompleted(before: Long): Int
}

package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks_table")
data class TaskEntity(
    @PrimaryKey
    val id: String,

    val kind: String,
    val title: String,
    val status: String,

    val message: String? = null,
    val error: String? = null,

    @ColumnInfo(name = "current", defaultValue = "0")
    val current: Int = 0,

    val total: Int? = null,

    @ColumnInfo(name = "indeterminate", defaultValue = "1")
    val indeterminate: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,

    val payload: String? = null,
    val phase: String? = null,
    val checkpoint: String? = null,

    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_heartbeat_at")
    val lastHeartbeatAt: Long? = null,
)

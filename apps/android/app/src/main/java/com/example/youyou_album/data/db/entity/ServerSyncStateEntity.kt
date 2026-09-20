package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_sync_state")
data class ServerSyncStateEntity(
    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "server_namespace")
    val serverNamespace: String,

    @ColumnInfo(name = "changes_cursor")
    val changesCursor: String,

    @ColumnInfo(name = "snapshot_revision")
    val snapshotRevision: Int,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

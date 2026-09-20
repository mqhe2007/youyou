package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "server_media_exports",
    indices = [Index("server_namespace", "server_media_id")]
)
data class ServerMediaExportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "server_namespace")
    val serverNamespace: String,

    @ColumnInfo(name = "server_media_id")
    val serverMediaId: String,

    @ColumnInfo(name = "source_uri")
    val sourceUri: String,

    @ColumnInfo(name = "relative_path", defaultValue = "")
    val relativePath: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "server_media_projection",
    primaryKeys = ["server_namespace", "server_media_id"],
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["local_photo_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("local_photo_id", unique = true)]
)
data class ServerMediaProjectionEntity(
    @ColumnInfo(name = "server_namespace")
    val serverNamespace: String,

    @ColumnInfo(name = "server_media_id")
    val serverMediaId: String,

    @ColumnInfo(name = "local_photo_id")
    val localPhotoId: String,

    @ColumnInfo(name = "server_version")
    val serverVersion: Int,

    @ColumnInfo(name = "last_applied_revision")
    val lastAppliedRevision: Int? = null,

    @ColumnInfo(name = "server_storage_id")
    val serverStorageId: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos_table",
    indices = [androidx.room.Index("content_hash"), androidx.room.Index("sort_at"), androidx.room.Index("source_uri")],
)
data class PhotoEntity(
    @PrimaryKey
    val id: String,

    val name: String,
    val path: String,

    @ColumnInfo(name = "source_type", defaultValue = "local")
    val sourceType: String = "local",

    @ColumnInfo(name = "storage_id")
    val storageId: String? = null,

    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,

    @ColumnInfo(name = "is_video")
    val isVideo: Boolean = false,

    val duration: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long? = null,

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long? = null,

    @ColumnInfo(name = "sort_at")
    val sortAt: Long? = null,
    @ColumnInfo(name = "taken_at") val takenAt: Long? = null,
    @ColumnInfo(name = "sort_source", defaultValue = "unknown") val sortSource: String = "unknown",
    @ColumnInfo(name = "time_version", defaultValue = "0") val timeVersion: Int = 0,
    @ColumnInfo(name = "original_name") val originalName: String? = null,

    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "exif_data")
    val exifData: String? = null,

    @ColumnInfo(name = "source_uri")
    val sourceUri: String? = null,

    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null,

    @ColumnInfo(name = "synced_storage_id")
    val syncedStorageId: String? = null,

    @ColumnInfo(name = "synced_remote_path")
    val syncedRemotePath: String? = null,

    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false,
)

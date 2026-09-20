package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "photo_tags_table",
    primaryKeys = ["tag_id", "photo_id"]
)
data class PhotoTagEntity(
    @ColumnInfo(name = "tag_id")
    val tagId: String,

    @ColumnInfo(name = "photo_id")
    val photoId: String,

    @ColumnInfo(name = "server_namespace")
    val serverNamespace: String? = null,
)

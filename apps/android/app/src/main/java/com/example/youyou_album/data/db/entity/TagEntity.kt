package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tags_table")
data class TagEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "source_type", defaultValue = "local")
    val sourceType: String = "local",

    @ColumnInfo(name = "server_namespace")
    val serverNamespace: String? = null,
)

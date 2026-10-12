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

    /** 服务端标签 id；本机标签为 null。跨端删除/打标用它定位远端对象。 */
    @ColumnInfo(name = "server_remote_id")
    val serverRemoteId: String? = null,

    /** 服务端标签版本；0 表示本机标签或尚未同步过版本的旧行。 */
    @ColumnInfo(name = "server_version", defaultValue = "0")
    val serverVersion: Long = 0,
)

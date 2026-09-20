package com.example.youyou_album.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 已发出远程删除操作的结果查询上下文（需求 UoN5J--JHK_R §2/§5）。
 *
 * 删除意图不能只挂任务中心（用户可清理条目导致意图丢失），因此独立持久化：
 * 仅保存本次操作 ID、目标身份、media id、预期版本与结果状态，重连后只查询
 * 结果、不自动重放 DELETE。结果凭据不随回收站清空而删除。
 */
@Entity(
    tableName = "pending_media_operations",
    indices = [Index("server_namespace"), Index("state")],
)
data class PendingMediaOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,

    val kind: String,

    @ColumnInfo(name = "server_namespace")
    val serverNamespace: String,

    @ColumnInfo(name = "server_media_id")
    val serverMediaId: String,

    @ColumnInfo(name = "local_photo_id")
    val localPhotoId: String?,

    @ColumnInfo(name = "expected_version")
    val expectedVersion: Int? = null,

    /** `pending` 待查询 / `unknown` 结果待确认 / `succeeded` / `failed` / `conflict` */
    val state: String,

    val message: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

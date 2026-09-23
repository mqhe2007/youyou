package com.example.youyou_album.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerHealthDto(
    val status: String = "unknown",
    val service: String = "unknown",
)

@Serializable
data class SetupStatusDto(
    val initialized: Boolean = false,
)

@Serializable
data class DeviceCredentialsDto(
    val deviceId: String = "",
    val token: String = "",
)

@Serializable
data class ServerInfoDto(
    val serverVersion: String = "",
    val apiVersion: String = "",
    val minClientVersion: String = "",
    val serverInstanceId: String = "",
    val userId: String? = null,
    val capabilities: CapabilitiesDto? = null,
    val storage: StorageInfoDto? = null,
) {
    @Serializable
    data class CapabilitiesDto(
        val storageDriver: String = "",
    )

    @Serializable
    data class StorageInfoDto(
        val readOnly: Boolean = true,
        val writable: Boolean = false,
    )
}

@Serializable
data class MediaPageDto(
    val items: List<ServerMediaDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class MediaFolderPageDto(
    val items: List<MediaFolderDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class MediaFolderDto(
    val path: String = "",
    val mediaCount: Int = 0,
)

@Serializable
data class ServerMediaDto(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val storageId: String = "",
    val size: Long = 0,
    val mimeType: String? = null,
    val isVideo: Boolean = false,
    val durationMs: Int? = null,
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val contentHash: String? = null,
    val identityState: String = "pending",
    val hashState: String = "pending",
    val takenAt: Long? = null,
    val sortAt: Long? = null,
    val sortSource: String? = null,
    val timeVersion: Int = 0,
    val originalName: String? = null,
    val version: Int = 1,
    // 旧服务端快照无此字段；为 null 时保留本地收藏状态
    val isFavorite: Boolean? = null,
    // 旧服务端快照无此字段；为 null 表示普通媒体
    val livePhoto: LivePhotoDto? = null,
)

/**
 * 实况照片标记（服务端投影为权威）。
 *
 * `role` = "still" 表示该静态帧有动态部分，`partnerMediaId` 为空表示动态部分尚未入库；
 * `role` = "motion" 表示本行只是动态部分，不作为独立媒体项展示。
 */
@Serializable
data class LivePhotoDto(
    val role: String = "",
    val embedded: Boolean = false,
    val groupKey: String? = null,
    val partnerMediaId: String? = null,
    val partnerContentHash: String? = null,
    val motionDurationMs: Int? = null,
)

@Serializable
data class FavoriteUpdateRequestDto(
    val isFavorite: Boolean,
)

/** 设备侧删除请求：操作 ID 幂等，预期版本做乐观并发。 */
@Serializable
data class MediaDeleteRequestDto(
    val operationId: String,
    val expectedVersion: Int? = null,
)

@Serializable
data class MediaDeleteResponseDto(
    val mediaId: String = "",
    /** `succeeded` / `failed` / `conflict` / `not_found` */
    val state: String = "",
    val trashed: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val message: String? = null,
    val currentVersion: Int? = null,
)

@Serializable
data class MediaOperationDto(
    val mediaId: String = "",
    val kind: String = "",
    val state: String = "",
    val result: kotlinx.serialization.json.JsonObject? = null,
    val error: String? = null,
    val createdAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class TagPageDto(
    val items: List<ServerTagDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class ServerTagDto(
    val id: String = "",
    val name: String = "",
    val version: Int = 1,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class ServerRelationDto(
    val albumId: String? = null,
    val tagId: String? = null,
    val mediaId: String = "",
    val version: Int = 1,
)

@Serializable
data class BootstrapStartDto(
    val snapshotId: String = "",
    val jobId: String = "",
    val state: String = "preparing",
)

@Serializable
data class BootstrapStatusDto(
    val serverVersion: String = "",
    val snapshotId: String = "",
    val jobId: String = "",
    val state: String = "preparing",
    val snapshotRevision: Int? = null,
    val expiresAt: Long = 0,
    val changesCursor: String? = null,
)

@Serializable
data class SnapshotPageDto(
    val items: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class ChangesPageDto(
    val items: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/**
 * bootstrap 快照分页条目的类型化信封，对齐服务端 OpenAPI 的 SnapshotEntity：
 * { entityId, entityVersion, sortAt, data }。data 为实际业务负载（媒体/标签/关联）。
 */
@Serializable
data class SnapshotEnvelopeDto<T>(
    val entityId: String = "",
    val entityVersion: Int = 1,
    val sortAt: Long? = null,
    val data: T? = null,
)

/**
 * changes 变更日志条目的类型化信封，对齐服务端 OpenAPI 的 ChangeItem：
 * { eventId, revision, entity, operation, entityId, version, data }。
 */
@Serializable
data class ChangeEnvelopeDto(
    val eventId: String = "",
    val revision: Int = 0,
    val entity: String = "",
    val operation: String = "",
    val entityId: String = "",
    val version: Int = 1,
    val data: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class ServerJobDto(
    val id: String = "",
    val kind: String = "",
    val status: String = "",
    val current: Int = 0,
    val total: Int? = null,
    val retryCount: Int = 0,
    val maxAttempts: Int = 0,
    val cancelRequested: Boolean = false,
)

@Serializable
data class PairRequestDto(
    val code: String,
    val deviceName: String,
)

@Serializable
data class CreateTagRequestDto(
    val name: String,
)

@Serializable
data class UpdateTagRequestDto(
    val name: String,
)

@Serializable
data class ApiErrorDto(
    val code: String? = null,
    val message: String? = null,
    @SerialName("requestId") val requestId: String? = null,
    val retryable: Boolean? = null,
    val details: Map<String, kotlinx.serialization.json.JsonObject>? = null,
)

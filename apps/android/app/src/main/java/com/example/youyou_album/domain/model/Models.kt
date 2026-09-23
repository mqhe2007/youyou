package com.example.youyou_album.domain.model

enum class MediaSyncDisplay {
    SYNCED, REMOTE_ONLY, LOCAL_ONLY;

    val iconName: String get() = when (this) {
        SYNCED -> "cloud_done"
        REMOTE_ONLY -> "cloud"
        LOCAL_ONLY -> "cloud_off"
    }
}

/**
 * 实况照片（iOS Live Photo / Android Motion Photo）标记，服务端投影为权威。
 *
 * [role] = "still" 表示本行是静态帧：[partnerMediaId] 为空表示动态部分尚未入库（半态）；
 * [role] = "motion" 表示本行只是某段实况的动态部分，不作为独立媒体项展示。
 * [embedded] = true 表示动态部分内嵌在同一文件里（单文件动态照片），没有独立的动态文件。
 */
data class LivePhoto(
    val role: String,
    val embedded: Boolean = false,
    val groupKey: String? = null,
    val partnerMediaId: String? = null,
    val partnerContentHash: String? = null,
    val motionDurationMs: Int? = null,
) {
    val isStill: Boolean get() = role == ROLE_STILL
    val isMotionPart: Boolean get() = role == ROLE_MOTION

    companion object {
        const val ROLE_STILL = "still"
        const val ROLE_MOTION = "motion"
    }
}

data class Photo(
    val id: String,
    val name: String,
    val path: String,
    val sourceType: String = "local",
    val storageId: String? = null,
    val thumbnailPath: String? = null,
    val isVideo: Boolean = false,
    val duration: Int? = null,
    val createdAt: Long? = null,
    val modifiedAt: Long? = null,
    val sortAt: Long? = null,
    val takenAt: Long? = null,
    val sortSource: String = "unknown",
    val timeVersion: Int = 0,
    val originalName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val mimeType: String? = null,
    val exifData: String? = null,
    val sourceUri: String? = null,
    val contentHash: String? = null,
    val isFavorite: Boolean = false,
    /**
     * 备份三态（仅本地/仅远程/已同步），由数据层按内容哈希比对派生，
     * 服务端清单为事实来源（PRD FR-3）。
     */
    val syncDisplay: MediaSyncDisplay = MediaSyncDisplay.LOCAL_ONLY,
    val remoteThumbnailUrl: String? = null,
    val remoteContentUrl: String? = null,
    val timelineKey: String? = null,
    /** 实况照片标记；null 表示普通媒体（服务端投影为权威）。 */
    val livePhoto: LivePhoto? = null,
)

data class Tag(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceType: String = "local",
    val serverNamespace: String? = null,
)

data class AppTask(
    val id: String,
    val kind: String,
    val title: String,
    val status: String,
    val message: String? = null,
    val error: String? = null,
    val current: Int = 0,
    val total: Int? = null,
    val indeterminate: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val payload: String? = null,
    val phase: String? = null,
    val checkpoint: String? = null,
    val retryCount: Int = 0,
    val lastHeartbeatAt: Long? = null,
)

data class ServerConnection(
    val baseUrl: String,
    val serverInstanceId: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    /** 配对时从 `/api/v1/server` 读到并落盘，供离线时仍能显示服务端版本。 */
    val serverVersion: String? = null,
)

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

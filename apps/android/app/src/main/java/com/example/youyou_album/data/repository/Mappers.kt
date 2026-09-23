package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.TagEntity
import com.example.youyou_album.data.db.entity.TaskEntity
import com.example.youyou_album.data.db.dao.TimelinePhoto
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.model.LivePhoto
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.Tag

internal fun TimelinePhoto.toDomain() = photo.toDomain().copy(
    sortAt = timelineAt, sortSource = timelineSource ?: photo.sortSource,
    takenAt = if (timelineSource == "capture") timelineAt else photo.takenAt,
    timelineKey = timelineKey,
    syncDisplay = when (backupState) {
        "SYNCED" -> MediaSyncDisplay.SYNCED
        "REMOTE_ONLY" -> MediaSyncDisplay.REMOTE_ONLY
        else -> MediaSyncDisplay.LOCAL_ONLY
    },
)

/**
 * 非时间线列表（详情/收藏）也按同一事实派生状态，不使用 Photo 的默认值。
 *
 * 三态按「实况整体」派生（FR-4）：一段实况的静态帧与动态部分共同构成一个备份单位，
 * 任一部分仅本机 → 「仅本机」；无仅本机部分且任一部分仅远程 → 「仅远程」；
 * 全部部分两端同哈希 → 「已同步」。普通媒体退化为按自身哈希判定，与既有行为一致。
 */
internal fun List<Photo>.withSyncDisplay(): List<Photo> {
    val localHashes = filter { it.sourceType != "server" }.mapNotNull { it.contentHash }.toSet()
    // 远程存在的部分 = 服务端投影行的自身哈希 + 其已入库的动态部分：服务端清单不投影动态
    // 部分，「动态部分已在服务端」只能由**服务端行**的 partnerMediaId 非空推出（FR-4/FR-6）。
    // 本机行的 partnerMediaId 指向本机配对，不代表远程已有。
    val serverRows = filter { it.sourceType == "server" }
    val serverHashes = serverRows.mapNotNull { it.contentHash }.toSet() +
        serverRows.mapNotNull { it.livePhoto?.partnerContentHash }.toSet()
    return map { photo ->
        val parts = photo.backupUnitHashes()
        val anyLocalOnly = parts.any { it in localHashes && it !in serverHashes }
        val anyRemoteOnly = parts.any { it in serverHashes && it !in localHashes }
        photo.copy(
            syncDisplay = when {
                // 哈希未知时退回按行来源判定：不能声称「已同步」。
                parts.isEmpty() -> if (photo.sourceType == "server") {
                    MediaSyncDisplay.REMOTE_ONLY
                } else {
                    MediaSyncDisplay.LOCAL_ONLY
                }
                anyLocalOnly -> MediaSyncDisplay.LOCAL_ONLY
                anyRemoteOnly -> MediaSyncDisplay.REMOTE_ONLY
                else -> MediaSyncDisplay.SYNCED
            },
        )
    }
}

/**
 * 一个备份单位的内容哈希集合：自身 + 配对的动态部分。
 *
 * 单文件动态照片的动态部分内嵌在同一文件里，因此只有自身哈希。
 */
private fun Photo.backupUnitHashes(): List<String> {
    val live = livePhoto ?: return listOfNotNull(contentHash)
    // 单文件动态照片的动态部分内嵌在同一文件里，只有自身哈希。
    if (live.embedded) return listOfNotNull(contentHash)
    return listOfNotNull(contentHash, live.partnerContentHash)
}

internal fun PhotoEntity.toDomain() = Photo(
    id = id, name = name, path = path, sourceType = sourceType,
    storageId = storageId, thumbnailPath = thumbnailPath, isVideo = isVideo,
    duration = duration, createdAt = createdAt, modifiedAt = modifiedAt,
    takenAt = takenAt, sortSource = sortSource, timeVersion = timeVersion, originalName = originalName,
    sortAt = sortAt, width = width, height = height, size = size,
    mimeType = mimeType, exifData = exifData, sourceUri = sourceUri,
    contentHash = contentHash, isFavorite = isFavorite,
    livePhoto = livePhoto(),
)

internal fun Photo.toEntity() = PhotoEntity(
    id = id, name = name, path = path, sourceType = sourceType,
    storageId = storageId, thumbnailPath = thumbnailPath, isVideo = isVideo,
    duration = duration, createdAt = createdAt, modifiedAt = modifiedAt,
    takenAt = takenAt, sortSource = sortSource, timeVersion = timeVersion, originalName = originalName,
    sortAt = sortAt, width = width, height = height, size = size,
    mimeType = mimeType, exifData = exifData, sourceUri = sourceUri,
    contentHash = contentHash, isFavorite = isFavorite,
    liveRole = livePhoto?.role, liveEmbedded = livePhoto?.embedded ?: false,
    liveGroupKey = livePhoto?.groupKey, livePartnerId = livePhoto?.partnerMediaId,
    livePartnerHash = livePhoto?.partnerContentHash,
    liveMotionDurationMs = livePhoto?.motionDurationMs,
)

/** 实况标记列 → 领域对象；`null`/`none` 一律表示普通媒体。 */
private fun PhotoEntity.livePhoto(): LivePhoto? {
    val role = liveRole ?: return null
    if (role != LivePhoto.ROLE_STILL && role != LivePhoto.ROLE_MOTION) return null
    return LivePhoto(
        role = role,
        embedded = liveEmbedded,
        groupKey = liveGroupKey,
        partnerMediaId = livePartnerId,
        partnerContentHash = livePartnerHash,
        motionDurationMs = liveMotionDurationMs,
    )
}

internal fun TagEntity.toDomain() = Tag(
    id = id, name = name, createdAt = createdAt, updatedAt = updatedAt,
    sourceType = sourceType, serverNamespace = serverNamespace,
)

internal fun Tag.toEntity() = TagEntity(
    id = id, name = name, createdAt = createdAt, updatedAt = updatedAt,
    sourceType = sourceType, serverNamespace = serverNamespace,
)

internal fun TaskEntity.toDomain() = AppTask(
    id = id, kind = kind, title = title, status = status, message = message,
    error = error, current = current, total = total, indeterminate = indeterminate,
    createdAt = createdAt, updatedAt = updatedAt, finishedAt = finishedAt,
    payload = payload, phase = phase, checkpoint = checkpoint,
    retryCount = retryCount, lastHeartbeatAt = lastHeartbeatAt,
)

internal fun AppTask.toEntity() = TaskEntity(
    id = id, kind = kind, title = title, status = status, message = message,
    error = error, current = current, total = total, indeterminate = indeterminate,
    createdAt = createdAt, updatedAt = updatedAt, finishedAt = finishedAt,
    payload = payload, phase = phase, checkpoint = checkpoint,
    retryCount = retryCount, lastHeartbeatAt = lastHeartbeatAt,
)

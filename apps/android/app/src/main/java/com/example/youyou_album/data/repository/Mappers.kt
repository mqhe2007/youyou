package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.data.db.entity.TagEntity
import com.example.youyou_album.data.db.entity.TaskEntity
import com.example.youyou_album.data.db.dao.TimelinePhoto
import com.example.youyou_album.domain.model.AppTask
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

/** 非时间线列表（详情/收藏）也按同一内容哈希事实派生状态，不使用 Photo 的默认值。 */
internal fun List<Photo>.withSyncDisplay(): List<Photo> {
    val localHashes = filter { it.sourceType != "server" }.mapNotNull { it.contentHash }.toSet()
    val serverHashes = filter { it.sourceType == "server" }.mapNotNull { it.contentHash }.toSet()
    return map { photo ->
        val hasCounterpart = photo.contentHash != null && photo.contentHash in
            (if (photo.sourceType == "server") localHashes else serverHashes)
        photo.copy(syncDisplay = when {
            hasCounterpart -> MediaSyncDisplay.SYNCED
            photo.sourceType == "server" -> MediaSyncDisplay.REMOTE_ONLY
            else -> MediaSyncDisplay.LOCAL_ONLY
        })
    }
}

internal fun PhotoEntity.toDomain() = Photo(
    id = id, name = name, path = path, sourceType = sourceType,
    storageId = storageId, thumbnailPath = thumbnailPath, isVideo = isVideo,
    duration = duration, createdAt = createdAt, modifiedAt = modifiedAt,
    takenAt = takenAt, sortSource = sortSource, timeVersion = timeVersion, originalName = originalName,
    sortAt = sortAt, width = width, height = height, size = size,
    mimeType = mimeType, exifData = exifData, sourceUri = sourceUri,
    contentHash = contentHash, isFavorite = isFavorite,
)

internal fun Photo.toEntity() = PhotoEntity(
    id = id, name = name, path = path, sourceType = sourceType,
    storageId = storageId, thumbnailPath = thumbnailPath, isVideo = isVideo,
    duration = duration, createdAt = createdAt, modifiedAt = modifiedAt,
    takenAt = takenAt, sortSource = sortSource, timeVersion = timeVersion, originalName = originalName,
    sortAt = sortAt, width = width, height = height, size = size,
    mimeType = mimeType, exifData = exifData, sourceUri = sourceUri,
    contentHash = contentHash, isFavorite = isFavorite,
)

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

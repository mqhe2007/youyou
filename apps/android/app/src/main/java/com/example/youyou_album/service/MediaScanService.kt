package com.example.youyou_album.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import com.example.youyou_album.data.db.dao.PhotoDao
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.provider.MediaStore
import com.example.youyou_album.data.repository.toDomain
import com.example.youyou_album.domain.model.MediaTime
import com.example.youyou_album.domain.model.LivePhoto
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 识别用文件样本上限：小文件整读，大文件只读头部（识别只是辅助，不影响扫描本身）。 */
private const val MAX_PROBE_BYTES = 32 * 1024 * 1024
private const val HEAD_PROBE_BYTES = 2 * 1024 * 1024

@Singleton
class MediaScanService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val photoDao: PhotoDao,
    private val contentHashService: ContentHashService,
    private val thumbnailCacheService: ThumbnailCacheService,
    private val mediaTaskCoordinator: MediaTaskCoordinator,
    private val mediaDeletionService: MediaDeletionService,
) {
    data class ScanResult(
        val totalScanned: Int,
        val newPhotos: Int,
        val updatedPhotos: Int,
        val removedPhotos: Int,
    )

    suspend fun fullScan(): ScanResult = withContext(Dispatchers.IO) {
        mediaDeletionService.reconcilePendingLocalDeletions()
        var newCount = 0
        var updatedCount = 0
        var total = 0
        val seenUris = mutableSetOf<String>()
        // Hold at most one batch of media metadata/thumbnails; never load remote previews during a local scan.
        for (batch in queryMediaStore().chunked(128)) {
            currentCoroutineContext().ensureActive()
            val existingByUri = photoDao.getBySourceUris(batch.mapNotNull { it.sourceUri }).associateBy { it.sourceUri }
            val toUpsert = mutableListOf<Photo>()
            for (item in batch) {
                total++
                seenUris.add(item.sourceUri!!)
                val old = existingByUri[item.sourceUri]?.toDomain()
                val metadataChanged = old != null && (old.modifiedAt != item.modifiedAt || old.size != item.size)
                val checkedHash = if(metadataChanged && old?.contentHash != null) contentHashService.sha256HexForUri(Uri.parse(item.sourceUri)) else old?.contentHash
                val sameContent = old != null && (!metadataChanged || (checkedHash != null && checkedHash == old.contentHash))
                if (old == null || metadataChanged || old.timeVersion < MediaTime.VERSION || !hasUsableThumbnail(old)) {
                    val candidate = MediaTime.resolve(if(sameContent) old!!.originalName ?: item.name else item.name, item.takenAt,
                        item.createdAt.takeUnless { sameContent || item.path.contains("/Pictures/youyou/") },
                        item.modifiedAt.takeUnless { sameContent || item.path.contains("/Pictures/youyou/") })
                    val time = MediaTime.choose(old?.takeIf { sameContent }?.let { MediaTime.Value(it.sortAt,it.sortSource) },candidate)
                    val photo = item.copy(
                        id = old?.id ?: item.id, sortAt=time.at, sortSource=time.source,
                        takenAt=item.takenAt ?: old?.takenAt.takeIf { sameContent },
                        originalName=old?.originalName.takeIf { sameContent } ?: item.name,
                        contentHash=checkedHash, isFavorite=old?.isFavorite ?: false,
                        storageId=old?.storageId,
                    )
                    if (mediaTaskCoordinator.isDeleting(photo.id)) continue
                    val thumb = thumbnailCacheService.getThumbnail(photoId=photo.id,sourceUri=photo.sourceUri,isVideo=photo.isVideo)
                    toUpsert.add(photo.copy(thumbnailPath=thumb?.absolutePath))
                    if(old==null) newCount++ else updatedCount++
                }
            }
            if(toUpsert.isNotEmpty()) photoRepository.upsertAll(toUpsert)
        }
        var removedCount = 0
        var after = ""
        while(true) {
            val batch = photoDao.localScanBatch(after,128)
            if(batch.isEmpty()) break
            // A download can insert into MediaStore after this scan's query snapshot. Confirm
            // absence before pruning its freshly indexed local row.
            val removed=batch.filter {
                it.sourceUri !in seenUris && !mediaTaskCoordinator.isDeleting(it.id) &&
                    !localMediaStillExists(it.sourceUri)
            }.map { it.id }
            if(removed.isNotEmpty()) { photoDao.deleteByIds(removed); removedCount+=removed.size }
            after=batch.last().id
        }

        ContentHashBackfillWorker.enqueue(context)
        applyLocalLivePairing()

        ScanResult(
            totalScanned = total,
            newPhotos = newCount,
            updatedPhotos = updatedCount,
            removedPhotos = removedCount,
        )
    }

    private fun localMediaStillExists(sourceUri: String?): Boolean {
        if (sourceUri == null) return false
        return try {
            context.contentResolver.query(
                Uri.parse(sourceUri), arrayOf(MediaStore.MediaColumns._ID), null, null, null,
            )?.use { it.moveToFirst() } ?: true
        } catch (_: SecurityException) {
            true
        }
    }


    private fun hasUsableThumbnail(photo: Photo): Boolean {
        val path = photo.thumbnailPath
        return !path.isNullOrEmpty() && File(path).exists()
    }

    /**
     * 本机实况收敛（FR-1/FR-8）：扫描结束后统一识别与配对。
     *
     * 单文件动态照片按 XMP 声明识别（并以文件长度兜底不误判）；成对实况按「同目录同基名
     * + 动态部分确有 iOS 实况元数据键」配对（文件名只是辅助线索）。任一侧消失即整体降级
     * 为普通媒体，下一次扫描自动收敛，不产生重复项。
     */
    private suspend fun applyLocalLivePairing() {
        val rows = photoDao.getAll().map { it.toDomain() }.filter { it.sourceType != "server" }
        // 用可变状态承载「补齐的哈希」，否则结尾 upsert 会把刚补好的哈希又覆盖回 null。
        val state = rows.associateBy { it.id }.toMutableMap()

        suspend fun withHash(photo: Photo): Photo {
            if (photo.contentHash != null) return photo
            val uri = photo.sourceUri ?: return photo
            val hash = contentHashService.sha256HexForUri(Uri.parse(uri)) ?: return photo
            val filled = photo.copy(contentHash = hash)
            state[photo.id] = filled
            return filled
        }

        val desired = mutableMapOf<String, LivePhoto?>()
        val images = rows.filter { !it.isVideo && it.sourceUri != null }.map { withHash(it) }
        val videosByStem = rows.filter { it.isVideo && it.sourceUri != null }
            .map { withHash(it) }
            .groupBy { stemKey(it.path, it.name) }

        for (image in images) {
            val sample = readSample(image.sourceUri!!, image.size)
            val embedded = sample != null &&
                LocalLivePhotoDetector.isEmbeddedMotionPhoto(sample, image.size ?: sample.size.toLong())
            val partner = if (embedded) {
                null
            } else {
                videosByStem[stemKey(image.path, image.name)]
                    ?.firstOrNull { candidate ->
                        readSample(candidate.sourceUri!!, candidate.size)
                            ?.let { LocalLivePhotoDetector.isAppleLiveMotionPart(it) } == true
                    }
            }
            val groupKey = "lp:${LocalLivePhotoDetector.stemOf(image.name)}"
            when {
                embedded -> {
                    desired[image.id] = LivePhoto(role = LivePhoto.ROLE_STILL, embedded = true)
                }
                partner != null -> {
                    desired[image.id] = LivePhoto(
                        role = LivePhoto.ROLE_STILL,
                        groupKey = groupKey,
                        partnerMediaId = partner.id,
                        partnerContentHash = partner.contentHash,
                        motionDurationMs = partner.duration,
                    )
                    desired[partner.id] = LivePhoto(
                        role = LivePhoto.ROLE_MOTION,
                        groupKey = groupKey,
                        partnerMediaId = image.id,
                        partnerContentHash = image.contentHash,
                    )
                }
            }
        }

        val original = rows.associateBy { it.id }
        val changed = state.values.mapNotNull { row ->
            val live = desired[row.id] ?: null
            val before = original[row.id] ?: return@mapNotNull null
            if (row.livePhoto == live && row.contentHash == before.contentHash) {
                null
            } else {
                row.copy(livePhoto = live)
            }
        }
        if (changed.isNotEmpty()) photoRepository.upsertAll(changed)
    }

    private fun stemKey(path: String, name: String): String =
        "${path.substringBeforeLast('/', "")}:${LocalLivePhotoDetector.stemOf(name)}"

    /**
     * 读取文件样本做识别：小文件整读（iOS 动态部分的元数据在 `moov` 里，可能在文件尾），
     * 大文件只读前 2 MiB。读不到就按识别不出处理，不影响扫描本身。
     */
    private fun readSample(uri: String, size: Long?): ByteArray? = try {
        val limit = if ((size ?: 0L) in 1..MAX_PROBE_BYTES) MAX_PROBE_BYTES else HEAD_PROBE_BYTES
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (total < limit) {
                val read = input.read(chunk, 0, minOf(chunk.size, limit - total))
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            buffer.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    private fun queryMediaStore(): Sequence<Photo> = sequence {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val name = cursor.getString(nameCol) ?: ""
                val data = cursor.getString(dataCol) ?: ""
                val dateTaken = MediaTime.valid(cursor.getLong(dateTakenCol))
                val dateAdded = cursor.getLong(dateAddedCol) * 1000
                val dateModified = cursor.getLong(dateModifiedCol) * 1000
                val width = cursor.getInt(widthCol).takeIf { it > 0 }
                val height = cursor.getInt(heightCol).takeIf { it > 0 }
                val size = cursor.getLong(sizeCol).takeIf { it > 0 }
                val mimeType = cursor.getString(mimeCol)
                val isVideo = mimeType?.startsWith("video/") == true

                yield(
                    Photo(
                        id = UUID.nameUUIDFromBytes(contentUri.toString().toByteArray()).toString(),
                        name = name,
                        path = data,
                        sourceType = "local",
                        isVideo = isVideo,
                        createdAt = dateAdded,
                        modifiedAt = dateModified,
                        takenAt = dateTaken,
                        sortAt = MediaTime.resolve(name, dateTaken, dateAdded, dateModified).at,
                        sortSource = MediaTime.resolve(name, dateTaken, dateAdded, dateModified).source,
                        timeVersion = MediaTime.VERSION,
                        originalName = name,
                        width = width,
                        height = height,
                        size = size,
                        mimeType = mimeType,
                        sourceUri = contentUri.toString(),
                    )
                )
            }
        }

        // Also query videos
        val videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
        )

        context.contentResolver.query(videoCollection, videoProjection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(videoCollection, id)
                val name = cursor.getString(nameCol) ?: ""
                val data = cursor.getString(dataCol) ?: ""
                val dateTaken = MediaTime.valid(cursor.getLong(dateTakenCol))
                val dateAdded = cursor.getLong(dateAddedCol) * 1000
                val dateModified = cursor.getLong(dateModifiedCol) * 1000
                val width = cursor.getInt(widthCol).takeIf { it > 0 }
                val height = cursor.getInt(heightCol).takeIf { it > 0 }
                val size = cursor.getLong(sizeCol).takeIf { it > 0 }
                val mimeType = cursor.getString(mimeCol)
                val duration = cursor.getInt(durationCol).takeIf { it > 0 }

                yield(
                    Photo(
                        id = UUID.nameUUIDFromBytes(contentUri.toString().toByteArray()).toString(),
                        name = name,
                        path = data,
                        sourceType = "local",
                        isVideo = true,
                        duration = duration,
                        createdAt = dateAdded,
                        modifiedAt = dateModified,
                        takenAt = dateTaken,
                        sortAt = MediaTime.resolve(name, dateTaken, dateAdded, dateModified).at,
                        sortSource = MediaTime.resolve(name, dateTaken, dateAdded, dateModified).source,
                        timeVersion = MediaTime.VERSION,
                        originalName = name,
                        width = width,
                        height = height,
                        size = size,
                        mimeType = mimeType,
                        sourceUri = contentUri.toString(),
                    )
                )
            }
        }


    }

    suspend fun backfillContentHash() = withContext(Dispatchers.IO) {
        var afterId = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = photoDao.getMissingLocalHashes(afterId, 100)
            if (batch.isEmpty()) break
            for (photo in batch) {
                currentCoroutineContext().ensureActive()
                val uri = photo.sourceUri!!
                val hash = contentHashService.sha256HexForUri(Uri.parse(uri))
                currentCoroutineContext().ensureActive()
                if (hash != null) {
                    photoDao.fillHashWithTime(photo.id, uri, photo.modifiedAt, photo.size, hash)
                }
            }
            // Advance even on unreadable files; retry those on the next scan, without spinning.
            afterId = batch.last().id
        }
    }
}

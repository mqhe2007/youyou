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
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
            val removed=batch.filter { it.sourceUri !in seenUris && !mediaTaskCoordinator.isDeleting(it.id) }.map { it.id }
            if(removed.isNotEmpty()) { photoDao.deleteByIds(removed); removedCount+=removed.size }
            after=batch.last().id
        }

        ContentHashBackfillWorker.enqueue(context)

        ScanResult(
            totalScanned = total,
            newPhotos = newCount,
            updatedPhotos = updatedCount,
            removedPhotos = removedCount,
        )
    }


    private fun hasUsableThumbnail(photo: Photo): Boolean {
        val path = photo.thumbnailPath
        return !path.isNullOrEmpty() && File(path).exists()
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

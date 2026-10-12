package com.example.youyou_album.service

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import com.example.youyou_album.data.db.dao.PendingDownloadDao
import com.example.youyou_album.data.db.entity.PendingDownloadEntity
import com.example.youyou_album.domain.model.LivePhoto
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体下载服务：把「仅远程」或已同步媒体落盘到系统相册（Pictures/youyou）。
 *
 * 服务端媒体直接在系统相册的 `IS_PENDING=1` 行上分段写入：
 *  * 失败/进程死亡后 pending 行与 [PendingDownloadEntity] 记录保留；
 *  * 重试按已写入字节发 HTTP Range 请求续传（服务端不支持 Range 时自动从头重写）；
 *  * 完成后校验大小与 SHA-256（续传时前缀先回读计入摘要，校验覆盖整个文件）；
 *  * 剩余空间不足时提前给出可读原因，不启动传输。
 */
@Singleton
class MediaDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiServiceFactory: ApiServiceFactory,
    private val serverConnectionStore: ServerConnectionStore,
    private val photoRepository: PhotoRepository,
    private val pendingDownloadDao: PendingDownloadDao,
    private val mediaTaskCoordinator: MediaTaskCoordinator,
) {

    private companion object {
        const val SAVED_MESSAGE = "已保存到系统相册"
        const val MOTION_MIME_TYPE = "video/quicktime"
        const val DOWNLOAD_BUFFER_BYTES = 256 * 1024
        const val FREE_SPACE_MARGIN_BYTES = 1024L * 1024L
    }

    /** 下载/转存单个媒体到系统相册，返回用户可读的结果消息。 */
    suspend fun downloadToGallery(photo: Photo, baseUrl: String? = null, authorization: String? = null): String {
        if (!mediaTaskCoordinator.tryRegisterActive(photo.id)) return "该媒体正在删除，已取消下载"
        return try {
            val result = downloadInternal(photo, baseUrl, authorization)
            if (result != SAVED_MESSAGE) return result
            // FR-4 整体搬运：远程实况的静态帧与动态部分一起落盘，避免只落下半张实况。
            // 本机静态帧不走这里（动态部分本来就在本机）。
            val live = photo.livePhoto
            if (photo.sourceUri != null || live?.isStill != true || live.embedded) return result
            val motion = downloadMotionPart(photo, live, baseUrl, authorization)
            if (motion == SAVED_MESSAGE) {
                "已保存到系统相册（实况照片：静态帧与动态部分）"
            } else {
                "已保存静态帧；实况动态部分未保存：$motion"
            }
        } finally {
            mediaTaskCoordinator.unregisterActive(photo.id)
        }
    }

    /**
     * 实况动态部分：按静态帧在服务端记录的配对媒体 id 下载为系统相册视频。
     * 落盘后由后台扫描按内容标识重新配对，本机两部分的配对关系随之收敛。
     */
    private suspend fun downloadMotionPart(still: Photo, live: LivePhoto, baseUrl: String?, authorization: String?): String = withContext(Dispatchers.IO) {
        val motionMediaId = live.partnerMediaId ?: return@withContext "服务端未记录配对动态部分"
        val url = baseUrl ?: serverConnectionStore.getConnection()?.baseUrl ?: return@withContext "未连接服务端"
        val name = still.name.substringBeforeLast('.', still.name) + ".MOV"
        try {
            downloadServerMedia(
                photo = still.copy(
                    id = motionMediaId,
                    name = name,
                    mimeType = MOTION_MIME_TYPE,
                    isVideo = true,
                    duration = live.motionDurationMs?.div(1000),
                    contentHash = live.partnerContentHash,
                    size = null,
                    path = "",
                    storageId = null,
                    sourceType = "local",
                    sourceUri = null,
                    thumbnailPath = null,
                    remoteThumbnailUrl = null,
                    remoteContentUrl = null,
                    livePhoto = LivePhoto(
                        role = LivePhoto.ROLE_MOTION,
                        groupKey = live.groupKey,
                        motionDurationMs = live.motionDurationMs,
                    ),
                ),
                serverMediaId = motionMediaId,
                baseUrl = url,
                authorization = authorization,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun downloadInternal(photo: Photo, baseUrl: String?, authorization: String?): String = withContext(Dispatchers.IO) {
        try {
            val localUri = photo.sourceUri
            if (localUri != null) {
                val input = context.contentResolver.openInputStream(Uri.parse(localUri))
                    ?: return@withContext "无法读取照片内容"
                input.use {
                    saveLocalStreamToGallery(
                        inputStream = it,
                        displayName = photo.name,
                        mimeType = photo.mimeType ?: "image/jpeg",
                        isVideo = photo.isVideo,
                        photo = photo,
                    )
                }
            } else {
                // 服务端媒体：投影解析出服务端媒体 ID（本地行走内容哈希找服务端孪生）
                val serverMediaId = photoRepository.resolveServerMediaId(photo)
                    ?: return@withContext "无法读取照片内容"
                val url = baseUrl ?: serverConnectionStore.getConnection()?.baseUrl
                    ?: return@withContext "未连接服务端"
                downloadServerMedia(photo, serverMediaId, url, authorization)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 服务端媒体下载（可断点续传）。
     *
     * 复用上次中断的 pending 行：按已写入字节 Range 续传，服务端不支持 Range 时
     * 截断重写；失败保留现场供下次续传，校验失败则丢弃残件避免反复撞错。
     */
    private suspend fun downloadServerMedia(
        photo: Photo,
        serverMediaId: String,
        baseUrl: String,
        authorization: String?,
    ): String {
        val apiService = apiServiceFactory.create(baseUrl)
        val expectedSize = photo.size?.takeIf { it > 0 }
        val expectedHash = photo.contentHash?.takeIf { it.isNotBlank() }
        val displayName = photo.name
        val mimeType = photo.mimeType ?: if (photo.isVideo) "video/mp4" else "image/jpeg"

        // 1. 复用上次中断的 pending 行；内容标识变化即作废重下。
        var record = pendingDownloadDao.get(serverMediaId)
        if (record != null && !recordMatches(record, photo, expectedSize, expectedHash)) {
            discardPendingDownload(serverMediaId, record.uri)
            record = null
        }

        val pendingUri: Uri = record?.uri?.let { Uri.parse(it) } ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/youyou")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            context.contentResolver.insert(mediaCollection(photo.isVideo), values) ?: return "保存失败"
        }

        return try {
            val pfd = context.contentResolver.openFileDescriptor(pendingUri, "rw")
                ?: error("无法写入系统相册")
            try {
                var existing = pfd.statSize.takeIf { it > 0 } ?: 0L
                if (expectedSize != null && existing > expectedSize) {
                    // 残留内容比预期还大：截断重来。
                    Os.ftruncate(pfd.fileDescriptor, 0)
                    existing = 0
                }
                // 已写完但未收尾（上次在 IS_PENDING=0 之前中断）：只校验收尾。
                if (expectedSize != null && existing >= expectedSize) {
                    val hash = sha256OfPrefix(pendingUri, expectedSize)
                    if (expectedHash != null && hash != expectedHash) {
                        discardPendingDownload(serverMediaId, pendingUri.toString())
                        return "下载内容校验失败，请重试"
                    }
                    publishDownloadedMedia(photo, pendingUri, hash, expectedSize)
                    pendingDownloadDao.delete(serverMediaId)
                    return SAVED_MESSAGE
                }
                // 2. 空间检查：只要求「剩余字节 + 余量」。
                if (expectedSize != null) {
                    val remaining = expectedSize - existing
                    val available = availableBytes()
                    if (available != null && available < remaining + FREE_SPACE_MARGIN_BYTES) {
                        persistResumeRecord(serverMediaId, pendingUri, expectedSize, expectedHash, displayName, mimeType, photo.isVideo)
                        return "存储空间不足：还需 ${formatSize(remaining)}，可用 ${formatSize(available)}；清理空间后重试可续传"
                    }
                }
                // 3. Range 续传；服务端不支持 Range（200）时截断重写。
                val rangeHeader = if (existing > 0) "bytes=$existing-" else null
                var response = apiService.openMediaContent(serverMediaId, range = rangeHeader, authorization = authorization)
                if (response.code() == 416 && rangeHeader != null) {
                    Os.ftruncate(pfd.fileDescriptor, 0)
                    existing = 0
                    response = apiService.openMediaContent(serverMediaId, range = null, authorization = authorization)
                }
                if (!response.isSuccessful) {
                    throw retrofit2.HttpException(response)
                }
                val resume = existing > 0 && response.code() == 206
                if (existing > 0 && !resume) {
                    Os.ftruncate(pfd.fileDescriptor, 0)
                }
                val start = if (resume) existing else 0L
                val body = response.body() ?: error("服务端未返回内容")
                val digest = MessageDigest.getInstance("SHA-256")
                if (start > 0) {
                    // 续传校验覆盖整个文件：前缀先回读计入摘要。
                    hashPrefixInto(digest, pendingUri, start)
                }
                Os.lseek(pfd.fileDescriptor, start, OsConstants.SEEK_SET)
                var total = start
                body.byteStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        var offset = 0
                        while (offset < read) {
                            offset += Os.write(pfd.fileDescriptor, buffer, offset, read - offset)
                        }
                        digest.update(buffer, 0, read)
                        total += read
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSize != null && total != expectedSize) {
                    discardPendingDownload(serverMediaId, pendingUri.toString())
                    return "下载内容不完整（${total}/${expectedSize} 字节），请重试"
                }
                if (expectedHash != null && hash != expectedHash) {
                    discardPendingDownload(serverMediaId, pendingUri.toString())
                    return "下载内容校验失败，请重试"
                }
                publishDownloadedMedia(photo, pendingUri, hash, total)
                pendingDownloadDao.delete(serverMediaId)
                SAVED_MESSAGE
            } finally {
                pfd.close()
            }
        } catch (e: CancellationException) {
            // 取消同样保留现场：下次重试可续传。
            persistResumeRecord(serverMediaId, pendingUri, expectedSize, expectedHash, displayName, mimeType, photo.isVideo)
            throw e
        } catch (e: Exception) {
            // 失败保留现场，下一次重试按已写入字节续传。
            persistResumeRecord(serverMediaId, pendingUri, expectedSize, expectedHash, displayName, mimeType, photo.isVideo)
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 本机文件复制到系统相册（无续传语义，直接流式落盘）。 */
    private suspend fun saveLocalStreamToGallery(
        inputStream: java.io.InputStream,
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
        photo: Photo,
    ): String {
        var insertedUri: Uri? = null
        var indexedId: String? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/youyou")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(mediaCollection(isVideo), values) ?: return "保存失败"
            insertedUri = uri
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            (context.contentResolver.openOutputStream(uri) ?: error("无法写入系统相册")).use { output ->
                written = java.security.DigestInputStream(inputStream, digest).copyTo(output)
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(photo.contentHash == null || hash == photo.contentHash) { "下载内容校验失败" }
            indexedId = localIdFor(uri, isVideo)
            photoRepository.upsert(photo.copy(
                contentHash = hash, size = written,
                id = indexedId,
                sourceType = "local", sourceUri = scanUri(uri, isVideo), path = "", storageId = null,
                thumbnailPath = null, remoteThumbnailUrl = null, remoteContentUrl = null,
                timeVersion = com.example.youyou_album.domain.model.MediaTime.VERSION,
            ))
            finalizePending(uri)
            enqueueGalleryScan()
            SAVED_MESSAGE
        } catch (e: Exception) {
            insertedUri?.let {
                runCatching { context.contentResolver.delete(it, null, null) }
                indexedId?.let { id -> photoRepository.deleteById(id) }
            }
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 下载收尾：入库本机行、清 IS_PENDING、触发相册扫描。 */
    private suspend fun publishDownloadedMedia(photo: Photo, uri: Uri, hash: String, written: Long) {
        val localId = localIdFor(uri, photo.isVideo)
        photoRepository.upsert(photo.copy(
            contentHash = hash, size = written,
            id = localId,
            sourceType = "local", sourceUri = scanUri(uri, photo.isVideo), path = "", storageId = null,
            thumbnailPath = null, remoteThumbnailUrl = null, remoteContentUrl = null,
            timeVersion = com.example.youyou_album.domain.model.MediaTime.VERSION,
        ))
        finalizePending(uri)
        enqueueGalleryScan()
    }

    private fun finalizePending(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun enqueueGalleryScan() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("download_media_scan")
        workManager.enqueueUniqueWork(
            MediaScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MediaScanWorker>().build(),
        )
    }

    private fun mediaCollection(isVideo: Boolean): Uri =
        if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    /**
     * Scanner queries the aggregate external collection, while insert uses external_primary.
     * Normalize the alias before indexing so the following scan finds this inherited row.
     */
    private fun scanUri(insertedUri: Uri, isVideo: Boolean): String =
        ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentUris.parseId(insertedUri),
        ).toString()

    private fun localIdFor(insertedUri: Uri, isVideo: Boolean): String =
        UUID.nameUUIDFromBytes(scanUri(insertedUri, isVideo).toByteArray()).toString()

    private fun recordMatches(
        record: PendingDownloadEntity,
        photo: Photo,
        expectedSize: Long?,
        expectedHash: String?,
    ): Boolean {
        if (record.contentHash != expectedHash) return false
        if (record.expectedSize != (expectedSize ?: 0L)) return false
        if (record.displayName != photo.name) return false
        if (record.isVideo != photo.isVideo) return false
        return true
    }

    private suspend fun persistResumeRecord(
        serverMediaId: String,
        uri: Uri,
        expectedSize: Long?,
        expectedHash: String?,
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
    ) {
        pendingDownloadDao.upsert(
            PendingDownloadEntity(
                serverMediaId = serverMediaId,
                uri = uri.toString(),
                contentHash = expectedHash,
                expectedSize = expectedSize ?: 0L,
                displayName = displayName,
                mimeType = mimeType,
                isVideo = isVideo,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun discardPendingDownload(serverMediaId: String, uri: String) {
        pendingDownloadDao.delete(serverMediaId)
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    private fun sha256OfPrefix(uri: Uri, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        hashPrefixInto(digest, uri, length)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashPrefixInto(digest: MessageDigest, uri: Uri, length: Long) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            var left = length
            val buffer = ByteArray(256 * 1024)
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                digest.update(buffer, 0, read)
                left -= read
            }
            check(left == 0L) { "续传前缀读取不完整" }
        } ?: error("无法读取续传数据")
    }

    private fun availableBytes(): Long? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir ?: return null
        return try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) {
            null
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

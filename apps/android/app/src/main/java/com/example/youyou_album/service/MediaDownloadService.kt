package com.example.youyou_album.service

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.youyou_album.domain.model.LivePhoto
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体下载服务：把「仅远程」或已同步媒体落盘到系统相册（Pictures/youyou）。
 * 本地媒体直接读 ContentResolver；服务端媒体先解析服务端媒体 ID 再流式下载。
 * 供详情页单张保存与批量下载前台服务复用。
 */
@Singleton
class MediaDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiServiceFactory: ApiServiceFactory,
    private val serverConnectionStore: ServerConnectionStore,
    private val photoRepository: PhotoRepository,
    private val mediaTaskCoordinator: MediaTaskCoordinator,
) {

    private companion object {
        const val SAVED_MESSAGE = "已保存到系统相册"
        const val MOTION_MIME_TYPE = "video/quicktime"
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
            val apiService = apiServiceFactory.create(url)
            apiService.openMediaContent(motionMediaId, authorization = authorization).byteStream().use { input ->
                saveInputStreamToGallery(
                    inputStream = input,
                    displayName = name,
                    mimeType = MOTION_MIME_TYPE,
                    isVideo = true,
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
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun downloadInternal(photo: Photo, baseUrl: String?, authorization: String?): String = withContext(Dispatchers.IO) {
        try {
            val input = resolveContent(photo, baseUrl, authorization) ?: return@withContext "无法读取照片内容"
            input.use {
                saveInputStreamToGallery(
                    inputStream = it,
                    displayName = photo.name,
                    mimeType = photo.mimeType ?: "image/jpeg",
                    isVideo = photo.isVideo,
                    photo = photo,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun resolveContent(photo: Photo, baseUrl: String?, authorization: String?): java.io.InputStream? {
        if (photo.sourceUri != null) {
            return context.contentResolver.openInputStream(Uri.parse(photo.sourceUri))
        }
        // 服务端媒体：投影解析出服务端媒体 ID（本地行走内容哈希找服务端孪生）
        val serverMediaId = photoRepository.resolveServerMediaId(photo)
            ?: return null
        val url = baseUrl ?: serverConnectionStore.getConnection()?.baseUrl ?: return null
        val apiService = apiServiceFactory.create(url)
        return apiService.openMediaContent(serverMediaId, authorization = authorization).byteStream()
    }

    private suspend fun saveInputStreamToGallery(
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

            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val uri = context.contentResolver.insert(collection, values) ?: return "保存失败"
            insertedUri = uri
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var written = 0L
            (context.contentResolver.openOutputStream(uri) ?: error("无法写入系统相册")).use { output ->
                written = java.security.DigestInputStream(inputStream, digest).copyTo(output)
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(photo.contentHash == null || hash == photo.contentHash) { "下载内容校验失败" }
            // Scanner queries the aggregate external collection, while insert uses external_primary.
            // Normalize the alias before indexing so the following scan finds this inherited row.
            val scanUri = android.content.ContentUris.withAppendedId(
                if(isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.content.ContentUris.parseId(uri),
            ).toString()
            val localId = java.util.UUID.nameUUIDFromBytes(scanUri.toByteArray()).toString()
            indexedId = localId
            photoRepository.upsert(photo.copy(
                contentHash = hash, size = written,
                id = localId,
                sourceType = "local", sourceUri = scanUri, path = "", storageId = null,
                thumbnailPath = null, remoteThumbnailUrl = null, remoteContentUrl = null,
                timeVersion = com.example.youyou_album.domain.model.MediaTime.VERSION,
            ))
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_media_scan",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MediaScanWorker>().build(),
            )
            SAVED_MESSAGE
        } catch (e: Exception) {
            insertedUri?.let {
                runCatching { context.contentResolver.delete(it, null, null) }
                indexedId?.let { id -> photoRepository.deleteById(id) }
            }
            "保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}

package com.example.youyou_album.service

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailCacheService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cacheDir: File = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    suspend fun getThumbnail(photoId: String, sourceUri: String?, isVideo: Boolean = false, size: Int = 512): File? {
        val cached = File(cacheDir, "$photoId.jpg")
        if (cached.exists()) return cached
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(sourceUri) ?: return@withContext null
                val bitmap = if (isVideo) {
                    extractVideoFrame(uri, size)
                } else {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
                }
                if (bitmap != null) {
                    // 目录可能已被「清除缓存」删除，写入前重建，避免静默失败
                    cached.parentFile?.mkdirs()
                    FileOutputStream(cached).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    cached
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun extractVideoFrame(uri: Uri, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // 提取 1 秒处的帧（单位微秒），避免第一帧是黑帧
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let {
                ThumbnailUtils.extractThumbnail(it, size, size, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)
            }
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    fun getThumbnailPath(photoId: String): String = File(cacheDir, "$photoId.jpg").absolutePath

    fun hasThumbnail(photoId: String): Boolean = File(cacheDir, "$photoId.jpg").exists()

    fun deleteThumbnail(photoId: String) {
        File(cacheDir, "$photoId.jpg").takeIf { it.exists() }?.delete()
    }

    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}

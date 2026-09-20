package com.example.youyou_album.service

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片原图缓存服务：缓存服务端原图到本地，支持 SHA-256 校验。
 */
@Singleton
class OriginalPhotoCacheService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentHashService: ContentHashService,
) {
    private val cacheDir: File = File(context.cacheDir, "originals").apply { mkdirs() }

    /**
     * 获取缓存的原图文件，如果不存在返回 null。
     */
    fun getCachedOriginal(photoId: String): File? {
        val file = File(cacheDir, photoId)
        return if (file.exists()) file else null
    }

    /**
     * 检查原图是否已缓存。
     */
    fun isCached(photoId: String): Boolean {
        return File(cacheDir, photoId).exists()
    }

    /**
     * 缓存本地 Uri 的原图到缓存目录。
     * @return 缓存后的文件，失败返回 null。
     */
    suspend fun cacheFromUri(photoId: String, sourceUri: Uri, expectedHash: String? = null): File? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext null
                val outputFile = File(cacheDir, photoId)
                FileOutputStream(outputFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                // 校验 SHA-256
                if (expectedHash != null) {
                    val actualHash = contentHashService.sha256HexForUri(Uri.fromFile(outputFile))
                    if (actualHash != expectedHash) {
                        outputFile.delete()
                        return@withContext null
                    }
                }
                outputFile
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 缓存字节流到缓存目录。
     * @return 缓存后的文件，失败返回 null。
     */
    suspend fun cacheFromStream(
        photoId: String,
        inputStream: java.io.InputStream,
        expectedHash: String? = null,
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                val outputFile = File(cacheDir, photoId)
                FileOutputStream(outputFile).use { output ->
                    inputStream.copyTo(output)
                }

                // 校验 SHA-256
                if (expectedHash != null) {
                    val actualHash = contentHashService.sha256HexForUri(Uri.fromFile(outputFile))
                    if (actualHash != expectedHash) {
                        outputFile.delete()
                        return@withContext null
                    }
                }
                outputFile
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 删除指定照片的原图缓存。
     */
    fun deleteCachedOriginal(photoId: String) {
        File(cacheDir, photoId).takeIf { it.exists() }?.delete()
    }

    /**
     * 清除所有原图缓存。
     */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 获取缓存总大小（字节）。
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * 获取缓存文件数量。
     */
    fun getCacheCount(): Int {
        return cacheDir.listFiles()?.size ?: 0
    }
}

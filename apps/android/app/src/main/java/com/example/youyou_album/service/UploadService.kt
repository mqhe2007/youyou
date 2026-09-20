package com.example.youyou_album.service

import android.content.Context
import android.net.Uri
import com.example.youyou_album.data.api.ProgressRequestBody
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.UploadResponseDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiServiceFactory: ApiServiceFactory,
    private val serverConnectionStore: ServerConnectionStore,
) {

    data class UploadResult(
        val mediaId: String,
        val path: String,
        val size: Long,
        val sha256: String,
    )

    /**
     * 上传单个媒体文件。
     * 上传过程中同时计算 SHA-256，完成后与服务端返回值校验。
     */
    suspend fun uploadPhoto(
        sourceUri: String,
        fileName: String,
        mimeType: String?,
        size: Long,
        expectedSha256: String,
        takenAt: Long?,
        sortAt: Long? = null,
        sortSource: String = "unknown",
        originalName: String? = null,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): UploadResult = withContext(Dispatchers.IO) {
        val connection = serverConnectionStore.getConnection()
            ?: throw IllegalStateException("未连接服务端")

        val apiService = apiServiceFactory.create(connection.baseUrl)

        val uri = Uri.parse(sourceUri)
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开文件: $sourceUri")

        // 包装 InputStream，上传过程中同时计算 SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val countingInputStream = object : InputStream() {
            override fun read(): Int {
                val b = inputStream.read()
                if (b >= 0) digest.update(b.toByte())
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = inputStream.read(b, off, len)
                if (n > 0) digest.update(b, off, n)
                return n
            }

            override fun close() {
                inputStream.close()
            }
        }

        val requestBody = ProgressRequestBody(
            inputStream = countingInputStream,
            contentLength = size,
            contentType = mimeType?.toMediaTypeOrNull(),
            onProgress = onProgress,
        )

        val response: UploadResponseDto = apiService.uploadMedia(
            expectedSize = size,
            expectedSha256 = expectedSha256,
            fileName = fileName,
            mimeType = mimeType,
            takenAt = takenAt,
            sortAt = sortAt,
            sortSource = sortSource,
            timeVersion = 1,
            originalName = originalName,
            body = requestBody,
        )

        // 校验本地计算的 hash 与预期一致
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha256 != expectedSha256) {
            throw IllegalStateException(
                "上传内容 hash 不一致: 预期 ${expectedSha256.take(16)}…, 实际 ${actualSha256.take(16)}…"
            )
        }

        // 校验服务端返回的 hash 与预期一致
        if (response.sha256 != expectedSha256) {
            throw IllegalStateException(
                "服务端返回 hash 不一致: 预期 ${expectedSha256.take(16)}…, 返回 ${response.sha256.take(16)}…"
            )
        }

        UploadResult(
            mediaId = response.mediaId,
            path = response.path,
            size = response.size,
            sha256 = response.sha256,
        )
    }
}

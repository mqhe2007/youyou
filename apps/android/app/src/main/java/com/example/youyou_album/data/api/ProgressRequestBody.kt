package com.example.youyou_album.data.api

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.sink
import java.io.InputStream

/**
 * 带进度回调的 RequestBody，用于文件上传进度监听。
 * 不造轮子：基于 Okio ForwardingSink 实现。
 */
class ProgressRequestBody(
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val contentType: MediaType?,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    // The InputStream is consumed once. Let the foreground service reopen it for each retry;
    // OkHttp must not replay this body internally after a connection failure or redirect.
    override fun isOneShot(): Boolean = true

    // 返回 -1 使用 chunked streaming mode，避免 Content-Length 不准确导致 Broken pipe
    override fun contentLength(): Long = -1

    override fun writeTo(sink: BufferedSink) {
        val forwardingSink = object : ForwardingSink(sink) {
            var bytesWritten = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, contentLength)
            }
        }
        forwardingSink.buffer().use { bufferedSink ->
            inputStream.use { input ->
                input.copyTo(bufferedSink.outputStream())
            }
            bufferedSink.flush()
        }
    }
}

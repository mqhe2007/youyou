package com.example.youyou_album.service

/**
 * 本机实况识别（FR-1，含 `content://` 读取）。
 *
 * 事实来源与服务端一致：
 *  * 单文件动态照片按 Android Motion Photo 1.0 的 XMP 声明（`Camera:MotionPhoto` +
 *    `Container:Directory` 的 `Item:Length`），并要求文件长度装得下声明的追加视频字节。
 *  * iOS 成对实况的动态部分带 QuickTime 元数据键 `com.apple.quicktime.content.identifier`，
 *    同目录同基名只作辅助线索（与静态帧配对时使用）。
 *
 * 识别失败一律降级为普通媒体，不猜。
 */
object LocalLivePhotoDetector {

    private val XMP_MARKER = "http://ns.adobe.com/xap/1.0/".toByteArray()
    private val XMP_END = "</x:xmpmeta>".toByteArray()
    private val CONTENT_IDENTIFIER_KEY =
        "com.apple.quicktime.content.identifier".toByteArray(Charsets.ISO_8859_1)

    /** 单文件动态照片：XMP 声明了动态部分，且文件长度装得下声明的追加视频字节。 */
    fun isEmbeddedMotionPhoto(bytes: ByteArray, fileSize: Long): Boolean {
        val xmp = extractXmp(bytes) ?: return false
        if (!motionPhotoFlag(xmp)) return false
        val videoLength = containerMotionItemLength(xmp) ?: return false
        return videoLength > 0 && fileSize >= videoLength
    }

    /** 该文件是否是 iOS 实况的动态部分（带共享内容标识元数据键）。 */
    fun isAppleLiveMotionPart(bytes: ByteArray): Boolean =
        indexOf(bytes, CONTENT_IDENTIFIER_KEY) != null

    /** 按同目录同基名配对用的主名（小写）；无扩展名时就是文件名本身。 */
    fun stemOf(name: String): String {
        val stem = name.substringBeforeLast('.', missingDelimiterValue = name)
        return (if (stem.isEmpty()) name else stem).lowercase()
    }

    private fun extractXmp(bytes: ByteArray): String? {
        val start = indexOf(bytes, XMP_MARKER) ?: return null
        val from = start + XMP_MARKER.size + if (bytes.getOrNull(start + XMP_MARKER.size) == 0.toByte()) 1 else 0
        val rest = bytes.copyOfRange(from, bytes.size)
        val end = indexOf(rest, XMP_END) ?: return null
        return String(rest, 0, end + XMP_END.size, Charsets.UTF_8)
    }

    /** `Camera:MotionPhoto` 是否为动态照片标记（属性与元素两种写法都要认）。 */
    private fun motionPhotoFlag(xmp: String): Boolean {
        var cursor = 0
        while (true) {
            val index = xmp.indexOf("MotionPhoto", cursor)
            if (index < 0) return false
            val after = xmp.substring(index + "MotionPhoto".length).trimStart()
            val value = when {
                after.startsWith("=") -> quotedValue(after.drop(1).trimStart())
                after.startsWith(">") -> after.drop(1).substringBefore('<')
                else -> null
            }
            if (value?.trim() == "1") return true
            cursor = index + "MotionPhoto".length
        }
    }

    /** `Container:Directory` 里语义为动态照片（或视频 MIME）的条目长度。 */
    private fun containerMotionItemLength(xmp: String): Long? {
        var cursor = 0
        var best: Long? = null
        while (true) {
            val index = xmp.indexOf("Item:Length", cursor)
            if (index < 0) return best
            val after = xmp.substring(index + "Item:Length".length).trimStart()
            val length = after.removePrefix("=").trimStart().let { quotedValue(it) }?.trim()?.toLongOrNull()
            val elementStart = xmp.lastIndexOf('<', index).let { if (it < 0) index else it }
            val window = xmp.substring(elementStart, minOf(xmp.length, index + 512))
            val mime = attributeValue(window, "Item:Mime")
            val semantic = attributeValue(window, "Item:Semantic")
            val isMotion = semantic == "MotionPhoto" || mime?.startsWith("video/") == true
            if (length != null && length > 0 && isMotion && (best == null || length > best)) {
                best = length
            }
            cursor = index + "Item:Length".length
        }
    }

    private fun attributeValue(window: String, name: String): String? {
        val index = window.indexOf(name)
        if (index < 0) return null
        val after = window.substring(index + name.length).trimStart()
        return quotedValue(after.removePrefix("=").trimStart())
    }

    private fun quotedValue(text: String): String? {
        val quote = text.firstOrNull() ?: return null
        if (quote != '"' && quote != '\'') return null
        val value = text.drop(1)
        val end = value.indexOf(quote)
        return if (end < 0) null else value.substring(0, end)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return null
    }
}

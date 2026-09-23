package com.example.youyou_album.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本机实况识别（FR-1）：单文件动态照片按 XMP 声明 + 文件长度兜底，不误判。 */
class LocalLivePhotoDetectorTest {

    private fun xmp(videoLength: Long, flag: String = "1"): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:Camera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" Camera:MotionPhoto="$flag" Camera:MotionPhotoVersion="1"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Length="100" Item:Semantic="Primary" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Length="$videoLength" Item:Semantic="MotionPhoto" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    private fun jpegWithXmp(packet: String): ByteArray {
        val marker = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
        val payload = marker + packet.toByteArray()
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
        val length = (payload.size + 2).toShort()
        return head + byteArrayOf(((length.toInt() shr 8) and 0xFF).toByte(), (length.toInt() and 0xFF).toByte()) +
            payload + ByteArray(64)
    }

    @Test
    fun `识别单文件动态照片`() {
        val file = jpegWithXmp(xmp(128))
        assertTrue(LocalLivePhotoDetector.isEmbeddedMotionPhoto(file, fileSize = 256))
    }

    @Test
    fun `声明的视频字节超出文件长度时不认`() {
        val file = jpegWithXmp(xmp(1_000_000))
        assertFalse(LocalLivePhotoDetector.isEmbeddedMotionPhoto(file, fileSize = 256))
    }

    @Test
    fun `不是动态照片标记时不认`() {
        val file = jpegWithXmp(xmp(128, flag = "0"))
        assertFalse(LocalLivePhotoDetector.isEmbeddedMotionPhoto(file, fileSize = 256))
        assertFalse(LocalLivePhotoDetector.isEmbeddedMotionPhoto("plain image".toByteArray(), 12))
    }

    @Test
    fun `元素写法的标记也要认`() {
        val packet = xmp(128).replace("Camera:MotionPhoto=\"1\"", "Camera:MotionPhoto=\"1\"")
            .replace(
                """<rdf:Description rdf:about="" xmlns:Camera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1">""",
                """<rdf:Description rdf:about="" xmlns:Camera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"><Camera:MotionPhoto>1</Camera:MotionPhoto>""",
            )
        assertTrue(LocalLivePhotoDetector.isEmbeddedMotionPhoto(jpegWithXmp(packet), fileSize = 256))
    }

    @Test
    fun `识别 iOS 实况动态部分的元数据键`() {
        assertTrue(
            LocalLivePhotoDetector.isAppleLiveMotionPart(
                "header com.apple.quicktime.content.identifier tail".toByteArray(Charsets.ISO_8859_1),
            ),
        )
        assertFalse(LocalLivePhotoDetector.isAppleLiveMotionPart("ordinary video".toByteArray()))
    }

    @Test
    fun `同基名配对用主名取小写`() {
        assertEquals("img_1234", LocalLivePhotoDetector.stemOf("IMG_1234.HEIC"))
        assertEquals("img_1234", LocalLivePhotoDetector.stemOf("IMG_1234.MOV"))
        assertEquals("noext", LocalLivePhotoDetector.stemOf("noext"))
    }
}

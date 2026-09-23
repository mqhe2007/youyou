package com.example.youyou_album.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D4：动态部分来源解析（默认静态封面，显式触发才播放；本机原件优先）。
 */
class LivePhotoPlaybackTest {

    private val photo = Photo(
        id = "p",
        name = "IMG_1",
        path = "IMG_1.HEIC",
        sourceType = "server",
        contentHash = "still-hash",
        remoteContentUrl = "https://nas/api/v1/media/p/content",
        livePhoto = LivePhoto(
            role = LivePhoto.ROLE_STILL,
            partnerMediaId = "motion-1",
            partnerContentHash = "motion-hash",
            motionDurationMs = 3005,
        ),
    )

    @Test
    fun `本机动态原件优先：离线也能播放且不带服务端令牌`() {
        val source = resolveLiveMotionSource(
            photo,
            localUriByHash = { if (it == "motion-hash") "content://media/9" else null },
            remoteContentUrlFor = { error("不应请求远程") },
        )
        assertEquals(LiveMotionSource("content://media/9", requiresAuth = false), source)
    }

    @Test
    fun `无本机动态原件时走远程内容并显式带鉴权`() {
        val source = resolveLiveMotionSource(
            photo,
            localUriByHash = { null },
            remoteContentUrlFor = { id -> "https://nas/api/v1/media/$id/content" },
        )
        assertEquals(
            LiveMotionSource("https://nas/api/v1/media/motion-1/content", requiresAuth = true),
            source,
        )
    }

    @Test
    fun `单文件动态照片直接用本文件（内嵌动态）`() {
        val embedded = photo.copy(
            remoteContentUrl = null,
            sourceUri = "content://media/1",
            livePhoto = LivePhoto(role = LivePhoto.ROLE_STILL, embedded = true),
        )
        val source = resolveLiveMotionSource(
            embedded,
            localUriByHash = { error("内嵌动态没有独立文件") },
            remoteContentUrlFor = { error("内嵌动态没有独立文件") },
        )
        assertEquals(LiveMotionSource("content://media/1", requiresAuth = false), source)
    }

    @Test
    fun `动态部分不可得时不伪造可播放状态`() {
        assertNull(
            resolveLiveMotionSource(
                photo,
                localUriByHash = { null },
                remoteContentUrlFor = { null },
            ),
        )
        assertNull(
            resolveLiveMotionSource(
                photo.copy(livePhoto = null),
                localUriByHash = { error("普通媒体无动态部分") },
                remoteContentUrlFor = { error("普通媒体无动态部分") },
            ),
        )
    }
}

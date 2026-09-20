package com.example.youyou_album.presentation.photo

import com.example.youyou_album.data.api.YouyouApiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅远程视频直连服务端内容接口：必须同时带 Bearer 令牌与客户端版本头。
 * 缺失客户端版本头会让服务端返回 426 upgrade_required，表现为「视频加载失败」。
 */
class VideoPlaybackHeadersTest {
    @Test
    fun remoteOnlyVideoCarriesTokenAndClientVersion() {
        val headers = videoPlaybackHeaders(
            hasLocalSource = false,
            hasRemoteContent = true,
            token = "device-token",
        )
        assertEquals("Bearer device-token", headers["Authorization"])
        assertEquals(YouyouApiService.CLIENT_VERSION, headers["X-Youyou-Client-Version"])
    }

    @Test
    fun remoteOnlyVideoWithoutTokenStillCarriesClientVersion() {
        val headers = videoPlaybackHeaders(
            hasLocalSource = false,
            hasRemoteContent = true,
            token = "   ",
        )
        assertTrue(!headers.containsKey("Authorization"))
        assertEquals(YouyouApiService.CLIENT_VERSION, headers["X-Youyou-Client-Version"])
    }

    @Test
    fun localVideoSendsNoHeaders() {
        val headers = videoPlaybackHeaders(
            hasLocalSource = true,
            hasRemoteContent = true,
            token = "device-token",
        )
        assertTrue(headers.isEmpty())
    }

    @Test
    fun videoWithoutAnySourceSendsNoHeaders() {
        val headers = videoPlaybackHeaders(
            hasLocalSource = false,
            hasRemoteContent = false,
            token = "device-token",
        )
        assertTrue(headers.isEmpty())
    }
}

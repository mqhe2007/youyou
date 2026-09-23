package com.example.youyou_album.data.repository

import com.example.youyou_album.domain.model.LivePhoto
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FR-4：三态按「实况整体」派生（静态帧 + 动态部分是一个备份单位）。
 *
 * 对应验收 5（三态徽章对实况正确）与验收 12（半同步态只补传缺失部分）。
 * 语义要点：服务端清单不投影动态部分，「远程已有动态部分」只能由**服务端行**的
 * `partnerMediaId` 非空推出；本机行的 partnerMediaId 指向本机配对，不代表远程已有。
 */
class LivePhotoSyncDisplayTest {

    private fun photo(
        id: String,
        hash: String?,
        sourceType: String,
        livePhoto: LivePhoto? = null,
    ) = Photo(
        id = id,
        name = id,
        path = "$id.jpg",
        sourceType = sourceType,
        contentHash = hash,
        livePhoto = livePhoto,
    )

    /** 一段实况的静态帧；[partnerHash]/[partnerId] 按该行所知的实况状态显式给出。 */
    private fun still(
        id: String,
        hash: String,
        sourceType: String,
        partnerHash: String? = null,
        partnerId: String? = null,
    ) = photo(
        id,
        hash,
        sourceType,
        LivePhoto(
            role = LivePhoto.ROLE_STILL,
            partnerMediaId = partnerId,
            partnerContentHash = partnerHash,
            motionDurationMs = 1500,
        ),
    )

    @Test
    fun `普通媒体行为不变`() {
        val photos = listOf(
            photo("a", "h1", "local"),
            photo("b", "h1", "server"),
            photo("c", "h2", "server"),
            photo("d", null, "local"),
        ).withSyncDisplay()
        assertEquals(MediaSyncDisplay.SYNCED, photos[0].syncDisplay)
        assertEquals(MediaSyncDisplay.SYNCED, photos[1].syncDisplay)
        assertEquals(MediaSyncDisplay.REMOTE_ONLY, photos[2].syncDisplay)
        assertEquals(MediaSyncDisplay.LOCAL_ONLY, photos[3].syncDisplay)
    }

    @Test
    fun `半同步态：动态部分只在本机时判为仅本机`() {
        // 验收 12：本机完整（静态帧 + 动态部分），远程只有静态帧（服务端未配对）。
        val photos = listOf(
            still("local-still", "S1", "local", partnerHash = "M1"),
            photo("local-motion", "M1", "local"),
            still("remote-still", "S1", "server"),
        ).withSyncDisplay()
        assertEquals(
            "动态部分只在本机 → 仅本机，同步动作只补传动态部分",
            MediaSyncDisplay.LOCAL_ONLY,
            photos[0].syncDisplay,
        )
    }

    @Test
    fun `动态部分只在远程时判为仅远程`() {
        // 服务端已配对（partnerMediaId 非空）→ 远程已有动态部分；本机没有。
        val photos = listOf(
            still("local-still", "S1", "local", partnerHash = "M1"),
            still("remote-still", "S1", "server", partnerHash = "M1", partnerId = "motion-M1"),
        ).withSyncDisplay()
        assertEquals(MediaSyncDisplay.REMOTE_ONLY, photos[0].syncDisplay)
        assertEquals(MediaSyncDisplay.REMOTE_ONLY, photos[1].syncDisplay)
    }

    @Test
    fun `两个部分都在两端时判为已同步`() {
        val photos = listOf(
            still("local-still", "S1", "local", partnerHash = "M1"),
            photo("local-motion", "M1", "local"),
            still("remote-still", "S1", "server", partnerHash = "M1", partnerId = "motion-M1"),
        ).withSyncDisplay()
        assertEquals(MediaSyncDisplay.SYNCED, photos[0].syncDisplay)
        assertEquals(MediaSyncDisplay.SYNCED, photos[2].syncDisplay)
    }

    @Test
    fun `单文件动态照片只有一个部分：整体哈希两端一致即已同步`() {
        val embedded = photo(
            "embedded",
            "mv-1",
            "local",
            LivePhoto(role = LivePhoto.ROLE_STILL, embedded = true),
        )
        val twin = photo("twin", "mv-1", "server")
        val photos = listOf(embedded, twin).withSyncDisplay()
        assertEquals(MediaSyncDisplay.SYNCED, photos[0].syncDisplay)
        assertEquals(MediaSyncDisplay.SYNCED, photos[1].syncDisplay)
    }

    @Test
    fun `半态：动态部分尚未入库时只按静态帧判定`() {
        val photos = listOf(still("local-still", "S1", "local")).withSyncDisplay()
        assertEquals(MediaSyncDisplay.LOCAL_ONLY, photos[0].syncDisplay)
    }

    @Test
    fun `本机配对不代表远程已有动态部分`() {
        // 本机行的 partnerMediaId 指向本机配对；若把它算作远程存在，半同步态会被误判成已同步。
        val photos = listOf(
            still("local-still", "S1", "local", partnerHash = "M1", partnerId = "local-motion-id"),
            photo("local-motion", "M1", "local"),
            still("remote-still", "S1", "server"),
        ).withSyncDisplay()
        assertEquals(MediaSyncDisplay.LOCAL_ONLY, photos[0].syncDisplay)
    }
}

package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.entity.PhotoEntity
import com.example.youyou_album.domain.model.LivePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 实况照片标记在「领域对象 ↔ 数据库列」之间往返不丢语义（需求 QI-8Xs1ejZaQ）。
 */
class LivePhotoMappingTest {

    private fun entity(
        role: String? = null,
        embedded: Boolean = false,
        groupKey: String? = null,
        partnerId: String? = null,
        partnerHash: String? = null,
        motionDurationMs: Int? = null,
    ) = PhotoEntity(
        id = "p1",
        name = "IMG_1.jpg",
        path = "library/IMG_1.jpg",
        liveRole = role,
        liveEmbedded = embedded,
        liveGroupKey = groupKey,
        livePartnerId = partnerId,
        livePartnerHash = partnerHash,
        liveMotionDurationMs = motionDurationMs,
    )

    @Test
    fun `普通媒体没有实况标记`() {
        assertNull(entity().toDomain().livePhoto)
        assertNull(entity(role = "none").toDomain().livePhoto)
        assertNull(entity(role = "不认识的角色").toDomain().livePhoto)
    }

    @Test
    fun `静态帧半态保留：动态部分尚未入库`() {
        val photo = entity(
            role = LivePhoto.ROLE_STILL,
            embedded = false,
            groupKey = "lp:group-1",
            motionDurationMs = 1500,
        ).toDomain()
        val live = checkNotNull(photo.livePhoto)
        assertEquals(LivePhoto.ROLE_STILL, live.role)
        assertEquals(true, live.isStill)
        assertNull("半态的动态部分尚未入库", live.partnerMediaId)
        assertNull(live.partnerContentHash)
        assertEquals(1500, live.motionDurationMs)
        assertEquals("lp:group-1", live.groupKey)
    }

    @Test
    fun `单文件动态照片标记内嵌动态部分`() {
        val live = checkNotNull(entity(role = LivePhoto.ROLE_STILL, embedded = true).toDomain().livePhoto)
        assertEquals(true, live.embedded)
    }

    @Test
    fun `成对实况携带动态部分内容哈希供派生三态`() {
        val live = checkNotNull(
            entity(
                role = LivePhoto.ROLE_STILL,
                partnerId = "motion-1",
                partnerHash = "sha256-of-motion",
                motionDurationMs = 3005,
            ).toDomain().livePhoto,
        )
        assertEquals("motion-1", live.partnerMediaId)
        assertEquals("sha256-of-motion", live.partnerContentHash)
    }

    @Test
    fun `动态部分行自己也是实况标记的一部分`() {
        val live = checkNotNull(
            entity(role = LivePhoto.ROLE_MOTION, partnerId = "still-1", partnerHash = "sha256-of-still")
                .toDomain().livePhoto,
        )
        assertEquals(true, live.isMotionPart)
    }

    @Test
    fun `领域对象写回数据库列不丢语义`() {
        val live = LivePhoto(
            role = LivePhoto.ROLE_STILL,
            embedded = true,
            groupKey = "lp:group-2",
            partnerMediaId = "motion-2",
            partnerContentHash = "hash-2",
            motionDurationMs = 1200,
        )
        val entity = entity(
            role = live.role,
            embedded = live.embedded,
            groupKey = live.groupKey,
            partnerId = live.partnerMediaId,
            partnerHash = live.partnerContentHash,
            motionDurationMs = live.motionDurationMs,
        )
        assertEquals(live, entity.toDomain().livePhoto)
    }
}

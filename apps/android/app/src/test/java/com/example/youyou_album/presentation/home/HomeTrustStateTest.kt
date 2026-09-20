package com.example.youyou_album.presentation.home

import com.example.youyou_album.presentation.common.formatLastSyncedAt
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTrustStateTest {
    @Test
    fun formatLastSyncedAt_usesClearRelativeCopy() {
        val now = 10_000_000L

        assertEquals("尚未完成同步", formatLastSyncedAt(null, now))
        assertEquals("刚刚同步", formatLastSyncedAt(now - 30_000, now))
        assertEquals("5 分钟前同步", formatLastSyncedAt(now - 5 * 60_000, now))
        assertEquals("2 小时前同步", formatLastSyncedAt(now - 2 * 3_600_000, now))
    }
}

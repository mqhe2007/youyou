package com.example.youyou_album.presentation.photo

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerTest {
    @Test
    fun formatsPlaybackTimeWithoutNegativeOrOverflowValues() {
        assertEquals("0:00", formatPlaybackTime(-1L))
        assertEquals("0:59", formatPlaybackTime(59_999L))
        assertEquals("1:00", formatPlaybackTime(60_000L))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
        assertEquals("24:00:00", formatPlaybackTime(Long.MAX_VALUE))
    }
}

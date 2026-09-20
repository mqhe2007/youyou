package com.example.youyou_album.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class MediaTimeTest {
    @Test fun filenameSupportsMillisecondsAndCollisionSuffix() {
        assertEquals(1704067200000L, MediaTime.filename("IMG_20240101_000000.jpg"))
        assertEquals(1704067200123L, MediaTime.filename("VID_20240101_000000_123_habc123.mp4"))
        assertEquals(1704067200000L, MediaTime.filename("IMG_20240101_000000_habc.jpg"))
    }
    @Test fun rejectsInvalidCalendarAndUnrecognizedNames() {
        listOf("IMG_20230229_120000.jpg", "IMG_20240101_250000.jpg", "IMG_20240101_0000.jpg", "IMG_nodate_habcdef12.jpg", "x20240101120000", "IMG_20240101_000000_junk.jpg", "IMG_20990101_000000.jpg").forEach { assertNull(it,MediaTime.filename(it)) }
        assertNotNull(MediaTime.filename("IMG_20240229_120000.jpg"))
    }
    @Test fun filenameDoesNotDependOnDeviceTimezone() {
        val old=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val a=MediaTime.filename("IMG_20240101_000000.jpg")
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(a,MediaTime.filename("IMG_20240101_000000.jpg"))
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun captureBeatsFilenameWhichBeatsFilesystem() {
        val capture=1609459200000L
        val now=System.currentTimeMillis()
        assertEquals(MediaTime.Value(capture,"capture"),MediaTime.resolve("IMG_20240101_000000.jpg",capture,now,now))
        assertEquals("filename",MediaTime.resolve("IMG_20240101_000000.jpg",null,now,now).source)
    }
    @Test fun rescansFreezeFallbackButAllowBetterEvidence() {
        val old=MediaTime.Value(1609459200000L,"modified")
        assertEquals(old,MediaTime.resolve("plain.jpg",null,null,System.currentTimeMillis(),old))
        assertEquals("filename",MediaTime.resolve("IMG_20240101_000000.jpg",null,null,System.currentTimeMillis(),old).source)
        val fixed=MediaTime.Value(1704067200000L,"filename")
        assertEquals(fixed,MediaTime.resolve("IMG_20230101_000000.jpg",null,null,null,fixed))
    }
    @Test fun unknownAndSecondsNeverBecomeToday() {
        assertEquals(MediaTime.Value(null,"unknown"),MediaTime.resolve("unknown",null,null,null))
        assertNull(MediaTime.valid(1704067200))
        assertNull(MediaTime.valid(Long.MAX_VALUE))
    }
}

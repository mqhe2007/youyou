package com.example.youyou_album.data.repository

import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSyncDisplayTest {
    private fun photo(id: String, source: String, hash: String?) = Photo(
        id = id, name = id, path = id, sourceType = source, contentHash = hash,
    )

    @Test
    fun detailAndFavoritesReflectBothCopiesAndUnknownHashes() {
        val photos = listOf(
            photo("local-pair", "local", "shared"),
            photo("server-pair", "server", "shared"),
            photo("local-only", "local", "local"),
            photo("remote-only", "server", "remote"),
            photo("local-unknown", "local", null),
            photo("remote-unknown", "server", null),
        ).withSyncDisplay()
        assertEquals(listOf(
            MediaSyncDisplay.SYNCED, MediaSyncDisplay.SYNCED,
            MediaSyncDisplay.LOCAL_ONLY, MediaSyncDisplay.REMOTE_ONLY,
            MediaSyncDisplay.LOCAL_ONLY, MediaSyncDisplay.REMOTE_ONLY,
        ), photos.map { it.syncDisplay })
        // 服务端副本消失后不能保留旧的「已同步」状态。
        assertEquals(MediaSyncDisplay.LOCAL_ONLY,
            photos.filter { it.sourceType != "server" }.withSyncDisplay().first().syncDisplay)
    }
}

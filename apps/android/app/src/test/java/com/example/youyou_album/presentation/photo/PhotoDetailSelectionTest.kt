package com.example.youyou_album.presentation.photo

import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.MediaSyncDisplay
import org.junit.Assert.*
import org.junit.Test

class PhotoDetailSelectionTest {
    private fun photo(id: String, hash: String? = null, source: String = "local") =
        Photo(id = id, name = id, path = id, contentHash = hash, sourceType = source)

    @Test fun opensRequestedPhotoAndFollowsReordering() {
        val a = photo("a")
        val b = photo("b")
        val opened = PhotoDetailUiState().refreshPhotos(listOf(a, b), "b")
        assertEquals(1, opened.currentIndex)
        val refreshed = opened.refreshPhotos(listOf(b, a), "b")
        assertEquals("b", refreshed.photos[refreshed.currentIndex].id)
        assertEquals(0, refreshed.currentIndex)
    }

    @Test fun followsRemoteToSyncedLocalWithDifferentIdAndPosition() {
        val remote = photo("remote", "hash", "server")
        val other = photo("other")
        val before = PhotoDetailUiState(listOf(other, remote), 1, false)
        val local = photo("local", "hash").copy(syncDisplay = MediaSyncDisplay.SYNCED)
        val after = before.refreshPhotos(listOf(local, other), "remote")
        assertEquals(local, after.photos[after.currentIndex])
        assertEquals(MediaSyncDisplay.SYNCED, after.photos[after.currentIndex].syncDisplay)
    }

    @Test fun doesNotPairUnknownHashesAndHandlesDeletedLastPhoto() {
        val remote = photo("remote", source = "server")
        val before = PhotoDetailUiState(listOf(photo("a"), remote), 1, false)
        val after = before.refreshPhotos(listOf(photo("a"), photo("b")), "remote")
        assertEquals(1, after.currentIndex)
        assertEquals(0, after.refreshPhotos(emptyList(), "remote").currentIndex)
    }

    @Test fun sameIdWinsAndReceivesFavoriteAndSyncUpdates() {
        val a = photo("a", "hash")
        val before = PhotoDetailUiState(listOf(a), 0, false)
        val updated = a.copy(isFavorite = true, syncDisplay = MediaSyncDisplay.SYNCED)
        val after = before.refreshPhotos(listOf(photo("duplicate", "hash"), updated), "a")
        assertEquals(updated, after.photos[after.currentIndex])
    }

    /**
     * 时间线两段式发射：首屏 200 条先到，用户在更深处点开的那张常常不在窗口里。
     * 这时不能就地落到第 0 张——否则全量到达后锚点已经变成第一张，永远回不到目标。
     */
    @Test fun keepsRetryingEntryIdUntilFullTimelineArrives() {
        val a = photo("a")
        val b = photo("b")
        val deep = photo("deep")

        val window = PhotoDetailUiState().refreshPhotos(listOf(a, b), "deep")
        assertEquals(0, window.currentIndex)
        assertFalse(window.anchored)

        val full = window.refreshPhotos(listOf(a, b, deep), "deep")
        assertEquals("deep", full.photos[full.currentIndex].id)
        assertTrue(full.anchored)

        // 定位成功后才改为跟随当前选中行（翻页不会被重新拽回入口那张）。
        val flipped = full.copy(currentIndex = 0).refreshPhotos(listOf(deep, a, b), "deep")
        assertEquals("a", flipped.photos[flipped.currentIndex].id)
    }

    /** 没有入口 id（例如从别处直接进入详情页）时不使用锚点重试。 */
    @Test fun blankEntryIdAnchorsImmediately() {
        val state = PhotoDetailUiState().refreshPhotos(listOf(photo("a")), "")
        assertTrue(state.anchored)
        assertEquals(0, state.currentIndex)
    }
}

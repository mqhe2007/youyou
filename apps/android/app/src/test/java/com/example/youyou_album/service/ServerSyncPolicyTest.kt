package com.example.youyou_album.service

import com.example.youyou_album.presentation.common.remoteMediaCountText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSyncPolicyTest {
    @Test
    fun incrementalOnlyAfterCursorIsCommitted() {
        assertFalse(hasIncrementalCursor(null, null, "server_a"))
        assertFalse(hasIncrementalCursor("server_a", "", "server_a"))
        assertFalse(hasIncrementalCursor("server_a", "   ", "server_a"))
        assertFalse(hasIncrementalCursor("server_old", "cursor", "server_a"))
        assertTrue(hasIncrementalCursor("server_a", "cursor", "server_a"))
    }

    @Test
    fun emptyLibraryIsNotShownAsZeroWhileManifestIsStillSyncing() {
        assertEquals("—", remoteMediaCountText(settled = false, syncing = true, count = 0))
        assertEquals("同步中", remoteMediaCountText(settled = true, syncing = true, count = 0))
        assertEquals("12", remoteMediaCountText(settled = true, syncing = true, count = 12))
        assertEquals("0", remoteMediaCountText(settled = true, syncing = false, count = 0))
    }
}

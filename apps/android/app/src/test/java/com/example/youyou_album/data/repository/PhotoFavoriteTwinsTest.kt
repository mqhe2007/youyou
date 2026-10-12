package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.entity.PhotoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 收藏列表的孪生行收敛：同一内容哈希的本机行与服务端投影行只出现一次，
 * 且优先保留时间线里可见的本机行（服务端行在本机有同哈希副本时被时间线隐藏）。
 */
class PhotoFavoriteTwinsTest {
    private fun row(id: String, source: String, hash: String?) = PhotoEntity(
        id = id, name = id, path = id, sourceType = source, contentHash = hash, isFavorite = true,
    )

    @Test
    fun collapsesServerTwinIntoVisibleLocalRowButKeepsOrder() {
        val rows = listOf(
            row("s1", "server", "h1"),
            row("l1", "local", "h1"),
            row("s2", "server", "h2"),
            row("l3", "local", "h3"),
        )
        val collapsed = collapseHashTwins(rows, { it.contentHash }) { it.sourceType == "server" }
        assertEquals(listOf("l1", "s2", "l3"), collapsed.map { it.id })
    }

    @Test
    fun serverOnlyAndUnknownHashRowsSurvive() {
        val rows = listOf(
            row("remote", "server", "h-remote"),
            row("unknown-local", "local", null),
            row("unknown-server", "server", null),
        )
        val collapsed = collapseHashTwins(rows, { it.contentHash }) { it.sourceType == "server" }
        assertEquals(listOf("remote", "unknown-local", "unknown-server"), collapsed.map { it.id })
    }

    @Test
    fun keepsFirstLocalRowWhenMultipleLocalTwinsShareHash() {
        val rows = listOf(
            row("l1", "local", "h1"),
            row("l2", "local", "h1"),
        )
        val collapsed = collapseHashTwins(rows, { it.contentHash }) { it.sourceType == "server" }
        assertEquals(listOf("l1"), collapsed.map { it.id })
    }
}

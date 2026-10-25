package com.example.youyou_album.service

import com.example.youyou_album.domain.model.ServerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferTaskPayloadTest {
    @Test
    fun persistedCheckpointRetriesOnlyIncompleteItems() {
        val payload = TransferTaskPayload(
            identity = "server\ninstance\ndevice",
            items = listOf(
                TransferTaskItem("a", "a.jpg", "content://a", "hash-a"),
                TransferTaskItem("b", "b.jpg", "content://b", "hash-b"),
                TransferTaskItem("c", "c.jpg", "content://c", "hash-c"),
            ),
        ).update("a", "succeeded").update("b", "failed", "网络中断")

        val restored = TransferTaskPayload.read(TransferTaskPayload.write(payload))!!
        assertEquals(setOf("b", "c"), restored.retryIds)
        assertEquals(1, restored.succeeded)
        assertEquals(1, restored.failed)
        assertEquals(1, restored.unfinished)
        assertEquals("网络中断", restored.items.first { it.photoId == "b" }.message)
    }

    @Test
    fun identityRequiresServerAndDevice() {
        val connection = ServerConnection("https://photos.example", serverInstanceId = "instance")
        assertEquals("https://photos.example\ninstance\ndevice", TransferTaskPayload.identity(connection, "device"))
        assertNull(TransferTaskPayload.identity(connection, null))
        assertNull(TransferTaskPayload.identity(connection.copy(serverInstanceId = null), "device"))
    }

    /** 发起时落库的批次全部是 pending：服务端只凭这份 payload 取 id，漏一项就少传一张。 */
    @Test
    fun freshlyQueuedBatchIsEntirelyRetryable() {
        val payload = TransferTaskPayload(
            identity = "server\ninstance\ndevice",
            items = listOf(TransferTaskItem("a", "a.jpg"), TransferTaskItem("b", "b.jpg")),
        )
        assertEquals(setOf("a", "b"), payload.retryIds)
        assertEquals(0, payload.succeeded)
    }
}

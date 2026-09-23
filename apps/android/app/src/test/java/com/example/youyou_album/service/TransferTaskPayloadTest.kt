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
}

package com.example.youyou_album.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTaskCoordinatorTest {

    @Test
    fun deleteGateBlocksNewTasks() {
        val coordinator = MediaTaskCoordinator()
        assertFalse(coordinator.isDeleting("p1"))
        coordinator.beginDelete(listOf("p1", "p2"))
        assertTrue(coordinator.isDeleting("p1"))
        assertTrue(coordinator.isDeleting("p2"))
        coordinator.endDelete(listOf("p1"))
        assertFalse(coordinator.isDeleting("p1"))
        assertTrue(coordinator.isDeleting("p2"))
    }

    @Test
    fun awaitIdleWaitsForActiveTask() = runBlocking {
        val coordinator = MediaTaskCoordinator()
        coordinator.registerActive("p1")
        // 在途计数归零后 awaitIdle 返回 true。
        val waiter = async(Dispatchers.Default) {
            coordinator.awaitIdle(listOf("p1"), timeoutMs = 2_000)
        }
        delay(120)
        assertFalse(waiter.isCompleted)
        coordinator.unregisterActive("p1")
        assertTrue(waiter.await())
    }

    @Test
    fun remoteDeletionMarkDiscardsStaleUpsertButAllowsNewVersion() {
        val coordinator = MediaTaskCoordinator()
        coordinator.markRemoteDeleted("ns", "m1", version = 3)
        assertTrue(coordinator.shouldDiscardRemoteUpsert("ns", "m1", incomingVersion = 3))
        assertTrue(coordinator.shouldDiscardRemoteUpsert("ns", "m1", incomingVersion = 2))
        // 恢复/重建后的更高版本解除标记并放行。
        assertFalse(coordinator.shouldDiscardRemoteUpsert("ns", "m1", incomingVersion = 4))
        // 标记已解除，后续低版本也不再被丢弃（避免长期屏蔽）。
        assertFalse(coordinator.shouldDiscardRemoteUpsert("ns", "m1", incomingVersion = 1))
    }

    @Test
    fun remoteDeletionMarkIsNamespaceScoped() {
        val coordinator = MediaTaskCoordinator()
        coordinator.markRemoteDeleted("ns-a", "m1", version = 1)
        assertFalse(coordinator.shouldDiscardRemoteUpsert("ns-b", "m1", incomingVersion = 1))
    }
}

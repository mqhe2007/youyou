package com.example.youyou_album.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删除与在途任务的协调器（需求 UoN5J--JHK_R §7）。
 *
 * 三件事：
 *  1. **删除闸门**：删除中的媒体 id 进入闸门，上传/下载/扫描不得再为它们启动新任务。
 *  2. **在途任务计数**：上传/下载登记活跃 id，删除前等待其达到可确认状态，
 *     从而把「上传已在远程完成」的真实后果纳入远程删除。
 *  3. **旧响应丢弃**：成功的远程删除登记一个带版本的短时标记；删除前启动的旧快照/
 *     变更响应若携带不高于该版本的 upsert 会被丢弃，避免旧投影覆盖新状态。
 *     不建立长期待删屏蔽表——标记只在内存、带 TTL，且遇到更高版本（恢复/重建）即失效。
 */
@Singleton
class MediaTaskCoordinator @Inject constructor() {

    private val deleting = ConcurrentHashMap.newKeySet<String>()
    private val activeCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** namespace|serverMediaId -> 已删除版本与登记时刻。 */
    private val deletedRemote = ConcurrentHashMap<String, DeletedMark>()

    private data class DeletedMark(val version: Int, val at: Long)

    companion object {
        private const val REMOTE_MARK_TTL_MS = 10 * 60 * 1000L
        private const val IDLE_POLL_MS = 80L
    }

    @Synchronized
    fun beginDelete(ids: Collection<String>) {
        deleting.addAll(ids)
    }

    fun endDelete(ids: Collection<String>) {
        deleting.removeAll(ids.toSet())
    }

    /** 该媒体是否正在删除：上传/下载/扫描据此跳过新任务。 */
    fun isDeleting(photoId: String): Boolean = photoId in deleting

    /** 与删除闸门原子交接，避免检查后才登记的竞态。 */
    @Synchronized
    fun tryRegisterActive(photoId: String): Boolean {
        if (photoId in deleting) return false
        registerActive(photoId)
        return true
    }

    fun registerActive(photoId: String) {
        activeCounts.computeIfAbsent(photoId) { AtomicInteger(0) }.incrementAndGet()
    }

    fun unregisterActive(photoId: String) {
        activeCounts.computeIfPresent(photoId) { _, counter ->
            if (counter.decrementAndGet() <= 0) null else counter
        }
    }

    /** 等待给定媒体的在途任务归零；超时返回 false，调用方必须停止本次删除。 */
    suspend fun awaitIdle(ids: Collection<String>, timeoutMs: Long = 8_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val busy = ids.any { (activeCounts[it]?.get() ?: 0) > 0 }
            if (!busy) return true
            kotlinx.coroutines.delay(IDLE_POLL_MS)
        }
        return ids.none { (activeCounts[it]?.get() ?: 0) > 0 }
    }

    /** 成功远程删除后登记短时标记，用于丢弃删除前启动的旧响应。 */
    fun markRemoteDeleted(namespace: String, serverMediaId: String, version: Int?) {
        deletedRemote[key(namespace, serverMediaId)] = DeletedMark(version ?: 0, System.currentTimeMillis())
    }

    /**
     * 传入的 upsert 是否应被丢弃。更高版本（恢复/重建后的新版本）会解除标记并允许落库。
     */
    fun shouldDiscardRemoteUpsert(namespace: String, serverMediaId: String, incomingVersion: Int): Boolean {
        val key = key(namespace, serverMediaId)
        val mark = deletedRemote[key] ?: return false
        if (System.currentTimeMillis() - mark.at > REMOTE_MARK_TTL_MS) {
            deletedRemote.remove(key)
            return false
        }
        if (incomingVersion > mark.version) {
            deletedRemote.remove(key)
            return false
        }
        return true
    }

    private fun key(namespace: String, serverMediaId: String) = "$namespace|$serverMediaId"
}

package com.example.youyou_album.service

import android.content.Context
import android.content.IntentSender
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import androidx.room.withTransaction
import com.example.youyou_album.data.api.dto.MediaDeleteRequestDto
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.db.dao.MediaOperationDao
import com.example.youyou_album.data.db.dao.PhotoDao
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.entity.PendingLocalDeletionEntity
import com.example.youyou_album.data.db.entity.PendingMediaOperationEntity
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 删除协调器（需求 UoN5J--JHK_R §2/§3/§7）。
 *
 * 删除范围与网络策略：
 *  * 仅本机：删本机原件及索引/缓存，不请求远程。
 *  * 仅远程（在线）：删服务端原件，成功后移除本机投影。
 *  * 已同步（在线）：先取得本机授权并完成本机删除，再执行远程删除；两侧不是原子事务。
 *  * 离线/解绑：有本机原件只删本机；仅远程无本机原件时删除不可用。
 *
 * 系统回收站授权（`createTrashRequest`）必须由 Activity 发起，因此本服务把流程切分为
 * `begin` → `onSystemConfirmation` → `Finished` 的显式步骤。
 */
@Singleton
class MediaDeletionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val photoDao: PhotoDao,
    private val serverProjectionDao: ServerProjectionDao,
    private val mediaOperationDao: MediaOperationDao,
    private val connectionStore: ServerConnectionStore,
    private val apiServiceFactory: ApiServiceFactory,
    private val contentHashService: ContentHashService,
    private val thumbnailCacheService: ThumbnailCacheService,
    private val mediaTaskCoordinator: MediaTaskCoordinator,
    private val remoteDeletionIdentity: RemoteDeletionIdentity,
    private val taskRepository: TaskRepository,
) {
    enum class DeleteScope(val local: Boolean, val remote: Boolean) {
        PHONE(true, false), SERVER(false, true), BOTH(true, true),
    }

    companion object {
        /** targetSdk 37 下单批最多 2000 个 URI，超出必须串行分批。 */
        private const val MAX_URIS_PER_REQUEST = 2000
    }

    internal data class DeleteTarget(
        val photoId: String,
        val sourceUri: String?,
        val serverMediaId: String?,
        val serverVersion: Int?,
    )

    data class DeletePreviewItem(val photoId: String, val hasLocal: Boolean, val hasRemote: Boolean)

    /** Read current local index and current-namespace projection before showing a destructive choice. */
    suspend fun preview(photoIds: List<String>): List<DeletePreviewItem> {
        val connection = connectionStore.getConnection()
        val namespace = connection?.let { "server_" + contentHashService.sha256HexForString(it.baseUrl).substring(0, 16) }
        return photoDao.getByIds(photoIds.distinct()).map { photo ->
            val remote = namespace?.let { ns ->
                serverProjectionDao.getByLocalPhotoIdInNamespace(photo.id, ns)?.serverMediaId
                    ?: photo.contentHash?.let { serverProjectionDao.getServerMediaIdForContentHash(it, ns) }
            }
            DeletePreviewItem(photo.id, photo.sourceType != "server" && photo.sourceUri != null, remote != null)
        }
    }

    /** 一次删除流程的跨回调状态；由调用方在系统回调后原样回传。 */
    data class DeleteSession internal constructor(
        val scope: DeleteScope,
        internal val online: Boolean,
        internal val namespace: String?,
        internal val remoteSession: RemoteDeletionSession? = null,
        internal val targets: List<DeleteTarget>,
        internal val localPending: List<DeleteTarget>,
        internal val localDone: Set<String>,
        /** 当前系统请求之后仍需处理的剩余本机项（单批上限之外）。 */
        internal val localRest: List<DeleteTarget> = emptyList(),
        internal val localFailed: Int = 0,
    )

    sealed interface DeleteStep {
        /** 需要 Activity 通过 `startIntentSenderForResult` 拉起系统确认/回收站请求。 */
        data class NeedsSystemConfirmation(
            val intentSender: IntentSender,
            val session: DeleteSession,
        ) : DeleteStep

        data class Finished(val summary: DeleteSummary) : DeleteStep
    }

    data class DeleteSummary(
        val deletedLocal: Int = 0,
        val remoteTrashed: Int = 0,
        val remoteRetained: Int = 0,
        val remoteFailed: Int = 0,
        val remotePending: Int = 0,
        val remoteUnavailable: Int = 0,
        val notDeleted: Int = 0,
        val localFailed: Int = 0,
    ) {
        fun message(): String {
            val parts = mutableListOf<String>()
            if (deletedLocal > 0) parts += "手机已移入系统回收机制 $deletedLocal 项"
            if (remoteTrashed > 0) parts += "服务器已移入回收站 $remoteTrashed 项"
            if (remoteRetained > 0) parts += "服务器原件保留 $remoteRetained 项"
            if (remoteFailed > 0) parts += "服务器移除失败 $remoteFailed 项"
            if (remotePending > 0) parts += "服务器移除结果待确认 $remotePending 项"
            if (remoteUnavailable > 0) parts += "服务器 $remoteUnavailable 项未移除，请连接后重试"
            if (localFailed > 0) parts += "手机移除失败 $localFailed 项，服务器未移除"
            if (notDeleted > 0) parts += "已取消/未授权 $notDeleted 项，未删除"
            if (parts.isEmpty()) return "没有可删除的项目"
            return parts.joinToString("；")
        }
    }

    private sealed interface Decision {
        data class DeleteLocal(val photoId: String) : Decision
        data class ConvertToRemote(val photoId: String) : Decision
        data class Retain(val photoId: String) : Decision
    }

    /**
     * 开始删除。进程上次遗留的本机异步删除先核对真实状态，绝不把丢失回调当失败重复执行。
     */
    suspend fun begin(photoIds: List<String>, scope: DeleteScope): DeleteStep {
        reconcilePendingLocalDeletions()
        // 阻断新任务，并等待在途上传/下载达到可确认状态后再决定删除目标。
        mediaTaskCoordinator.beginDelete(photoIds)
        if (!mediaTaskCoordinator.awaitIdle(photoIds)) {
            mediaTaskCoordinator.endDelete(photoIds)
            return finish(scope, photoIds.distinct().size, DeleteSummary(localFailed = photoIds.distinct().size))
        }
        val selected = photoDao.getByIds(photoIds)
        // FR-5 实况整体删除：静态帧与动态部分一起删，避免留下「半张实况」。
        // 本机动态部分按配对 id 找到本机行；服务端动态部分按投影行的 live_partner_id 追加远程目标。
        val extraLocalIds = selected.mapNotNull { entity ->
            if (entity.liveRole != "still" || entity.liveEmbedded) return@mapNotNull null
            val partnerId = entity.livePartnerId ?: return@mapNotNull null
            partnerId.takeIf { photoDao.getById(it) != null }
        }
        val entities = photoDao.getByIds((photoIds + extraLocalIds).distinct())
        val connection = connectionStore.getConnection()
        val namespace = connection?.let { "server_" + contentHashService.sha256HexForString(it.baseUrl).substring(0, 16) }
        val targets = entities.map { entity ->
            // 同步态按内容哈希派生，投影可能挂在另一条 server 行上；优先本行投影，回退按哈希解析。
            val direct = namespace?.let { serverProjectionDao.getByLocalPhotoIdInNamespace(entity.id, it) }
            val serverMediaId = direct?.serverMediaId
                ?: entity.contentHash?.let { hash -> namespace?.let { serverProjectionDao.getServerMediaIdForContentHash(hash, it) } }
            val serverVersion = direct?.serverVersion
                ?: serverMediaId?.let { mediaId ->
                    namespace?.let { serverProjectionDao.getByMediaId(it, mediaId)?.serverVersion }
                }
            DeleteTarget(
                photoId = entity.id,
                sourceUri = entity.sourceUri?.takeIf { entity.sourceType != ServerSyncService.PROJECTION_STORAGE_ID },
                serverMediaId = serverMediaId,
                serverVersion = serverVersion,
            )
        }
        // 远端动态部分（静态帧的投影行记录了它的媒体 id 与版本）。
        val extraRemoteTargets = entities.mapNotNull { entity ->
            if (entity.liveRole != "still" || entity.liveEmbedded) return@mapNotNull null
            val hash = entity.contentHash ?: return@mapNotNull null
            val twin = photoDao.getServerRowByHash(hash) ?: return@mapNotNull null
            val motionId = twin.livePartnerId ?: return@mapNotNull null
            if (targets.any { it.serverMediaId == motionId }) return@mapNotNull null
            DeleteTarget(
                photoId = "live-motion:${entity.id}",
                sourceUri = null,
                serverMediaId = motionId,
                serverVersion = namespace?.let { serverProjectionDao.getByMediaId(it, motionId)?.serverVersion },
            )
        }
        val allTargets = targets + extraRemoteTargets
        if (allTargets.isEmpty()) {
            mediaTaskCoordinator.endDelete(photoIds)
            return finish(scope, photoIds.distinct().size, DeleteSummary())
        }
        // 仅本机无需探测服务端；连接不可达时及时按离线策略继续本机授权。
        val remoteSession = if (scope.remote && allTargets.any { it.serverMediaId != null } && connection != null && networkAvailable()) {
            withTimeoutOrNull(3_000L) { remoteDeletionIdentity.capture() }
        } else null
        if (scope.remote && remoteSession == null) {
            mediaTaskCoordinator.endDelete(photoIds)
            return finish(scope, photoIds.distinct().size, DeleteSummary(remoteUnavailable = allTargets.count { it.serverMediaId != null }))
        }
        val session = DeleteSession(
            scope = scope,
            // 离线判定以真实网络可用为准：仍绑定但断网时只删本机、不请求远程。
            online = remoteSession != null,
            remoteSession = remoteSession,
            namespace = namespace,
            targets = allTargets,
            localPending = allTargets.filter { scope.local && it.sourceUri != null },
            localDone = emptySet(),
        )
        return advanceLocal(session)
    }

    /** 系统回收站确认回调。 */
    suspend fun onSystemConfirmation(session: DeleteSession, approved: Boolean): DeleteStep {
        if (!approved) {
            return finalize(session, cancelled = session.localPending.size + session.localRest.size)
        }
        val confirmed = session.localPending
        val deleted = confirmed.filter { localDeletionConfirmed(it.sourceUri!!) == true }
        val next = session.copy(
            localPending = session.localRest,
            localRest = emptyList(),
            localDone = session.localDone + deleted.map { it.photoId },
            localFailed = session.localFailed + confirmed.size - deleted.size,
        )
        return if (next.localPending.isNotEmpty()) advanceLocal(next) else finalize(next)
    }

    /** 推进本机删除：按 targetSdk 37 的单项上限分批交给系统回收站。 */
    private suspend fun advanceLocal(session: DeleteSession): DeleteStep {
        if (session.localPending.isEmpty()) return finalize(session)
        val chunk = session.localPending.take(MAX_URIS_PER_REQUEST)
        val rest = session.localPending.drop(MAX_URIS_PER_REQUEST)
        persistPendingLocal(chunk)
        val uris = chunk.map { Uri.parse(it.sourceUri!!) }
        val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
        return DeleteStep.NeedsSystemConfirmation(
            pendingIntent.intentSender,
            session.copy(localPending = chunk, localRest = rest),
        )
    }

    private suspend fun persistPendingLocal(targets: List<DeleteTarget>) {
        val now = System.currentTimeMillis()
        val batchId = UUID.randomUUID().toString()
        mediaOperationDao.insertPendingLocal(
            targets.map {
                PendingLocalDeletionEntity(
                    photoId = it.photoId,
                    sourceUri = it.sourceUri!!,
                    batchId = batchId,
                    createdAt = now,
                )
            }
        )
    }

    /**
     * 结束流程：逐项处理远程删除，然后在同一 Room 事务里落库本机行与投影行。
     * 已同步照片删除本机行会级联删除投影，避免出现「仅远程」中间态。
     */
    private suspend fun finalize(session: DeleteSession, cancelled: Int = 0): DeleteStep.Finished {
        val remoteSession = session.remoteSession
        val now = System.currentTimeMillis()
        val decisions = mutableListOf<Decision>()
        val remoteDeletedIds = mutableSetOf<String>()
        var deletedLocal = 0
        var remoteTrashed = 0
        var remoteRetained = 0
        var remoteFailed = 0
        var remotePending = 0
        var remoteUnavailable = 0

        for (target in session.targets) {
            val hasLocal = target.sourceUri != null
            val localRequested = session.scope.local && hasLocal
            val localDeleted = !localRequested || target.photoId in session.localDone
            if (localRequested && !localDeleted) {
                decisions += Decision.Retain(target.photoId)
                continue
            }
            if (localRequested) deletedLocal++
            val serverMediaId = target.serverMediaId
            if (serverMediaId == null || !session.scope.remote) {
                decisions += when {
                    !localRequested -> Decision.Retain(target.photoId)
                    serverMediaId != null -> Decision.ConvertToRemote(target.photoId)
                    else -> Decision.DeleteLocal(target.photoId)
                }
                if (localRequested && serverMediaId != null) remoteRetained++
                continue
            }
            if (remoteSession == null || !remoteDeletionIdentity.isCurrent(remoteSession)) {
                if (localRequested) {
                    decisions += Decision.ConvertToRemote(target.photoId)
                    remoteRetained++
                    remoteUnavailable++
                } else {
                    decisions += Decision.Retain(target.photoId)
                    remoteUnavailable++
                }
                continue
            }
            val operationId = UUID.randomUUID().toString()
            // 未知版本不能省略并发保护，保留远程供同步取得版本后显性重试。
            val expectedVersion = target.serverVersion?.takeIf { it > 0 }
            if (expectedVersion == null) {
                decisions += if (localRequested) Decision.ConvertToRemote(target.photoId) else Decision.Retain(target.photoId)
                remoteFailed++
                continue
            }
            mediaOperationDao.upsertOperation(
                PendingMediaOperationEntity(
                    operationId = operationId,
                    kind = "delete",
                    serverNamespace = remoteSession.scope,
                    serverMediaId = serverMediaId,
                    localPhotoId = target.photoId,
                    expectedVersion = expectedVersion,
                    state = "pending",
                    message = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            try {
                val response = remoteSession.api.deleteMedia(
                    serverMediaId,
                    MediaDeleteRequestDto(operationId, expectedVersion),
                    remoteSession.authorization,
                )
                when (response.state) {
                    "succeeded" -> {
                        mediaOperationDao.updateOperationState(operationId, "succeeded", null, now)
                        mediaTaskCoordinator.markRemoteDeleted(session.namespace ?: "", serverMediaId, target.serverVersion)
                        decisions += if (localRequested || !hasLocal) Decision.DeleteLocal(target.photoId) else Decision.Retain(target.photoId)
                        remoteDeletedIds += serverMediaId
                        remoteTrashed++
                    }
                    "not_found" -> {
                        mediaOperationDao.updateOperationState(operationId, "failed", response.message, now)
                        mediaTaskCoordinator.markRemoteDeleted(session.namespace ?: "", serverMediaId, target.serverVersion)
                        decisions += if (localRequested || !hasLocal) Decision.DeleteLocal(target.photoId) else Decision.Retain(target.photoId)
                    }
                    "conflict" -> {
                        mediaOperationDao.updateOperationState(operationId, "conflict", response.message, now)
                        decisions += if (localRequested) Decision.ConvertToRemote(target.photoId) else Decision.Retain(target.photoId)
                        remoteFailed++
                    }
                    else -> {
                        mediaOperationDao.updateOperationState(operationId, "failed", response.message, now)
                        decisions += if (localRequested) Decision.ConvertToRemote(target.photoId) else Decision.Retain(target.photoId)
                        remoteFailed++
                    }
                }
            } catch (error: Exception) {
                // 超时/连接丢失：只标记结果待确认，真实远程状态由正常同步校正，不自动重放。
                mediaOperationDao.updateOperationState(operationId, "unknown", error.message, now)
                decisions += if (localRequested) Decision.ConvertToRemote(target.photoId) else Decision.Retain(target.photoId)
                remotePending++
            }
        }

        database.withTransaction {
            session.namespace?.let { namespace ->
                for (mediaId in remoteDeletedIds) {
                    val projection = serverProjectionDao.getByMediaId(namespace, mediaId)
                    projection?.let {
                        val photo = photoDao.getById(it.localPhotoId)
                        if (photo?.sourceUri == null) photoDao.deleteById(it.localPhotoId)
                    }
                    serverProjectionDao.deleteByMediaId(namespace, mediaId)
                }
            }
            val toDelete = decisions.filterIsInstance<Decision.DeleteLocal>().map { it.photoId }
            if (toDelete.isNotEmpty()) photoDao.deleteByIds(toDelete)
            decisions.filterIsInstance<Decision.ConvertToRemote>().forEach {
                photoDao.convertToServerProjection(it.photoId)
            }
            mediaOperationDao.clearPendingLocal(session.targets.map { it.photoId })
        }

        // 缓存文件清理放在事务之后，可重试；不声称文件删除随 Room 回滚。
        decisions.forEach { decision ->
            val photoId = when (decision) {
                is Decision.DeleteLocal -> decision.photoId
                is Decision.ConvertToRemote -> decision.photoId
                is Decision.Retain -> null
            }
            if (photoId != null) {
                runCatching { thumbnailCacheService.deleteThumbnail(photoId) }
            }
        }

        mediaTaskCoordinator.endDelete(session.targets.map { it.photoId })

        return finish(
            session.scope,
            session.targets.count { !it.photoId.startsWith("live-motion:") },
            DeleteSummary(
                deletedLocal = deletedLocal,
                remoteTrashed = remoteTrashed,
                remoteRetained = remoteRetained,
                remoteFailed = remoteFailed,
                remotePending = remotePending,
                remoteUnavailable = remoteUnavailable,
                notDeleted = cancelled,
                localFailed = session.localFailed,
            )
        )
    }

    private suspend fun finish(scope: DeleteScope, count: Int, summary: DeleteSummary): DeleteStep.Finished {
        val now = System.currentTimeMillis()
        val failed = summary.remoteFailed + summary.remotePending + summary.remoteUnavailable + summary.localFailed
        val status = when {
            failed > 0 -> "failed"
            summary.notDeleted > 0 -> "cancelled"
            else -> "completed"
        }
        taskRepository.upsert(
            AppTask(
                id = UUID.randomUUID().toString(),
                kind = "delete",
                title = when (scope) {
                    DeleteScope.PHONE -> "从手机移除 $count 项"
                    DeleteScope.SERVER -> "从服务器移除 $count 项"
                    DeleteScope.BOTH -> "从手机和服务器移除 $count 项"
                },
                status = status,
                message = summary.message(),
                current = count,
                total = count,
                indeterminate = false,
                createdAt = now,
                updatedAt = now,
                finishedAt = now,
            )
        )
        taskRepository.cleanupRecentResults(now - 7L * 24 * 60 * 60 * 1000)
        return DeleteStep.Finished(summary)
    }

    /**
     * 进程重建/启动后核对上次已提交的本机删除：文件已不在 MediaStore（含已入系统回收站）
     * 的按删除收尾；仍存在的清除占位，绝不重复破坏性操作。
     */
    suspend fun reconcilePendingLocalDeletions() {
        val rows = mediaOperationDao.listAllPendingLocal().filterNot { mediaTaskCoordinator.isDeleting(it.photoId) }
        if (rows.isEmpty()) return
        val results = rows.associate { it.photoId to localDeletionConfirmed(it.sourceUri) }
        val gone = rows.filter { results[it.photoId] == true }.map { it.photoId }
        if (gone.isNotEmpty()) {
            database.withTransaction {
                for (photoId in gone) {
                    if (serverProjectionDao.getByLocalPhotoId(photoId) != null) {
                        photoDao.convertToServerProjection(photoId)
                    } else {
                        photoDao.deleteById(photoId)
                    }
                }
            }
            gone.forEach { runCatching { thumbnailCacheService.deleteThumbnail(it) } }
        }
        mediaOperationDao.clearPendingLocal(results.filterValues { it != null }.keys.toList())
    }

    /** 真实网络可用性：无活动网络（如飞行模式）即视为离线，避免对远程发起删除请求。 */
    private fun networkAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 查询失败不能证明已删除；显式核对系统回收站标志。 */
    private fun localDeletionConfirmed(sourceUri: String): Boolean? = try {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED)
        context.contentResolver.query(Uri.parse(sourceUri), columns, null, null, null)?.use { cursor ->
            !cursor.moveToFirst() || cursor.getInt(1) == 1
        }
    } catch (_: Exception) {
        null
    }
}

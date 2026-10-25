package com.example.youyou_album.service

import android.util.Log
import androidx.room.withTransaction
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.ChangeEnvelopeDto
import com.example.youyou_album.data.api.dto.ServerInfoDto
import com.example.youyou_album.data.api.dto.ServerMediaDto
import com.example.youyou_album.data.api.dto.ServerRelationDto
import com.example.youyou_album.data.api.dto.ServerTagDto
import com.example.youyou_album.data.api.dto.SnapshotEnvelopeDto
import com.example.youyou_album.data.db.dao.PhotoDao
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.data.db.entity.ServerMediaProjectionEntity
import com.example.youyou_album.data.db.entity.ServerSyncStateEntity
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.LivePhoto
import com.example.youyou_album.domain.model.Tag
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TagRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ServerSyncReport(
    val snapshotId: String,
    val snapshotRevision: Int,
    val mediaCount: Int,
    val removedCount: Int,
)

enum class ServerSyncPhase {
    IDLE,
    /** 服务端还在物化快照，客户端还没有可写入的行。 */
    PREPARING,
    /** 正在把清单写入本机，远程媒体数量会跟着增加。 */
    APPLYING,
}

data class ServerSyncActivity(
    val phase: ServerSyncPhase = ServerSyncPhase.IDLE,
    val appliedMedia: Int = 0,
    val lastError: String? = null,
) {
    val running: Boolean get() = phase != ServerSyncPhase.IDLE
}

@Singleton
class ServerSyncService @Inject constructor(
    private val database: AppDatabase,
    private val apiServiceFactory: ApiServiceFactory,
    private val photoRepository: PhotoRepository,
    private val photoDao: PhotoDao,
    private val tagRepository: TagRepository,
    private val serverProjectionDao: ServerProjectionDao,
    private val serverSyncStateDao: ServerSyncStateDao,
    private val serverConnectionStore: ServerConnectionStore,
    private val contentHashService: ContentHashService,
    private val json: Json,
    private val mediaTaskCoordinator: MediaTaskCoordinator,
    private val remoteDeletionIdentity: RemoteDeletionIdentity,
) {
    companion object {
        const val PROJECTION_STORAGE_ID = "server"
        /** 大库物化快照可能远超过半分钟；准备中就失败会让下一次又从头拉。 */
        private const val MAX_BOOTSTRAP_WAIT_MS = 30L * 60L * 1000L
        private const val POLL_INTERVAL_MS = 1_000L
        private const val TAG = "ServerSync"

        /** 对账单批删除量：规避 SQLite 的 SQL 变量数上限。 */
        private const val RECONCILE_CHUNK = 400
    }

    // ponytail: one active account needs one lock; use per-account locks only if concurrent accounts arrive.
    private val syncMutex = Mutex()
    private val launchLock = Any()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inflight: Deferred<ServerSyncReport?>? = null

    /** 断开/换绑期间拒绝新的同步，避免清库和写入交错。 */
    @Volatile private var acceptingSync = true

    private val _activity = MutableStateFlow(ServerSyncActivity())
    val activity: StateFlow<ServerSyncActivity> = _activity.asStateFlow()

    private lateinit var serverNamespace: String
    private var apiService: YouyouApiService? = null
    private var apiServiceBaseUrl: String? = null

    /**
     * 页面退出不会取消这里的同步。连接页返回后清单仍继续写，远程媒体数量会在写入过程中增加。
     * 同一时刻只跑一轮；后来的刷新等候这一轮，而不是再开一份全量清单。
     */
    fun requestSync(baseUrl: String): Deferred<ServerSyncReport?> {
        synchronized(launchLock) {
            if (!acceptingSync) {
                return syncScope.async { null }
            }
            inflight?.takeIf { it.isActive }?.let { return it }
            val job = syncScope.async { sync(baseUrl) }
            inflight = job
            job.invokeOnCompletion {
                synchronized(launchLock) { if (inflight === job) inflight = null }
            }
            return job
        }
    }

    /** 停掉在途同步并暂时不再接受新任务。调用方清完身份后必须 [resumeSync]。 */
    suspend fun cancelOngoingSync() {
        val job = synchronized(launchLock) {
            acceptingSync = false
            inflight.also { inflight = null }
        }
        job?.cancel()
        job?.join()
    }

    fun resumeSync() {
        acceptingSync = true
    }

    fun setApiService(service: YouyouApiService) {
        this.apiService = service
    }

    /**
     * 上传接口已经确认远端文件落盘时，立即把这条事实写入本机服务端投影。
     * 详情页和时间线订阅同一张 Room 视图，因此无需等待下一轮增量同步就能显示「已同步」。
     * 后续正常同步会按 mediaId 命中并补齐服务端版本等字段。
     */
    suspend fun recordUploadedMedia(
        baseUrl: String,
        localPhoto: Photo,
        upload: UploadService.UploadResult,
    ) {
        val namespace = resolveNamespace(baseUrl)
        if (mediaTaskCoordinator.shouldDiscardRemoteUpsert(namespace, upload.mediaId, 0)) {
            Log.d(TAG, "discard uploaded media upsert for deleted media ${upload.mediaId}")
            return
        }
        val existing = photoRepository.getByServerMediaId(namespace, upload.mediaId)
        val remotePhoto = localPhoto.copy(
            id = existing?.id ?: UUID.randomUUID().toString(),
            path = upload.path,
            sourceType = "server",
            storageId = PROJECTION_STORAGE_ID,
            thumbnailPath = null,
            sourceUri = null,
            size = upload.size,
            contentHash = upload.sha256,
            remoteThumbnailUrl = null,
            remoteContentUrl = null,
        )
        database.withTransaction {
            photoRepository.upsert(remotePhoto)
            serverProjectionDao.upsert(
                ServerMediaProjectionEntity(
                    serverNamespace = namespace,
                    serverMediaId = upload.mediaId,
                    localPhotoId = remotePhoto.id,
                    serverVersion = 0,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            photoDao.reconcileTimeForNamespace(upload.sha256, namespace)
        }
        // 上传响应不带版本；立即拉取一次真实版本，避免紧随其后的删除因未知版本只删本机、
        // 留下远程副本。拉取失败保留未知版本，由后续同步补齐。
        runCatching {
            val media = apiServiceFactory.create(baseUrl).getMedia(upload.mediaId)
            if (media.version > 0) {
                val projection = serverProjectionDao.getByMediaId(namespace, upload.mediaId) ?: return@runCatching
                serverProjectionDao.upsert(
                    projection.copy(serverVersion = media.version, updatedAt = System.currentTimeMillis())
                )
            }
        }.onFailure { Log.d(TAG, "upload version fetch skipped: ${it.javaClass.simpleName}") }
    }

    /** Wait for an in-flight sync before replacing its account and clearing its projections. */
    suspend fun prepareAccountSwitch(change: suspend () -> Unit) = syncMutex.withLock { change() }

    suspend fun sync(baseUrl: String): ServerSyncReport? = syncMutex.withLock {
        try {
            val normalizedUrl = baseUrl.trimEnd('/')
            val service = apiService?.takeIf { apiServiceBaseUrl == normalizedUrl }
                ?: apiServiceFactory.create(normalizedUrl)
            apiService = service
            apiServiceBaseUrl = normalizedUrl
            // 身份快照（地址 + 服务端实例 + 用户）参与命名空间推导：同一地址换用户
            // （不经连接页）时必须隔离旧投影，而不是继续用旧游标增量。
            val info = service.serverInfo()
            serverNamespace = computeServerNamespace(
                normalizedUrl,
                info.serverInstanceId.ifBlank { null },
                info.userId?.takeIf { it.isNotBlank() },
            )
            repairStoredIdentity(normalizedUrl, info)
            _activity.value = ServerSyncActivity(phase = ServerSyncPhase.APPLYING)
            // 返回值：迁移后状态 / 已是目标命名空间的原状态 / 需整份重拉时的 null。
            val previousState = migrateLegacyNamespace(serverSyncStateDao.get(), normalizedUrl)
            val report = if (hasIncrementalCursor(
                    previousState?.serverNamespace,
                    previousState?.changesCursor,
                    serverNamespace,
                )
            ) {
                try {
                    applyChanges(previousState!!, service)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 增量失败就整份重拉。取消必须往外抛，不能在断开时再开一轮清单。
                    Log.d(TAG, "incremental sync failed, bootstrapping: ${e.message}")
                    bootstrap(service)
                }
            } else {
                bootstrap(service)
            }

            // 一台设备只有一个活跃身份：任何一次同步成功后都回收其它身份的残留投影
            //（换绑、覆盖安装、服务端地址变更都会留下它们）。放在同步成功之后，
            // 同步失败时不动旧数据。
            purgeOtherNamespaces()
            remoteDeletionIdentity.reconcile()
            _activity.value = ServerSyncActivity()
            report
        } catch (e: CancellationException) {
            _activity.value = ServerSyncActivity()
            throw e
        } catch (e: Exception) {
            _activity.value = ServerSyncActivity(lastError = e.message ?: "同步失败")
            throw e
        }
    }

    /**
     * 补齐持久化的服务端身份（同址换实例/换用户）。只改身份字段，不动首连引导标记。
     */
    private suspend fun repairStoredIdentity(baseUrl: String, info: ServerInfoDto) {
        val connection = serverConnectionStore.getConnection() ?: return
        if (connection.baseUrl.trimEnd('/') != baseUrl) return
        val instanceId = info.serverInstanceId.ifBlank { null }
        val userId = info.userId?.takeIf { it.isNotBlank() }
        if (connection.serverInstanceId == instanceId && connection.userId == userId) return
        Log.d(TAG, "server identity changed; repairing stored connection")
        serverConnectionStore.updateIdentity(serverInstanceId = instanceId, userId = userId)
    }

    /**
     * 命名空间从「仅地址」升级为「地址+实例+用户」后的平滑迁移。
     *
     * 旧投影挂在旧式命名空间下；若旧命名空间恰好是本地址的旧式推导且游标有效，
     * 就把本机所有身份行（投影/标签/关联/导出/同步状态）整批改名到新命名空间并
     * 保留游标——升级后的首次同步继续走增量，不做整库重建。
     *
     * 返回值约定（调用方用它判断是否走增量）：
     * - 已是目标命名空间 → 原样返回 [state]，游标有效则走增量；
     * - 发生迁移 → 返回改名后的状态，继续走增量；
     * - 需要整份重拉（无状态 / 空游标 / 非旧式命名空间 / 迁移失败）→ null。
     *
     * 可达场景（配对/换绑都经连接页清库）中旧数据必然属于同一身份；同址换用户
     * 的盲区在这里延续旧行为，自本次迁移后由身份化命名空间覆盖。改名失败会退化为
     * 正常的整库重建（hasIncrementalCursor 不成立），不丢数据。
     */
    private suspend fun migrateLegacyNamespace(
        state: ServerSyncStateEntity?,
        baseUrl: String,
    ): ServerSyncStateEntity? {
        if (state == null) return null
        val oldNamespace = state.serverNamespace
        // 已是目标命名空间时必须原样返回：调用方拿返回值判断增量游标。
        // 若在这里返回 null，每次同步都会被当成「没有游标」而整份 bootstrap。
        if (oldNamespace.isBlank()) return null
        if (oldNamespace == serverNamespace) return state
        if (state.changesCursor.isBlank()) return null
        if (!isLegacyStyleNamespace(oldNamespace, baseUrl, contentHashService::sha256HexForString)) {
            return null
        }
        return try {
            database.withTransaction {
                serverProjectionDao.renameNamespace(oldNamespace, serverNamespace)
                database.tagDao().renameNamespace(oldNamespace, serverNamespace)
                database.tagDao().renameRelationNamespace(oldNamespace, serverNamespace)
                database.serverExportDao().renameNamespace(oldNamespace, serverNamespace)
                serverSyncStateDao.upsert(
                    state.copy(
                        serverNamespace = serverNamespace,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            Log.d(TAG, "legacy namespace migrated: $oldNamespace -> $serverNamespace")
            state.copy(serverNamespace = serverNamespace)
        } catch (e: Exception) {
            Log.w(TAG, "legacy namespace migration failed; falling back to bootstrap", e)
            null
        }
    }

    /**
     * 删除非当前身份的投影行及其 `photos_table` 行。与 `RemoteAccountCacheCleaner`（连接页
     * 解绑/换绑时整表清理）互补，覆盖不经连接页写入服务端地址、以及历史版本遗留的路径。
     * 必须先删照片再删投影，因为照片的删除条件依赖投影表做子查询。
     */
    private suspend fun purgeOtherNamespaces() {
        database.withTransaction {
            val photos = photoDao.deleteServerPhotosOutsideNamespace(serverNamespace)
            val projections = serverProjectionDao.deleteProjectionsOutsideNamespace(serverNamespace)
            if (photos > 0 || projections > 0) {
                Log.d(TAG, "purge other namespaces photos=$photos projections=$projections")
            }
        }
    }

    /**
     * 以 bootstrap 快照为事实来源回收当前身份的投影：快照里没有的 `server_media_id` 视为已从
     * 媒体库移除，连同其 `photos_table` 行一起删除。分批执行以规避 SQLite 的 SQL 变量数上限。
     */
    private suspend fun reconcileNamespace(loadedMediaIds: Set<String>): Int {
        val stale = serverProjectionDao.listServerMediaIds(serverNamespace)
            .filterNot { it in loadedMediaIds }
        if (stale.isEmpty()) return 0

        var removed = 0
        for (chunk in stale.chunked(RECONCILE_CHUNK)) {
            database.withTransaction {
                val photoIds = serverProjectionDao.listLocalPhotoIdsIn(serverNamespace, chunk)
                if (photoIds.isNotEmpty()) photoDao.deleteByIds(photoIds)
                serverProjectionDao.deleteIn(serverNamespace, chunk)
            }
            removed += chunk.size
        }
        return removed
    }

    private suspend fun bootstrap(service: YouyouApiService): ServerSyncReport {
        val start = service.startBootstrap()
        val deadline = System.currentTimeMillis() + MAX_BOOTSTRAP_WAIT_MS
        var status = service.getBootstrap(start.snapshotId)
        while (status.state == "preparing" && System.currentTimeMillis() < deadline) {
            _activity.value = ServerSyncActivity(phase = ServerSyncPhase.PREPARING)
            delay(POLL_INTERVAL_MS)
            status = service.getBootstrap(start.snapshotId)
        }
        if (status.state != "ready") {
            val reason = status.lastError?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            throw IllegalStateException(
                if (status.state == "preparing") "服务端清单仍在准备，请稍后在时间线再刷新一次"
                else "服务端快照未就绪：${status.state}$reason",
            )
        }
        val revision = status.snapshotRevision ?: throw IllegalStateException("服务端快照缺少 revision")

        // 先挂上身份，连接页才能在写入过程中看到远程媒体数量，而不是等整份清单提交后突然跳变。
        // 游标留空：中途退出的下一轮仍走全量，不会把写了一半的清单当成已经同步完。
        beginBootstrapIdentity()

        // 1. Sync media（按页落库，避免三万条各开一次事务）
        val loadedMediaIds = mutableSetOf<String>()
        var mediaCount = 0
        var cursor: String? = null
        while (true) {
            val page = service.listBootstrapEntity(start.snapshotId, "media", cursor = cursor, limit = 100)
            val medias = mutableListOf<ServerMediaDto>()
            for (raw in page.items) {
                val envelope = json.decodeFromJsonElement(
                    SnapshotEnvelopeDto.serializer(ServerMediaDto.serializer()),
                    raw,
                )
                val media = envelope.data
                    ?: throw IllegalStateException("bootstrap media 条目缺少 data 字段: $raw")
                if (media.id.isEmpty()) continue
                loadedMediaIds.add(media.id)
                medias.add(media)
            }
            upsertMediaBatch(medias, revision)
            mediaCount += medias.size
            _activity.value = ServerSyncActivity(
                phase = ServerSyncPhase.APPLYING,
                appliedMedia = mediaCount,
            )
            if (!page.hasMore || page.nextCursor.isNullOrEmpty()) break
            if (page.nextCursor == cursor) throw IllegalStateException("清单分页没有前进")
            cursor = page.nextCursor
        }
        Log.d(TAG, "bootstrap media done count=$mediaCount")

        // 1b. 对账快照：回收当前身份下已从媒体库消失的投影（本机索引不动）。
        // 放在保存游标之前——失败则不推进游标，下次同步重新 bootstrap 并重试对账。
        val removedCount = reconcileNamespace(loadedMediaIds)
        Log.d(TAG, "bootstrap reconcile removed=$removedCount")

        // 2. Sync tags
        val loadedTagIds = mutableSetOf<String>()
        syncBootstrapEntity(service, start.snapshotId, "tags", ServerTagDto.serializer()) { tag ->
            if (tag.id.isEmpty()) return@syncBootstrapEntity
            val localId = serverEntityId("tag", tag.id)
            loadedTagIds.add(localId)
            upsertTag(tag, localId)
        }

        // 3. Sync relations（仅处理标签关联；相册关联由客户端忽略）
        syncBootstrapEntity(service, start.snapshotId, "relations", ServerRelationDto.serializer()) { relation ->
            upsertRelation(relation)
        }

        // 4. Save sync state
        val changesCursor = status.changesCursor ?: throw IllegalStateException("服务端快照缺少 changes cursor")
        serverSyncStateDao.upsert(
            ServerSyncStateEntity(
                id = 1,
                serverNamespace = serverNamespace,
                changesCursor = changesCursor,
                snapshotRevision = revision,
                updatedAt = System.currentTimeMillis(),
            )
        )

        return ServerSyncReport(
            snapshotId = start.snapshotId,
            snapshotRevision = revision,
            mediaCount = mediaCount,
            removedCount = removedCount,
        )
    }

    private suspend fun applyChanges(state: ServerSyncStateEntity, service: YouyouApiService): ServerSyncReport {
        var cursor = state.changesCursor
        var appliedCount = 0
        var removedCount = 0
        var lastRevision = state.snapshotRevision

        while (true) {
            val page = service.listChanges(cursor = cursor, limit = 200)
            for (raw in page.items) {
                // changes 条目为信封 {eventId, revision, entity, operation, entityId, version, data}；
                // 解码失败或缺少关键字段时立即抛出，禁止静默跳过（防止结构漂移导致 0 条落库）
                val change = json.decodeFromJsonElement(ChangeEnvelopeDto.serializer(), raw)
                if (change.revision != 0) lastRevision = change.revision

                when (change.entity) {
                    "media" -> {
                        if (change.entityId.isEmpty()) throw IllegalStateException("changes media 缺少 entityId: $raw")
                        if (change.operation == "delete") {
                            photoRepository.deleteServerProjection(serverNamespace, change.entityId)
                            removedCount++
                        } else if (change.operation == "upsert") {
                            // 变更信封已携带完整媒体载荷，直接落库，避免每条媒体变更一次
                            // GET 的 N+1；旧服务端载荷缺 `version` 字段时回源拉取兜底
                            // （版本缺失会把删除的乐观并发校验带偏）。
                            val data = change.data
                            val media = if (data != null && data.containsKey("version")) {
                                json.decodeFromJsonElement(ServerMediaDto.serializer(), data)
                            } else {
                                service.getMedia(change.entityId)
                            }
                            if (media.id.isEmpty()) throw IllegalStateException("changes media 缺少 id: $raw")
                            upsertMedia(media, lastRevision)
                            appliedCount++
                        }
                    }
                    "album", "album_relation" -> {
                        // 客户端已移除相册功能，忽略服务端相册变更
                    }
                    "tag" -> {
                        if (change.entityId.isEmpty()) throw IllegalStateException("changes tag 缺少 entityId: $raw")
                        val localId = serverEntityId("tag", change.entityId)
                        if (change.operation == "delete") {
                            tagRepository.deleteById(localId)
                            removedCount++
                        } else if (change.operation == "upsert" && change.data != null) {
                            val tag = json.decodeFromJsonElement(ServerTagDto.serializer(), change.data)
                            upsertTag(tag, localId)
                            appliedCount++
                        } else {
                            return bootstrap(service)
                        }
                    }
                    "tag_relation" -> {
                        if (change.data != null) {
                            val relation = json.decodeFromJsonElement(ServerRelationDto.serializer(), change.data)
                            if (change.operation == "delete") {
                                removeRelation(relation)
                            } else if (change.operation == "upsert") {
                                upsertRelation(relation)
                            }
                            appliedCount++
                        }
                    }
                    else -> return bootstrap(service)
                }
            }
            val nextCursor = page.nextCursor
            if (nextCursor != null && nextCursor.isNotEmpty()) {
                if (nextCursor == cursor) throw IllegalStateException("变更分页没有前进")
                cursor = nextCursor
                serverSyncStateDao.upsert(
                    ServerSyncStateEntity(
                        id = 1,
                        serverNamespace = serverNamespace,
                        changesCursor = cursor,
                        snapshotRevision = lastRevision,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            if (!page.hasMore || nextCursor == null || nextCursor.isEmpty()) break
        }

        serverSyncStateDao.upsert(
            ServerSyncStateEntity(
                id = 1,
                serverNamespace = serverNamespace,
                changesCursor = cursor,
                snapshotRevision = lastRevision,
                updatedAt = System.currentTimeMillis(),
            )
        )

        return ServerSyncReport(
            snapshotId = "incremental",
            snapshotRevision = lastRevision,
            mediaCount = appliedCount,
            removedCount = removedCount,
        )
    }

    /**
     * 全量清单开始写入前先记下当前身份，但先不提交游标。
     * 这样连接页的远程媒体计数能跟着每一页增长；中断后再进来仍会整份重拉。
     */
    private suspend fun beginBootstrapIdentity() {
        val existing = serverSyncStateDao.get()
        val keepClock = existing?.serverNamespace == serverNamespace && existing.updatedAt > 0
        serverSyncStateDao.upsert(
            ServerSyncStateEntity(
                id = 1,
                serverNamespace = serverNamespace,
                changesCursor = "",
                snapshotRevision = 0,
                updatedAt = if (keepClock) existing.updatedAt else 0L,
            ),
        )
    }

    private suspend fun upsertMedia(media: ServerMediaDto, revision: Int) {
        upsertMediaBatch(listOf(media), revision)
    }

    private suspend fun upsertMediaBatch(items: List<ServerMediaDto>, revision: Int) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val photos = mutableListOf<Photo>()
        val projections = mutableListOf<ServerMediaProjectionEntity>()
        val favoriteUpdates = mutableListOf<Pair<String, Boolean>>()
        for (media in items) {
            if (media.id.isEmpty()) continue
            val existing = photoRepository.getByServerMediaId(serverNamespace, media.id)
            // 删除前启动的旧快照/变更响应不得覆盖已删除状态；更高版本（恢复/重建）则放行。
            if (existing == null &&
                mediaTaskCoordinator.shouldDiscardRemoteUpsert(serverNamespace, media.id, media.version)
            ) {
                Log.d(TAG, "discard stale upsert for deleted media ${media.id}")
                continue
            }
            val photo = projectedPhoto(media, existing)
            photos.add(photo)
            projections.add(
                ServerMediaProjectionEntity(
                    serverNamespace = serverNamespace,
                    serverMediaId = media.id,
                    localPhotoId = photo.id,
                    serverVersion = media.version,
                    lastAppliedRevision = revision,
                    serverStorageId = media.storageId,
                    updatedAt = now,
                ),
            )
            media.contentHash?.let { hash ->
                media.isFavorite?.let { favoriteUpdates.add(hash to it) }
            }
        }
        if (photos.isEmpty()) return
        // 图片与远程映射一起提交，防止观察者先收到缺少预览地址的图片。
        database.withTransaction {
            photoRepository.upsertAll(photos)
            serverProjectionDao.upsertAll(projections)
            photos.mapNotNull { it.contentHash }.distinct().forEach { hash ->
                photoDao.reconcileTimeForNamespace(hash, serverNamespace)
            }
            // 收藏以服务端为事实来源：本机孪生行跟随服务端的收藏状态（时间线里
            // 可见的是本机行，收藏页可能呈现服务端行，两处必须一致）。
            favoriteUpdates.distinct().forEach { (hash, favorite) ->
                photoDao.applyServerFavoriteToLocals(hash, favorite)
            }
        }
    }

    private fun projectedPhoto(media: ServerMediaDto, existing: Photo?): Photo {
        val time = if (media.timeVersion >= 1) {
            com.example.youyou_album.domain.model.MediaTime.Value(media.sortAt, media.sortSource ?: "unknown")
        } else {
            com.example.youyou_album.domain.model.MediaTime.resolve(
                media.originalName ?: media.name,
                null,
                null,
                media.sortAt,
            )
        }
        return Photo(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = media.name,
            path = media.path,
            sourceType = "server",
            storageId = PROJECTION_STORAGE_ID,
            isVideo = media.isVideo,
            duration = media.durationMs,
            createdAt = media.takenAt,
            modifiedAt = null,
            sortAt = time.at,
            takenAt = media.takenAt.takeIf { media.timeVersion >= 1 },
            sortSource = time.source,
            timeVersion = media.timeVersion,
            originalName = media.originalName ?: media.name,
            width = media.width,
            height = media.height,
            size = media.size,
            mimeType = media.mimeType,
            contentHash = media.contentHash,
            isFavorite = media.isFavorite ?: existing?.isFavorite ?: false,
            livePhoto = media.livePhoto?.let { live ->
                LivePhoto(
                    role = live.role,
                    embedded = live.embedded,
                    groupKey = live.groupKey,
                    partnerMediaId = live.partnerMediaId,
                    partnerContentHash = live.partnerContentHash,
                    motionDurationMs = live.motionDurationMs,
                )
            },
        )
    }

    private suspend fun upsertTag(tag: ServerTagDto, localId: String) {
        tagRepository.upsert(
            Tag(
                id = localId,
                name = tag.name,
                createdAt = tag.createdAt,
                updatedAt = tag.updatedAt,
                sourceType = "server",
                serverNamespace = serverNamespace,
                serverRemoteId = tag.id,
                version = tag.version.toLong(),
            )
        )
    }

    private suspend fun upsertRelation(relation: ServerRelationDto) {
        val mediaId = relation.mediaId
        if (mediaId.isEmpty()) return
        if (relation.tagId.isNullOrEmpty()) return

        var localPhoto = photoRepository.getByServerMediaId(serverNamespace, mediaId)
        if (localPhoto == null) {
            try {
                val media = apiService?.getMedia(mediaId) ?: return
                upsertMedia(media, 0)
                localPhoto = photoRepository.getByServerMediaId(serverNamespace, mediaId)
            } catch (_: Exception) {
                return
            }
        }
        if (localPhoto == null) return

        val tagId = serverEntityId("tag", relation.tagId)
        tagRepository.addTagToPhoto(tagId, localPhoto.id, serverNamespace)
    }

    private suspend fun removeRelation(relation: ServerRelationDto) {
        val mediaId = relation.mediaId
        if (mediaId.isEmpty()) return
        if (relation.tagId.isNullOrEmpty()) return
        val localPhoto = photoRepository.getByServerMediaId(serverNamespace, mediaId) ?: return

        tagRepository.removeTagFromPhoto(serverEntityId("tag", relation.tagId), localPhoto.id)
    }

    /**
     * 分页拉取 bootstrap 快照某一实体，并将每个条目按类型化信封 SnapshotEnvelopeDto<T>
     * 解码后交给 block。解码失败或条目缺少 data 字段时立即抛出（fail-fast），
     * 避免服务端结构变化时静默 0 条落库。
     */
    private suspend fun <T> syncBootstrapEntity(
        service: YouyouApiService,
        snapshotId: String,
        entity: String,
        serializer: KSerializer<T>,
        block: suspend (T) -> Unit,
    ) {
        var cursor: String? = null
        do {
            val page = service.listBootstrapEntity(snapshotId, entity, cursor = cursor, limit = 100)
            for (raw in page.items) {
                val envelope = json.decodeFromJsonElement(SnapshotEnvelopeDto.serializer(serializer), raw)
                val data = envelope.data
                    ?: throw IllegalStateException("bootstrap $entity 条目缺少 data 字段: $raw")
                block(data)
            }
            if (!page.hasMore || page.nextCursor == null) break
            cursor = page.nextCursor
        } while (true)
    }

    private fun serverEntityId(entity: String, remoteId: String): String =
        serverEntityLocalId(contentHashService, serverNamespace, entity, remoteId)

    /**
     * 当前生效的服务端命名空间（地址 + 服务端实例 + 用户）。
     * 优先使用本轮同步推导的身份，其次读持久化的同步状态；都没有（尚未完成
     * 任何一次同步）时返回 null。
     */
    suspend fun activeServerNamespace(): String? {
        if (::serverNamespace.isInitialized && serverNamespace.isNotBlank()) return serverNamespace
        return serverSyncStateDao.get()?.serverNamespace?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析某个服务端地址对应的命名空间；本轮尚未同步且状态为空时按持久化身份
     * 字段直接推导（旧服务端无身份字段时退回仅地址推导，保持兼容）。
     */
    private suspend fun resolveNamespace(baseUrl: String): String {
        activeServerNamespace()?.let { return it }
        val normalizedUrl = baseUrl.trimEnd('/')
        val connection = serverConnectionStore.getConnection()
            ?.takeIf { it.baseUrl.trimEnd('/') == normalizedUrl }
        return computeServerNamespace(
            normalizedUrl,
            connection?.serverInstanceId,
            connection?.userId,
        )
    }

    /**
     * 命名空间同时绑定地址、服务端实例与用户：同一地址换实例/换用户都会得到新
     * 命名空间，旧投影随 `purgeOtherNamespaces` 回收。旧服务端（无身份字段）
     * 保持仅地址推导，避免无谓的整库重拉。
     */
    private fun computeServerNamespace(
        baseUrl: String,
        serverInstanceId: String?,
        userId: String?,
    ): String {
        val url = baseUrl.trimEnd('/')
        val identity = if (serverInstanceId.isNullOrBlank() && userId.isNullOrBlank()) {
            url
        } else {
            "$url\n${serverInstanceId.orEmpty()}\n${userId.orEmpty()}"
        }
        return "server_${contentHashService.sha256HexForString(identity).substring(0, 16)}"
    }
}

/**
 * 服务端实体 id 到本机稳定 id 的推导，同步引擎与标签写路径共用同一算法。
 */
internal fun serverEntityLocalId(
    contentHashService: ContentHashService,
    namespace: String,
    entity: String,
    remoteId: String,
): String {
    val digest = contentHashService.sha256HexForString("$namespace:$entity:$remoteId")
    return "t${digest.substring(0, 32)}"
}

/**
 * 旧式命名空间判定（升级迁移用）：命名空间必须确实由旧算法「仅地址哈希」产生。
 * [sha256Hex] 参数化以便单测；生产传入 ContentHashService.sha256HexForString。
 */
internal fun isLegacyStyleNamespace(
    namespace: String,
    baseUrl: String,
    sha256Hex: (String) -> String,
): Boolean {
    val candidates = setOf(baseUrl, baseUrl.trimEnd('/'))
    return candidates.any { "server_${sha256Hex(it).substring(0, 16)}" == namespace }
}

/**
 * Factory to create YouyouApiService instances with dynamic baseUrl.
 */
@Singleton
class ApiServiceFactory @Inject constructor(
    private val okHttpClient: okhttp3.OkHttpClient,
    private val json: Json,
) {
    fun create(baseUrl: String, mediaOperations: Boolean = false): YouyouApiService {
        val contentType = "application/json".toMediaType()
        return retrofit2.Retrofit.Builder()
            .baseUrl("$baseUrl${YouyouApiService.API_PREFIX}")
            .client(if (mediaOperations) okHttpClient.newBuilder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build() else okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(YouyouApiService::class.java)
    }
}

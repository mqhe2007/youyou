package com.example.youyou_album.service

import android.util.Log
import androidx.room.withTransaction
import com.example.youyou_album.data.db.AppDatabase
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.ChangeEnvelopeDto
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
import kotlinx.coroutines.delay
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

@Singleton
class ServerSyncService @Inject constructor(
    private val database: AppDatabase,
    private val apiServiceFactory: ApiServiceFactory,
    private val photoRepository: PhotoRepository,
    private val photoDao: PhotoDao,
    private val tagRepository: TagRepository,
    private val serverProjectionDao: ServerProjectionDao,
    private val serverSyncStateDao: ServerSyncStateDao,
    private val contentHashService: ContentHashService,
    private val json: Json,
    private val mediaTaskCoordinator: MediaTaskCoordinator,
    private val remoteDeletionIdentity: RemoteDeletionIdentity,
) {
    companion object {
        const val PROJECTION_STORAGE_ID = "server"
        private const val MAX_BOOTSTRAP_POLLS = 120
        private const val POLL_INTERVAL_MS = 250L
        private const val TAG = "ServerSync"

        /** 对账单批删除量：规避 SQLite 的 SQL 变量数上限。 */
        private const val RECONCILE_CHUNK = 400
    }

    // ponytail: one active account needs one lock; use per-account locks only if concurrent accounts arrive.
    private val syncMutex = Mutex()
    private lateinit var serverNamespace: String
    private var apiService: YouyouApiService? = null

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
        val namespace = computeServerNamespace(baseUrl)
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
            val service = apiService?.takeIf {
                ::serverNamespace.isInitialized && serverNamespace == computeServerNamespace(baseUrl)
            } ?: apiServiceFactory.create(baseUrl)
            apiService = service
            serverNamespace = computeServerNamespace(baseUrl)
            val previousState = serverSyncStateDao.get()
            val report = if (previousState != null && previousState.serverNamespace == serverNamespace) {
                try {
                    applyChanges(previousState, service)
                } catch (e: Exception) {
                    // Fall through to bootstrap on any error
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
            report
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
        var status = service.getBootstrap(start.snapshotId)
        var attempt = 0
        while (status.state == "preparing" && attempt < MAX_BOOTSTRAP_POLLS) {
            delay(POLL_INTERVAL_MS)
            status = service.getBootstrap(start.snapshotId)
            attempt++
        }
        if (status.state != "ready") {
            throw IllegalStateException("服务端快照未就绪：${status.state}")
        }
        val revision = status.snapshotRevision ?: throw IllegalStateException("服务端快照缺少 revision")

        // 1. Sync media
        val loadedMediaIds = mutableSetOf<String>()
        var mediaCount = 0
        syncBootstrapEntity(service, start.snapshotId, "media", ServerMediaDto.serializer()) { media ->
            if (media.id.isEmpty()) return@syncBootstrapEntity
            loadedMediaIds.add(media.id)
            upsertMedia(media, revision)
            mediaCount++
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
                            val media = service.getMedia(change.entityId)
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

    private suspend fun upsertMedia(media: ServerMediaDto, revision: Int) {
        if (media.id.isEmpty()) return
        Log.d(TAG, "upsertMedia id=${media.id} name=${media.name} mime=${media.mimeType}")
        val existing = photoRepository.getByServerMediaId(serverNamespace, media.id)
        // 删除前启动的旧快照/变更响应不得覆盖已删除状态；更高版本（恢复/重建）则放行。
        if (existing == null &&
            mediaTaskCoordinator.shouldDiscardRemoteUpsert(serverNamespace, media.id, media.version)
        ) {
            Log.d(TAG, "discard stale upsert for deleted media ${media.id}")
            return
        }
        val time = if (media.timeVersion >= 1) {
            com.example.youyou_album.domain.model.MediaTime.Value(media.sortAt, media.sortSource ?: "unknown")
        } else com.example.youyou_album.domain.model.MediaTime.resolve(media.originalName ?: media.name, null, null, media.sortAt)
        val sortAt = time.at
        val photo = Photo(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = media.name,
            path = media.path,
            sourceType = "server",
            storageId = PROJECTION_STORAGE_ID,
            isVideo = media.isVideo,
            duration = media.durationMs,
            createdAt = media.takenAt,
            modifiedAt = null,
            sortAt = sortAt,
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
        // 图片与远程映射一起提交，防止观察者先收到缺少预览地址的图片。
        database.withTransaction {
            photoRepository.upsert(photo)
            serverProjectionDao.upsert(
                ServerMediaProjectionEntity(
                    serverNamespace = serverNamespace,
                    serverMediaId = media.id,
                    localPhotoId = photo.id,
                    serverVersion = media.version,
                    lastAppliedRevision = revision,
                    serverStorageId = media.storageId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            database.photoDao().reconcileTimeForNamespace(photo.contentHash,serverNamespace)
        }
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

    private fun serverEntityId(entity: String, remoteId: String): String {
        val digest = contentHashService.sha256HexForString("$serverNamespace:$entity:$remoteId")
        return "t${digest.substring(0, 32)}"
    }

    private fun computeServerNamespace(baseUrl: String): String {
        return "server_${contentHashService.sha256HexForString(baseUrl).substring(0, 16)}"
    }
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

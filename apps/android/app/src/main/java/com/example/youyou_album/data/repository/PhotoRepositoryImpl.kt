package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.dao.PhotoDao
import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PhotoRepositoryImpl @Inject constructor(
    private val photoDao: PhotoDao,
    private val serverProjectionDao: ServerProjectionDao,
    private val serverSyncStateDao: ServerSyncStateDao,
    private val connectionStore: com.example.youyou_album.service.ServerConnectionStore,
) : PhotoRepository {

    /**
     * 当前生效的服务端身份。未绑定或尚未同步时为空串——此时任何服务端投影都不可见，
     * 保证「未绑定只浏览本机」。
     */
    private fun activeNamespace(): Flow<String> =
        serverSyncStateDao.observe().map { state ->
            state?.serverNamespace?.takeIf { it.isNotBlank() } ?: ""
        }

    /** 远程地址只用于展示，避免把 HTTP 地址写进本机媒体 URI。 */
    private suspend fun Photo.withRemotePreview(): Photo {
        if (sourceType != "server") return this
        val connection = connectionStore.getConnection() ?: return this
        val projection = serverProjectionDao.getByLocalPhotoId(id) ?: return this
        val mediaUrl = "${connection.baseUrl.trimEnd('/')}/api/v1/media/${projection.serverMediaId}"
        return copy(
            remoteThumbnailUrl = "$mediaUrl/thumbnail?size=512&v=${projection.serverVersion}",
            remoteContentUrl = "$mediaUrl/content?v=${projection.serverVersion}",
        )
    }

    override fun observeAll(): Flow<List<Photo>> =
        photoDao.observeAll().map { list -> list.map { it.toDomain() }.withSyncDisplay().map { it.withRemotePreview() } }

    override suspend fun getAll(): List<Photo> = photoDao.getAll().map { it.toDomain() }.withSyncDisplay().map { it.withRemotePreview() }

    override suspend fun getById(id: String): Photo? =
        photoDao.getById(id)?.toDomain()?.withRemotePreview()

    override suspend fun getBySourceUri(sourceUri: String): Photo? =
        photoDao.getBySourceUri(sourceUri)?.toDomain()

    override suspend fun upsert(photo: Photo) = photoDao.upsertWithTime(photo.toEntity())

    override suspend fun upsertAll(photos: List<Photo>) =
        photoDao.upsertAllWithTime(photos.map { it.toEntity() })

    override suspend fun deleteById(id: String) = photoDao.deleteById(id)

    override suspend fun deletePhotos(ids: List<String>) {
        if (ids.isNotEmpty()) photoDao.deleteByIds(ids)
    }

    override suspend fun count(): Int = photoDao.count()

    override suspend fun countMissingContentHash(): Int = photoDao.countMissingContentHash()

    override suspend fun getByContentHash(hash: String): Photo? =
        photoDao.getByContentHash(hash)?.toDomain()

    override suspend fun getByNameAndSize(name: String, size: Long): List<Photo> =
        photoDao.getByNameAndSize(name, size).map { it.toDomain() }

    override suspend fun getByServerMediaId(namespace: String, serverMediaId: String): Photo? =
        photoDao.getByServerMediaId(namespace, serverMediaId)?.toDomain()

    override suspend fun deleteServerProjection(namespace: String, serverMediaId: String) {
        // 先删照片再删投影：照片的删除条件依赖投影表做子查询。
        photoDao.deleteServerProjection(namespace, serverMediaId)
        serverProjectionDao.deleteByMediaId(namespace, serverMediaId)
    }

    override suspend fun toggleFavorite(id: String, isFavorite: Boolean) =
        photoDao.updateFavorite(id, isFavorite)

    override suspend fun getFavorites(): List<Photo> =
        getAll().filter { it.isFavorite }

    override fun observeFavorites(): Flow<List<Photo>> =
        activeNamespace().flatMapLatest { namespace ->
            photoDao.observeFavorites(namespace)
                .map { list -> list.map { it.toDomain() }.withSyncDisplay().map { it.withRemotePreview() } }
        }

    override fun observeTimeline(filter: MediaSyncDisplay?): Flow<List<Photo>> =
        activeNamespace().flatMapLatest { namespace ->
            photoDao.observeTimeline(filter?.name ?: "ALL", namespace)
                .map { list -> list.map { it.toDomain().withRemotePreview() } }
        }

    /**
     * 查看器翻页用的可见集合：与时间线（不筛选）完全同源，避免网格与查看器条数不一致。
     *
     * 不能用 [observeTimeline] 的第一次发射——它是首屏分段的，第一次只发窗口大小，
     * 拿 `.first()` 当全量会让幻灯片/查看器只看到前几百张。
     */
    override suspend fun getTimeline(): List<Photo> {
        val namespace = activeNamespace().first()
        return photoDao.getTimelineOnce("ALL", namespace)
            .map { it.toDomain().withRemotePreview() }
    }

    override suspend fun resolveServerMediaId(photo: Photo): String? {
        if (photo.sourceType == "server") {
            return serverProjectionDao.getByLocalPhotoId(photo.id)?.serverMediaId
        }
        val hash = photo.contentHash ?: return null
        return serverProjectionDao.getServerMediaIdForContentHash(hash)
    }

    override suspend fun getVideos(): List<Photo> =
        photoDao.getVideos().map { it.toDomain() }
}

package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.data.db.dao.TagDao
import com.example.youyou_album.data.db.entity.PhotoTagEntity
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.Tag
import com.example.youyou_album.domain.repository.TagRepository
import com.example.youyou_album.service.ServerConnectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TagRepositoryImpl @Inject constructor(
    private val tagDao: TagDao,
    private val serverSyncStateDao: ServerSyncStateDao,
    private val serverProjectionDao: ServerProjectionDao,
    private val connectionStore: ServerConnectionStore,
) : TagRepository {

    override fun observeAll(): Flow<List<Tag>> =
        tagDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): Tag? = tagDao.getById(id)?.toDomain()

    override suspend fun getLocalByName(name: String): Tag? = tagDao.getLocalByName(name)?.toDomain()

    override suspend fun upsert(tag: Tag) = tagDao.upsert(tag.toEntity())

    override suspend fun deleteById(id: String) = tagDao.deleteById(id)

    /** 当前生效的服务端身份；未绑定时为空串，服务端投影不参与状态派生。 */
    private fun activeNamespace(): Flow<String> =
        serverSyncStateDao.observe().map { state ->
            state?.serverNamespace?.takeIf { it.isNotBlank() } ?: ""
        }

    override fun observePhotosByTag(tagId: String): Flow<List<Photo>> =
        activeNamespace().flatMapLatest { namespace ->
            tagDao.observePhotosByTag(tagId, namespace)
                .map { list -> list.map { it.toDomain() }.map { it.withRemotePreview() } }
        }

    /** 远程条目补预览地址，与时间线/收藏入口一致。 */
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

    override suspend fun addTagToPhoto(tagId: String, photoId: String, serverNamespace: String?) =
        tagDao.addTagToPhoto(
            PhotoTagEntity(tagId = tagId, photoId = photoId, serverNamespace = serverNamespace)
        )

    override suspend fun removeTagFromPhoto(tagId: String, photoId: String) =
        tagDao.removeTagFromPhoto(tagId, photoId)
}

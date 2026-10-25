package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.dao.ServerProjectionDao
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.data.db.dao.TagDao
import com.example.youyou_album.data.db.entity.PhotoTagEntity
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.Tag
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TagRepository
import com.example.youyou_album.service.ServerConnectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val photoRepository: PhotoRepository,
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

    /** 标签详情与时间线同源：投影列随查询带出，这里不再逐条回查投影表。 */
    override fun observePhotosByTag(tagId: String): Flow<List<Photo>> =
        activeNamespace().flatMapLatest { namespace ->
            tagDao.observePhotosByTag(tagId, namespace)
                .map { list ->
                    val baseUrl = connectionStore.getConnection()?.baseUrl
                    list.map { it.toDomain(baseUrl) }
                }
        }

    override suspend fun addTagToPhoto(tagId: String, photoId: String, serverNamespace: String?) =
        tagDao.addTagToPhoto(
            PhotoTagEntity(tagId = tagId, photoId = photoId, serverNamespace = serverNamespace)
        )

    override suspend fun removeTagFromPhoto(tagId: String, photoId: String) =
        tagDao.removeTagFromPhoto(tagId, photoId)

    override suspend fun getTagsForPhoto(photo: Photo): List<Tag> {
        // 关联可能挂在时间线可见的本机行上，也可能挂在同一媒体的服务端投影行上
        //（服务端下行的关联由同步引擎挂到投影行）。
        val rowIds = linkedSetOf(photo.id)
        val namespace = activeNamespace().first()
        if (namespace.isNotBlank()) {
            photoRepository.resolveServerMediaId(photo)?.let { serverMediaId ->
                serverProjectionDao.getByMediaId(namespace, serverMediaId)?.localPhotoId?.let { rowIds += it }
            }
        }
        return rowIds.flatMap { tagDao.getTagsForPhoto(it) }
            .distinctBy { it.id }
            .map { it.toDomain() }
    }
}

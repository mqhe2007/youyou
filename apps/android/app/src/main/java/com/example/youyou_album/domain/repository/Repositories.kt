package com.example.youyou_album.domain.repository

import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface PhotoRepository {
    fun observeAll(): Flow<List<Photo>>
    suspend fun getAll(): List<Photo>
    suspend fun getById(id: String): Photo?
    suspend fun getBySourceUri(sourceUri: String): Photo?
    suspend fun upsert(photo: Photo)
    suspend fun upsertAll(photos: List<Photo>)
    suspend fun deleteById(id: String)
    suspend fun deletePhotos(ids: List<String>)
    suspend fun count(): Int
    suspend fun countMissingContentHash(): Int
    suspend fun getByContentHash(hash: String): Photo?
    suspend fun getByNameAndSize(name: String, size: Long): List<Photo>
    suspend fun getByServerMediaId(namespace: String, serverMediaId: String): Photo?
    suspend fun deleteServerProjection(namespace: String, serverMediaId: String)
    suspend fun toggleFavorite(id: String, isFavorite: Boolean)
    suspend fun getFavorites(): List<Photo>

    fun observeFavorites(): kotlinx.coroutines.flow.Flow<List<Photo>>

    /**
     * 合并时间线（本机索引全量 + 当前服务端身份的投影，按哈希去重展示），
     * filter 为 null 表示全部。服务端投影仅在属于当前 `server_namespace` 时可见。
     */
    fun observeTimeline(filter: MediaSyncDisplay?): kotlinx.coroutines.flow.Flow<List<Photo>>

    /** 时间线的当前可见集合（不筛选），供查看器/幻灯片翻页，保证与网格同源。 */
    suspend fun getTimeline(): List<Photo>

    /** 解析媒体对应的服务端媒体 ID（收藏推送、内容下载用）；无服务端副本返回 null。 */
    suspend fun resolveServerMediaId(photo: Photo): String?
    suspend fun getVideos(): List<Photo>
}

interface TagRepository {
    fun observeAll(): Flow<List<Tag>>
    suspend fun getById(id: String): Tag?
    suspend fun getLocalByName(name: String): Tag?
    suspend fun upsert(tag: Tag)
    suspend fun deleteById(id: String)
    fun observePhotosByTag(tagId: String): Flow<List<Photo>>
    suspend fun addTagToPhoto(tagId: String, photoId: String, serverNamespace: String? = null)
    suspend fun removeTagFromPhoto(tagId: String, photoId: String)
}

interface TaskRepository {
    fun observeAll(): Flow<List<AppTask>>
    fun observeActive(): Flow<List<AppTask>>
    suspend fun getById(id: String): AppTask?
    suspend fun upsert(task: AppTask)
    suspend fun updateStatus(id: String, status: String, message: String?, updatedAt: Long)
    suspend fun updateProgress(id: String, current: Int, total: Int?, indeterminate: Boolean, phase: String?, updatedAt: Long)
    suspend fun deleteById(id: String)
}

package com.example.youyou_album.presentation.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.Tag
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TagRepository
import com.example.youyou_album.service.ServerConnectionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TagViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val photoRepository: PhotoRepository,
    mediaDeletionService: com.example.youyou_album.service.MediaDeletionService,
    connectionStore: ServerConnectionStore,
) : ViewModel() {

    /** 删除入口：本机系统授权 + 远程删除由协调器分步完成。 */
    val mediaDelete = com.example.youyou_album.presentation.common.MediaDeleteController(
        mediaDeletionService,
        viewModelScope,
    )

    /** 是否已绑定服务端（用于删除确认文案区分在线/离线后果）。 */
    val serverConnected: StateFlow<Boolean> = connectionStore.connectionFlow
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val tags: StateFlow<List<Tag>> = tagRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createTag(name: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            tagRepository.upsert(
                Tag(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            tagRepository.deleteById(tagId)
        }
    }

    fun getPhotosForTag(tagId: String): StateFlow<List<Photo>> =
        tagRepository.observePhotosByTag(tagId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removePhotosFromTag(tagId: String, photoIds: List<String>) {
        viewModelScope.launch {
            for (photoId in photoIds) {
                tagRepository.removeTagFromPhoto(tagId, photoId)
            }
        }
    }

    fun deletePhotos(photoIds: List<String>) {
        mediaDelete.delete(photoIds)
    }
}

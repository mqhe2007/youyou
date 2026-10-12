package com.example.youyou_album.presentation.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.Tag
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TagRepository
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.service.ServerTagService
import com.example.youyou_album.service.ServerWriteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TagViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val photoRepository: PhotoRepository,
    private val serverTagService: ServerTagService,
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun createTag(name: String) {
        viewModelScope.launch {
            // 在线：服务端标签（跨端可见）；离线：本机标签（只在本机）。
            when (val result = serverTagService.createTag(name)) {
                is ServerWriteResult.Success -> Unit
                ServerWriteResult.Offline -> upsertLocalTag(name)
                is ServerWriteResult.Failure -> _message.value = result.message
            }
        }
    }

    private suspend fun upsertLocalTag(name: String) {
        val now = System.currentTimeMillis()
        tagRepository.upsert(
            Tag(
                id = UUID.randomUUID().toString(),
                name = name,
                createdAt = now,
                updatedAt = now,
                sourceType = "local",
            )
        )
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            val tag = tagRepository.getById(tagId) ?: return@launch
            if (tag.sourceType != "server" || tag.serverRemoteId.isNullOrBlank()) {
                tagRepository.deleteById(tagId)
                return@launch
            }
            when (val result = serverTagService.deleteTag(tag)) {
                is ServerWriteResult.Success -> Unit
                ServerWriteResult.Offline -> _message.value = "未连接服务端，暂时无法删除服务端标签"
                is ServerWriteResult.Failure -> _message.value = result.message
            }
        }
    }

    fun getPhotosForTag(tagId: String): StateFlow<List<Photo>> =
        tagRepository.observePhotosByTag(tagId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removePhotosFromTag(tagId: String, photoIds: List<String>) {
        viewModelScope.launch {
            val tag = tagRepository.getById(tagId) ?: return@launch
            var offlineBlocked = false
            var firstFailure: String? = null
            for (photoId in photoIds) {
                val photo = photoRepository.getById(photoId) ?: continue
                when (val result = serverTagService.removeTagFromPhoto(tag, photo)) {
                    is ServerWriteResult.Success -> Unit
                    ServerWriteResult.Offline -> offlineBlocked = true
                    is ServerWriteResult.Failure -> if (firstFailure == null) firstFailure = result.message
                }
            }
            _message.value = firstFailure
                ?: if (offlineBlocked) "未连接服务端，部分移除未生效" else null
        }
    }

    fun deletePhotos(photoIds: List<String>, scope: com.example.youyou_album.service.MediaDeletionService.DeleteScope) {
        mediaDelete.delete(photoIds, scope)
    }
}

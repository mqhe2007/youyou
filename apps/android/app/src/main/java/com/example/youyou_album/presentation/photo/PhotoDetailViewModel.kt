package com.example.youyou_album.presentation.photo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.model.LiveMotionSource
import com.example.youyou_album.domain.model.resolveLiveMotionSource
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.service.ApiServiceFactory
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.service.TransferTaskLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class PhotoDetailUiState(
    val photos: List<Photo> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = true,
)

@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val photoRepository: PhotoRepository,
    private val tagRepository: com.example.youyou_album.domain.repository.TagRepository,
    private val serverTagService: com.example.youyou_album.service.ServerTagService,
    private val apiServiceFactory: ApiServiceFactory,
    private val serverConnectionStore: ServerConnectionStore,
    private val mediaDownloadService: com.example.youyou_album.service.MediaDownloadService,
    private val mediaDeletionService: com.example.youyou_album.service.MediaDeletionService,
    private val tokenProvider: TokenProvider,
    private val transferLauncher: TransferTaskLauncher,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** 删除入口：本机系统授权 + 远程删除由协调器分步完成。 */
    val mediaDelete = com.example.youyou_album.presentation.common.MediaDeleteController(
        mediaDeletionService,
        viewModelScope,
    )

    /** 是否已绑定服务端（用于删除确认文案区分在线/离线后果）。 */
    val serverConnected: StateFlow<Boolean> = serverConnectionStore.connectionFlow
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val initialPhotoId: String = savedStateHandle.get<String>("photoId") ?: ""

    private val _uiState = MutableStateFlow(PhotoDetailUiState())
    val uiState: StateFlow<PhotoDetailUiState> = _uiState.asStateFlow()

    /** 当前照片已属标签（含挂在服务端投影行上的关联）。 */
    private val _photoTags = MutableStateFlow<List<com.example.youyou_album.domain.model.Tag>>(emptyList())
    val photoTags: StateFlow<List<com.example.youyou_album.domain.model.Tag>> = _photoTags.asStateFlow()

    /** 全部标签，供选择弹窗。 */
    val allTags: StateFlow<List<com.example.youyou_album.domain.model.Tag>> =
        tagRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _tagMessage = MutableStateFlow<String?>(null)
    val tagMessage: StateFlow<String?> = _tagMessage.asStateFlow()

    fun clearTagMessage() {
        _tagMessage.value = null
    }

    private var lastTagPhotoId: String? = null

    init {
        viewModelScope.launch {
            photoRepository.observeTimeline(null).collect { allPhotos ->
                _uiState.value = _uiState.value.refreshPhotos(allPhotos, initialPhotoId)
                // 翻页后同步刷新当前照片的标签。
                val currentId = currentPhoto()?.id
                if (currentId != lastTagPhotoId) {
                    lastTagPhotoId = currentId
                    refreshPhotoTags()
                }
            }
        }
    }

    fun refreshPhotoTags() {
        viewModelScope.launch {
            _photoTags.value = currentPhoto()?.let { photo ->
                runCatching { tagRepository.getTagsForPhoto(photo) }.getOrDefault(emptyList())
            } ?: emptyList()
        }
    }

    /** 在信息面板/弹窗里切换某个标签与当前照片的关联。 */
    fun togglePhotoTag(tag: com.example.youyou_album.domain.model.Tag) {
        val photo = currentPhoto() ?: return
        val attached = _photoTags.value.any { it.id == tag.id }
        viewModelScope.launch {
            val result = if (attached) {
                serverTagService.removeTagFromPhoto(tag, photo)
            } else {
                serverTagService.addTagToPhoto(tag, photo)
            }
            when (result) {
                is com.example.youyou_album.service.ServerWriteResult.Success -> Unit
                com.example.youyou_album.service.ServerWriteResult.Offline ->
                    _tagMessage.value =
                        if (attached) "未连接服务端，暂时无法移除该标签" else "未连接服务端，无法添加到服务端标签"
                is com.example.youyou_album.service.ServerWriteResult.Failure ->
                    _tagMessage.value = result.message
            }
            refreshPhotoTags()
        }
    }

    fun setCurrentIndex(index: Int, displayedPhotos: List<Photo>) {
        // Ignore pager callbacks belonging to a list that Room has already replaced.
        if (_uiState.value.photos !== displayedPhotos || index !in displayedPhotos.indices) return
        _uiState.value = _uiState.value.copy(currentIndex = index)
    }

    fun currentPhoto(): Photo? = _uiState.value.photos.getOrNull(_uiState.value.currentIndex)

    /**
     * 实况动态部分的播放来源（D4：长按或点按标记触发）。
     *
     * 本机原件优先（离线可播放、`content://` 不带令牌），否则走远程内容；两者都不可得时
     * 返回 `null`，详情页保持静态封面并给出可理解状态。
     */
    suspend fun liveMotionSource(photo: Photo): LiveMotionSource? {
        val baseUrl = serverConnectionStore.getConnection()?.baseUrl?.trimEnd('/')
        // 本机动态部分不能从时间线列表找：时间线按设计不展示配对的动态部分行（FR-6），
        // 必须查全量本机行。
        val localByHash = photoRepository.getAll()
            .asSequence()
            .filter { it.sourceType != "server" }
            .mapNotNull { row -> row.contentHash?.let { it to row.sourceUri } }
            .toMap()
        val localById = photoRepository.getAll()
            .asSequence()
            .filter { it.sourceType != "server" }
            .mapNotNull { row -> row.sourceUri?.let { row.id to it } }
            .toMap()
        return resolveLiveMotionSource(
            photo,
            localUriByHash = { hash -> localByHash[hash] },
            remoteContentUrlFor = { partnerId ->
                baseUrl?.let { base -> "$base/api/v1/media/$partnerId/content" }
            },
            localUriById = { id -> localById[id] },
        )
    }

    fun videoRequestHeaders(photo: Photo): Map<String, String> =
        videoPlaybackHeaders(
            hasLocalSource = photo.sourceUri != null,
            hasRemoteContent = photo.remoteContentUrl != null,
            token = tokenProvider.getToken(),
        )

    fun deleteCurrentPhoto(scope: com.example.youyou_album.service.MediaDeletionService.DeleteScope) {
        val photo = currentPhoto() ?: return
        mediaDelete.delete(listOf(photo.id), scope)
    }

    fun toggleFavorite() {
        val photo = currentPhoto() ?: return
        val newFavorite = !photo.isFavorite
        viewModelScope.launch {
            photoRepository.toggleFavorite(photo.id, newFavorite)
            // 实时更新本地状态以立即反馈
            _uiState.value = _uiState.value.copy(
                photos = _uiState.value.photos.map {
                    if (it.id == photo.id) it.copy(isFavorite = newFavorite) else it
                },
            )
            // 收藏以服务端为事实来源：有服务端副本时推送；失败静默，等下次同步纠正
            try {
                val serverMediaId = photoRepository.resolveServerMediaId(photo)
                    ?: return@launch
                val connection = serverConnectionStore.getConnection() ?: return@launch
                apiServiceFactory.create(connection.baseUrl).setFavorite(
                    serverMediaId,
                    com.example.youyou_album.data.api.dto.FavoriteUpdateRequestDto(newFavorite),
                )
            } catch (_: Exception) {
            }
        }
    }

    /** 仅远程照片：落盘到系统相册。 */
    suspend fun saveToGallery(): String {
        val photo = currentPhoto() ?: return "照片不存在"
        return mediaDownloadService.downloadToGallery(photo)
    }

    /**
     * 仅本机照片：交上传前台服务同步到远程。
     * 未连接服务端时先返回提示，避免点了之后只有通知、页面无反馈。
     */
    suspend fun syncToServer(): String {
        val photo = currentPhoto() ?: return "照片不存在"
        return transferLauncher.launch("upload", listOf(photo)) ?: "正在上传到服务器…"
    }
}

/**
 * 仅远程视频（无本机原件）由 ExoPlayer 直接拉服务端内容，不经过 OkHttp 拦截器，
 * 因此必须自行补齐客户端请求头：
 * - `Authorization`：服务端媒体内容接口要求设备 Bearer 令牌，缺失会 401。
 * - `X-Youyou-Client-Version`：服务端版本门禁要求，缺失会 426 upgrade_required。
 */
internal fun videoPlaybackHeaders(
    hasLocalSource: Boolean,
    hasRemoteContent: Boolean,
    token: String?,
): Map<String, String> {
    if (hasLocalSource || !hasRemoteContent) return emptyMap()
    return buildMap {
        token?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        put("X-Youyou-Client-Version", YouyouApiService.CLIENT_VERSION)
    }
}

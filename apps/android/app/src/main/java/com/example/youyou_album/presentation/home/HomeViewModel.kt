package com.example.youyou_album.presentation.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TaskRepository
import com.example.youyou_album.service.ContentHashService
import com.example.youyou_album.service.DownloadForegroundService
import com.example.youyou_album.service.MediaScanService
import com.example.youyou_album.service.MediaScanWorker
import com.example.youyou_album.service.MediaDeletionService
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.service.ServerSyncService
import com.example.youyou_album.service.ApiServiceFactory
import com.example.youyou_album.service.UploadForegroundService
import com.example.youyou_album.service.UploadService
import com.example.youyou_album.data.db.dao.ServerSyncStateDao
import com.example.youyou_album.presentation.common.ServerReachability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class HomeUiState(
    val photos: List<Photo> = emptyList(),
    val isScanning: Boolean = false,
    val scanMessage: String? = null,
    val selectedPhotoIds: Set<String> = emptySet(),
    val isMultiSelect: Boolean = false,
    val isInitialLoading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val mediaScanService: MediaScanService,
    private val serverConnectionStore: ServerConnectionStore,
    private val serverSyncService: ServerSyncService,
    private val serverSyncStateDao: ServerSyncStateDao,
    private val apiServiceFactory: ApiServiceFactory,
    private val uploadService: UploadService,
    private val taskRepository: TaskRepository,
    private val contentHashService: ContentHashService,
    mediaDeletionService: MediaDeletionService,
    application: Application,
) : AndroidViewModel(application) {

    /** 删除入口：本机系统授权 + 远程删除由协调器分步完成。 */
    val mediaDelete = com.example.youyou_album.presentation.common.MediaDeleteController(
        mediaDeletionService,
        viewModelScope,
    )

    /** 备份状态筛选；null 表示全部。 */
    val backupFilter = MutableStateFlow<MediaSyncDisplay?>(null)

    val photos: StateFlow<List<Photo>> = backupFilter
        .flatMapLatest { f -> photoRepository.observeTimeline(f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _reachability = MutableStateFlow(ServerReachability.UNBOUND)

    val trustState: StateFlow<HomeTrustState> = combine(
        serverConnectionStore.connectionFlow,
        serverSyncStateDao.observe(),
        _reachability,
    ) { connection, syncState, reachability ->
        HomeTrustState(
            reachability = if (connection == null) ServerReachability.UNBOUND else reachability,
            lastSyncedAt = syncState?.takeIf { it.serverNamespace.isNotBlank() }?.updatedAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeTrustState())

    fun setBackupFilter(filter: MediaSyncDisplay?) {
        backupFilter.value = filter
    }

    /** 批量下载选中的「仅远程」媒体到本机。 */
    fun downloadSelected(photoIds: Set<String>) {
        if (photoIds.isNotEmpty()) {
            DownloadForegroundService.start(getApplication(), photoIds)
        }
    }

    /** 正在进行和需要处理的任务数量，用于首页后台活动入口的角标提醒。 */
    val activityTaskCount: StateFlow<Int> = taskRepository.observeAll()
        .map { tasks ->
            tasks.count {
                it.status == "running" || it.status == "pending" || it.status == "failed"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var remoteSyncJob: Job? = null

    /** 远程清单不依赖本机照片权限；进入前台和手动刷新共用此入口。 */
    fun refreshRemotePhotos(reportFailure: Boolean = false) {
        if (remoteSyncJob?.isActive == true) return
        remoteSyncJob = viewModelScope.launch {
            val connection = serverConnectionStore.getConnection() ?: return@launch
            _reachability.value = ServerReachability.CHECKING
            try {
                serverSyncService.sync(connection.baseUrl)
                _reachability.value = ServerReachability.ONLINE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reachability.value = ServerReachability.OFFLINE
                if (reportFailure) {
                    _uiState.value = _uiState.value.copy(scanMessage = "更新失败，请检查连接")
                }
            }
        }
    }

    /** 用户主动刷新（首页「更多 → 刷新」），保留成功与失败反馈。 */
    fun refreshTimeline() {
        refreshRemotePhotos(reportFailure = true)
        if (hasStoragePermission()) refreshPhotos(notify = true)
    }

    init {
        observeConnectionHealth()
        loadPhotos()
    }

    private fun observeConnectionHealth() {
        viewModelScope.launch {
            serverConnectionStore.connectionFlow.collectLatest { connection ->
                if (connection == null) {
                    _reachability.value = ServerReachability.UNBOUND
                    return@collectLatest
                }
                _reachability.value = ServerReachability.CHECKING
                _reachability.value = try {
                    apiServiceFactory.create(connection.baseUrl).health()
                    ServerReachability.ONLINE
                } catch (_: Exception) {
                    ServerReachability.OFFLINE
                }
            }
        }
    }

    fun loadPhotos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isInitialLoading = true, error = null)
            try {
                // 等待第一次数据发射后标记加载完成
                photos.first()
                _uiState.value = _uiState.value.copy(isInitialLoading = false)
                // 启动时自动扫描媒体库（生成缩略图等）；后台动作静默，不弹提示条
                refreshPhotos()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isInitialLoading = false,
                    error = "加载失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    fun retry() {
        loadPhotos()
    }

    /**
     * 触发一次本机媒体扫描。
     *
     * [notify] 只在用户主动发起时置 true（手动刷新、点击「扫描媒体」）。
     * 启动自动扫描、权限授予后的自动扫描等后台动作静默执行，避免每次进首页都弹提示条。
     */
    fun refreshPhotos(notify: Boolean = false) {
        // 无存储权限时不触发扫描，避免空扫和异常
        if (!hasStoragePermission()) {
            if (notify) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    scanMessage = "请先授予存储权限",
                )
            }
            return
        }

        val taskId = java.util.UUID.randomUUID().toString()
        val inputData = Data.Builder()
            .putString(MediaScanWorker.TASK_ID_KEY, taskId)
            .build()

        val scanRequest = OneTimeWorkRequestBuilder<MediaScanWorker>()
            .setInputData(inputData)
            .addTag("media_scan")
            .build()

        WorkManager.getInstance(getApplication()).enqueue(scanRequest)

        _uiState.value = _uiState.value.copy(isScanning = true)
        if (notify) {
            _uiState.value = _uiState.value.copy(scanMessage = "正在扫描…")
        }
    }

    private fun hasStoragePermission(): Boolean {
        val context = getApplication<Application>()
        fun granted(permission: String): Boolean =
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        return when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                (granted(android.Manifest.permission.READ_MEDIA_IMAGES) &&
                    granted(android.Manifest.permission.READ_MEDIA_VIDEO)) ||
                    granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU ->
                granted(android.Manifest.permission.READ_MEDIA_IMAGES) &&
                    granted(android.Manifest.permission.READ_MEDIA_VIDEO)
            else -> granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun togglePhotoSelection(photoId: String) {
        val current = _uiState.value.selectedPhotoIds
        val newSet = if (photoId in current) current - photoId else current + photoId
        _uiState.value = _uiState.value.copy(
            selectedPhotoIds = newSet,
            isMultiSelect = newSet.isNotEmpty(),
        )
    }

    fun setSelectedPhotos(photoIds: Set<String>) {
        _uiState.value = _uiState.value.copy(
            selectedPhotoIds = photoIds,
            isMultiSelect = photoIds.isNotEmpty(),
        )
    }

    fun toggleSelectAll(allIds: List<String>) {
        val current = _uiState.value.selectedPhotoIds
        val allSelected = allIds.isNotEmpty() && allIds.size == current.size && allIds.all { it in current }
        _uiState.value = _uiState.value.copy(
            selectedPhotoIds = if (allSelected) emptySet() else allIds.toSet(),
            isMultiSelect = !allSelected,
        )
    }

    fun enterMultiSelect(initialId: String? = null) {
        _uiState.value = _uiState.value.copy(
            isMultiSelect = true,
            selectedPhotoIds = if (initialId != null) setOf(initialId) else emptySet(),
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPhotoIds = emptySet(), isMultiSelect = false)
    }

    fun deleteSelected(ids: Set<String>) {
        mediaDelete.delete(ids.toList())
        clearSelection()
    }

    /**
     * 启动前台服务上传选中的照片到服务端。
     * 上传在前台服务中执行，显示通知进度，不受页面生命周期影响。
     */
    fun syncSelectedToServer(photoIds: Set<String>) {
        UploadForegroundService.start(getApplication(), photoIds)
    }

    fun clearScanMessage() {
        _uiState.value = _uiState.value.copy(scanMessage = null)
    }
}

package com.example.youyou_album.presentation.task

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.repository.TaskRepository
import com.example.youyou_album.service.UploadForegroundService
import com.example.youyou_album.service.DownloadForegroundService
import com.example.youyou_album.service.SecureStorageService
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.service.TransferTaskPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TaskCenterViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val connectionStore: ServerConnectionStore,
    private val secureStorageService: SecureStorageService,
    application: Application,
) : AndroidViewModel(application) {

    init {
        viewModelScope.launch {
            taskRepository.cleanupRecentResults(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        }
    }

    val runningTasks: StateFlow<List<AppTask>> = taskRepository.observeAll()
        .map { tasks -> tasks.filter { it.status == "running" || it.status == "pending" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val attentionTasks: StateFlow<List<AppTask>> = taskRepository.observeAll()
        .map { tasks -> tasks.filter { it.status == "failed" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTasks: StateFlow<List<AppTask>> = taskRepository.observeAll()
        .map { tasks -> tasks.filter { it.status == "completed" || it.status == "cancelled" }.take(50) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 停止上传前台服务，并让已取消任务退出活动页。 */
    fun cancelTask(task: AppTask) {
        if ((task.status != "running" && task.status != "pending") || task.kind == "scan") return

        val context = getApplication<Application>()
        val service = if (task.kind == "download") DownloadForegroundService::class.java else UploadForegroundService::class.java
        context.stopService(Intent(context, service))

        viewModelScope.launch {
            val finishedAt = System.currentTimeMillis()
            taskRepository.updateStatus(
                id = task.id,
                status = "cancelled",
                message = "已取消",
                updatedAt = finishedAt,
            )
            taskRepository.getById(task.id)?.let { current ->
                val payload = TransferTaskPayload.read(current.payload)
                val cancelled = payload?.copy(items = payload.items.map {
                    if (it.status == "pending") it.copy(status = "cancelled") else it
                })
                taskRepository.upsert(current.copy(
                    finishedAt = finishedAt,
                    payload = cancelled?.let(TransferTaskPayload::write) ?: current.payload,
                ))
            }
        }
    }

    suspend fun retryTask(task: AppTask): String {
        if (task.status != "failed") return "任务状态已更新"
        val current = taskRepository.getById(task.id) ?: return "任务记录已不存在"
        val payload = TransferTaskPayload.read(current.payload)
        if (task.kind == "scan") return "请在时间线中刷新；扫描记录已保留"
        if (task.kind == "delete") return "请核对手机和服务器的实际位置后重新发起移除；原记录已保留"
        if (payload == null || payload.identity.isBlank()) return "旧任务没有可重放上下文，请重新选择照片"
        val identity = TransferTaskPayload.identity(connectionStore.getConnection(), secureStorageService.getDeviceId())
        if (identity != payload.identity) return "当前账号与任务目标不同，请切回原账号或重新选择照片"
        if (payload.retryIds.isEmpty()) return "没有失败或未完成项可重试"
        return when (task.kind) {
            "upload" -> {
                val inaccessible = withContext(Dispatchers.IO) {
                    payload.items.filter { it.photoId in payload.retryIds }.firstNotNullOfOrNull { item ->
                        val source = item.sourceUri ?: return@firstNotNullOfOrNull "源文件已不存在，请重新选择照片；原记录已保留"
                        try {
                            getApplication<Application>().contentResolver.openFileDescriptor(Uri.parse(source), "r")
                                ?.use { } ?: return@firstNotNullOfOrNull "源文件无法打开，请重新选择照片；原记录已保留"
                            null
                        } catch (_: SecurityException) {
                            "照片访问权限已撤回，请重新授权后重试；原记录已保留"
                        } catch (_: java.io.FileNotFoundException) {
                            "源文件已不存在，请重新选择照片；原记录已保留"
                        }
                    }
                }
                if (inaccessible != null) return inaccessible
                UploadForegroundService.start(getApplication(), payload.retryIds, task.id)
                "正在重试失败的上传项"
            }
            "download" -> {
                DownloadForegroundService.start(getApplication(), payload.retryIds, task.id)
                "正在重试失败的下载项"
            }
            else -> "请重新发起操作；记录已保留"
        }
    }

    fun dismissTask(task: AppTask) {
        if (task.status != "failed") return
        viewModelScope.launch {
            taskRepository.deleteById(task.id)
        }
    }
}

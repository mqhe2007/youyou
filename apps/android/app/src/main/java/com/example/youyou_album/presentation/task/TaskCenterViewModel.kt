package com.example.youyou_album.presentation.task

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.repository.TaskRepository
import com.example.youyou_album.service.UploadForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskCenterViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    application: Application,
) : AndroidViewModel(application) {

    /**
     * 任务中心是运行监视器，不是历史记录：完成和取消的任务立即退出页面。
     * 失败任务会保留，直到用户重试或忽略。
     */
    val runningTasks: StateFlow<List<AppTask>> = taskRepository.observeAll()
        .map { tasks -> tasks.filter { it.status == "running" || it.status == "pending" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val attentionTasks: StateFlow<List<AppTask>> = taskRepository.observeAll()
        .map { tasks -> tasks.filter { it.status == "failed" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 停止上传前台服务，并让已取消任务退出活动页。 */
    fun cancelTask(task: AppTask) {
        if (task.status != "running" && task.status != "pending") return

        val context = getApplication<Application>()
        context.stopService(Intent(context, UploadForegroundService::class.java))

        viewModelScope.launch {
            val finishedAt = System.currentTimeMillis()
            taskRepository.updateStatus(
                id = task.id,
                status = "cancelled",
                message = "已取消",
                updatedAt = finishedAt,
            )
            taskRepository.getById(task.id)?.let {
                taskRepository.upsert(it.copy(finishedAt = finishedAt))
            }
        }
    }

    /**
     * 失败任务暂未保存足够的重试上下文；给出下一步后将其从待处理区移除，
     * 避免同一条失败记录长期滞留。
     */
    fun retryTask(task: AppTask): String {
        if (task.status != "failed") return "任务状态已更新"
        dismissTask(task)
        return when (task.kind) {
            "upload" -> "请重新选择照片后同步"
            "download" -> "请重新选择照片后下载"
            "scan" -> "请在时间线中刷新"
            else -> "请重新发起操作"
        }
    }

    fun dismissTask(task: AppTask) {
        if (task.status != "failed") return
        viewModelScope.launch {
            taskRepository.deleteById(task.id)
        }
    }
}

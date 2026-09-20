package com.example.youyou_album.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.repository.TaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

@HiltWorker
class MediaScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaScanService: MediaScanService,
    private val taskRepository: TaskRepository,
) : CoroutineWorker(context, params) {

    companion object {
        const val TASK_ID_KEY = "task_id"
        private const val TAG = "MediaScanWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork started")
        val taskId = inputData.getString(TASK_ID_KEY) ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 创建任务记录
        val task = AppTask(
            id = taskId,
            kind = "scan",
            title = "扫描媒体库",
            status = "running",
            message = "正在扫描...",
            indeterminate = true,
            createdAt = now,
            updatedAt = now,
        )
        taskRepository.upsert(task)
        Log.d(TAG, "Task record created: $taskId")

        return try {
            val result = mediaScanService.fullScan()
            Log.d(TAG, "Scan completed: total=${result.totalScanned}, new=${result.newPhotos}")
            val finishTime = System.currentTimeMillis()
            val message = "扫描完成：共 ${result.totalScanned} 项，新增 ${result.newPhotos}，更新 ${result.updatedPhotos}，移除 ${result.removedPhotos}"

            taskRepository.updateStatus(
                id = taskId,
                status = "completed",
                message = message,
                updatedAt = finishTime,
            )
            taskRepository.getById(taskId)?.let {
                taskRepository.upsert(it.copy(finishedAt = finishTime))
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            val finishTime = System.currentTimeMillis()
            taskRepository.updateStatus(
                id = taskId,
                status = "failed",
                message = "扫描失败: ${e.message ?: e.javaClass.simpleName}",
                updatedAt = finishTime,
            )
            taskRepository.getById(taskId)?.let {
                taskRepository.upsert(it.copy(finishedAt = finishTime))
            }
            Result.failure()
        }
    }
}

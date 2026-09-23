package com.example.youyou_album.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.youyou_album.MainActivity
import com.example.youyou_album.R
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TaskRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * 下载前台服务：把选中的「仅远程」媒体批量下载到系统相册。
 * 下载完成后下次媒体扫描会把新文件索引为本地副本，状态自动转为「已同步」。
 */
@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject lateinit var mediaDownloadService: MediaDownloadService
    @Inject lateinit var photoRepository: PhotoRepository
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var mediaTaskCoordinator: MediaTaskCoordinator
    @Inject lateinit var serverConnectionStore: ServerConnectionStore
    @Inject lateinit var secureStorageService: SecureStorageService
    @Inject lateinit var tokenProvider: TokenProvider

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val EXTRA_PHOTO_IDS = "photo_ids"
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context, photoIds: Set<String>, taskId: String? = null) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                putStringArrayListExtra(EXTRA_PHOTO_IDS, ArrayList(photoIds))
                taskId?.let { putExtra(EXTRA_TASK_ID, it) }
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val photoIds = intent?.getStringArrayListExtra(EXTRA_PHOTO_IDS)?.toSet() ?: emptySet()
        val retryTaskId = intent?.getStringExtra(EXTRA_TASK_ID)

        startForeground(NOTIFICATION_ID, buildNotification("准备下载...", 0, 0))

        serviceScope.launch {
            try {
                performDownload(photoIds, retryTaskId)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performDownload(photoIds: Set<String>, retryTaskId: String?) {
        val connection = serverConnectionStore.getConnection()
        val identity = TransferTaskPayload.identity(connection, secureStorageService.getDeviceId())
        val token = tokenProvider.getToken()
        val generation = tokenProvider.generation
        val oldTask = retryTaskId?.let { taskRepository.getById(it) }
        val oldPayload = TransferTaskPayload.read(oldTask?.payload)
        if (retryTaskId != null && (oldTask?.kind != "download" || oldPayload == null || identity.isNullOrBlank() || oldPayload.identity != identity)) {
            oldTask?.let { taskRepository.upsert(it.copy(status = "failed", message = "目标账号已变化或任务上下文缺失，请重新选择照片下载", updatedAt = System.currentTimeMillis())) }
            return
        }
        val allPhotos = photoRepository.getAll()
        val selected = allPhotos.filter { it.id in photoIds }
        val payload = oldPayload ?: TransferTaskPayload(
            identity = identity.orEmpty(),
            items = selected.map { TransferTaskItem(it.id, it.name, contentHash = it.contentHash) },
        )
        val localHashes = allPhotos.filter { photo ->
            photo.sourceType == "local" && photo.sourceUri != null && runCatching {
                contentResolver.openInputStream(Uri.parse(photo.sourceUri))?.use { true } == true
            }.getOrDefault(false)
        }.mapNotNull { it.contentHash }.toSet()
        val targets = if (oldPayload == null) selected else selected.filter { photo ->
            val item = oldPayload.items.firstOrNull { it.photoId == photo.id }
            item != null && photo.id in oldPayload.retryIds && item.contentHash == photo.contentHash &&
                (item.contentHash == null || item.contentHash !in localHashes)
        }

        if (targets.isEmpty() && retryTaskId == null) {
            updateNotification("没有可下载的媒体", 0, 0)
            return
        }

        val taskId = retryTaskId ?: java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        taskRepository.upsert(
            (oldTask ?: AppTask(
                id = taskId,
                kind = "download",
                title = "下载 ${targets.size} 项到本机",
                status = "running",
                message = "准备下载...",
                current = 0,
                total = targets.size,
                indeterminate = false,
                createdAt = now,
                updatedAt = now,
            )).copy(
                status = "running", message = "准备下载...", finishedAt = null,
                payload = TransferTaskPayload.write(payload),
                retryCount = (oldTask?.retryCount ?: 0) + if (oldTask == null) 0 else 1,
                current = 0, total = targets.size, updatedAt = now,
            )
        )

        suspend fun recordItem(photoId: String, status: String, message: String? = null) {
            taskRepository.getById(taskId)?.let { task ->
                TransferTaskPayload.read(task.payload)?.let { current ->
                    taskRepository.upsert(task.copy(payload = TransferTaskPayload.write(current.update(photoId, status, message))))
                }
            }
        }
        if (oldPayload != null) {
            val targetIds = targets.map { it.id }.toSet()
            for (item in oldPayload.items.filter { it.photoId in oldPayload.retryIds && it.photoId !in targetIds }) {
                if (item.contentHash != null && item.contentHash in localHashes) recordItem(item.photoId, "succeeded")
                else recordItem(item.photoId, "failed", "媒体已不存在或发生变化，请重新选择照片")
            }
        }

        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val failReasons = Collections.synchronizedList(mutableListOf<String>())

        targets.forEachIndexed { index, photo ->
            taskRepository.updateProgress(
                id = taskId,
                current = index,
                total = targets.size,
                indeterminate = false,
                phase = "正在下载: ${photo.name}",
                updatedAt = System.currentTimeMillis(),
            )
            updateNotification("正在下载: ${photo.name}", index, targets.size)

            if (mediaTaskCoordinator.isDeleting(photo.id)) {
                failCount.incrementAndGet()
                failReasons.add("${photo.name}: 正在删除，已取消")
                recordItem(photo.id, "failed", "正在删除，稍后再试")
                return@forEachIndexed
            }
            if (connection == null || token == null || identity == null ||
                generation != tokenProvider.generation ||
                identity != TransferTaskPayload.identity(serverConnectionStore.getConnection(), secureStorageService.getDeviceId())) {
                failReasons.add("${photo.name}: 连接或账号已变化，请重新连接后重试")
                recordItem(photo.id, "failed", "连接或账号已变化")
                return@forEachIndexed
            }
            val result = mediaDownloadService.downloadToGallery(photo, connection.baseUrl, "Bearer $token")
            if (result == "已保存到系统相册" || result.startsWith("已保存到系统相册（实况照片：")) {
                successCount.incrementAndGet()
                recordItem(photo.id, "succeeded")
            } else {
                failCount.incrementAndGet()
                failReasons.add("${photo.name}: $result")
                recordItem(photo.id, "failed", result)
            }
        }

        val finishTime = System.currentTimeMillis()
        val finalPayload = TransferTaskPayload.read(taskRepository.getById(taskId)?.payload) ?: payload
        val success = finalPayload.succeeded
        val fail = finalPayload.failed + finalPayload.unfinished
        val status = if (fail == 0) "completed" else "failed"
        var message = "下载${if (fail == 0) "全部" else "部分"}完成：成功 $success，失败 $fail（共 ${finalPayload.items.size}）"
        if (failReasons.isNotEmpty()) {
            message += "\n" + failReasons.joinToString("\n")
        }

        taskRepository.updateProgress(
            id = taskId,
            current = finalPayload.items.size,
            total = finalPayload.items.size,
            indeterminate = false,
            phase = message,
            updatedAt = finishTime,
        )
        taskRepository.updateStatus(
            id = taskId,
            status = status,
            message = message,
            updatedAt = finishTime,
        )
        taskRepository.getById(taskId)?.let {
            taskRepository.upsert(it.copy(finishedAt = finishTime))
        }
        taskRepository.cleanupRecentResults(finishTime - 7L * 24 * 60 * 60 * 1000)

        updateNotification(message, finalPayload.items.size, finalPayload.items.size)
    }

    private fun buildNotification(title: String, current: Int, total: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, NotificationChannels.CHANNEL_DOWNLOAD)
            .setContentTitle("柚柚相册")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(total, current, total == 0)

        if (total > 0) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText("$title\n$current / $total")
            )
        }

        return builder.build()
    }

    private fun updateNotification(title: String, current: Int, total: Int) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, current, total))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

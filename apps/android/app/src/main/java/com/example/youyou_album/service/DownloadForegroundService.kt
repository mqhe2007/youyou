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
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TaskRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context, taskId: String) {
            context.startForegroundService(buildIntent(context, taskId))
        }

        /** 执行内容全部来自 tasks_table.payload，Intent 不带任何列表，体积恒定。 */
        internal fun buildIntent(context: Context, taskId: String): Intent =
            Intent(context, DownloadForegroundService::class.java).putExtra(EXTRA_TASK_ID, taskId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)

        startForeground(NOTIFICATION_ID, buildNotification("准备下载...", 0, 0))

        // 先 startForeground 再判空：否则 startForegroundService 起的服务 5 秒内没前台通知会被系统判 ANR。
        if (taskId == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                performDownload(taskId)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performDownload(taskId: String) {
        val connection = serverConnectionStore.getConnection()
        val identity = TransferTaskPayload.identity(connection, secureStorageService.getDeviceId())
        val token = tokenProvider.getToken()
        val generation = tokenProvider.generation

        val task = taskRepository.getById(taskId)
        val stored = TransferTaskPayload.read(task?.payload)
        if (task?.kind != "download" || stored == null || identity.isNullOrBlank() || stored.identity != identity) {
            taskRepository.updateStatus(taskId, "failed", "目标账号已变化或任务上下文缺失，请重新选择照片下载", System.currentTimeMillis())
            return
        }

        val photoIds = stored.items.mapTo(HashSet()) { it.photoId }
        // shortcut: 整表加载，索引到 5 万量级时内存吃紧，届时改为按 id 分批查询
        val allPhotos = photoRepository.getAll()
        val selected = allPhotos.filter { it.id in photoIds }
        val localHashes = allPhotos.filter { photo ->
            photo.sourceType == "local" && photo.sourceUri != null && runCatching {
                contentResolver.openInputStream(Uri.parse(photo.sourceUri))?.use { true } == true
            }.getOrDefault(false)
        }.mapNotNull { it.contentHash }.toSet()
        val itemByPhotoId = stored.items.associateBy { it.photoId }
        val retryIds = stored.retryIds
        val targets = selected.filter { photo ->
            val item = itemByPhotoId[photo.id]
            item != null && photo.id in retryIds && item.contentHash == photo.contentHash &&
                (item.contentHash == null || item.contentHash !in localHashes)
        }

        val now = System.currentTimeMillis()

        taskRepository.upsert(
            task.copy(
                title = "下载 ${targets.size} 项到本机",
                status = "running", message = "准备下载...", finishedAt = null,
                payload = TransferTaskPayload.write(stored),
                retryCount = task.retryCount + if (task.status == "failed") 1 else 0,
                current = 0, total = targets.size, updatedAt = now,
            )
        )

        if (targets.isEmpty()) {
            val message = "没有可下载的媒体"
            updateNotification(message, 0, 0)
            taskRepository.updateStatus(taskId, "failed", message, System.currentTimeMillis())
            return
        }

        // 每条状态先留在内存、结束后一次性写回 payload，避免每项都整串 decode/encode。
        val itemStates = ConcurrentHashMap<String, Pair<String, String?>>()
        fun recordItem(photoId: String, status: String, message: String? = null) {
            itemStates[photoId] = status to message
        }
        val targetIds = targets.mapTo(HashSet()) { it.id }
        for (item in stored.items.filter { it.photoId in retryIds && it.photoId !in targetIds }) {
            if (item.contentHash != null && item.contentHash in localHashes) recordItem(item.photoId, "succeeded")
            else recordItem(item.photoId, "failed", "媒体已不存在或发生变化，请重新选择照片")
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
        val finalPayload = stored.copy(
            items = stored.items.map { item ->
                val state = itemStates[item.photoId]
                item.copy(status = state?.first ?: item.status, message = state?.second ?: item.message)
            },
        )
        val success = finalPayload.succeeded
        val fail = finalPayload.failed + finalPayload.unfinished
        val status = if (fail == 0) "completed" else "failed"
        var message = "下载${if (fail == 0) "全部" else "部分"}完成：成功 $success，失败 $fail（共 ${finalPayload.items.size}）"
        if (failReasons.isNotEmpty()) {
            message += "\n" + failReasons.joinToString("\n")
        }

        taskRepository.getById(taskId)?.let {
            taskRepository.upsert(
                it.copy(
                    status = status, message = message, phase = message,
                    payload = TransferTaskPayload.write(finalPayload),
                    current = finalPayload.items.size, total = finalPayload.items.size,
                    indeterminate = false, finishedAt = finishTime, updatedAt = finishTime,
                )
            )
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

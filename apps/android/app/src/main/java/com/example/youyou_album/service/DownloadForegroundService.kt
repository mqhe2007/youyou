package com.example.youyou_album.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.youyou_album.MainActivity
import com.example.youyou_album.R
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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val EXTRA_PHOTO_IDS = "photo_ids"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context, photoIds: Set<String>) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                putStringArrayListExtra(EXTRA_PHOTO_IDS, ArrayList(photoIds))
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val photoIds = intent?.getStringArrayListExtra(EXTRA_PHOTO_IDS)?.toSet() ?: emptySet()

        startForeground(NOTIFICATION_ID, buildNotification("准备下载...", 0, 0))

        serviceScope.launch {
            try {
                performDownload(photoIds)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performDownload(photoIds: Set<String>) {
        val allPhotos = photoRepository.getAll()
        val targets = allPhotos.filter { it.id in photoIds }

        if (targets.isEmpty()) {
            updateNotification("没有可下载的媒体", 0, 0)
            return
        }

        val taskId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        taskRepository.upsert(
            AppTask(
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
            )
        )

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
                return@forEachIndexed
            }
            val result = mediaDownloadService.downloadToGallery(photo)
            if (result.startsWith("已保存")) {
                successCount.incrementAndGet()
            } else {
                failCount.incrementAndGet()
                failReasons.add("${photo.name}: $result")
            }
        }

        val finishTime = System.currentTimeMillis()
        val success = successCount.get()
        val fail = failCount.get()
        val status = if (fail == 0) "completed" else "failed"
        var message = "下载${if (fail == 0) "全部" else ""}完成：成功 $success，失败 $fail（共 ${targets.size}）"
        if (failReasons.isNotEmpty()) {
            message += "\n" + failReasons.joinToString("\n")
        }

        taskRepository.updateProgress(
            id = taskId,
            current = targets.size,
            total = targets.size,
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

        updateNotification(message, targets.size, targets.size)
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

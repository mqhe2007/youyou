package com.example.youyou_album.service

import android.app.Notification
import android.app.NotificationChannel
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
import com.example.youyou_album.domain.model.Photo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * 上传前台服务：在后台执行上传，显示通知进度。
 * 确保上传不受页面生命周期影响，离开页面也能继续上传。
 */
@AndroidEntryPoint
class UploadForegroundService : Service() {

    @Inject lateinit var uploadService: UploadService
    @Inject lateinit var photoRepository: com.example.youyou_album.domain.repository.PhotoRepository
    @Inject lateinit var taskRepository: com.example.youyou_album.domain.repository.TaskRepository
    @Inject lateinit var contentHashService: ContentHashService
    @Inject lateinit var serverConnectionStore: ServerConnectionStore
    @Inject lateinit var serverSyncService: ServerSyncService
    @Inject lateinit var mediaTaskCoordinator: MediaTaskCoordinator

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val EXTRA_PHOTO_IDS = "photo_ids"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, photoIds: Set<String>) {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                putStringArrayListExtra(EXTRA_PHOTO_IDS, ArrayList(photoIds))
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val photoIds = intent?.getStringArrayListExtra(EXTRA_PHOTO_IDS)?.toSet() ?: emptySet()

        startForeground(NOTIFICATION_ID, buildNotification("准备上传...", 0, 0))

        serviceScope.launch {
            try {
                performUpload(photoIds)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performUpload(photoIds: Set<String>) {
        val connection = serverConnectionStore.getConnection()
        if (connection == null) {
            updateNotification("未连接服务端", 0, 0)
            return
        }

        val allPhotos = photoRepository.getAll()
        val selectedPhotos = allPhotos.filter { it.id in photoIds }
        // FR-4 实况整体搬运：一段实况的静态帧与动态部分共同搬运（选中静态帧即带上本机动态部分），
        // 已在服务端的部分不重复上传——半同步态下只补传缺失的动态部分。
        val remoteHashes = allPhotos
            .filter { it.sourceType == "server" }
            .mapNotNull { it.contentHash }
            .toSet()
        val liveParts = selectedPhotos.mapNotNull { selected ->
            val live = selected.livePhoto ?: return@mapNotNull null
            if (!live.isStill || live.embedded) return@mapNotNull null
            allPhotos.firstOrNull { it.sourceType != "server" && it.id == live.partnerMediaId }
        }
        val localPhotos = (selectedPhotos + liveParts)
            .distinctBy { it.id }
            .filter { it.sourceType == "local" && it.sourceUri != null && it.contentHash !in remoteHashes }

        if (localPhotos.isEmpty()) {
            updateNotification("没有可上传的本地照片", 0, 0)
            return
        }

        val taskId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        taskRepository.upsert(
            AppTask(
                id = taskId,
                kind = "upload",
                title = "上传 ${localPhotos.size} 项到服务端",
                status = "running",
                message = "准备上传...",
                current = 0,
                total = localPhotos.size,
                indeterminate = false,
                createdAt = now,
                updatedAt = now,
            )
        )

        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val failReasons = Collections.synchronizedList(mutableListOf<String>())

        // 最多 2 个并发上传
        val semaphore = Semaphore(2)

        coroutineScope {
            localPhotos.map { photo ->
                async {
                    semaphore.withPermit {
                        // 删除中的媒体不再启动上传；登记在途供删除前等待。
                        if (!mediaTaskCoordinator.tryRegisterActive(photo.id)) return@withPermit
                        try {
                            val started = completedCount.incrementAndGet()
                            taskRepository.updateProgress(
                                id = taskId,
                                current = started - 1,
                                total = localPhotos.size,
                                indeterminate = false,
                                phase = "正在上传: ${photo.name}",
                                updatedAt = System.currentTimeMillis(),
                            )
                            updateNotification("正在上传: ${photo.name}", started - 1, localPhotos.size)

                            val contentHash = photo.contentHash ?: run {
                                val hash = contentHashService.sha256HexForUri(android.net.Uri.parse(photo.sourceUri!!))
                                    ?: throw IllegalStateException("无法计算文件哈希")
                                photoRepository.upsert(photo.copy(contentHash = hash))
                                hash
                            }

                            // 失败重试：最多 3 次，指数退避（1s, 2s）
                            var lastError: Exception? = null
                            var uploadResult: UploadService.UploadResult? = null
                            for (attempt in 0 until 3) {
                                try {
                                    uploadResult = uploadService.uploadPhoto(
                                        sourceUri = photo.sourceUri!!,
                                        fileName = photo.name,
                                        mimeType = photo.mimeType,
                                        size = photo.size ?: 0,
                                        expectedSha256 = contentHash,
                                        takenAt = photo.takenAt,
                                        sortAt = photo.sortAt,
                                        sortSource = photo.sortSource,
                                        originalName = photo.originalName,
                                        livePhoto = photo.livePhoto,
                                    )
                                    lastError = null
                                    break
                                } catch (e: Exception) {
                                    lastError = e
                                    android.util.Log.w("Upload", "上传失败 (尝试 ${attempt + 1}/3): ${photo.name}", e)
                                    if (attempt < 2) {
                                        delay(1000L * (attempt + 1)) // 指数退避
                                    }
                                }
                            }
                            if (lastError != null) {
                                throw lastError
                            }
                            serverSyncService.recordUploadedMedia(
                                baseUrl = connection.baseUrl,
                                localPhoto = photo.copy(contentHash = contentHash),
                                upload = checkNotNull(uploadResult),
                            )
                            successCount.incrementAndGet()
                        } catch (e: Exception) {
                            failCount.incrementAndGet()
                            val reason = e.message ?: e.javaClass.simpleName
                            failReasons.add("${photo.name}: $reason")
                            android.util.Log.e("Upload", "上传失败: ${photo.name}", e)
                        } finally {
                            mediaTaskCoordinator.unregisterActive(photo.id)
                        }
                    }
                }
            }.awaitAll()
        }

        val finishTime = System.currentTimeMillis()
        val success = successCount.get()
        val fail = failCount.get()
        val status = if (fail == 0) "completed" else "failed"
        var message = "上传${if (fail == 0) "全部" else ""}完成：成功 $success，失败 $fail（共 ${localPhotos.size}）"
        if (failReasons.isNotEmpty()) {
            message += "\n" + failReasons.joinToString("\n")
        }

        taskRepository.updateProgress(
            id = taskId,
            current = localPhotos.size,
            total = localPhotos.size,
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

        updateNotification(message, localPhotos.size, localPhotos.size)
    }

    private fun buildNotification(title: String, current: Int, total: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, NotificationChannels.CHANNEL_UPLOAD)
            .setContentTitle("柚柚相册")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
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

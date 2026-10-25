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
import com.example.youyou_album.data.api.interceptor.TokenProvider
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
import java.util.concurrent.ConcurrentHashMap
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
    @Inject lateinit var secureStorageService: SecureStorageService
    @Inject lateinit var tokenProvider: TokenProvider

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, taskId: String) {
            context.startForegroundService(buildIntent(context, taskId))
        }

        /** 执行内容全部来自 tasks_table.payload，Intent 不带任何列表，体积恒定。 */
        internal fun buildIntent(context: Context, taskId: String): Intent =
            Intent(context, UploadForegroundService::class.java).putExtra(EXTRA_TASK_ID, taskId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)

        startForeground(NOTIFICATION_ID, buildNotification("准备上传...", 0, 0))

        // 先 startForeground 再判空：否则 startForegroundService 起的服务 5 秒内没前台通知会被系统判 ANR。
        if (taskId == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                performUpload(taskId)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performUpload(taskId: String) {
        val connection = serverConnectionStore.getConnection()
        val identity = TransferTaskPayload.identity(connection, secureStorageService.getDeviceId())
        val token = tokenProvider.getToken()
        val generation = tokenProvider.generation

        val task = taskRepository.getById(taskId)
        val stored = TransferTaskPayload.read(task?.payload)
        if (connection == null || identity.isNullOrBlank() || task?.kind != "upload" || stored == null || stored.identity != identity) {
            taskRepository.updateStatus(taskId, "failed", "目标账号已变化或任务上下文缺失，请重新选择照片上传", System.currentTimeMillis())
            return
        }

        val photoIds = stored.items.mapTo(HashSet()) { it.photoId }
        // shortcut: 整表加载，索引到 5 万量级时内存吃紧，届时改为按 id 分批查询
        val allPhotos = photoRepository.getAll()
        // FR-4 实况整体搬运：一段实况的静态帧与动态部分共同搬运（选中静态帧即带上本机动态部分），
        // 已在服务端的部分不重复上传——半同步态下只补传缺失的动态部分。
        val remoteHashes = allPhotos
            .filter { it.sourceType == "server" }
            .mapNotNull { it.contentHash }
            .toSet()
        val selectedPhotos = allPhotos.filter { it.id in photoIds }
        val liveParts = selectedPhotos.mapNotNull { selected ->
            val live = selected.livePhoto ?: return@mapNotNull null
            if (!live.isStill || live.embedded) return@mapNotNull null
            allPhotos.firstOrNull { it.sourceType != "server" && it.id == live.partnerMediaId }
        }
        val candidates = (selectedPhotos + liveParts)
            .distinctBy { it.id }
            .filter { it.sourceType == "local" && it.sourceUri != null && it.contentHash !in remoteHashes }

        // 实况动态部分不在发起时的选择集里，补进 payload，否则它传成功了也记不上状态。
        val payload = stored.copy(
            items = (stored.items + liveParts
                .filter { part -> part.id !in photoIds }
                .map { TransferTaskItem(it.id, it.name, it.sourceUri, it.contentHash) })
                .distinctBy { it.photoId },
        )
        val itemByPhotoId = payload.items.associateBy { it.photoId }
        val retryIds = payload.retryIds
        val localPhotos = candidates.filter { photo ->
            val item = itemByPhotoId[photo.id]
            item != null && photo.id in retryIds && photo.sourceUri == item.sourceUri &&
                (item.contentHash == null || item.contentHash == photo.contentHash)
        }

        val now = System.currentTimeMillis()

        taskRepository.upsert(
            task.copy(
                title = "上传 ${localPhotos.size} 项到服务端",
                status = "running", message = "准备上传...", finishedAt = null,
                payload = TransferTaskPayload.write(payload),
                retryCount = task.retryCount + if (task.status == "failed") 1 else 0,
                current = 0, total = localPhotos.size, updatedAt = now,
            )
        )

        // 每条状态先留在内存、结束后一次性写回 payload。
        // 原来每项都整串 decode/encode 一遍，N 项就是 O(N²)，几万条时永远跑不完。
        val itemStates = ConcurrentHashMap<String, Pair<String, String?>>()
        val hashOverrides = ConcurrentHashMap<String, String>()
        fun recordItem(photoId: String, status: String, message: String? = null) {
            itemStates[photoId] = status to message
        }
        val validIds = localPhotos.mapTo(HashSet()) { it.id }
        for (item in payload.items.filter { it.photoId in retryIds && it.photoId !in validIds }) {
            if (item.contentHash != null && item.contentHash in remoteHashes) {
                recordItem(item.photoId, "succeeded")
            } else {
                recordItem(item.photoId, "failed", "源文件已不存在或发生变化，请重新选择照片")
            }
        }

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
                        if (!mediaTaskCoordinator.tryRegisterActive(photo.id)) {
                            recordItem(photo.id, "failed", "正在删除，稍后再试")
                            return@withPermit
                        }
                        try {
                            check(token != null && generation == tokenProvider.generation &&
                                identity == TransferTaskPayload.identity(serverConnectionStore.getConnection(), secureStorageService.getDeviceId())) {
                                "连接或账号已变化，请重新连接后重试"
                            }
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
                            hashOverrides[photo.id] = contentHash

                            // 失败重试：最多 3 次，指数退避（1s, 2s）
                            var lastError: Exception? = null
                            var uploadResult: UploadService.UploadResult? = null
                            for (attempt in 0 until 3) {
                                try {
                                    check(generation == tokenProvider.generation &&
                                        identity == TransferTaskPayload.identity(serverConnectionStore.getConnection(), secureStorageService.getDeviceId())) {
                                        "连接或账号已变化，请重新连接后重试"
                                    }
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
                                        baseUrl = connection.baseUrl,
                                        authorization = "Bearer $token",
                                    )
                                    check(generation == tokenProvider.generation &&
                                        identity == TransferTaskPayload.identity(serverConnectionStore.getConnection(), secureStorageService.getDeviceId())) {
                                        "连接或账号已变化；上传结果待原账号同步确认"
                                    }
                                    lastError = null
                                    break
                                } catch (e: Exception) {
                                    lastError = e
                                    if (e is retrofit2.HttpException) {
                                        android.util.Log.w("Upload", "服务端拒绝上传: ${e.response()?.errorBody()?.string()?.take(500)}")
                                    }
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
                            recordItem(photo.id, "succeeded")
                        } catch (e: Exception) {
                            failCount.incrementAndGet()
                            val reason = e.message ?: e.javaClass.simpleName
                            failReasons.add("${photo.name}: $reason")
                            recordItem(photo.id, "failed", reason)
                            android.util.Log.e("Upload", "上传失败: ${photo.name}", e)
                        } finally {
                            mediaTaskCoordinator.unregisterActive(photo.id)
                        }
                    }
                }
            }.awaitAll()
        }

        val finishTime = System.currentTimeMillis()
        val finalPayload = payload.copy(
            items = payload.items.map { item ->
                val state = itemStates[item.photoId]
                item.copy(
                    status = state?.first ?: item.status,
                    message = state?.second ?: item.message,
                    contentHash = hashOverrides[item.photoId] ?: item.contentHash,
                )
            },
        )
        val success = finalPayload.succeeded
        val fail = finalPayload.failed + finalPayload.unfinished
        val status = if (fail == 0) "completed" else "failed"
        var message = "上传${if (fail == 0) "全部" else "部分"}完成：成功 $success，失败 $fail（共 ${finalPayload.items.size}）"
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

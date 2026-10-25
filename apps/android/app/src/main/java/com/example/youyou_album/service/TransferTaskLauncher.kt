package com.example.youyou_album.service

import android.content.Context
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传输任务的唯一发起口：先落库，再把 taskId 交给前台服务。
 *
 * 不把 id 列表放进 Intent：时间线全选是「本机索引 + 服务端投影」全量，几万条 id
 * 序列化后超过 Binder 事务上限（约 1MB），startForegroundService 会在主线程抛
 * TransactionTooLargeException，整个进程当场退出。
 */
@Singleton
class TransferTaskLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val serverConnectionStore: ServerConnectionStore,
    private val secureStorageService: SecureStorageService,
) {
    /** 返回 null 表示已发起；非空是给用户看的原因。 */
    suspend fun launch(kind: String, photos: List<Photo>): String? {
        val upload = kind == "upload"
        if (photos.isEmpty()) return if (upload) "没有可上传的项目" else "没有可下载的项目"
        val identity = TransferTaskPayload.identity(
            serverConnectionStore.getConnection(),
            secureStorageService.getDeviceId(),
        ) ?: return "未连接服务端，请先在设置中完成连接"

        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        taskRepository.upsert(
            AppTask(
                id = taskId,
                kind = kind,
                title = if (upload) "上传 ${photos.size} 项到服务端" else "下载 ${photos.size} 项到本机",
                status = "pending",
                message = if (upload) "准备上传..." else "准备下载...",
                current = 0,
                total = photos.size,
                indeterminate = false,
                createdAt = now,
                updatedAt = now,
                payload = TransferTaskPayload.write(
                    TransferTaskPayload(
                        identity = identity,
                        items = photos.map { TransferTaskItem(it.id, it.name, it.sourceUri, it.contentHash) },
                    ),
                ),
            )
        )
        if (upload) UploadForegroundService.start(context, taskId) else DownloadForegroundService.start(context, taskId)
        return null
    }
}

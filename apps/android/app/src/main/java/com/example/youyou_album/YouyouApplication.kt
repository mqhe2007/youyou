package com.example.youyou_album

import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.youyou_album.domain.repository.TaskRepository
import com.example.youyou_album.service.ServerConnectionStore
import com.example.youyou_album.data.api.interceptor.TokenProvider
import com.example.youyou_album.data.api.YouyouApiService

@HiltAndroidApp
class YouyouApplication : BaseYouyouApplication(), ImageLoaderFactory {

    @Inject lateinit var connectionStore: ServerConnectionStore
    @Inject lateinit var tokenProvider: TokenProvider
    @Inject lateinit var taskRepository: TaskRepository

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val base = runBlocking { connectionStore.getConnection() }?.baseUrl?.toHttpUrlOrNull()
                val request = original.newBuilder().removeHeader("Authorization")
                // 每次网络请求（包括重定向）只向当前绑定的服务端发送设备凭据。
                if (base != null && original.url.scheme == base.scheme &&
                    original.url.host == base.host && original.url.port == base.port &&
                    original.url.encodedPath.startsWith("${base.encodedPath.trimEnd('/')}/api/v1/media/")) {
                    request.header("X-Youyou-Client-Version", YouyouApiService.CLIENT_VERSION)
                    tokenProvider.getToken()?.let { request.header("Authorization", "Bearer $it") }
                }
                chain.proceed(request.build())
            }.build()
        return ImageLoader.Builder(this).okHttpClient(client).build()
    }

    @Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 手动初始化 WorkManager（已在 Manifest 中禁用默认初始化器）
        // 必须在 super.onCreate() 之后，此时 Hilt 已注入 workerFactory
        WorkManager.initialize(this, workManagerConfiguration)
        // Foreground transfers are START_NOT_STICKY. A fresh process cannot still own an old
        // running transfer; keep its item checkpoint so the user can retry only unfinished items.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            taskRepository.observeAll().first()
                .filter { it.kind in setOf("upload", "download") && it.status == "running" }
                .forEach { task ->
                    val now = System.currentTimeMillis()
                    taskRepository.upsert(task.copy(
                        status = "failed",
                        message = "传输因进程重启中断，可重试未完成项",
                        finishedAt = now,
                        updatedAt = now,
                    ))
                }
            taskRepository.cleanupRecentResults(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        }
    }
}

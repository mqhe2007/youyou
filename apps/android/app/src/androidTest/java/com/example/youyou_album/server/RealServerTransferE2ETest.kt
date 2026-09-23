package com.example.youyou_album.server

import android.content.ContentValues
import android.content.Context
import android.app.Application
import android.content.ContentUris
import android.graphics.Rect
import android.graphics.Bitmap
import android.provider.MediaStore
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.exifinterface.media.ExifInterface
import com.example.youyou_album.MainActivity
import com.example.youyou_album.data.api.YouyouApiService
import com.example.youyou_album.data.api.dto.MediaDeleteRequestDto
import com.example.youyou_album.data.db.dao.MediaOperationDao
import com.example.youyou_album.data.db.entity.PendingMediaOperationEntity
import com.example.youyou_album.di.AcceptanceEntryPoint
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.model.MediaTime
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.domain.repository.PhotoRepository
import com.example.youyou_album.domain.repository.TaskRepository
import com.example.youyou_album.service.DownloadForegroundService
import com.example.youyou_album.service.MediaDeletionService
import com.example.youyou_album.service.TransferTaskPayload
import com.example.youyou_album.service.UploadForegroundService
import com.example.youyou_album.presentation.server.ConnectionStage
import com.example.youyou_album.presentation.server.ServerConnectionViewModel
import com.example.youyou_album.presentation.task.TaskCenterViewModel
import com.example.youyou_album.util.TestDependencies
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Enabled only by scripts/e2e_android_server.py, which supplies an isolated real server. */
@RunWith(AndroidJUnit4::class)
class RealServerTransferE2ETest {
    @Test
    fun transferTenEachDirectionAndRetryFailedItem() = runBlocking {
        TestDependencies.assumeNoRealUserState()
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("youyou.baseUrl")
        val pairingCode = args.getString("youyou.pairingCode")
        val remotePrefix = args.getString("youyou.remotePrefix")
        val otherPairingCode = args.getString("youyou.otherPairingCode")
        val emptyPairingCode = args.getString("youyou.emptyPairingCode")
        assumeTrue("Run with scripts/e2e_android_server.py", !baseUrl.isNullOrBlank() && !pairingCode.isNullOrBlank() && !remotePrefix.isNullOrBlank() && !otherPairingCode.isNullOrBlank() && !emptyPairingCode.isNullOrBlank())
        val url = requireNotNull(baseUrl)
        val code = requireNotNull(pairingCode)
        val salt = java.net.URI(url).port
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deps = EntryPointAccessors.fromApplication(context, AcceptanceEntryPoint::class.java)
        val connectionStore = deps.connectionStore()
        val secureStorage = deps.secureStorage()
        val tokenProvider = deps.tokenProvider()
        val photoRepository = deps.photoRepository()
        val taskRepository = deps.taskRepository()
        val api = deps.apiServiceFactory().create(url)
        val created = mutableListOf<android.net.Uri>()
        val createdPhotoIds = mutableListOf<String>()
        try {
            // Prior isolated runs may have left test-only failed batches in this emulator app.
            taskRepository.observeAll().first().filter { task ->
                listOf("upload-", "retry-", "switch-account-").any { it in task.payload.orEmpty() }
            }.forEach { taskRepository.deleteById(it.id) }
            connectionStore.clearConnection()
            secureStorage.clearDeviceToken()
            secureStorage.clearDeviceId()
            tokenProvider.setToken(null)
            val connectionViewModel = ServerConnectionViewModel(
                connectionStore, secureStorage, tokenProvider, deps.serverSyncService(),
                deps.remoteAccountCacheCleaner(), deps.apiServiceFactory(),
                deps.serverSyncStateDao(), deps.serverProjectionDao(),
            )
            connectionViewModel.connectWithQr(
                """{"type":"youyou-connect","version":1,"serverUrl":"$url","pairingCode":"$code"}"""
            )
            val connectState = withTimeout(120_000) {
                connectionViewModel.uiState.first { it.connectionStage == ConnectionStage.COMPLETE ||
                    (!it.isConnecting && it.feedback?.isError == true) }
            }
            assertEquals(connectState.feedback?.message, ConnectionStage.COMPLETE, connectState.connectionStage)
            assertTrue(connectionStore.firstConnectGuidePending.first())
            val firstDeviceId = secureStorage.getDeviceId()
            connectionViewModel.connectWithQr(
                """{"type":"youyou-connect","version":1,"serverUrl":"$url","pairingCode":"$code"}"""
            )
            val reusedCode = withTimeout(30_000) {
                connectionViewModel.uiState.first { !it.isConnecting && it.feedback?.isError == true }
            }
            assertTrue(reusedCode.feedback?.message.orEmpty(), reusedCode.feedback?.message.orEmpty().contains("已过期或已使用"))
            assertEquals(firstDeviceId, secureStorage.getDeviceId())
            assertEquals(firstDeviceId, connectionStore.getConnection()?.deviceId)
            photoRepository.getAll().filter { it.sourceType == "local" && it.name.startsWith("first-connect-") }
                .forEach { photoRepository.deleteById(it.id) }
            val remote = photoRepository.getAll().filter { it.sourceType == "server" && it.name.startsWith(requireNotNull(remotePrefix)) }
            assertEquals(10, remote.size)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                if (accessibilityTexts().any { it.contains("Android App Compatibility") }) {
                    tapAccessibilityText("Don't Show Again")
                }
                delay(2_000)
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val imageFile = File(context.filesDir, "acceptance/first-connect.png")
                imageFile.parentFile?.mkdirs()
                FileOutputStream(imageFile).use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val firstScreenTexts = accessibilityTexts()
                assertTrue("first-connect UI text=$firstScreenTexts", firstScreenTexts.any { it.contains("已接入照片库") })
                tapAccessibilityText("查看")
                val guideDismissed = withTimeoutOrNull(10_000) {
                    while (connectionStore.firstConnectGuidePending.first()) delay(100)
                    true
                }
                assertTrue("first-connect guide did not dismiss: ${accessibilityTexts()}", guideDismissed == true)
                val detailTexts = withTimeoutOrNull(10_000) {
                    var texts = accessibilityTexts()
                    while (texts.none { it == "下载" }) {
                        delay(100)
                        texts = accessibilityTexts()
                    }
                    texts
                }
                assertTrue("first photo detail text=${detailTexts ?: accessibilityTexts()}", detailTexts?.any { it == "下载" } == true)
                assertTrue(tapAccessibilityText("信息"))
                val remoteInfo = withTimeout(10_000) {
                    var texts = accessibilityTexts()
                    while (texts.none { it == "仅服务器" }) {
                        delay(100)
                        texts = accessibilityTexts()
                    }
                    texts
                }
                assertTrue(remoteInfo.contains("存放位置"))
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                )
                val downloadStart = System.currentTimeMillis()
                DownloadForegroundService.start(context, remote.map { it.id }.toSet())
                val download = awaitTask(taskRepository, "download", downloadStart)
                assertEquals(download.message, "completed", download.status)
                assertEquals(10, TransferTaskPayload.read(download.payload)?.succeeded)
                val localHashes = photoRepository.getAll().filter { it.sourceType == "local" }.mapNotNull { it.contentHash }.toSet()
                assertTrue(
                    "downloaded local hashes=${localHashes.size}, remote hashes=${remote.mapNotNull { it.contentHash }.size}; " +
                        "missing=${remote.mapNotNull { it.contentHash }.filterNot { it in localHashes }}; " +
                        "photos=${photoRepository.getAll().map { it.name to it.sourceType }}",
                    localHashes.containsAll(remote.mapNotNull { it.contentHash }),
                )
                assertTrue(tapAccessibilityText("信息"))
                val syncedInfo = withTimeout(10_000) {
                    var texts = accessibilityTexts()
                    while (texts.none { it == "手机和服务器都有" }) {
                        delay(100)
                        texts = accessibilityTexts()
                    }
                    texts
                }
                assertTrue(syncedInfo.contains("存放位置"))
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                )

                val local = (0 until 10).map { index ->
                    val takenAt = 1_700_000_000_000L + index * 1000L
                    val bytes = jpeg(context, 100 + index, salt, takenAt)
                    val name = "upload-$salt-$index.jpg"
                    val uri = insertPng(context, name, bytes, takenAt)
                    created += uri
                    photo(uri.toString(), name, bytes, takenAt)
                }
                createdPhotoIds += local.map { it.id }
                photoRepository.upsertAll(local)
                val uploadStart = System.currentTimeMillis()
                UploadForegroundService.start(context, local.map { it.id }.toSet())
                val upload = awaitTask(taskRepository, "upload", uploadStart)
                assertEquals(upload.message, "completed", upload.status)
                assertEquals(10, TransferTaskPayload.read(upload.payload)?.succeeded)
                val allServer = api.listMedia(limit = 100).items
                val uploaded = allServer.filter { it.originalName?.startsWith("upload-$salt-") == true }
                assertEquals("server items=${allServer.map { it.name to it.originalName }}", 10, uploaded.size)
                assertEquals(local.mapNotNull { it.contentHash }.toSet(), uploaded.mapNotNull { it.contentHash }.toSet())
                uploaded.forEach { item ->
                    val index = requireNotNull(item.originalName).removePrefix("upload-$salt-").removeSuffix(".jpg").toInt()
                    assertEquals(1_700_000_000_000L + index * 1000L, item.takenAt)
                    val sourceBytes = context.contentResolver.openInputStream(android.net.Uri.parse(local[index].sourceUri))!!.use { it.readBytes() }
                    assertEquals(sourceBytes.toList(), api.openMediaContent(item.id).bytes().toList())
                }
                val timeline = photoRepository.getTimeline()
                (remote.mapNotNull { it.contentHash } + local.mapNotNull { it.contentHash }).forEach { hash ->
                    assertEquals(
                        "merged timeline duplicates hash=$hash rows=${timeline.filter { it.contentHash == hash }.map { Triple(it.name, it.sourceType, it.sourceUri) }}",
                        1, timeline.count { it.contentHash == hash },
                    )
                }

                val good = png(250, salt)
                val bad = png(251, salt)
                val retryName = "retry-$salt.png"
                val retryUri = insertPng(context, retryName, bad, 1_700_000_001_000L)
                created += retryUri
                val retryPhoto = photo(retryUri.toString(), retryName, good, 1_700_000_001_000L)
                createdPhotoIds += retryPhoto.id
                photoRepository.upsert(retryPhoto)
                val failedStart = System.currentTimeMillis()
                UploadForegroundService.start(context, setOf(retryPhoto.id))
                val failed = awaitTask(taskRepository, "upload", failedStart)
                assertEquals("failed", failed.status)
                assertEquals(1, TransferTaskPayload.read(failed.payload)?.failed)
                context.contentResolver.openOutputStream(retryUri, "wt")!!.use { it.write(good) }
                delay(1_000)
                UploadForegroundService.start(context, setOf(retryPhoto.id), failed.id)
                val retried = withTimeout(120_000) {
                    taskRepository.observeAll().first { rows ->
                        rows.any { it.id == failed.id && it.retryCount > 0 && it.status == "completed" }
                    }.first { it.id == failed.id }
                }
                assertEquals(1, TransferTaskPayload.read(retried.payload)?.succeeded)
                assertEquals(1, api.listMedia(limit = 100).items.count { it.originalName == retryName })
                assertFalse(photoRepository.getTimeline().isEmpty())

                verifyLocalOnlyUi(context, photoRepository, salt, created, createdPhotoIds)

                verifyDeleteScopes(scenario, deps.mediaDeletionService(), photoRepository, taskRepository, api, remote)
                val pendingOldAccountOperation = verifyRemoteRecovery(
                    api, secureStorage.getDeviceToken(), deps.mediaOperationDao(),
                    deps.serverSyncService(), remote, url,
                )
                val homeReady = withTimeoutOrNull(10_000) {
                    while (accessibilityTexts().none { it == "后台活动" }) delay(100)
                    true
                }
                assertTrue("home toolbar after detail=${accessibilityTexts()}", homeReady == true)
                tapAccessibilityText("后台活动")
                val activityTexts = withTimeoutOrNull(10_000) {
                    var texts = accessibilityTexts()
                    while (texts.none { it.contains("最近结果") }) {
                        delay(100)
                        texts = accessibilityTexts()
                    }
                    texts
                }
                assertTrue("recent results UI=${activityTexts ?: accessibilityTexts()}", activityTexts?.any { it.contains("上传") } == true)
                delay(1_000)
                val taskImage = File(context.filesDir, "acceptance/task-center.png")
                FileOutputStream(taskImage).use {
                    requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                )

                verifyAccountSwitchAndFailures(
                    scenario, context, connectionViewModel, connectionStore, secureStorage,
                    photoRepository, taskRepository, api, url, requireNotNull(otherPairingCode),
                    salt, created, createdPhotoIds, deps.mediaOperationDao(), pendingOldAccountOperation,
                    deps.mediaDeletionService(), remote,
                )
                verifyEmptyAndUnreachableConnection(
                    connectionViewModel, connectionStore, secureStorage,
                    photoRepository, url, requireNotNull(emptyPairingCode),
                )
            }
        } finally {
            photoRepository.getAll().filter {
                it.sourceType == "local" && (it.id in createdPhotoIds || it.name.startsWith(requireNotNull(remotePrefix)))
            }.forEach { photoRepository.deleteById(it.id) }
            cleanupDownloads(context, requireNotNull(remotePrefix))
            created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            connectionStore.clearConnection()
            tokenProvider.setToken(null)
            secureStorage.clearDeviceToken()
        }
    }

    private suspend fun verifyAccountSwitchAndFailures(
        scenario: ActivityScenario<MainActivity>,
        context: Context,
        connectionViewModel: ServerConnectionViewModel,
        connectionStore: com.example.youyou_album.service.ServerConnectionStore,
        secureStorage: com.example.youyou_album.service.SecureStorageService,
        photoRepository: PhotoRepository,
        taskRepository: TaskRepository,
        api: YouyouApiService,
        url: String,
        otherPairingCode: String,
        salt: Int,
        created: MutableList<android.net.Uri>,
        createdPhotoIds: MutableList<String>,
        operationDao: MediaOperationDao,
        pendingOldAccountOperation: String,
        deletion: MediaDeletionService,
        remote: List<Photo>,
    ) {
        val wrongBytes = png(252, salt)
        val expectedBytes = png(253, salt)
        val switchName = "switch-account-$salt.png"
        val switchUri = insertPng(context, switchName, wrongBytes, 1_700_000_002_000L)
        created += switchUri
        val switchPhoto = photo(switchUri.toString(), switchName, expectedBytes, 1_700_000_002_000L)
        createdPhotoIds += switchPhoto.id
        photoRepository.upsert(switchPhoto)
        val switchStart = System.currentTimeMillis()
        UploadForegroundService.start(context, setOf(switchPhoto.id))
        val switchFailure = awaitTask(taskRepository, "upload", switchStart)
        assertEquals("failed", switchFailure.status)
        // Model a scanner removing a vanished source row while retaining its task payload.
        photoRepository.deleteById(switchPhoto.id)
        delay(1_000)
        UploadForegroundService.start(context, setOf(switchPhoto.id), switchFailure.id)
        val missingSource = withTimeout(30_000) {
            taskRepository.observeAll().first { rows ->
                rows.any { it.id == switchFailure.id && it.retryCount > 0 && it.status == "failed" }
            }.first { it.id == switchFailure.id }
        }
        val sourceError = TransferTaskPayload.read(missingSource.payload)?.items?.firstOrNull()?.message.orEmpty()
        assertTrue(sourceError, sourceError.contains("源文件") || sourceError.contains("无法打开文件"))
        val pendingDeletePhoto = photoRepository.getAll().first {
            it.sourceType == "local" && it.contentHash == remote[5].contentHash
        }
        val pendingDelete = deletion.begin(listOf(pendingDeletePhoto.id), MediaDeletionService.DeleteScope.BOTH)
            as MediaDeletionService.DeleteStep.NeedsSystemConfirmation
        val oldDeviceId = secureStorage.getDeviceId()
        connectionViewModel.connectWithQr(
            """{"type":"youyou-connect","version":1,"serverUrl":"$url","pairingCode":"$otherPairingCode"}"""
        )
        val switched = withTimeout(120_000) {
            connectionViewModel.uiState.first {
                it.connectionStage == ConnectionStage.COMPLETE && it.connection?.deviceId != oldDeviceId
            }
        }
        assertEquals(ConnectionStage.COMPLETE, switched.connectionStage)
        assertEquals(1, photoRepository.getAll().count { it.sourceType == "server" })
        assertEquals("unknown", operationDao.getOperation(pendingOldAccountOperation)?.state)
        operationDao.deleteOperation(pendingOldAccountOperation)
        scenario.onActivity { activity ->
            activity.startIntentSenderForResult(pendingDelete.intentSender, 703, null, 0, 0, 0)
        }
        assertTrue(tapAccessibilityText(awaitTrashApproval()))
        delay(500)
        val switchedDelete = deletion.onSystemConfirmation(pendingDelete.session, true)
            as MediaDeletionService.DeleteStep.Finished
        assertEquals(1, switchedDelete.summary.deletedLocal)
        assertEquals(1, switchedDelete.summary.remoteUnavailable)
        assertEquals(0, switchedDelete.summary.remoteTrashed)
        assertTrue(taskRepository.observeAll().first().any {
            it.kind == "delete" && it.status == "failed" && it.message?.contains("服务器 1 项未移除") == true
        })
        val retryMessage = TaskCenterViewModel(
            taskRepository, connectionStore, secureStorage,
            ApplicationProvider.getApplicationContext<Application>(),
        ).retryTask(switchFailure)
        assertTrue(retryMessage, retryMessage.contains("当前账号"))
        UploadForegroundService.start(context, setOf(switchPhoto.id), switchFailure.id)
        val blocked = withTimeout(30_000) {
            taskRepository.observeAll().first { rows ->
                rows.any { it.id == switchFailure.id && it.message?.contains("目标账号已变化") == true }
            }.first { it.id == switchFailure.id }
        }
        assertEquals("failed", blocked.status)
        assertEquals(0, api.listMedia(limit = 100).items.count { it.originalName == switchName })
    }

    private suspend fun verifyRemoteRecovery(
        api: YouyouApiService,
        token: String?,
        operationDao: MediaOperationDao,
        sync: com.example.youyou_album.service.ServerSyncService,
        remote: List<Photo>,
        url: String,
    ): String {
        val authorization = "Bearer ${requireNotNull(token)}"
        val info = api.serverInfo(authorization)
        val user = requireNotNull(info.userId)
        val scopeKey = "${info.serverInstanceId.length}:${info.serverInstanceId}:$user"
        val scope = "identity_" + MessageDigest.getInstance("SHA-256")
            .digest(scopeKey.toByteArray()).joinToString("") { "%02x".format(it) }
        val items = api.listMedia(limit = 100).items
        val deleted = items.first { it.contentHash == remote[3].contentHash }
        val operationId = UUID.randomUUID().toString()
        assertEquals("succeeded", api.deleteMedia(
            deleted.id, MediaDeleteRequestDto(operationId, deleted.version), authorization,
        ).state)
        val now = System.currentTimeMillis()
        operationDao.upsertOperation(PendingMediaOperationEntity(
            operationId, "delete", scope, deleted.id, remote[3].id, deleted.version,
            "unknown", "模拟响应丢失，待查询", now, now,
        ))
        sync.sync(url)
        assertEquals("succeeded", operationDao.getOperation(operationId)?.state)
        operationDao.deleteOperation(operationId)

        val pendingTarget = items.first { it.contentHash == remote[4].contentHash }
        val pendingId = UUID.randomUUID().toString()
        operationDao.upsertOperation(PendingMediaOperationEntity(
            pendingId, "delete", scope, pendingTarget.id, remote[4].id, pendingTarget.version,
            "unknown", "原账号结果待确认", now, now,
        ))
        return pendingId
    }

    private suspend fun verifyEmptyAndUnreachableConnection(
        viewModel: ServerConnectionViewModel,
        connectionStore: com.example.youyou_album.service.ServerConnectionStore,
        secureStorage: com.example.youyou_album.service.SecureStorageService,
        photoRepository: PhotoRepository,
        url: String,
        emptyPairingCode: String,
    ) {
        val priorDeviceId = secureStorage.getDeviceId()
        viewModel.connectWithQr(
            """{"type":"youyou-connect","version":1,"serverUrl":"$url","pairingCode":"$emptyPairingCode"}"""
        )
        withTimeout(120_000) {
            viewModel.uiState.first {
                it.connectionStage == ConnectionStage.COMPLETE && it.connection?.deviceId != priorDeviceId
            }
        }
        assertEquals(0, photoRepository.getAll().count { it.sourceType == "server" })
        assertTrue(connectionStore.firstConnectGuidePending.first())
        val emptyGuide = withTimeout(10_000) {
            var texts = accessibilityTexts()
            while (texts.none { it.contains("服务器照片库暂无内容") || it.contains("照片库暂无内容") }) {
                delay(100)
                texts = accessibilityTexts()
            }
            texts
        }
        assertTrue(emptyGuide.toString(), emptyGuide.any { it == "上传" || it == "扫描" })

        val emptyDeviceId = secureStorage.getDeviceId()
        viewModel.connectWithQr(
            """{"type":"youyou-connect","version":1,"serverUrl":"http://127.0.0.1:1","pairingCode":"unreachable"}"""
        )
        val unreachable = withTimeout(30_000) {
            viewModel.uiState.first { !it.isConnecting && it.feedback?.isError == true }
        }
        assertTrue(unreachable.feedback?.message.orEmpty().contains("无法连接服务端"))
        assertEquals(emptyDeviceId, secureStorage.getDeviceId())
        assertEquals(emptyDeviceId, connectionStore.getConnection()?.deviceId)
    }

    private suspend fun verifyLocalOnlyUi(
        context: Context,
        photoRepository: PhotoRepository,
        salt: Int,
        created: MutableList<android.net.Uri>,
        createdPhotoIds: MutableList<String>,
    ) {
        val localOnlyName = "local-only-$salt.png"
        val localOnlyBytes = png(249, salt)
        val localOnlyUri = insertPng(context, localOnlyName, localOnlyBytes, 1_700_000_003_000L)
        created += localOnlyUri
        val localOnly = photo(localOnlyUri.toString(), localOnlyName, localOnlyBytes, 1_700_000_003_000L)
        createdPhotoIds += localOnly.id
        photoRepository.upsert(localOnly)

        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
        val homeTexts = withTimeout(10_000) {
            var texts = accessibilityTexts()
            while (texts.none { it == "筛选照片" }) {
                delay(100)
                texts = accessibilityTexts()
            }
            texts
        }
        assertTrue(homeTexts.contains("后台活动"))
        assertTrue(tapAccessibilityText("筛选照片"))
        val filterTexts = withTimeout(10_000) {
            var texts = accessibilityTexts()
            while (!texts.containsAll(listOf("仅本机", "仅服务器", "手机和服务器都有"))) {
                delay(100)
                texts = accessibilityTexts()
            }
            texts
        }
        assertTrue(filterTexts.toString(), filterTexts.containsAll(listOf("仅本机", "仅服务器", "手机和服务器都有")))
        assertTrue(tapAccessibilityText("仅本机"))
        val localTile = "$localOnlyName，仅本机"
        withTimeout(10_000) {
            while (accessibilityTexts().none { it == localTile }) delay(100)
        }
        assertTrue(tapAccessibilityText(localTile))
        withTimeout(10_000) {
            while (accessibilityTexts().none { it == "信息" }) delay(100)
        }
        assertTrue(tapAccessibilityText("信息"))
        withTimeout(10_000) {
            while (accessibilityTexts().none { it == "仅本机" }) delay(100)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
        withTimeout(10_000) {
            while (accessibilityTexts().any { it == "详细信息" }) delay(100)
        }
        if (accessibilityTexts().none { it == "后台活动" }) {
            withTimeout(10_000) {
                while (accessibilityTexts().none { it == "返回" || it == "后台活动" }) delay(100)
            }
            if (accessibilityTexts().none { it == "后台活动" }) assertTrue(tapAccessibilityText("返回"))
        }
        withTimeout(10_000) {
            while (accessibilityTexts().none { it == "后台活动" }) delay(100)
        }
    }

    private suspend fun verifyDeleteScopes(
        scenario: ActivityScenario<MainActivity>,
        deletion: MediaDeletionService,
        photoRepository: PhotoRepository,
        taskRepository: TaskRepository,
        api: YouyouApiService,
        remote: List<Photo>,
    ) {
        val serverScopePhoto = photoRepository.getAll().first {
            it.sourceType == "local" && it.contentHash == remote[0].contentHash
        }
        assertTrue(deletion.preview(listOf(serverScopePhoto.id)).single().hasRemote)
        val serverScope = deletion.begin(listOf(serverScopePhoto.id), MediaDeletionService.DeleteScope.SERVER)
            as MediaDeletionService.DeleteStep.Finished
        assertEquals(1, serverScope.summary.remoteTrashed)
        assertEquals(0, serverScope.summary.deletedLocal)
        assertNotNull(photoRepository.getById(serverScopePhoto.id))
        assertFalse(api.listMedia(limit = 100).items.any { it.contentHash == remote[0].contentHash })
        val phoneScopePhoto = photoRepository.getAll().first {
            it.sourceType == "local" && it.contentHash == remote[1].contentHash
        }
        val phoneScope = deletion.begin(listOf(phoneScopePhoto.id), MediaDeletionService.DeleteScope.PHONE)
            as MediaDeletionService.DeleteStep.NeedsSystemConfirmation
        val cancelledPhone = deletion.onSystemConfirmation(phoneScope.session, false)
            as MediaDeletionService.DeleteStep.Finished
        assertEquals(1, cancelledPhone.summary.notDeleted)
        assertNotNull(photoRepository.getById(phoneScopePhoto.id))
        assertTrue(api.listMedia(limit = 100).items.any { it.contentHash == remote[1].contentHash })
        val approvedPhone = deletion.begin(listOf(phoneScopePhoto.id), MediaDeletionService.DeleteScope.PHONE)
            as MediaDeletionService.DeleteStep.NeedsSystemConfirmation
        scenario.onActivity { activity ->
            activity.startIntentSenderForResult(approvedPhone.intentSender, 701, null, 0, 0, 0)
        }
        assertTrue(tapAccessibilityText(awaitTrashApproval()))
        delay(500)
        val phoneDone = deletion.onSystemConfirmation(approvedPhone.session, true)
            as MediaDeletionService.DeleteStep.Finished
        assertEquals(phoneDone.summary.message(), 1, phoneDone.summary.deletedLocal)
        assertEquals(1, phoneDone.summary.remoteRetained)
        assertTrue(api.listMedia(limit = 100).items.any { it.contentHash == remote[1].contentHash })
        val bothScopePhoto = photoRepository.getAll().first {
            it.sourceType == "local" && it.contentHash == remote[2].contentHash
        }
        val bothScope = deletion.begin(listOf(bothScopePhoto.id), MediaDeletionService.DeleteScope.BOTH)
            as MediaDeletionService.DeleteStep.NeedsSystemConfirmation
        scenario.onActivity { activity ->
            activity.startIntentSenderForResult(bothScope.intentSender, 702, null, 0, 0, 0)
        }
        assertTrue(tapAccessibilityText(awaitTrashApproval()))
        delay(500)
        val bothDone = deletion.onSystemConfirmation(bothScope.session, true)
            as MediaDeletionService.DeleteStep.Finished
        assertEquals(bothDone.summary.message(), 1, bothDone.summary.deletedLocal)
        assertEquals(1, bothDone.summary.remoteTrashed)
        assertFalse(api.listMedia(limit = 100).items.any { it.contentHash == remote[2].contentHash })
        assertEquals(null, photoRepository.getById(bothScopePhoto.id))
        assertTrue(taskRepository.observeAll().first().any { it.kind == "delete" && it.message?.contains("服务器已移入回收站") == true })
    }

    private suspend fun awaitTask(repository: TaskRepository, kind: String, started: Long): AppTask =
        withTimeout(120_000) {
            repository.observeAll().first { tasks ->
                tasks.any { it.kind == kind && it.createdAt >= started && it.status in setOf("completed", "failed") }
            }.first { it.kind == kind && it.createdAt >= started && it.status in setOf("completed", "failed") }
        }

    private suspend fun awaitTrashApproval(): String = withTimeout(10_000) {
        var label: String? = null
        while (label == null) {
            label = accessibilityTexts().firstOrNull {
                it.equals("Move to trash", ignoreCase = true) || it == "Move" ||
                    it == "Allow" || it.contains("移至回收站") || it == "允许"
            }
            if (label == null) delay(100)
        }
        label
    }

    private fun png(index: Int, salt: Int): ByteArray {
        val image = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        image.setPixel(0, 0, android.graphics.Color.rgb(index * 17 % 256, index * 29 % 256, index * 43 % 256))
        image.setPixel(1, 0, android.graphics.Color.rgb(salt and 255, salt shr 8, index))
        return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun jpeg(context: Context, index: Int, salt: Int, takenAt: Long): ByteArray {
        val image = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        image.setPixel(0, 0, android.graphics.Color.rgb(index * 17 % 256, index * 29 % 256, index * 43 % 256))
        image.setPixel(1, 0, android.graphics.Color.rgb(salt and 255, salt shr 8, index))
        val file = File.createTempFile("youyou-upload-", ".jpg", context.cacheDir)
        try {
            FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(takenAt)),
            )
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun insertPng(context: Context, name: String, bytes: ByteArray, takenAt: Long): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".jpg")) "image/jpeg" else "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/youyou-e2e")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun cleanupDownloads(context: Context, prefix: String) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val ids = mutableListOf<Long>()
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$prefix%", "Pictures/youyou/%"), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        ids.forEach { id -> context.contentResolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
    }

    private fun accessibilityTexts(): List<String> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        fun collect(node: AccessibilityNodeInfo): List<String> = buildList {
            node.text?.toString()?.let(::add)
            node.contentDescription?.toString()?.let(::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(collect(it)) }
        }
        return collect(root)
    }

    private fun tapAccessibilityText(label: String): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow ?: return false
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        val target = find(root) ?: return false
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        automation.injectInputEvent(down, true)
        automation.injectInputEvent(up, true)
        down.recycle()
        up.recycle()
        return true
    }


    private fun photo(uri: String, name: String, content: ByteArray, takenAt: Long): Photo = Photo(
        id = UUID.nameUUIDFromBytes(
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(android.net.Uri.parse(uri)))
                .toString().toByteArray()
        ).toString(),
        name = name,
        path = "",
        sourceType = "local",
        sourceUri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(android.net.Uri.parse(uri)),
        ).toString(),
        size = content.size.toLong(),
        mimeType = if (name.endsWith(".jpg")) "image/jpeg" else "image/png",
        contentHash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
        takenAt = takenAt,
        sortAt = takenAt,
        sortSource = "exif",
        timeVersion = MediaTime.VERSION,
        originalName = name,
    )
}

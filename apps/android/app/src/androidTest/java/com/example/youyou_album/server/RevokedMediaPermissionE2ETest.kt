package com.example.youyou_album.server

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.youyou_album.di.AcceptanceEntryPoint
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.model.ServerConnection
import com.example.youyou_album.presentation.task.TaskCenterViewModel
import com.example.youyou_album.service.TransferTaskItem
import com.example.youyou_album.service.TransferTaskPayload
import com.example.youyou_album.util.TestDependencies
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The runner revokes READ_MEDIA_IMAGES between instrumentation processes. */
@RunWith(AndroidJUnit4::class)
class RevokedMediaPermissionE2ETest {
    @Test fun failedUploadIsNotReplayedAfterSystemRevokesPhotoAccess() = runBlocking {
        TestDependencies.assumeNoRealUserState()
        val source = InstrumentationRegistry.getArguments().getString("youyou.externalUri")
        assumeTrue("Run with scripts/e2e_android_server.py", !source.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse(requireNotNull(source))
        val deps = EntryPointAccessors.fromApplication(context, AcceptanceEntryPoint::class.java)
        val store = deps.connectionStore()
        val secure = deps.secureStorage()
        val tasks = deps.taskRepository()
        val connection = ServerConnection(
            baseUrl = "http://127.0.0.1:1", serverInstanceId = "permission-test",
            deviceId = "permission-test",
        )
        store.saveConnection(connection)
        secure.saveDeviceId("permission-test")
        val task = AppTask(
            id = "permission-${UUID.randomUUID()}", kind = "upload", title = "撤权重试验收",
            status = "failed", message = "等待重试", current = 0, total = 1,
            indeterminate = false, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            payload = TransferTaskPayload.write(TransferTaskPayload(
                requireNotNull(TransferTaskPayload.identity(connection, "permission-test")),
                listOf(TransferTaskItem("external-media", "external.png", source, status = "failed")),
            )),
        )
        tasks.upsert(task)
        try {
            val denied = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { } }.exceptionOrNull()
            assertTrue("system-owned fixture remains readable after revoke", denied != null)
            val message = TaskCenterViewModel(
                tasks, store, secure, ApplicationProvider.getApplicationContext<Application>(),
            ).retryTask(task)
            assertTrue(message, message.contains("权限已撤回"))
            assertEquals("failed", tasks.getById(task.id)?.status)
        } finally {
            tasks.deleteById(task.id)
            store.clearConnection()
            secure.clearDeviceId()
        }
    }
}

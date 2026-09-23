package com.example.youyou_album.server

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.youyou_album.MainActivity
import com.example.youyou_album.di.AcceptanceEntryPoint
import com.example.youyou_album.presentation.server.ConnectionStage
import com.example.youyou_album.presentation.server.ServerConnectionViewModel
import com.example.youyou_album.util.TestDependencies
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Desensitized real UI capture using only the repository's published marketing photo assets. */
@RunWith(AndroidJUnit4::class)
class MarketingScreenshotE2ETest {
    @Test fun captureFirstConnection() = runBlocking {
        TestDependencies.assumeNoRealUserState()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("youyou.baseUrl")
        val code = args.getString("youyou.pairingCode")
        assumeTrue("Run with scripts/e2e_android_server.py", !url.isNullOrBlank() && !code.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deps = EntryPointAccessors.fromApplication(context, AcceptanceEntryPoint::class.java)
        val viewModel = ServerConnectionViewModel(
            deps.connectionStore(), deps.secureStorage(), deps.tokenProvider(),
            deps.serverSyncService(), deps.remoteAccountCacheCleaner(), deps.apiServiceFactory(),
            deps.serverSyncStateDao(), deps.serverProjectionDao(),
        )
        viewModel.connectWithQr(
            """{"type":"youyou-connect","version":1,"serverUrl":"${requireNotNull(url)}","pairingCode":"${requireNotNull(code)}"}"""
        )
        val connected = withTimeout(120_000) {
            viewModel.uiState.first { it.connectionStage == ConnectionStage.COMPLETE || it.feedback?.isError == true }
        }
        assertEquals(connected.feedback?.message, ConnectionStage.COMPLETE, connected.connectionStage)
        assertEquals(4, deps.photoRepository().getAll().count { it.sourceType == "server" })
        ActivityScenario.launch(MainActivity::class.java).use {
            delay(2_000)
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val file = File(context.filesDir, "acceptance/marketing-first-connect.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Unit
    }
}

package com.example.youyou_album.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 守住 2026-10-25 的全选闪退根因：Intent 只许带 taskId。
 * 一旦有人把 id 列表塞回 extras，选择规模会重新顶到 Binder 事务上限。
 */
@RunWith(AndroidJUnit4::class)
class TransferStartIntentTest {
    @Test
    fun startIntentCarriesOnlyTaskId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            UploadForegroundService.buildIntent(context, "task-1"),
            DownloadForegroundService.buildIntent(context, "task-2"),
        ).forEach { intent ->
            assertEquals(setOf("task_id"), intent.extras!!.keySet())
        }
    }
}

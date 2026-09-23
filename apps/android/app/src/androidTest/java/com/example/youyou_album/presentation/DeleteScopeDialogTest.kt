package com.example.youyou_album.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.presentation.widgets.DeleteScopeDialog
import com.example.youyou_album.service.MediaDeletionService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteScopeDialogTest {
    @get:Rule val rule = createComposeRule()

    private val photos = listOf(
        Photo("local", "local.jpg", ""),
        Photo("both", "both.jpg", ""),
        Photo("remote", "remote.jpg", ""),
    )
    private val preview = listOf(
        MediaDeletionService.DeletePreviewItem("local", true, false),
        MediaDeletionService.DeletePreviewItem("both", true, true),
        MediaDeletionService.DeletePreviewItem("remote", false, true),
    )

    @Test fun mixedBatchShowsApplicableAndSkippedCounts() {
        var selected: MediaDeletionService.DeleteScope? = null
        rule.setContent {
            MaterialTheme {
                DeleteScopeDialog(photos, preview, true, {}, { selected = it })
            }
        }
        rule.onNodeWithText("本次范围内 3 项，跳过 0 项。手机原件移入系统回收机制。").assertIsDisplayed()
        rule.onNodeWithText("仅从服务器移除").performClick()
        rule.onNodeWithText("本次范围内 2 项，跳过 1 项。手机原件保留。").assertIsDisplayed()
        rule.onNodeWithText("确认移除").performClick()
        assertEquals(MediaDeletionService.DeleteScope.SERVER, selected)
    }

    @Test fun offlineKeepsRemoteScopesUnavailable() {
        var selected: MediaDeletionService.DeleteScope? = null
        rule.setContent {
            MaterialTheme {
                DeleteScopeDialog(photos, preview, false, {}, { selected = it })
            }
        }
        rule.onNodeWithText("当前离线，涉及服务器的操作不可用。请连接服务端后再试。").assertIsDisplayed()
        rule.onNodeWithText("仅从服务器移除").performClick()
        rule.onNodeWithText("本次范围内 2 项，跳过 1 项。手机原件移入系统回收机制。").assertIsDisplayed()
        rule.onNodeWithText("确认移除").performClick()
        assertEquals(MediaDeletionService.DeleteScope.PHONE, selected)
    }
}

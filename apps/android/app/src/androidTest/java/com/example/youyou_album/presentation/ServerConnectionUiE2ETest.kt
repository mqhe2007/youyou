package com.example.youyou_album.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.youyou_album.MainActivity
import com.example.youyou_album.util.TestDependencies
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * 服务器连接页面 UI E2E 测试。
 *
 * 验证连接管理页面的核心 UI 元素和交互：
 * 1. 页面标题和扫码连接卡片
 * 2. 未连接状态下的按钮状态
 * 3. 返回导航
 */
@RunWith(AndroidJUnit4::class)
class ServerConnectionUiE2ETest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * 真机保护：先判定是否允许改写真实状态，**再**启动真实 MainActivity。
     * 顺序由 `RuleChain` 保证——写在 `@Before` 里已经太晚，那时 Activity 已被拉起、真实库已被写过。
     */
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDependencies.realDeviceGuard()).around(composeRule)

    @Before
    fun enterLocalOnlyApp() {
        val skip = composeRule.onAllNodesWithText("暂不绑定")
        if (skip.fetchSemanticsNodes().isNotEmpty()) skip.onFirst().performClick()
    }

    /** 导航到服务器连接页面：首页 → 更多 → 设置 → 连接管理 */
    private fun navigateToServerConnection() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("连接管理").assertIsDisplayed()
        composeRule.onNodeWithText("连接管理").performClick()
    }

    @Test
    fun serverConnectionPage_titleIsDisplayed() {
        navigateToServerConnection()
        composeRule.onNodeWithText("连接管理").assertIsDisplayed()
    }

    @Test
    fun serverConnectionPage_scanCardIsDisplayed() {
        navigateToServerConnection()
        composeRule.onNodeWithText("扫码连接").assertIsDisplayed()
        composeRule.onNodeWithText("受邀成员向管理员索取自己的二维码；管理员先部署服务，再到管理端「用户」页生成。").assertIsDisplayed()
        composeRule.onNodeWithText("查看部署说明").assertIsDisplayed()
    }

    @Test
    fun serverConnectionPage_scanButtonIsEnabledWhenNotConnected() {
        navigateToServerConnection()
        val button = composeRule.onNodeWithText("扫码连接")
        button.assertIsDisplayed()
        button.assertIsEnabled()
    }

    @Test
    fun serverConnectionPage_backButtonReturnsToSettings() {
        navigateToServerConnection()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithText("清除缓存").assertIsDisplayed()
    }

    @Test
    fun serverConnectionPage_manualDeviceNameHintIsDisplayed() {
        navigateToServerConnection()
        composeRule.onNodeWithText("手动输入").performClick()
        composeRule.onNodeWithText("设备名称（可选）").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("留空则自动获取").assertIsDisplayed()
    }
}

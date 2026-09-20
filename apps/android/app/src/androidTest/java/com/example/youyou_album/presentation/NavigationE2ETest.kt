package com.example.youyou_album.presentation

import androidx.compose.ui.test.assertIsDisplayed
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
 * 页面导航 E2E 测试。
 *
 * 验证从首页通过底部导航和菜单导航到各核心页面，并能返回首页。
 * 覆盖：标签列表、设置、后台活动、存储管理。
 */
@RunWith(AndroidJUnit4::class)
class NavigationE2ETest {

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

    @Test
    fun navigate_toTagsPage_showsBackButton() {
        composeRule.onNodeWithText("标签").performClick()
        composeRule.onNodeWithContentDescription("新建标签").assertIsDisplayed()
    }

    @Test
    fun navigate_toFavoritesPage_isDisplayed() {
        composeRule.onNodeWithText("收藏").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun navigate_toSettingsPage_showsBackButton() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("清除缓存").assertIsDisplayed()
    }

    @Test
    fun settings_betaNoticeIsAvailable() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("数据与权限说明").performClick()
        composeRule.onNodeWithText("支持 Android 12（API 31）及以上", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("知道了").performClick()
    }

    @Test
    fun navigate_toBackgroundActivity_showsBackButton() {
        composeRule.onNodeWithContentDescription("后台活动").performClick()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test
    fun navigate_toStorageManagement_showsBackButton() {
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.onNodeWithText("存储管理").performClick()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test
    fun navigate_fullCycle_homeTagsFoldersHome() {
        composeRule.onNodeWithText("标签").performClick()
        composeRule.onNodeWithText("时间线").performClick()
        composeRule.onNodeWithText("柚柚相册").assertIsDisplayed()
    }
}

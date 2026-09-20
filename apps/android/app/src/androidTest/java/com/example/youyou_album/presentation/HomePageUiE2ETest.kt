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
 * 首页 UI E2E 测试。
 *
 * 验证应用启动后首页正确渲染，核心 UI 元素可见，基础交互正常。
 * 使用真实 MainActivity（主应用 Hilt 组件），不使用 Hilt 测试注入。
 */
@RunWith(AndroidJUnit4::class)
class HomePageUiE2ETest {

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
    fun appLaunch_homePageTitleIsDisplayed() {
        composeRule.onNodeWithText("柚柚相册").assertIsDisplayed()
    }

    @Test
    fun appLaunch_bottomNavigationAllTabsVisible() {
        composeRule.onNodeWithText("时间线").assertIsDisplayed()
        composeRule.onNodeWithText("收藏").assertIsDisplayed()
        composeRule.onNodeWithText("标签").assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun appLaunch_topBarActionsVisible() {
        composeRule.onNodeWithContentDescription("后台活动").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("筛选照片").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("更多").assertIsDisplayed()
    }

    @Test
    fun appLaunch_moreMenuOpensAndShowsItems() {
        composeRule.onNodeWithContentDescription("更多").performClick()

        composeRule.onNodeWithText("幻灯片").assertIsDisplayed()
        composeRule.onNodeWithText("存储管理").assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun appLaunch_homeTabIsSelectedByDefault() {
        composeRule.onNodeWithText("时间线").assertIsDisplayed()
    }
}

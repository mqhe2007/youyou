package com.example.youyou_album.presentation.navigation

import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.youyou_album.R
import com.example.youyou_album.presentation.favorites.FavoritesPage
import com.example.youyou_album.presentation.home.HomePage
import com.example.youyou_album.presentation.login.LoginPage
import com.example.youyou_album.presentation.photo.PhotoDetailPage
import com.example.youyou_album.presentation.photo.SlideshowPage
import com.example.youyou_album.presentation.server.QrScannerPage
import com.example.youyou_album.presentation.server.ServerConnectionPage
import com.example.youyou_album.presentation.settings.SettingsPage
import com.example.youyou_album.presentation.storage.StoragePage
import com.example.youyou_album.presentation.tag.TagDetailPage
import com.example.youyou_album.presentation.tag.TagListPage
import com.example.youyou_album.presentation.task.TaskCenterPage

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val HOME = "home"
    const val FAVORITES = "favorites"
    const val TAGS = "tags"
    const val TAG_DETAIL = "tag/{tagId}"
    const val PHOTO_DETAIL = "photo/{photoId}"
    const val SETTINGS = "settings"
    const val SERVER_CONNECTION = "server_connection"
    const val QR_SCANNER = "qr_scanner"
    const val TASK_CENTER = "task_center"
    const val SLIDESHOW = "slideshow"
    const val STORAGE = "storage"

    fun tagDetail(tagId: String) = "tag/$tagId"
    fun photoDetail(photoId: String) = "photo/$photoId"
}

// 二级页面采用主流横向层级转场：子页整页从右进入/向右退出，父层向左位移并轻微压暗。
// 280ms 位于主流 280–350ms 区间，`0.2, 0, 0, 1` 是强调减速曲线，末段收得稳。
private const val PAGE_TRANSITION_DURATION_MS = 280

/** 沉浸查看器的原地淡入淡出：与沉浸内容节奏一致，略长于普通二级页横移。 */
private const val DETAIL_FADE_DURATION_MS = 450
private const val PARENT_PARALLAX_FRACTION = 0.25f
private const val PARENT_DIM_MAX_ALPHA = 0.10f
private val PageTransitionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// 顶级标签之间没有父子层级，方向性位移是假隐喻，改用 Material 的 fade-through：
// 旧页先淡出（前 30%），新页再淡入（后 70%），两段不重叠，中间以页面背景色承接，
// 因此不会出现两页正文叠化重影。
private const val TAB_FADE_DURATION_MS = 240
private const val TAB_FADE_OUT_MS = 72
private const val TAB_FADE_IN_DELAY_MS = 72
private const val TAB_FADE_IN_MS = TAB_FADE_DURATION_MS - TAB_FADE_IN_DELAY_MS

private fun systemAnimationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

/** 顶级标签的新页：站在旧页淡出之后才起步，两段不重叠。 */
private fun tabFadeIn(motionEnabled: Boolean): EnterTransition =
    if (motionEnabled) {
        fadeIn(
            animationSpec = tween(
                durationMillis = TAB_FADE_IN_MS,
                delayMillis = TAB_FADE_IN_DELAY_MS,
                easing = PageTransitionEasing,
            ),
        )
    } else EnterTransition.None

/** 顶级标签的旧页：先淡出到页面背景色，不透明度混合，不透出窗口白底。 */
private fun tabFadeOut(motionEnabled: Boolean): ExitTransition =
    if (motionEnabled) {
        fadeOut(
            animationSpec = tween(
                durationMillis = TAB_FADE_OUT_MS,
                easing = PageTransitionEasing,
            ),
        )
    } else ExitTransition.None

/**
 * 二级页的新页：从屏幕右侧完整滑入。
 * 父页在 [MainShellLayer] 中同步向左位移并压暗，形成主流 App 的层级视差。
 */
private fun pageEnter(motionEnabled: Boolean): EnterTransition =
    if (motionEnabled) {
        slideInHorizontally(
            animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = PageTransitionEasing),
            initialOffsetX = { fullWidth -> fullWidth },
        )
    } else EnterTransition.None

/** 二级页前进时，下面的页面只向左移 25%，不做整页退出，保证两页始终有内容承接。 */
private fun pageExit(motionEnabled: Boolean): ExitTransition =
    if (motionEnabled) {
        slideOutHorizontally(
            animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = PageTransitionEasing),
            targetOffsetX = { fullWidth -> -(fullWidth * PARENT_PARALLAX_FRACTION).toInt() },
        )
    } else ExitTransition.None

/** 返回时，被重新露出的页面从左侧 25% 位置回到原位，与上层子页的退出同步。 */
private fun pagePopEnter(motionEnabled: Boolean): EnterTransition =
    if (motionEnabled) {
        slideInHorizontally(
            animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = PageTransitionEasing),
            initialOffsetX = { fullWidth -> -(fullWidth * PARENT_PARALLAX_FRACTION).toInt() },
        )
    } else EnterTransition.None

/** 返回时，子页向右完整滑出，不再出现“卡一下直接消失”。 */
private fun pagePopExit(motionEnabled: Boolean): ExitTransition =
    if (motionEnabled) {
        slideOutHorizontally(
            animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = PageTransitionEasing),
            targetOffsetX = { fullWidth -> fullWidth },
        )
    } else ExitTransition.None

/**
 * 照片详情（沉浸查看器）不走整页横移，也不做共享元素缩放：黑底查看器原地淡入淡出。
 * 父层保持原位——查看器是覆盖式全屏层，位移会读成两层页面互相推挤。
 */
private fun detailFadeIn(motionEnabled: Boolean): EnterTransition =
    if (motionEnabled) {
        fadeIn(
            animationSpec = tween(DETAIL_FADE_DURATION_MS, easing = PageTransitionEasing),
        )
    } else EnterTransition.None

/** 返回时查看器原地淡出，露出下面的主框架。 */
private fun detailFadeOut(motionEnabled: Boolean): ExitTransition =
    if (motionEnabled) {
        fadeOut(
            animationSpec = tween(DETAIL_FADE_DURATION_MS, easing = PageTransitionEasing),
        )
    } else ExitTransition.None

@Composable
fun AppNavGraph(navController: NavHostController, startOnLogin: Boolean) {
    val motionEnabled = systemAnimationsEnabled()
    val outerBackStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val currentRoute = outerBackStack.lastOrNull()?.destination?.route

    // 主框架不再作为 NavHost 里的普通目的地：它常驻在二级页下方，
    // 返回时只做位移，不会触发底部栏和当前 Tab 页的重建。
    // 注意不能用“当前路由不是登录页”来判断：登录页也能进扫码页，
    // 必须确认外层返回栈里确实有 MAIN，主框架才应该出现在下层。
    val hasMainDestination = outerBackStack.any { it.destination.route == Routes.MAIN }
    val showMainShell = hasMainDestination && currentRoute != Routes.LOGIN
    val isChildOpen = showMainShell && currentRoute != Routes.MAIN

    // 内部 Tab NavHost 有自己的返回栈；如果它和 outer NavHost 共用 Activity 的返回分发器，
    // 返回优先级会随注册时机漂移（实测会出现“第一次返回先切 Tab”）。这里给主框架一个
    // 独立的返回分发器，再用 forwardBackToShell 只在没有二级页时把返回事件转发进来。
    val activity = LocalContext.current as? ComponentActivity
    val activityBackOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
        "AppNavGraph 需要 Activity 提供 OnBackPressedDispatcherOwner"
    }
    val shellBackDispatcher = remember(activity, activityBackOwner) {
        OnBackPressedDispatcher { activity?.finish() }
    }
    val shellBackOwner = remember(activityBackOwner, shellBackDispatcher) {
        object : OnBackPressedDispatcherOwner {
            override val lifecycle: Lifecycle get() = activityBackOwner.lifecycle
            override val onBackPressedDispatcher: OnBackPressedDispatcher get() = shellBackDispatcher
        }
    }
    val forwardBackToShell = remember(activityBackOwner, shellBackDispatcher) {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                shellBackDispatcher.onBackPressed()
            }
        }
    }
    DisposableEffect(activityBackOwner, forwardBackToShell) {
        activityBackOwner.onBackPressedDispatcher.addCallback(activityBackOwner, forwardBackToShell)
        onDispose { forwardBackToShell.remove() }
    }
    SideEffect {
        forwardBackToShell.isEnabled = showMainShell && !isChildOpen
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (showMainShell) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides shellBackOwner) {
                MainShellLayer(
                    outerController = navController,
                    isChildOpen = isChildOpen,
                    // 照片详情是沉浸查看器：原地淡入淡出，父层不做视差也不压暗。
                    immersiveChildOpen = isChildOpen && currentRoute == Routes.PHOTO_DETAIL,
                    motionEnabled = motionEnabled,
                )
            }
        }

                // 外层 NavHost 的返回回调之外再加一层兜底，防止回调注册时机变化导致子页无法返回。
                // 子页内的多选 BackHandler 注册更晚，仍优先处理；这里的回调只负责弹出子页。
                BackHandler(enabled = showMainShell && isChildOpen) {
                    navController.popBackStack()
                }

                // 子页打开时，主框架仍在组合树中用于动画承接，但必须屏蔽触摸和无障碍语义，
                // 否则子页空白区域的点击会穿透到底部 Tab，TalkBack 也会读到下层页面。
                if (isChildOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false).consume()
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    )
                }

                NavHost(
                    navController = navController,
                    startDestination = if (startOnLogin) Routes.LOGIN else Routes.MAIN,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        if (targetState.destination.route == Routes.PHOTO_DETAIL) {
                            detailFadeIn(motionEnabled)
                        } else {
                            pageEnter(motionEnabled)
                        }
                    },
                    // 照片详情进场时父层保持原位，查看器原地淡入。
                    exitTransition = {
                        if (targetState.destination.route == Routes.PHOTO_DETAIL) {
                            ExitTransition.None
                        } else {
                            pageExit(motionEnabled)
                        }
                    },
                    popEnterTransition = {
                        if (initialState.destination.route == Routes.PHOTO_DETAIL) {
                            EnterTransition.None
                        } else {
                            pagePopEnter(motionEnabled)
                        }
                    },
                    popExitTransition = {
                        if (initialState.destination.route == Routes.PHOTO_DETAIL) {
                            detailFadeOut(motionEnabled)
                        } else {
                            pagePopExit(motionEnabled)
                        }
                    },
                ) {
                    composable(Routes.LOGIN) {
                        LoginPage(
                            navController = navController,
                            onEnterApp = {
                                navController.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } }
                            },
                        )
                    }
                    composable(Routes.MAIN) {
                        // 主框架由 AppNavGraph 常驻渲染；这里保留 MAIN 作为返回栈锚点，
                        // 保证系统返回、手势返回和原有导航语义不变。
                        Box(modifier = Modifier.fillMaxSize())
                    }
                    composable(
                        route = Routes.TAG_DETAIL,
                        arguments = listOf(navArgument("tagId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val tagId = backStackEntry.arguments?.getString("tagId") ?: return@composable
                        TagDetailPage(
                            tagId = tagId,
                            onBack = { navController.popBackStack() },
                            onPhotoClick = { photoId ->
                                navController.navigate(Routes.photoDetail(photoId))
                            },
                        )
                    }
                    composable(
                        route = Routes.PHOTO_DETAIL,
                        arguments = listOf(navArgument("photoId") { type = NavType.StringType }),
                    ) {
                        PhotoDetailPage(
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        Routes.SERVER_CONNECTION,
                    ) {
                        ServerConnectionPage(
                            navController = navController,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        Routes.QR_SCANNER,
                    ) {
                        QrScannerPage(
                            onBack = { navController.popBackStack() },
                            onCodeScanned = { code ->
                                navController.previousBackStackEntry?.savedStateHandle?.set("qr_code", code)
                                navController.popBackStack()
                            },
                        )
                    }
                    composable(
                        Routes.TASK_CENTER,
                    ) {
                        TaskCenterPage(onBack = { navController.popBackStack() })
                    }
                    composable(
                        Routes.SLIDESHOW,
                    ) {
                        SlideshowPage(onBack = { navController.popBackStack() })
                    }
                    composable(
                        Routes.STORAGE,
                    ) {
                        StoragePage(onBack = { navController.popBackStack() })
                    }
                }
    }
}

/**
 * 常驻主框架层。
 *
 * [isChildOpen] 为 true 时，整个主框架向左位移 25% 并压暗，作为二级页的父层承接动画；
 * 返回时再回到原位。位移走 [graphicsLayer]，只改变渲染变换，不触发重新测量/排版。
 */
@Composable
private fun MainShellLayer(
    outerController: NavHostController,
    isChildOpen: Boolean,
    motionEnabled: Boolean,
    /** 照片详情是沉浸查看器：原地淡入淡出，父层不做视差也不压暗。 */
    immersiveChildOpen: Boolean = false,
) {
    val parallaxTarget = isChildOpen && !immersiveChildOpen
    val parallax = updateTransition(targetState = parallaxTarget, label = "main-shell-parallax")
    val progress by parallax.animateFloat(
        transitionSpec = {
            if (motionEnabled) {
                tween(durationMillis = PAGE_TRANSITION_DURATION_MS, easing = PageTransitionEasing)
            } else {
                snap()
            }
        },
        label = "main-shell-parallax-progress",
    ) { childOpen -> if (childOpen) 1f else 0f }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val parallaxDistancePx = constraints.maxWidth * PARENT_PARALLAX_FRACTION
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -parallaxDistancePx * progress }
                .then(if (isChildOpen) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            ProvideMainShellLifecycle(isForeground = !isChildOpen) {
                MainShell(outerController)
            }
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = progress * PARENT_DIM_MAX_ALPHA)),
                )
            }
        }
    }
}

/**
 * 让主框架内部页面的 Lifecycle 跟随“主框架是否在前台”：
 * 打开二级页时压到 STARTED，返回时恢复 RESUMED。
 *
 * 这样主框架虽然常驻但不会在子页期间继续做前台页才做的事；
 * 时间线返回时仍会触发一次 `LifecycleResumeEffect`，远程同步刷新语义保持不变。
 */
@Composable
private fun ProvideMainShellLifecycle(
    isForeground: Boolean,
    content: @Composable () -> Unit,
) {
    val hostOwner = LocalLifecycleOwner.current
    val shellOwner = remember(hostOwner) { ShellLifecycleOwner() }
    val foreground by rememberUpdatedState(isForeground)

    DisposableEffect(hostOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            shellOwner.sync(hostOwner.lifecycle.currentState, foreground)
        }
        hostOwner.lifecycle.addObserver(observer)
        shellOwner.sync(hostOwner.lifecycle.currentState, foreground)
        onDispose {
            hostOwner.lifecycle.removeObserver(observer)
            shellOwner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    SideEffect {
        shellOwner.sync(hostOwner.lifecycle.currentState, isForeground)
    }

    CompositionLocalProvider(LocalLifecycleOwner provides shellOwner, content = content)
}

/** 跟随宿主 Lifecycle，但允许在主框架被二级页覆盖时把前台状态压到 STARTED。 */
private class ShellLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun sync(hostState: Lifecycle.State, isForeground: Boolean) {
        val target = when {
            hostState <= Lifecycle.State.CREATED -> hostState
            !isForeground -> Lifecycle.State.STARTED
            else -> hostState
        }
        if (registry.currentState != target) {
            registry.currentState = target
        }
    }
}

/**
 * 主框架：时间线 / 收藏 / 标签 / 设置 四个真实路由 Tab，
 * 底部导航由导航系统持有状态（解决假 Tab 的指示与内容脱节）。
 */
@Composable
private fun MainShell(outerController: NavHostController) {
    val tabController = rememberNavController()
    val backStackEntry by tabController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val motionEnabled = systemAnimationsEnabled()

    fun openPhotoDetail(photoId: String) {
        outerController.navigate(Routes.photoDetail(photoId))
    }

    val tabs = listOf(
        Triple(Routes.HOME, R.drawable.lucide_ic_house, "时间线"),
        Triple(Routes.FAVORITES, R.drawable.lucide_ic_heart, "收藏"),
        Triple(Routes.TAGS, R.drawable.lucide_ic_tag, "标签"),
        Triple(Routes.SETTINGS, R.drawable.lucide_ic_settings, "设置"),
    )

    // 激活指示器走品牌色「实底药丸」，与 AppSnackbarHost 同一套语言。
    // 不用 M3 默认的 secondaryContainer（= Primary 15% 叠色）：鲜柚黄明度贴近暖象牙，
    // 淡底与页面底 ΔE 只有 12，读作米色（浅色模式）；叠在近黑上则会变成橄榄棕 #3C341C，
    // 且深色方案里 onSecondaryContainer 是近黑 #1C1B1A，选中图标对比度仅 1.39:1，
    // 反而弱于未选中态。
    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primary,
    )

    Scaffold(
        // HomePage 的 TopAppBar 已处理状态栏 inset；外层再应用一次会让整个
        // 时间线向下偏移一个状态栏高度。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                tabs.forEach { (route, icon, label) ->
                    NavigationBarItem(
                        colors = navItemColors,
                        icon = { Icon(painterResource(icon), contentDescription = label) },
                        label = { Text(label) },
                        selected = currentRoute == route,
                        onClick = {
                            if (currentRoute != route) tabController.navigate(route) {
                                popUpTo(tabController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = tabController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            enterTransition = { tabFadeIn(motionEnabled) },
            exitTransition = { tabFadeOut(motionEnabled) },
            popEnterTransition = { tabFadeIn(motionEnabled) },
            popExitTransition = { tabFadeOut(motionEnabled) },
        ) {
            composable(Routes.HOME) {
                HomePage(
                    onPhotoClick = { photoId -> openPhotoDetail(photoId) },
                    onNavigateToTaskCenter = { outerController.navigate(Routes.TASK_CENTER) },
                    onNavigateToSlideshow = { outerController.navigate(Routes.SLIDESHOW) },
                    onNavigateToStorage = { outerController.navigate(Routes.STORAGE) },
                    onNavigateToServerConnection = {
                        outerController.navigate(Routes.SERVER_CONNECTION)
                    },
                )
            }
            composable(Routes.FAVORITES) {
                FavoritesPage(
                    onPhotoClick = { photoId -> openPhotoDetail(photoId) },
                )
            }
            composable(Routes.TAGS) {
                TagListPage(
                    onTagClick = { tagId ->
                        outerController.navigate(Routes.tagDetail(tagId))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsPage(
                    onNavigateToServerConnection = {
                        outerController.navigate(Routes.SERVER_CONNECTION)
                    },
                )
            }
        }
    }
}

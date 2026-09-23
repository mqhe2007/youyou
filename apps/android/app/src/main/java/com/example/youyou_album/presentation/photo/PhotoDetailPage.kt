package com.example.youyou_album.presentation.photo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.example.youyou_album.presentation.widgets.AppSnackbarHost
import com.example.youyou_album.presentation.widgets.AppTextButton
import com.example.youyou_album.presentation.widgets.MediaDeleteLauncher
import com.example.youyou_album.presentation.widgets.deleteConfirmMessage
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.youyou_album.R
import com.example.youyou_album.ui.theme.Favorite
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.request.ImageRequest
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.LiveMotionSource
import kotlinx.coroutines.withTimeoutOrNull
import com.example.youyou_album.domain.model.Photo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailPage(
    onBack: () -> Unit,
    viewModel: PhotoDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val deleteStep by viewModel.mediaDelete.step.collectAsStateWithLifecycle()
    val deleteBusy by viewModel.mediaDelete.busy.collectAsStateWithLifecycle()
    val deleteMessage by viewModel.mediaDelete.message.collectAsStateWithLifecycle()
    val serverConnected by viewModel.serverConnected.collectAsStateWithLifecycle()
    MediaDeleteLauncher(
        step = deleteStep,
        onSystemConfirmation = { session, approved -> viewModel.mediaDelete.onSystemConfirmation(session, approved) },
    )
    var showChrome by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var actionBarHeightPx by remember { mutableIntStateOf(0) }
    val infoSheetState = rememberModalBottomSheetState()

    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount = { uiState.photos.size },
    )

    // Replace the pager observer together with its list, restore selection before accepting swipes.
    LaunchedEffect(uiState.photos, uiState.isLoading) {
        val displayedPhotos = uiState.photos
        if (!uiState.isLoading && displayedPhotos.isNotEmpty()) {
            pagerState.scrollToPage(uiState.currentIndex)
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page -> viewModel.setCurrentIndex(page, displayedPhotos) }
        }
    }

    val currentPhoto = uiState.photos.getOrNull(uiState.currentIndex)
    val actionBarHeight = with(LocalDensity.current) { actionBarHeightPx.toDp() }

    LaunchedEffect(currentPhoto?.id) {
        showChrome = true
    }

    LaunchedEffect(deleteMessage) {
        val msg = deleteMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.mediaDelete.clearMessage()
        if (viewModel.uiState.value.photos.isEmpty()) onBack()
    }

    if (showDeleteDialog && currentPhoto != null) {
        val deletePreview by viewModel.mediaDelete.preview.collectAsStateWithLifecycle()
        LaunchedEffect(currentPhoto.id) { viewModel.mediaDelete.prepare(listOf(currentPhoto.id)) }
        com.example.youyou_album.presentation.widgets.DeleteScopeDialog(
            photos = listOf(currentPhoto),
            preview = deletePreview,
            online = serverConnected && com.example.youyou_album.presentation.widgets.deleteNetworkAvailable(androidx.compose.ui.platform.LocalContext.current),
            onDismiss = { showDeleteDialog = false },
            onConfirm = { scope ->
                showDeleteDialog = false
                viewModel.deleteCurrentPhoto(scope)
            },
        )
    }

    // 沉浸查看器：画廊黑全出血，chrome 为半透明浮层（设计系统 galleryBackground）
    val containerColor = com.example.youyou_album.ui.theme.GalleryBackground
    val contentColor = Color.White
    // 浮层可读性：0.35 的 scrim 在亮色照片上不足以支撑白前景（WCAG 非文本 3:1），加强到 0.55
    val scrimColor = Color.Black.copy(alpha = 0.55f)

    // 进入前记录系统栏真实状态，沉浸态统一画廊黑与浅色图标；退出按原值恢复，
    // 避免浅/深主题、后台恢复或弹层关闭后被硬编码成浅色主题。
    val activity = context as? android.app.Activity
    DisposableEffect(activity, containerColor) {
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        val previousStatusBarColor = window?.statusBarColor
        val previousNavigationBarColor = window?.navigationBarColor
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
        window?.statusBarColor = containerColor.toArgb()
        window?.navigationBarColor = containerColor.toArgb()
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatusBarColor?.let { window.statusBarColor = it }
            previousNavigationBarColor?.let { window.navigationBarColor = it }
            previousLightStatusBars?.let { controller?.isAppearanceLightStatusBars = it }
            previousLightNavigationBars?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(containerColor),
    ) {
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", color = contentColor)
                }
            }
            uiState.photos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("照片不存在", color = contentColor)
                }
            }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val photo = uiState.photos[page]
                    PhotoDetailContent(
                        photo = photo,
                        active = page == pagerState.currentPage,
                        showChrome = showChrome,
                        onShowChromeChange = { showChrome = it },
                        videoRequestHeaders = viewModel.videoRequestHeaders(photo),
                        bottomControlsPadding = actionBarHeight,
                        topControlsPadding = actionBarHeight,
                        resolveLiveMotion = viewModel::liveMotionSource,
                    )
                }
            }
        }

        // 顶部浮层：返回 / 文件名 / 序号 / 更多
        if (showChrome) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(scrimColor, Color.Transparent),
                        ),
                    )
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回", tint = contentColor)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentPhoto?.name ?: "照片详情",
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 1,
                    )
                    if (uiState.photos.isNotEmpty()) {
                        Text(
                            text = "${uiState.currentIndex + 1}/${uiState.photos.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                }
                // 右上角不再设「更多」菜单：同步是详情页的主线动作，与分享/收藏同在底部操作栏，
                // 避免同一动作出现两个入口，也避免菜单里只剩下「已同步，无需操作」这种非动作项。
            }

            // 底部浮层操作栏
            if (currentPhoto != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { actionBarHeightPx = it.height }
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Transparent, scrimColor),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 同步项按备份三态就地分化：仅本机「同步」上传、仅远程「下载」落盘、
                    // 已同步没有可执行的搬运，按钮禁用并把标签换成状态词「已同步」，
                    // 这样它读起来是一个结论而不是「有个动作但点不动」（PRD FR-4）。
                    val syncDisplay = currentPhoto.syncDisplay
                    val synced = syncDisplay == MediaSyncDisplay.SYNCED
                    BottomActionItem(
                        icon = painterResource(
                            when (syncDisplay) {
                                MediaSyncDisplay.LOCAL_ONLY -> R.drawable.lucide_ic_cloud_upload
                                MediaSyncDisplay.REMOTE_ONLY -> R.drawable.lucide_ic_cloud_download
                                MediaSyncDisplay.SYNCED -> R.drawable.lucide_ic_check
                            },
                        ),
                        label = when (syncDisplay) {
                            MediaSyncDisplay.LOCAL_ONLY -> "上传"
                            MediaSyncDisplay.REMOTE_ONLY -> "下载"
                            MediaSyncDisplay.SYNCED -> "两端都有"
                        },
                        tint = if (synced) contentColor.copy(alpha = 0.38f) else contentColor,
                        enabled = !synced,
                        onClick = {
                            coroutineScope.launch {
                                val result = when (syncDisplay) {
                                    MediaSyncDisplay.LOCAL_ONLY -> viewModel.syncToServer()
                                    MediaSyncDisplay.REMOTE_ONLY -> viewModel.saveToGallery()
                                    MediaSyncDisplay.SYNCED -> return@launch
                                }
                                snackbarHostState.showSnackbar(result)
                            }
                        },
                    modifier = Modifier.weight(1f),
                    )
                    BottomActionItem(
                        icon = painterResource(R.drawable.lucide_ic_share_2),
                        label = "分享",
                        tint = contentColor,
                        onClick = { sharePhoto(context, currentPhoto) },
                    modifier = Modifier.weight(1f),
                    )
                    BottomActionItem(
                        icon = painterResource(R.drawable.lucide_ic_heart),
                        label = if (currentPhoto.isFavorite) "已收藏" else "收藏",
                        tint = if (currentPhoto.isFavorite) Favorite else contentColor,
                        onClick = { viewModel.toggleFavorite() },
                    modifier = Modifier.weight(1f),
                    )
                    BottomActionItem(
                        icon = painterResource(R.drawable.lucide_ic_info),
                        label = "信息",
                        tint = contentColor,
                        onClick = { showInfoSheet = true },
                    modifier = Modifier.weight(1f),
                    )
                    BottomActionItem(
                        icon = painterResource(R.drawable.lucide_ic_trash_2),
                        label = if (deleteBusy) "处理中…" else "删除",
                        enabled = !deleteBusy,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        AppSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        )
    }

    if (showInfoSheet && currentPhoto != null) {
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = infoSheetState,
        ) {
            PhotoInfoSheet(photo = currentPhoto)
        }
    }
}

@Composable
private fun PhotoInfoSheet(photo: Photo) {
    val context = LocalContext.current
    var exifInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(photo.id) {
        if (photo.sourceUri != null && !photo.isVideo) {
            exifInfo = readExif(context, android.net.Uri.parse(photo.sourceUri))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "详细信息",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        InfoRow("文件名", photo.name)
        if (photo.takenAt != null) InfoRow("拍摄时间", formatDateTime(photo.takenAt))
        else if (photo.sortAt != null) InfoRow(if(photo.sortSource == "filename") "文件名时间" else "媒体时间（回退）", formatDateTime(photo.sortAt))
        else InfoRow("媒体时间", "时间未知")
        if (photo.modifiedAt != null) InfoRow("修改时间", formatDateTime(photo.modifiedAt))
        if (photo.width != null && photo.height != null) InfoRow("尺寸", "${photo.width} × ${photo.height}")
        if (photo.size != null) InfoRow("文件大小", formatFileSize(photo.size))
        if (photo.mimeType != null) InfoRow("类型", photo.mimeType)
        if (photo.isVideo && photo.duration != null) InfoRow("时长", formatDuration(photo.duration))

        // EXIF 相机参数
        exifInfo["相机型号"]?.let { InfoRow("相机型号", it) }
        exifInfo["ISO"]?.let { InfoRow("ISO", it) }
        exifInfo["光圈"]?.let { InfoRow("光圈", it) }
        exifInfo["焦距"]?.let { InfoRow("焦距", it) }
        exifInfo["曝光时间"]?.let { InfoRow("曝光时间", it) }

        InfoRow("路径", photo.path)
        InfoRow("存放位置", when (photo.syncDisplay) {
            com.example.youyou_album.domain.model.MediaSyncDisplay.SYNCED -> "手机和服务器都有"
            com.example.youyou_album.domain.model.MediaSyncDisplay.REMOTE_ONLY -> "仅服务器"
            com.example.youyou_album.domain.model.MediaSyncDisplay.LOCAL_ONLY -> "仅本机"
        })
        if (photo.contentHash != null) InfoRow("内容哈希", photo.contentHash.take(16) + "...")
    }
}

private fun readExif(context: android.content.Context, uri: android.net.Uri): Map<String, String> {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = androidx.exifinterface.media.ExifInterface(input)
            buildMap {
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)?.let { put("相机型号", it) }
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED)?.let { put("ISO", it) }
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER)?.let { put("光圈", "f/$it") }
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH)?.let {
                    val parts = it.split("/")
                    if (parts.size == 2) {
                        val focal = parts[0].toDoubleOrNull()?.div(parts[1].toDoubleOrNull() ?: 1.0)
                        if (focal != null) put("焦距", String.format("%.1fmm", focal))
                    } else {
                        put("焦距", it)
                    }
                }
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)?.let {
                    val parts = it.split("/")
                    if (parts.size == 2) {
                        val exp = parts[0].toDoubleOrNull()?.div(parts[1].toDoubleOrNull() ?: 1.0)
                        if (exp != null) {
                            put("曝光时间", if (exp < 1) "1/${(1/exp).toInt()}s" else "${exp}s")
                        }
                    } else {
                        put("曝光时间", it)
                    }
                }
            }
        } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
    }
}

private fun formatDateTime(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

@Composable
private fun BottomActionItem(
    icon: Painter,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // 48dp 最小触控目标；宽度均分让五个操作的点击区一致
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(painter = icon, contentDescription = label, tint = tint, modifier = Modifier.padding(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun PhotoDetailContent(
    photo: Photo,
    active: Boolean,
    showChrome: Boolean,
    onShowChromeChange: (Boolean) -> Unit,
    videoRequestHeaders: Map<String, String>,
    bottomControlsPadding: androidx.compose.ui.unit.Dp,
    topControlsPadding: androidx.compose.ui.unit.Dp,
    resolveLiveMotion: suspend (Photo) -> LiveMotionSource?,
) {
    val model = photoDetailModel(photo)
    // 动态部分的来源要异步解析（远程地址依赖当前服务端身份）。
    var liveMotion by remember(photo.id) { mutableStateOf<LiveMotionSource?>(null) }
    LaunchedEffect(photo.id) { liveMotion = resolveLiveMotion(photo) }
    // D4：默认静态封面，只有显式触发才播放；切页/离开页面立即停止，避免声音残留。
    var playingLive by remember(photo.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) playingLive = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (photo.isVideo) {
                    Modifier
                } else {
                    Modifier.clickable { onShowChromeChange(!showChrome) }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            model == null -> Text("无法预览", color = Color.White)
            else -> {
                val videoUri = photo.sourceUri ?: photo.remoteContentUrl
                val motion = liveMotion
                if (photo.isVideo && videoUri != null) {
                    VideoPlayer(
                        uri = Uri.parse(videoUri),
                        requestHeaders = videoRequestHeaders,
                        active = active,
                        controlsVisible = showChrome,
                        onControlsVisibleChange = onShowChromeChange,
                        bottomControlsPadding = bottomControlsPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (playingLive && motion != null) {
                    // 实况动态部分：不设 imageDurationMs 即按视频播放；播完回到静态封面。
                    VideoPlayer(
                        uri = Uri.parse(motion.uri),
                        requestHeaders = if (motion.requiresAuth) videoRequestHeaders else emptyMap(),
                        active = active,
                        controlsVisible = showChrome,
                        onControlsVisibleChange = onShowChromeChange,
                        bottomControlsPadding = bottomControlsPadding,
                        onPlaybackEnded = { playingLive = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ZoomableImage(
                            model = model,
                            contentDescription = photo.name,
                            // D4：长按图片播放动态部分。
                            onLongPressPlay = motion?.let { { playingLive = true } },
                        )
                        if (photo.livePhoto != null) {
                            LivePhotoMarker(
                                playable = motion != null,
                                onPlay = { playingLive = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    // 顶部浮层会盖住标记并吃掉点按：让出顶栏高度再落位（D4 点按入口）
                                    .padding(top = topControlsPadding + 12.dp, end = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 实况标记（FR-2）：与视频的居中播放角标可区分，点按播放动态部分（D4）。
 * 动态部分不可得时只标记不给入口，不伪造可播放状态。
 */
@Composable
private fun LivePhotoMarker(
    playable: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), shape = CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .then(if (playable) Modifier.clickable(onClick = onPlay) else Modifier)
            .semantics {
                contentDescription = if (playable) {
                    "实况照片，点按播放动态部分"
                } else {
                    "实况照片，动态部分暂不可播放"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (playable) {
            Icon(
                painter = painterResource(R.drawable.lucide_ic_play),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
        Text("实况", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomableImage(
    model: Any,
    contentDescription: String,
    onLongPressPlay: (() -> Unit)? = null,
) {
    me.saket.telephoto.zoomable.coil.ZoomableAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            // D4：长按播放动态部分。只观察长按、不消费任何事件，缩放/平移完全不受影响。
            .then(if (onLongPressPlay != null) Modifier.onLongPressObserved(onLongPressPlay) else Modifier),
    )
}

/**
 * 只观察长按、不消费任何事件的手势修饰符。
 *
 * 图片区已有缩放/平移手势，不能让长按检测抢占 down 事件（实测 `detectTapGestures`
 * 会被 Telephoto 的手势识别吃掉）。这里等到长按超时才触发，期间抬起或产生位移即放弃，
 * 全程不消费事件。
 */
private fun Modifier.onLongPressObserved(onLongPress: () -> Unit): Modifier =
    pointerInput(onLongPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 按住不动时不会有新事件，必须靠超时定时器判定长按，否则永远等不到。
            val completed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // 位移超过触摸阈值就让给缩放/平移（合成事件会有微小抖动，不能按像素级相等判）。
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            }
            // completed == null：等到超时且全程按住无位移 → 长按成立；否则是抬起或移动，放弃。
            if (completed == null) onLongPress()
        }
    }

private fun sharePhoto(context: android.content.Context, photo: Photo) {
    try {
        val uri = when {
            photo.sourceUri != null -> Uri.parse(photo.sourceUri)
            photo.thumbnailPath != null -> {
                val file = File(photo.thumbnailPath)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            else -> null
        }
        if (uri == null) {
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (photo.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${photo.name}"))
    } catch (_: Exception) {
        // 分享失败静默处理
    }
}

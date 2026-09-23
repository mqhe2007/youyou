package com.example.youyou_album.presentation.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.youyou_album.presentation.widgets.AppSnackbarHost
import com.example.youyou_album.presentation.widgets.AppTextButton
import com.example.youyou_album.presentation.widgets.BatchAction
import com.example.youyou_album.presentation.widgets.BatchActionBar
import com.example.youyou_album.presentation.widgets.BatchBarEnterMs
import com.example.youyou_album.presentation.widgets.BatchBarExitMs
import com.example.youyou_album.presentation.common.ServerReachability
import com.example.youyou_album.presentation.common.formatLastSyncedAt
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.youyou_album.R
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.presentation.widgets.EmptyStateView
import com.example.youyou_album.presentation.widgets.ErrorStateView
import com.example.youyou_album.presentation.widgets.PhotoGrid
import com.example.youyou_album.presentation.widgets.PhotoGridSkeleton
import com.example.youyou_album.presentation.widgets.MediaDeleteLauncher
import com.example.youyou_album.presentation.widgets.deleteConfirmMessage
import com.example.youyou_album.presentation.widgets.DeleteScopeDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun HomePage(
    onPhotoClick: (String) -> Unit,
    onNavigateToTaskCenter: () -> Unit,
    onNavigateToSlideshow: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToServerConnection: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backupFilter by viewModel.backupFilter.collectAsStateWithLifecycle()
    val activityTaskCount by viewModel.activityTaskCount.collectAsStateWithLifecycle()
    val trustState by viewModel.trustState.collectAsStateWithLifecycle()
    val firstConnectGuidePending by viewModel.firstConnectGuidePending.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val deleteStep by viewModel.mediaDelete.step.collectAsStateWithLifecycle()
    val deletePreview by viewModel.mediaDelete.preview.collectAsStateWithLifecycle()
    val deleteMessage by viewModel.mediaDelete.message.collectAsStateWithLifecycle()
    MediaDeleteLauncher(
        step = deleteStep,
        onSystemConfirmation = { session, approved -> viewModel.mediaDelete.onSystemConfirmation(session, approved) },
    )
    var menuExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var unboundHintDismissed by rememberSaveable { mutableStateOf(false) }
    var offlineHintDismissed by rememberSaveable { mutableStateOf(false) }

    // 检查中不重置关闭状态，避免同一次离线的重试反复弹出提示。
    LaunchedEffect(trustState.reachability) {
        when (trustState.reachability) {
            ServerReachability.ONLINE -> {
                offlineHintDismissed = false
                unboundHintDismissed = false
            }
            ServerReachability.UNBOUND -> offlineHintDismissed = false
            else -> Unit
        }
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMediaPermissionExplanation by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mediaPermissions = remember {
        when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.any { it }) viewModel.refreshPhotos() }
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun hasMediaScanPermission(): Boolean = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            (granted(android.Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(android.Manifest.permission.READ_MEDIA_VIDEO)) ||
                granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU ->
            granted(android.Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(android.Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun requestMediaScan() {
        if (hasMediaScanPermission()) viewModel.refreshPhotos(notify = true) else showMediaPermissionExplanation = true
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.refreshRemotePhotos()
        onPauseOrDispose { }
    }

    val selecting = uiState.isMultiSelect
    val selectedCount = uiState.selectedPhotoIds.size
    val allIds = photos.map { it.id }
    val allSelected = allIds.isNotEmpty() && allIds.size == selectedCount && allIds.all { it in uiState.selectedPhotoIds }

    // 返回键：多选模式下退出多选
    BackHandler(enabled = selecting) {
        viewModel.clearSelection()
    }

    LaunchedEffect(uiState.scanMessage) {
        uiState.scanMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearScanMessage()
        }
    }

    LaunchedEffect(deleteMessage) {
        deleteMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.mediaDelete.clearMessage()
        }
    }

    val selectedPhotos = photos.filter { it.id in uiState.selectedPhotoIds }
    val deleteOnline = trustState.reachability == ServerReachability.ONLINE

    if (showDeleteDialog) {
        LaunchedEffect(uiState.selectedPhotoIds) { viewModel.mediaDelete.prepare(uiState.selectedPhotoIds.toList()) }
        DeleteScopeDialog(
            photos = selectedPhotos,
            preview = deletePreview,
            online = deleteOnline && com.example.youyou_album.presentation.widgets.deleteNetworkAvailable(context),
            onDismiss = { showDeleteDialog = false },
            onConfirm = { scope ->
                showDeleteDialog = false
                viewModel.deleteSelected(uiState.selectedPhotoIds, scope)
            },
        )
    }
    if (showMediaPermissionExplanation) {
        AlertDialog(
            onDismissRequest = { showMediaPermissionExplanation = false },
            title = { Text("照片访问权限") },
            text = { Text("柚柚相册只在你开始浏览或扫描时读取系统相册，用于建立本机索引和生成缩略图。") },
            confirmButton = { AppTextButton(onClick = { showMediaPermissionExplanation = false; mediaPermissionLauncher.launch(mediaPermissions) }) { Text("继续授权") } },
            dismissButton = { AppTextButton(onClick = { showMediaPermissionExplanation = false }) { Text("暂不授权") } },
        )
    }

    // 时间线是浏览与操作页面：使用固定高度工具栏，首屏优先留给照片内容。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val appBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.background,
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    colors = appBarColors,
                    title = { Text("已选 $selectedCount") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(painterResource(R.drawable.lucide_ic_x), contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.toggleSelectAll(allIds) },
                        ) {
                            Icon(
                                if (allSelected) painterResource(R.drawable.lucide_ic_square_check) else painterResource(R.drawable.lucide_ic_square),
                                contentDescription = if (allSelected) "取消全选" else "全选",
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    colors = appBarColors,
                    title = { Text("柚柚相册") },
                    actions = {
                        IconButton(onClick = { onNavigateToTaskCenter() }) {
                            BadgedBox(
                                badge = {
                                    if (activityTaskCount > 0) {
                                        Badge {
                                            Text(
                                                if (activityTaskCount > 99) "99+" else activityTaskCount.toString()
                                            )
                                        }
                                    }
                                },
                            ) {
                                Icon(painterResource(R.drawable.lucide_ic_list_checks), contentDescription = "后台活动")
                            }
                        }
                        Box {
                            IconButton(onClick = { filterExpanded = true }) {
                                Icon(painterResource(R.drawable.lucide_ic_list_filter), contentDescription = "筛选照片")
                            }
                            DropdownMenu(expanded = filterExpanded, onDismissRequest = { filterExpanded = false }) {
                                listOf(null, MediaSyncDisplay.LOCAL_ONLY, MediaSyncDisplay.REMOTE_ONLY, MediaSyncDisplay.SYNCED).forEach { filter ->
                                    val selected = backupFilter == filter
                                    DropdownMenuItem(
                                        modifier = (if (selected) {
                                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                                        } else {
                                            Modifier
                                        }).semantics { this.selected = selected },
                                        text = {
                                            Text(
                                                text = filterLabel(filter),
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(filterIcon(filter)),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = if (selected) {
                                                    MaterialTheme.colorScheme.onSurface
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        },
                                        onClick = { filterExpanded = false; viewModel.setBackupFilter(filter) },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(painterResource(R.drawable.lucide_ic_ellipsis_vertical), contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("刷新") },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.lucide_ic_refresh_cw),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = { menuExpanded = false; viewModel.refreshTimeline() },
                                )
                                DropdownMenuItem(
                                    text = { Text("幻灯片") },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.lucide_ic_presentation),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = { menuExpanded = false; onNavigateToSlideshow() },
                                )
                                DropdownMenuItem(
                                    text = { Text("存储管理") },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.lucide_ic_hard_drive),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = { menuExpanded = false; onNavigateToStorage() },
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selecting && selectedCount > 0,
                enter = fadeIn(tween(BatchBarEnterMs)) +
                    slideInVertically(tween(BatchBarEnterMs)) { it / 2 },
                exit = fadeOut(tween(BatchBarExitMs)) +
                    slideOutVertically(tween(BatchBarExitMs)) { it / 2 },
            ) {
                BatchActionBar(
                    onDelete = { showDeleteDialog = true },
                    actions = buildList {
                        if (selectedPhotos.any { it.syncDisplay == MediaSyncDisplay.LOCAL_ONLY }) {
                            add(
                                BatchAction(
                                    label = "上传",
                                    icon = R.drawable.lucide_ic_cloud_upload,
                                    primary = true,
                                    onClick = {
                                        viewModel.syncSelectedToServer(uiState.selectedPhotoIds)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("正在上传…")
                                        }
                                        viewModel.clearSelection()
                                    },
                                )
                            )
                        }
                        if (selectedPhotos.any { it.syncDisplay == MediaSyncDisplay.REMOTE_ONLY }) {
                            add(
                                BatchAction(
                                    label = "下载",
                                    icon = R.drawable.lucide_ic_cloud_download,
                                    primary = selectedPhotos.none { it.syncDisplay == MediaSyncDisplay.LOCAL_ONLY },
                                    onClick = {
                                        viewModel.downloadSelected(uiState.selectedPhotoIds)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("正在下载…")
                                        }
                                        viewModel.clearSelection()
                                    },
                                )
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.error == null && !uiState.isInitialLoading) {
                val showUnbound = trustState.reachability == ServerReachability.UNBOUND && !unboundHintDismissed
                val showOffline = trustState.reachability == ServerReachability.OFFLINE && !offlineHintDismissed
                if (showUnbound || showOffline) {
                    ConnectionHint(
                        state = trustState,
                        onManage = onNavigateToServerConnection,
                        onDismiss = { if (showUnbound) unboundHintDismissed = true else offlineHintDismissed = true },
                    )
                }
            }
            if (firstConnectGuidePending && trustState.reachability == ServerReachability.ONLINE && !uiState.isInitialLoading) {
                val remoteFirst = photos.firstOrNull { it.syncDisplay != MediaSyncDisplay.LOCAL_ONLY }
                val localFirst = photos.firstOrNull { it.sourceType != "server" && it.sourceUri != null }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                remoteFirst != null -> "已接入照片库，看看第一张照片"
                                localFirst != null -> "服务器照片库暂无内容，可以上传一张本机照片"
                                uiState.isScanning -> "正在扫描本机照片，完成后可上传第一张"
                                else -> "照片库暂无内容，扫描本机照片后可上传第一张"
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        AppTextButton(
                            enabled = !uiState.isScanning,
                            onClick = {
                                when {
                                    remoteFirst != null -> {
                                        viewModel.finishFirstConnectGuide()
                                        onPhotoClick(remoteFirst.id)
                                    }
                                    localFirst != null -> {
                                        viewModel.syncSelectedToServer(setOf(localFirst.id))
                                        viewModel.finishFirstConnectGuide()
                                    }
                                    else -> requestMediaScan()
                                }
                            },
                        ) {
                            Text(when {
                                remoteFirst != null -> "查看"
                                localFirst != null -> "上传"
                                else -> "扫描"
                            })
                        }
                        IconButton(onClick = { viewModel.finishFirstConnectGuide() }) {
                            Icon(painterResource(R.drawable.lucide_ic_x), contentDescription = "关闭首次接入引导")
                        }
                    }
                }
            }
            if (uiState.error != null) {
                ErrorStateView(
                    error = uiState.error!!,
                    onRetry = { viewModel.retry() },
                )
            } else if (uiState.isInitialLoading) {
                PhotoGridSkeleton()
            } else if (photos.isEmpty() && backupFilter != null && !selecting) {
                EmptyStateView(
                    icon = painterResource(R.drawable.lucide_ic_list_filter),
                    title = "没有符合条件的照片",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    message = "当前筛选：${filterLabel(backupFilter)}",
                    actionLabel = "显示全部",
                    onAction = { viewModel.setBackupFilter(null) },
                )
            } else if (photos.isEmpty() && !selecting) {
                EmptyStateView(
                    icon = painterResource(R.drawable.lucide_ic_images),
                    title = "还没有照片",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    message = "授权后扫描本机照片，远程照片经服务端同步",
                    actionLabel = "扫描媒体",
                    onAction = ::requestMediaScan,
                )
            } else {
                PhotoGrid(
                    photos = photos,
                    selectedIds = uiState.selectedPhotoIds,
                    selectionMode = selecting,
                    onPhotoClick = onPhotoClick,
                    onSelectedIdsChange = { ids ->
                        viewModel.setSelectedPhotos(ids)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun filterLabel(filter: MediaSyncDisplay?): String = when (filter) {
    null -> "全部"
    MediaSyncDisplay.LOCAL_ONLY -> "仅本机"
    MediaSyncDisplay.REMOTE_ONLY -> "仅服务器"
    MediaSyncDisplay.SYNCED -> "手机和服务器都有"
}

private fun filterIcon(filter: MediaSyncDisplay?): Int = when (filter) {
    null -> R.drawable.lucide_ic_images
    MediaSyncDisplay.LOCAL_ONLY -> R.drawable.lucide_ic_smartphone
    MediaSyncDisplay.REMOTE_ONLY -> R.drawable.lucide_ic_cloud
    MediaSyncDisplay.SYNCED -> R.drawable.lucide_ic_cloud_check
}

@Composable
private fun ConnectionHint(state: HomeTrustState, onManage: () -> Unit, onDismiss: () -> Unit) {
    val unbound = state.reachability == ServerReachability.UNBOUND
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (unbound) "连接服务端，按需上传照片" else "服务端暂不可用，本机照片仍可浏览", style = MaterialTheme.typography.bodySmall)
                if (!unbound) Text(formatLastSyncedAt(state.lastSyncedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppTextButton(onClick = onManage, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(if (unbound) "连接" else "查看") }
            IconButton(onClick = onDismiss) {
                Icon(painterResource(R.drawable.lucide_ic_x), contentDescription = "关闭连接提示", modifier = Modifier.size(18.dp))
            }
        }
    }
}

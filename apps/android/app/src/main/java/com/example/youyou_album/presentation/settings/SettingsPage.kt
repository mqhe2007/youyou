package com.example.youyou_album.presentation.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.example.youyou_album.presentation.widgets.AppSnackbarHost
import com.example.youyou_album.presentation.widgets.showAppSnackbar
import com.example.youyou_album.presentation.widgets.AppTextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.example.youyou_album.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youyou_album.BuildConfig
import com.example.youyou_album.presentation.widgets.SettingsSection
import com.example.youyou_album.presentation.widgets.SettingsSectionTitle
import com.example.youyou_album.presentation.widgets.SettingsDivider
import com.example.youyou_album.presentation.widgets.SettingsItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onBack: (() -> Unit)? = null,
    onNavigateToServerConnection: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showBetaNoticeDialog by remember { mutableStateOf(false) }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清除缓存") },
            text = { Text("只清理本机缩略图与原片缓存，不会删除已存储的照片。") },
            confirmButton = {
                AppTextButton(onClick = {
                    showClearCacheDialog = false
                    viewModel.clearCache { success, error ->
                        coroutineScope.launch {
                            snackbarHostState.showAppSnackbar(
                                message = if (success) "缓存已清除" else "清除失败：${error ?: "未知错误"}",
                                isError = !success,
                            )
                        }
                    }
                }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showClearCacheDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("检查更新") },
            text = {
                Text("当前版本：${BuildConfig.VERSION_NAME}\n\n已是最新版本。")
            },
            confirmButton = {
                AppTextButton(onClick = { showUpdateDialog = false }) {
                    Text("确定")
                }
            },
        )
    }


    if (showBetaNoticeDialog) {
        AlertDialog(
            onDismissRequest = { showBetaNoticeDialog = false },
            title = { Text("数据与权限说明") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "柚柚相册是自部署的多用户照片管理应用，支持 Android 12（API 31）及以上。\n\n" +
                            "数据位置：照片原件保存在你部署的服务器或手机本机；本应用不会把照片上传到柚柚官方云端。\n\n" +
                            "权限用途：相册权限用于扫描和显示媒体；相机用于扫描连接二维码；通知用于后台任务；本地网络权限用于连接你的服务端。\n\n" +
                            "本地数据：应用会保存连接地址、设备令牌、媒体索引、缩略图和缓存。卸载或清理缓存会删除对应本地数据，不会删除服务端原件。\n\n" +
                            "使用建议：本应用不构成备份服务。请先备份重要照片，不要将本应用作为唯一备份。\n\n" +
                            "隐私与反馈：当前不接入第三方统计或广告 SDK。完整隐私政策与服务条款见项目网站：https://youyou.mengqinghe.com",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                AppTextButton(onClick = { showBetaNoticeDialog = false }) {
                    Text("知道了")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回")
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 存储与缓存
            SettingsSectionTitle("存储与缓存")
            SettingsSection {
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_ic_folder),
                    title = "连接管理",
                    subtitle = "扫码连接服务端并同步照片",
                    trailing = { Icon(painterResource(R.drawable.lucide_ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = onNavigateToServerConnection,
                )
                SettingsDivider()
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_ic_brush_cleaning),
                    title = "清除缓存",
                    subtitle = "本机缩略图与原片缓存",
                    trailing = {
                        when {
                            uiState.isClearingCache -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            uiState.isCacheLoading -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Text(
                                formatCacheBytes(uiState.cacheSizeBytes),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.W600,
                            )
                        }
                    },
                    onClick = if (uiState.isClearingCache) null else {
                        { showClearCacheDialog = true }
                    },
                )
            }

            // 关于
            SettingsSectionTitle("关于")
            SettingsSection {
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_ic_info),
                    title = "版本",
                    trailing = {
                        Text(
                            BuildConfig.VERSION_NAME,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.W500,
                        )
                    },
                )
                SettingsDivider()
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_ic_download),
                    title = "检查更新",
                    trailing = { Icon(painterResource(R.drawable.lucide_ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { showUpdateDialog = true },
                )
                SettingsDivider()
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_ic_file_text),
                    title = "数据与权限说明",
                    subtitle = "数据、权限与使用建议",
                    trailing = { Icon(painterResource(R.drawable.lucide_ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { showBetaNoticeDialog = true },
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "柚柚相册",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "轻松管理人生影相",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}





private fun formatCacheBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
    return String.format("%.1f MB", bytes / (1024.0 * 1024))
}

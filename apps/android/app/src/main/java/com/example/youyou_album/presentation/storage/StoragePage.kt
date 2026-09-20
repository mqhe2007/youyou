package com.example.youyou_album.presentation.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.example.youyou_album.presentation.widgets.FlatSection
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.example.youyou_album.presentation.widgets.AppOutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youyou_album.R
import com.example.youyou_album.ui.theme.ErrorFill
import com.example.youyou_album.ui.theme.RadiusSm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoragePage(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
    val isCleaning by viewModel.isCleaning.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("存储管理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 设备存储概览
            FlatSection(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    // 标题行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.lucide_ic_hard_drive),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("设备存储", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 大字号已用空间 + 百分比
                    val usedDevice = storageInfo.totalDeviceStorage - storageInfo.availableDeviceStorage
                    val usedPercent = if (storageInfo.totalDeviceStorage > 0) {
                        usedDevice.toFloat() / storageInfo.totalDeviceStorage
                    } else 0f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                formatFileSize(usedDevice),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "已用空间",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            "${(usedPercent * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 进度条
                    LinearProgressIndicator(
                        progress = { usedPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 总计
                    Text(
                        "总计 ${formatFileSize(storageInfo.totalDeviceStorage)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 应用存储明细
            FlatSection(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    // 标题行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.lucide_ic_database),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("应用存储", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val totalApp = storageInfo.totalAppSize
                    StorageDetailRow(
                        icon = R.drawable.lucide_ic_images,
                        label = "缩略图缓存",
                        size = storageInfo.thumbnailCacheSize,
                        total = totalApp,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StorageDetailRow(
                        icon = R.drawable.lucide_ic_image,
                        label = "原图缓存",
                        size = storageInfo.originalCacheSize,
                        total = totalApp,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                    )
                    StorageDetailRow(
                        icon = R.drawable.lucide_ic_database,
                        label = "数据库",
                        size = storageInfo.databaseSize,
                        total = totalApp,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 分隔线
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // 应用总计（突出显示）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.lucide_ic_app_window),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "应用总计",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatFileSize(totalApp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // 缓存清理
            FlatSection(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 标题行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.lucide_ic_brush_cleaning),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("缓存清理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }

                    // 清理中状态
                    if (isCleaning) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("正在清理缓存...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // 清理缩略图缓存
                    AppOutlinedButton(
                        onClick = { viewModel.clearThumbnailCache() },
                        enabled = !isCleaning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.lucide_ic_images), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("清理缩略图缓存")
                    }

                    // 清理原图缓存
                    AppOutlinedButton(
                        onClick = { viewModel.clearOriginalCache() },
                        enabled = !isCleaning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.lucide_ic_image), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("清理原图缓存")
                    }

                    // 清理全部缓存（危险操作：errorFill 实底 + 白字，深浅色通用，4.83:1）
                    Button(
                        onClick = { viewModel.clearAllCache() },
                        enabled = !isCleaning,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ErrorFill,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.lucide_ic_trash_2), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("清理全部缓存", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageDetailRow(
    icon: Int,
    label: String,
    size: Long,
    total: Long,
    iconTint: androidx.compose.ui.graphics.Color,
) {
    val percent = if (total > 0) (size.toFloat() / total).coerceIn(0f, 1f) else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = iconTint,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))

        // 名称 + 进度条
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = iconTint,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))

        // 大小
        Text(
            formatFileSize(size),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(70.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

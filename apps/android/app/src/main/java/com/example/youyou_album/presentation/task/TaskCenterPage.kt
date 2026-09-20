package com.example.youyou_album.presentation.task

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youyou_album.R
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.presentation.widgets.AppSnackbarHost
import com.example.youyou_album.presentation.widgets.AppTextButton
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCenterPage(
    onBack: () -> Unit,
    viewModel: TaskCenterViewModel = hiltViewModel(),
) {
    val runningTasks by viewModel.runningTasks.collectAsStateWithLifecycle()
    val attentionTasks by viewModel.attentionTasks.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("后台活动", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Text(
                text = "这里只显示正在进行和需要处理的任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (runningTasks.isEmpty() && attentionTasks.isEmpty()) {
                NoActivityState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    if (runningTasks.isNotEmpty()) {
                        item(key = "running-header") {
                            ActivitySectionHeader(
                                title = "正在进行",
                                count = runningTasks.size,
                            )
                        }
                        items(runningTasks, key = { "running-${it.id}" }) { task ->
                            RunningTaskRow(
                                task = task,
                                onCancel = {
                                    viewModel.cancelTask(task)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("已取消任务")
                                    }
                                },
                            )
                        }
                    }

                    if (attentionTasks.isNotEmpty()) {
                        item(key = "attention-header") {
                            ActivitySectionHeader(
                                title = "需要处理",
                                count = attentionTasks.size,
                                modifier = Modifier.padding(top = if (runningTasks.isEmpty()) 0.dp else 28.dp),
                            )
                        }
                        items(attentionTasks, key = { "attention-${it.id}" }) { task ->
                            AttentionTaskRow(
                                task = task,
                                onRetry = {
                                    val message = viewModel.retryTask(task)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                },
                                onDismiss = {
                                    viewModel.dismissTask(task)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("已忽略任务")
                                    }
                                },
                            )
                        }
                    }

                    item(key = "auto-remove-note") {
                        Text(
                            text = "完成的任务会自动移除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 28.dp, bottom = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivitySectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$title · $count",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun RunningTaskRow(
    task: AppTask,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskIcon(
                painter = taskIcon(task.kind),
                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = runningDescription(task),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AppTextButton(onClick = onCancel) {
                Text("取消")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (!task.indeterminate && task.total != null && task.total > 0) {
            val progress = (task.current.toFloat() / task.total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${task.current}/${task.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 18.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun AttentionTaskRow(
    task: AppTask,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val failure = task.error ?: task.message ?: "任务未能完成"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskIcon(
                painter = painterResource(R.drawable.lucide_ic_circle_alert),
                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = failedTitle(task),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (failure.length > 50) {
                AppTextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "查看详情")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            AppTextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("重试")
            }
            AppTextButton(onClick = onDismiss) {
                Text("忽略")
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun TaskIcon(
    painter: Painter,
    backgroundColor: Color,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = tint,
        )
    }
}

@Composable
private fun NoActivityState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.lucide_ic_list_checks),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "没有后台活动",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "上传、下载和扫描时会在这里显示进度",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun runningDescription(task: AppTask): String =
    task.phase ?: task.message ?: if (task.status == "pending") "等待开始" else "正在处理"

private fun failedTitle(task: AppTask): String = when (task.kind) {
    "upload" -> "上传已暂停"
    "download" -> "下载已暂停"
    "scan" -> "扫描未完成"
    else -> task.title
}

@Composable
private fun taskIcon(kind: String): Painter = when (kind) {
    "upload" -> painterResource(R.drawable.lucide_ic_cloud_upload)
    "download" -> painterResource(R.drawable.lucide_ic_cloud_download)
    "scan" -> painterResource(R.drawable.lucide_ic_scan_line)
    else -> painterResource(R.drawable.lucide_ic_list_checks)
}

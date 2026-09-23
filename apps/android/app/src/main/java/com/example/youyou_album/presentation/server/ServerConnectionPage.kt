package com.example.youyou_album.presentation.server

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.youyou_album.R
import com.example.youyou_album.domain.model.ServerConnection
import com.example.youyou_album.presentation.common.ServerReachability
import com.example.youyou_album.presentation.common.formatSyncedAgo
import com.example.youyou_album.presentation.widgets.AppOutlinedButton
import com.example.youyou_album.presentation.widgets.AppSnackbarHost
import com.example.youyou_album.presentation.widgets.AppTextButton
import com.example.youyou_album.presentation.widgets.BrandWindow
import com.example.youyou_album.presentation.widgets.NoticeIcon
import com.example.youyou_album.presentation.widgets.NoticeLayout
import com.example.youyou_album.presentation.widgets.appTextFieldColors
import com.example.youyou_album.presentation.widgets.showAppSnackbar
import com.example.youyou_album.ui.theme.ErrorFill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConnectionPage(
    navController: NavController,
    onBack: () -> Unit,
    expandManual: Boolean = false,
    viewModel: ServerConnectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stats by viewModel.serverStats.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 成功与失败都走同一条轻提示通道，页面不再在末尾塞一段红字
    LaunchedEffect(uiState.feedback?.id) {
        val feedback = uiState.feedback ?: return@LaunchedEffect
        snackbarHostState.showAppSnackbar(feedback.message, isError = feedback.isError)
        viewModel.clearFeedback()
    }

    // 监听扫码结果（用 DisposableEffect 确保观察者被正确移除，避免重复触发）
    DisposableEffect(navController) {
        val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
        val observer = androidx.lifecycle.Observer<String> { code ->
            savedStateHandle?.remove<String>("qr_code")
            viewModel.connectWithQr(code)
        }
        savedStateHandle?.getLiveData<String>("qr_code")?.observeForever(observer)
        onDispose {
            savedStateHandle?.getLiveData<String>("qr_code")?.removeObserver(observer)
        }
    }

    val connected = uiState.connection != null
    var showDisconnectDialog by remember { mutableStateOf(false) }
    // 从扫码页「改用手动输入」进来时直接展开表单，不再让人多点一次
    var manualExpanded by rememberSaveable { mutableStateOf(expandManual) }

    var manualUrl by rememberSaveable { mutableStateOf("") }
    var manualCode by rememberSaveable { mutableStateOf("") }
    var manualDeviceName by rememberSaveable { mutableStateOf("") }

    // 连接状态一变（连上 / 断开）就收起手动输入，避免表单滞留在页面上。
    // 注意只在「变化」时收起：首次组合也要收起的话，会把「改用手动输入」刚展开的表单立刻关掉。
    var lastConnectionState by remember { mutableStateOf(connected) }
    LaunchedEffect(connected) {
        if (lastConnectionState != connected) {
            manualExpanded = false
            lastConnectionState = connected
        }
    }


    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("断开并清除远程数据") },
            text = {
                Text("只清除本机的远程数据（投影、标签、同步记录、缓存），不影响系统相册照片。")
            },
            confirmButton = {
                AppTextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect()
                }) { Text("断开并清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                AppTextButton(onClick = { showDisconnectDialog = false }) { Text("取消") }
            },
        )
    }

    val submitManual = {
        viewModel.connect(
            baseUrl = manualUrl,
            code = manualCode,
            deviceName = manualDeviceName.ifBlank { "Android Device" },
        )
    }

    // API 37 起未授予本地网络权限时系统会直接拦掉局域网请求，表现为「无法连接服务器」，
    // 与地址不可达混淆。手动输入路径必须和扫码路径一样先申请该权限，并在被拒时给出
    // 明确原因（体验设计 §3.4：权限缺失必须给出不同原因）。
    val context = LocalContext.current
    var localNetworkDenied by remember { mutableStateOf(false) }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkDenied = !granted
        if (granted) submitManual()
    }
    LaunchedEffect(localNetworkDenied) {
        if (localNetworkDenied) {
            snackbarHostState.showAppSnackbar("未获得本地网络权限，无法连接局域网服务端", isError = true)
            localNetworkDenied = false
        }
    }
    val onSubmitManual = {
        val granted = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) submitManual() else localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
    val manualReady = manualUrl.isNotBlank() && manualCode.isNotBlank()

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("连接管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            val connection = uiState.connection
            when {
                uiState.isConnecting -> ConnectingContent(state = uiState)

                connection != null -> ConnectedContent(
                    connection = connection,
                    reachability = uiState.reachability,
                    serverVersion = uiState.liveServerVersion ?: connection.serverVersion,
                    stats = stats,
                    manualExpanded = manualExpanded,
                    onScan = { navController.navigate("qr_scanner") },
                    onToggleManual = { manualExpanded = !manualExpanded },
                    onRecheck = { viewModel.checkHealth() },
                    onDisconnect = { showDisconnectDialog = true },
                    manualUrl = manualUrl,
                    manualCode = manualCode,
                    manualDeviceName = manualDeviceName,
                    onManualUrlChange = { manualUrl = it },
                    onManualCodeChange = { manualCode = it },
                    onManualDeviceNameChange = { manualDeviceName = it },
                    onSubmitManual = onSubmitManual,
                    manualReady = manualReady,
                )

                else -> UnconnectedContent(
                    manualExpanded = manualExpanded,
                    onScan = { navController.navigate("qr_scanner") },
                    onToggleManual = { manualExpanded = !manualExpanded },
                    manualUrl = manualUrl,
                    manualCode = manualCode,
                    manualDeviceName = manualDeviceName,
                    onManualUrlChange = { manualUrl = it },
                    onManualCodeChange = { manualCode = it },
                    onManualDeviceNameChange = { manualDeviceName = it },
                    onSubmitManual = onSubmitManual,
                    manualReady = manualReady,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 未连接

@Composable
private fun UnconnectedContent(
    manualExpanded: Boolean,
    onScan: () -> Unit,
    onToggleManual: () -> Unit,
    manualUrl: String,
    manualCode: String,
    manualDeviceName: String,
    onManualUrlChange: (String) -> Unit,
    onManualCodeChange: (String) -> Unit,
    onManualDeviceNameChange: (String) -> Unit,
    onSubmitManual: () -> Unit,
    manualReady: Boolean,
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    NoticeLayout(
        primaryLabel = if (manualExpanded) "连接" else "扫码连接",
        onPrimary = if (manualExpanded) onSubmitManual else onScan,
        primaryEnabled = if (manualExpanded) manualReady else true,
        secondaryLabel = if (manualExpanded) "改用扫码" else "手动输入",
        onSecondary = onToggleManual,
        contentAlignment = if (manualExpanded) Alignment.TopCenter else Alignment.Center,
    ) {
        if (manualExpanded) {
            Spacer(modifier = Modifier.height(20.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text("手动输入连接", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "受邀成员向管理员索取配对码；管理员在管理端「用户」页生成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                ManualConnectFields(
                    url = manualUrl,
                    code = manualCode,
                    deviceName = manualDeviceName,
                    onUrlChange = onManualUrlChange,
                    onCodeChange = onManualCodeChange,
                    onDeviceNameChange = onManualDeviceNameChange,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        } else {
            NoticeIcon(painter = painterResource(R.drawable.lucide_ic_scan_qr_code))
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "还没有连接服务端",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "受邀成员向管理员索取自己的二维码；管理员先部署服务，再到管理端「用户」页生成。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            AppTextButton(onClick = { uriHandler.openUri("https://youyou.mengqinghe.com/quickstart") }) {
                Text("查看部署说明")
            }
        }
    }
}

// ---------------------------------------------------------------- 连接中

@Composable
private fun ConnectingContent(state: ServerConnectionUiState) {
    val stage = state.connectionStage
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stage.label.ifBlank { "正在连接" },
            style = MaterialTheme.typography.titleMedium,
        )
        state.connectingBaseUrl?.let { url ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = displayHost(url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        StageProgress(currentStep = stage.step, total = CONNECTION_STAGE_NAMES.size)
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = CONNECTION_STAGE_NAMES.getOrElse(stage.step - 1) { "" },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "第 ${stage.step} 步 / 共 ${CONNECTION_STAGE_NAMES.size} 步",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = CONNECTION_STAGE_NAMES.joinToString(" → "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 四段进度：已完成段用品牌色实底，未到段用分隔线色。段名由图例一行给出。 */
@Composable
private fun StageProgress(currentStep: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (index < currentStep) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------- 已连接

@Composable
private fun ConnectedContent(
    connection: ServerConnection,
    reachability: ServerReachability,
    serverVersion: String?,
    stats: ServerStats,
    manualExpanded: Boolean,
    onScan: () -> Unit,
    onToggleManual: () -> Unit,
    onRecheck: () -> Unit,
    onDisconnect: () -> Unit,
    manualUrl: String,
    manualCode: String,
    manualDeviceName: String,
    onManualUrlChange: (String) -> Unit,
    onManualCodeChange: (String) -> Unit,
    onManualDeviceNameChange: (String) -> Unit,
    onSubmitManual: () -> Unit,
    manualReady: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        ConnectionStatusSection(
            reachability = reachability,
            baseUrl = connection.baseUrl,
            serverVersion = serverVersion,
            deviceName = connection.deviceName,
            stats = stats,
            onRecheck = onRecheck,
        )

        SectionRule()

        // 破坏性动作用通栏实底红：整屏唯一的强色块，配确认弹窗兜底。
        // 不用 colorScheme.error 作底 —— #F04438 压白字只有 3.76:1，达不到 AA。
        Button(
            onClick = onDisconnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = ErrorFill,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("断开连接")
        }

        SectionRule()

        // 更换服务端的两条并列路径：各占一半宽度，文案已说清动作，不再加题眉。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppOutlinedButton(
                onClick = onScan,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) { Text("扫码连接") }
            AppOutlinedButton(
                onClick = onToggleManual,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) { Text(if (manualExpanded) "收起" else "手动输入") }
        }

        if (manualExpanded) {
            Spacer(modifier = Modifier.height(16.dp))
            ManualConnectFields(
                url = manualUrl,
                code = manualCode,
                deviceName = manualDeviceName,
                onUrlChange = onManualUrlChange,
                onCodeChange = onManualCodeChange,
                onDeviceNameChange = onManualDeviceNameChange,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onSubmitManual,
                enabled = manualReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("连接")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 状态区：先说「连没连上、好不好」，再说地址，最后给三个指标。
 *
 * 左侧 56dp 品牌方窗是这一页的视觉重心（取景窗的缩小版，鲜柚黄实底），
 * 窗内图标随可达性换：可达云勾 / 不可达云断 / 检查中云。
 * 可达性由进入页面的健康检查驱动 —— 服务端挂掉时这里必须跟着变脸，不能一直报平安。
 */
@Composable
private fun ConnectionStatusSection(
    reachability: ServerReachability,
    baseUrl: String,
    serverVersion: String?,
    deviceName: String?,
    stats: ServerStats,
    onRecheck: () -> Unit,
) {
    val status = when (reachability) {
        ServerReachability.ONLINE -> StatusSpec(
            color = MaterialTheme.colorScheme.tertiary,
            text = "已连接 · 可达",
            icon = R.drawable.lucide_ic_cloud_check,
        )

        ServerReachability.OFFLINE -> StatusSpec(
            color = MaterialTheme.colorScheme.error,
            text = "服务端不可达",
            hint = "正在显示最近同步的数据",
            icon = R.drawable.lucide_ic_cloud_off,
        )

        ServerReachability.CHECKING -> StatusSpec(
            color = MaterialTheme.colorScheme.outline,
            text = "正在检查…",
            icon = R.drawable.lucide_ic_cloud,
        )

        ServerReachability.UNBOUND -> StatusSpec(
            color = MaterialTheme.colorScheme.outline,
            text = "未连接",
            icon = R.drawable.lucide_ic_cloud,
        )
    }
    val checked = reachability == ServerReachability.ONLINE || reachability == ServerReachability.OFFLINE

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandWindow(painter = painterResource(status.icon))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(status.color),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = status.text,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (reachability == ServerReachability.OFFLINE) {
                        AppTextButton(
                            onClick = onRecheck,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) { Text("重新检查") }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = displayHost(baseUrl),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        status.hint?.let { hint ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionRule()

        Row(modifier = Modifier.fillMaxWidth()) {
            StatusMetric(
                label = "远程媒体",
                value = if (checked) stats.remoteMediaCount.toString() else "—",
                modifier = Modifier.weight(1f),
            )
            StatusMetric(
                label = "上次同步",
                value = if (checked) formatSyncedAgo(stats.lastSyncedAt) else "—",
                modifier = Modifier.weight(1f),
            )
            StatusMetric(
                label = "服务端版本",
                value = if (checked && serverVersion != null) "v$serverVersion" else "—",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "本机设备 · ${deviceName ?: "未命名设备"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class StatusSpec(
    val color: Color,
    val text: String,
    val icon: Int,
    val hint: String? = null,
)

@Composable
private fun StatusMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

// ---------------------------------------------------------------- 公共片段

@Composable
private fun SectionRule() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun ManualConnectFields(
    url: String,
    code: String,
    deviceName: String,
    onUrlChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            colors = appTextFieldColors(),
            value = url,
            onValueChange = onUrlChange,
            label = { Text("服务端地址") },
            placeholder = { Text("http://192.168.1.100:8989") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            colors = appTextFieldColors(),
            value = code,
            onValueChange = onCodeChange,
            label = { Text("配对码") },
            placeholder = { Text("管理端生成的一次性配对码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            colors = appTextFieldColors(),
            value = deviceName,
            onValueChange = onDeviceNameChange,
            label = { Text("设备名称（可选）") },
            placeholder = { Text("留空则自动获取") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 地址去掉协议前缀，只留 主机:端口 —— 一行读完，也不像一串 URL。 */
private fun displayHost(baseUrl: String): String =
    baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

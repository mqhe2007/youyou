package com.example.youyou_album.presentation.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.example.youyou_album.presentation.widgets.AppTextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.youyou_album.R
import com.example.youyou_album.presentation.server.ServerConnectionViewModel

/**
 * 首启登录页：扫码 = 登录 = 绑定远程媒体库（服务端为每位用户签发专属二维码）。
 * 绑定成功或选择「暂不绑定」后进入主框架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginPage(
    navController: NavController,
    onEnterApp: () -> Unit,
    viewModel: ServerConnectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 监听扫码结果（与连接管理页一致的 savedStateHandle 协议）
    DisposableEffect(navController) {
        val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
        val observer = androidx.lifecycle.Observer<String> { code ->
            if (code != null) {
                savedStateHandle?.remove<String>("qr_code")
                viewModel.connectWithQr(code)
            }
        }
        savedStateHandle?.getLiveData<String>("qr_code")?.observeForever(observer)
        onDispose {
            savedStateHandle?.getLiveData<String>("qr_code")?.removeObserver(observer)
        }
    }

    // 绑定成功 → 进入主框架（清空登录页，返回键不再回到登录）
    LaunchedEffect(uiState.connection != null) {
        if (uiState.connection != null) {
            onEnterApp()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(modifier = Modifier.height(56.dp))
            Icon(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "柚柚相册",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "轻松管理人生影相",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = { navController.navigate(com.example.youyou_album.presentation.navigation.Routes.QR_SCANNER) },
                enabled = !uiState.isConnecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.lucide_ic_scan_line),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(if (uiState.isConnecting) "正在绑定" else "扫码绑定媒体库")
            }
            if (uiState.isConnecting) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "配对 → 验证身份 → 同步清单 → 完成\n当前：${uiState.connectionStage.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            AppTextButton(
                onClick = onEnterApp,
                enabled = !uiState.isConnecting,
            ) {
                Text("暂不绑定")
            }
            // 登录页不设轻提示宿主，失败信息就地展示；与连接管理页读同一个来源字段。
            // 登录页只有「配对」这一步，成功后立刻离开页面，因此不需要消费后清空。
            uiState.feedback?.takeIf { it.isError }?.let { feedback ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = feedback.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

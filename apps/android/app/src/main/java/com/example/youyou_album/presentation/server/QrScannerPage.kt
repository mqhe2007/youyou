package com.example.youyou_album.presentation.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.youyou_album.R
import com.example.youyou_album.presentation.widgets.NoticeIcon
import com.example.youyou_album.presentation.widgets.NoticeLayout
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerPage(
    onBack: () -> Unit,
    onCodeScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraRequestFinished by remember { mutableStateOf(hasCameraPermission) }
    var hasLocalNetworkPermission by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var localNetworkPermissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        cameraRequestFinished = true
    }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasLocalNetworkPermission = granted
        localNetworkPermissionDenied = !granted
        if (granted) {
            pendingCode?.let { code ->
                pendingCode = null
                onCodeScanned(code)
            }
        }
    }

    // 用户已经在上一页明确点击「扫码连接」，进入后直接交给系统申请相机权限。
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 从系统设置返回时重新读一次权限，否则「去设置里开权限」点了等于没点。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val cameraGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val localNetworkGranted =
            android.os.Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = cameraGranted
        hasLocalNetworkPermission = localNetworkGranted
        if (localNetworkGranted) {
            localNetworkPermissionDenied = false
            pendingCode?.let { code ->
                pendingCode = null
                onCodeScanned(code)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码连接") },
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
                .padding(innerPadding),
        ) {
            when {
                localNetworkPermissionDenied -> ScannerNotice(
                    icon = painterResource(R.drawable.lucide_ic_ban),
                    title = "未获得本地网络权限",
                    message = "连接局域网服务端需要本地网络权限。",
                    primaryLabel = "打开系统设置",
                    onPrimary = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    secondaryLabel = "改用手动输入",
                    onSecondary = onBack,
                )

                hasCameraPermission -> {
                    CameraPreview(
                        onBarcodeDetected = { code ->
                            if (hasLocalNetworkPermission) {
                                onCodeScanned(code)
                            } else {
                                pendingCode = code
                                localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                            }
                        },
                    )
                }

                cameraRequestFinished -> ScannerNotice(
                    icon = painterResource(R.drawable.lucide_ic_ban),
                    title = "未获得相机权限",
                    message = "扫描二维码需要相机权限。",
                    primaryLabel = "打开系统设置",
                    onPrimary = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    secondaryLabel = "改用手动输入",
                    onSecondary = onBack,
                )

                // 权限弹窗正在拉起；保持底页空白，避免闪现额外确认页面。
                else -> Unit
            }
        }
    }
}

/**
 * 权限拒绝后的补救页：品牌取景窗图标 + 大标题 + 说明 + 底部固定的主/次操作。
 * 骨架与「连接管理」未连接态共用 [NoticeLayout]，两页是同一类页面，不再各写一份。
 */
@Composable
private fun ScannerNotice(
    icon: Painter,
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    NoticeLayout(
        primaryLabel = primaryLabel,
        onPrimary = onPrimary,
        secondaryLabel = secondaryLabel,
        onSecondary = onSecondary,
    ) {
        NoticeIcon(painter = icon)
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

@Composable
fun CameraPreview(
    onBarcodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var scanned by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            try {
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val image = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    scanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            for (barcode in barcodes) {
                                                if (barcode.valueType == Barcode.TYPE_TEXT || barcode.valueType == Barcode.TYPE_URL) {
                                                    val code = barcode.rawValue
                                                    if (code != null && !scanned) {
                                                        scanned = true
                                                        onBarcodeDetected(code)
                                                    }
                                                }
                                            }
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                } else {
                                    imageProxy.close()
                                }
                            } catch (e: Exception) {
                                imageProxy.close()
                            }
                        }
                    }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

package com.example.youyou_album.presentation.photo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource
import com.example.youyou_album.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideshowPage(
    onBack: () -> Unit,
    viewModel: SlideshowViewModel = hiltViewModel(),
) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 画廊黑背景上系统栏图标切浅色，退出页面还原
    DisposableEffect(context) {
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = true
            controller?.isAppearanceLightNavigationBars = true
        }
    }
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }

    LaunchedEffect(isPlaying, photos.size) {
        while (isPlaying && photos.isNotEmpty()) {
            delay(3000)
            currentIndex = (currentIndex + 1) % photos.size
        }
    }

    Scaffold(
        containerColor = com.example.youyou_album.ui.theme.GalleryBackground,
        topBar = {
            TopAppBar(
                title = { Text("幻灯片 ${if (photos.isNotEmpty()) "${currentIndex + 1}/${photos.size}" else ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.example.youyou_album.ui.theme.GalleryBackground,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
                ),
            )
        },
        floatingActionButton = {
            if (photos.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { isPlaying = !isPlaying },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        painter = if (isPlaying) painterResource(R.drawable.lucide_ic_pause) else painterResource(R.drawable.lucide_ic_play),
                        contentDescription = if (isPlaying) "暂停" else "播放",
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading -> Text("加载中...", style = MaterialTheme.typography.bodyLarge, color = androidx.compose.ui.graphics.Color.White)
                photos.isEmpty() -> Text("暂无照片", style = MaterialTheme.typography.bodyLarge, color = androidx.compose.ui.graphics.Color.White)
                else -> {
                    val photo = photos[currentIndex]
                    val model = photo.sourceUri ?: photo.path
                    if (model != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(model)
                                .crossfade(true)
                                .build(),
                            contentDescription = photo.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(photo.name, style = MaterialTheme.typography.bodyLarge, color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }
}

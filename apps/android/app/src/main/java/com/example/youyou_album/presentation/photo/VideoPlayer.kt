package com.example.youyou_album.presentation.photo

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.youyou_album.R
import kotlinx.coroutines.delay

private const val CONTROL_HIDE_DELAY_MS = 3_000L

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    uri: Uri,
    requestHeaders: Map<String, String>,
    active: Boolean,
    controlsVisible: Boolean,
    onControlsVisibleChange: (Boolean) -> Unit,
    bottomControlsPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri, requestHeaders) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = active
            }
    }
    var isPlaying by remember(exoPlayer) { mutableStateOf(exoPlayer.isPlaying) }
    var playbackState by remember(exoPlayer) { mutableIntStateOf(exoPlayer.playbackState) }
    var durationMs by remember(exoPlayer) { mutableLongStateOf(0L) }
    var positionMs by remember(exoPlayer) { mutableLongStateOf(0L) }
    var previewPositionMs by remember(exoPlayer) { mutableLongStateOf(0L) }
    var seeking by remember(exoPlayer) { mutableStateOf(false) }
    var playbackError by remember(exoPlayer) { mutableStateOf<String?>(null) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                playbackState = player.playbackState
                durationMs = player.duration.validDuration()
                if (!seeking) positionMs = player.currentPosition.coerceAtLeast(0L)
                if (!player.isPlaying) onControlsVisibleChange(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.errorCodeName
                onControlsVisibleChange(true)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(active, exoPlayer) {
        if (active) exoPlayer.play() else exoPlayer.pause()
    }

    LaunchedEffect(exoPlayer, isPlaying, seeking) {
        while (isPlaying && !seeking) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.validDuration()
            delay(250L)
        }
    }

    LaunchedEffect(isPlaying, controlsVisible, seeking) {
        if (isPlaying && controlsVisible && !seeking) {
            delay(CONTROL_HIDE_DELAY_MS)
            onControlsVisibleChange(false)
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.background(Color.Black),
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { it.player = exoPlayer },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onControlsVisibleChange(!controlsVisible) },
            ),
        )

        if (playbackState == Player.STATE_BUFFERING && playbackError == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(40.dp),
                color = Color.White,
            )
        }

        playbackError?.let {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("视频加载失败", color = Color.White)
                Text(
                    "重试",
                    color = Color.White,
                    modifier = Modifier
                        .semantics { contentDescription = "重试播放视频" }
                        .clickable {
                            playbackError = null
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                        .padding(12.dp),
                )
            }
        }

        if (controlsVisible && playbackError == null) {
            IconButton(
                onClick = {
                    onControlsVisibleChange(true)
                    if (playbackState == Player.STATE_ENDED) {
                        exoPlayer.seekTo(0L)
                        exoPlayer.play()
                    } else if (isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.55f), shape = androidx.compose.foundation.shape.CircleShape),
            ) {
                val icon = when {
                    playbackState == Player.STATE_ENDED -> R.drawable.lucide_ic_rotate_ccw
                    isPlaying -> R.drawable.lucide_ic_pause
                    else -> R.drawable.lucide_ic_play
                }
                Icon(
                    painter = painterResource(icon),
                    contentDescription = when {
                        playbackState == Player.STATE_ENDED -> "重新播放"
                        isPlaying -> "暂停"
                        else -> "播放"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }

            PlaybackProgress(
                positionMs = if (seeking) previewPositionMs else positionMs,
                durationMs = durationMs,
                onSeekStart = {
                    seeking = true
                    onControlsVisibleChange(true)
                },
                onSeekPreview = { previewPositionMs = it },
                onSeekFinished = {
                    if (durationMs > 0L) exoPlayer.seekTo(previewPositionMs.coerceIn(0L, durationMs))
                    seeking = false
                    positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))),
                    )
                    .padding(horizontal = 16.dp)
                    .padding(bottom = bottomControlsPadding + 12.dp, top = 28.dp),
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    positionMs: Long,
    durationMs: Long,
    onSeekStart: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = durationMs > 0L
    val safePosition = if (enabled) positionMs.coerceIn(0L, durationMs) else 0L
    val density = LocalDensity.current
    val useStackedLayout = density.fontScale > 1.3f

    BoxWithConstraints(modifier = modifier) {
        val stacked = useStackedLayout || maxWidth < 360.dp
        if (stacked) {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeLabel(formatPlaybackTime(safePosition))
                    TimeLabel(if (enabled) formatPlaybackTime(durationMs) else "–:––")
                }
                SeekSlider(enabled, safePosition, durationMs, onSeekStart, onSeekPreview, onSeekFinished)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(formatPlaybackTime(safePosition))
                SeekSlider(
                    enabled,
                    safePosition,
                    durationMs,
                    onSeekStart,
                    onSeekPreview,
                    onSeekFinished,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                TimeLabel(if (enabled) formatPlaybackTime(durationMs) else "–:––")
            }
        }
    }
}

@Composable
private fun SeekSlider(
    enabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeekStart: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = positionMs.toFloat(),
        onValueChange = {
            onSeekStart()
            onSeekPreview(it.toLong())
        },
        onValueChangeFinished = onSeekFinished,
        enabled = enabled,
        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
        modifier = modifier.semantics {
            contentDescription = if (enabled) "视频进度 ${formatPlaybackTime(positionMs)}" else "视频时长未知"
        },
    )
}

@Composable
private fun TimeLabel(text: String) {
    Text(text = text, color = Color.White, maxLines = 1)
}

private fun Long.validDuration(): Long =
    takeIf { it != C.TIME_UNSET && it in 1..86_400_000L } ?: 0L

internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceIn(0L, 86_400_000L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

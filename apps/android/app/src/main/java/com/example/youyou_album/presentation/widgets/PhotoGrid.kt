package com.example.youyou_album.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dragselectcompose.core.gridDragSelect
import com.dragselectcompose.core.rememberDragSelectState
import com.dragselectcompose.extensions.dragSelectToggleable
import com.example.youyou_album.R
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.presentation.photo.photoThumbnailModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 时间线网格：纯照片项（无日期 Header）+ drag-select-compose 范围拖选，
 * 右侧浮层年月刻度（视口顶锚：仅当前顶部年月展开为文本）。
 */
@Composable
fun PhotoGrid(
    photos: List<Photo>,
    selectedIds: Set<String> = emptySet(),
    selectionMode: Boolean = false,
    onPhotoClick: (String) -> Unit,
    onPhotoLongClick: (String) -> Unit = {},
    onSelectedIdsChange: (Set<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val columns = 4

    val dragSelectState = rememberDragSelectState<Photo>(
        compareSelector = { it.id },
    )
    val gridState = dragSelectState.gridState
    val scope = rememberCoroutineScope()

    val duplicateTimelineKeys = remember(photos) {
        photos.groupingBy { it.timelineKey ?: it.id }.eachCount().filterValues { it > 1 }.keys
    }
    val yearMonthMarks = remember(photos) { buildYearMonthMarks(photos) }

    // 视口顶锚：只激活「视口内最上（最小 index）照片」所属年月，保证同时只有一个文本
    val activeMonthKey by remember(yearMonthMarks) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || yearMonthMarks.isEmpty()) {
                null
            } else {
                val topIndex = visible.minOf { it.index }
                yearMonthMarks.lastOrNull { it.firstIndex <= topIndex }?.key
            }
        }
    }

    // 数据变化时保留仍存在的选中项
    LaunchedEffect(photos) {
        dragSelectState.reconcile(photos)
    }

    // 外部选中（全选 / 清空 / Toolbar）→ 库状态
    LaunchedEffect(selectedIds, photos) {
        val selectedPhotos = photos.filter { it.id in selectedIds }
        val currentIds = dragSelectState.selected.map { it.id }.toSet()
        if (currentIds != selectedIds) {
            if (selectedIds.isEmpty()) {
                dragSelectState.disableSelectionMode()
            } else {
                dragSelectState.updateSelected(selectedPhotos)
            }
        }
    }

    val currentOnSelectedIdsChange by rememberUpdatedState(onSelectedIdsChange)
    val currentOnPhotoLongClick by rememberUpdatedState(onPhotoLongClick)

    // 库选中 → 外部；首次进入多选时补一次 longClick 回调
    LaunchedEffect(dragSelectState) {
        var wasEmpty = true
        snapshotFlow { dragSelectState.selected.map { it.id }.toSet() }
            .distinctUntilChanged()
            .collect { ids ->
                if (wasEmpty && ids.isNotEmpty()) {
                    currentOnPhotoLongClick(ids.first())
                }
                wasEmpty = ids.isEmpty()
                currentOnSelectedIdsChange(ids)
            }
    }

    val inSelectionUi = selectionMode || dragSelectState.inSelectionMode

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .gridDragSelect(
                    items = photos,
                    state = dragSelectState,
                    enableAutoScroll = true,
                ),
        ) {
            items(
                items = photos,
                key = { photo ->
                    val key = photo.timelineKey ?: photo.id
                    if (key in duplicateTimelineKeys) photo.id else key
                },
            ) { photo ->
                val tileInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val tilePressed by tileInteraction.collectIsPressedAsState()
                PhotoTile(
                    photo = photo,
                    isSelected = dragSelectState.isSelected(photo),
                    selectionMode = inSelectionUi,
                    modifier = Modifier
                        .graphicsLayer { alpha = if (tilePressed) 0.82f else 1f }
                        .then(
                            if (dragSelectState.inSelectionMode) {
                                Modifier.dragSelectToggleable(
                                    state = dragSelectState,
                                    item = photo,
                                )
                            } else {
                                Modifier.clickable(
                                    interactionSource = tileInteraction,
                                    indication = androidx.compose.foundation.LocalIndication.current,
                                ) { onPhotoClick(photo.id) }
                            },
                        ),
                )
            }
        }

        if (yearMonthMarks.size >= 2) {
            YearMonthScale(
                marks = yearMonthMarks,
                activeKey = activeMonthKey,
                onScrubFraction = { fraction ->
                    val index = (fraction * (photos.lastIndex.coerceAtLeast(0)))
                        .toInt()
                        .coerceIn(0, photos.lastIndex.coerceAtLeast(0))
                    scope.launch {
                        gridState.scrollToItem(index)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp),
            )
        }
    }
}

private data class YearMonthMark(
    val key: String,
    val label: String,
    val firstIndex: Int,
    /** 0f..1f，按首张照片在列表中的位置 */
    val fraction: Float,
)

private fun buildYearMonthMarks(photos: List<Photo>): List<YearMonthMark> {
    if (photos.isEmpty()) return emptyList()
    val labelFormat = SimpleDateFormat("yyyy年M月", Locale.getDefault())
    val marks = ArrayList<YearMonthMark>()
    var lastKey: String? = null
    val lastIndex = (photos.size - 1).coerceAtLeast(1)
    val calendar = Calendar.getInstance()
    photos.forEachIndexed { index, photo ->
        val timestamp = photo.sortAt ?: 0L
        val key: String
        val label: String
        if (timestamp > 0L) {
            calendar.timeInMillis = timestamp
            key = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}"
            label = labelFormat.format(Date(timestamp))
        } else {
            key = "unknown"
            label = "时间未知"
        }
        if (key != lastKey) {
            marks.add(
                YearMonthMark(
                    key = key,
                    label = label,
                    firstIndex = index,
                    fraction = index.toFloat() / lastIndex,
                ),
            )
            lastKey = key
        }
    }
    return marks
}

/**
 * 浮层年月刻度：叠在网格右侧，不挤占布局宽度。
 * 激活项向左展开完整「yyyy年M月」；拖动手势落在右侧窄条上。
 */
@Composable
private fun YearMonthScale(
    marks: List<YearMonthMark>,
    activeKey: String?,
    onScrubFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)

    BoxWithConstraints(
        modifier = modifier
            // 视觉刻度贴右缘不变，命中区扩到 48dp 触控标准
            .width(48.dp)
            .semantics {
                contentDescription = "年月快速导航：上下拖动或点按跳转到对应时间"
            }
            .pointerInput(marks) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    val h = size.height.coerceAtLeast(1)
                    val fraction = (change.position.y / h).coerceIn(0f, 1f)
                    onScrubFraction(fraction)
                }
            }
            // 拖拽之外的替代交互：点按某位置即跳转
            .pointerInput(marks) {
                detectTapGestures { offset ->
                    val h = size.height.coerceAtLeast(1)
                    val fraction = (offset.y / h).coerceIn(0f, 1f)
                    onScrubFraction(fraction)
                }
            },
    ) {
        val trackHeight = maxHeight
        marks.forEach { mark ->
            val y = trackHeight * mark.fraction
            if (mark.key == activeKey) {
                Text(
                    text = mark.label,
                    color = labelColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = y - 8.dp)
                        .wrapContentWidth(align = Alignment.End, unbounded = true)
                        .background(labelBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = y)
                        .padding(end = 6.dp)
                        .size(width = 8.dp, height = 1.5.dp)
                        .background(tickColor, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
internal fun PhotoTile(
    photo: Photo,
    isSelected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val model = photoThumbnailModel(photo)
    var imageLoadFailed by remember(photo.id, model) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .padding(1.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${photo.name}，" + when (photo.syncDisplay) {
                    MediaSyncDisplay.LOCAL_ONLY -> "仅本机"
                    MediaSyncDisplay.REMOTE_ONLY -> "仅远程"
                    MediaSyncDisplay.SYNCED -> "已同步，本机和远程都有"
                }
            }
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent,
            ),
    ) {
        // 画面本体单独一层：播放角标、同步徽章和多选圈叠在其上。
        Box(modifier = Modifier.fillMaxSize()) {
            PhotoThumbnailLayer(photo = photo, onLoadFailed = { imageLoadFailed = true })

            if (imageLoadFailed || model == null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (photo.isVideo) "视频暂不可预览" else "缩略图暂不可用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Text(
                            text = if (photo.sourceUri.isNullOrBlank()) "可重新扫描" else "可重试加载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (photo.isVideo) {
            Icon(
                painter = painterResource(R.drawable.lucide_ic_circle_play),
                contentDescription = "视频",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
            )
        }

        SyncStatusBadge(
            syncDisplay = photo.syncDisplay,
            modifier = Modifier.align(Alignment.BottomEnd),
        )

        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.4f),
                        shape = CircleShape,
                    )
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        painter = painterResource(R.drawable.lucide_ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 网格 tile 的缩略图画面（方形裁切）。 */
@Composable
private fun PhotoThumbnailLayer(photo: Photo, onLoadFailed: () -> Unit = {}) {
    val context = LocalContext.current
    val view = LocalView.current
    val thumbnailModel = photoThumbnailModel(photo)
    Box(modifier = Modifier.fillMaxSize()) {
        if (thumbnailModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailModel)
                    .crossfade(true)
                    .listener(onError = { _, _ -> view.post(onLoadFailed) })
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SyncStatusBadge(
    syncDisplay: MediaSyncDisplay,
    modifier: Modifier = Modifier,
) {
    val iconRes = when (syncDisplay) {
        MediaSyncDisplay.SYNCED -> return
        MediaSyncDisplay.REMOTE_ONLY -> R.drawable.lucide_ic_cloud
        MediaSyncDisplay.LOCAL_ONLY -> R.drawable.lucide_ic_smartphone
    }
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(24.dp)
            .background(color = Color.Black.copy(alpha = 0.6f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

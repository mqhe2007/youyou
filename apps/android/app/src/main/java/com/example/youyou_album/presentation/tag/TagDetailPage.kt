package com.example.youyou_album.presentation.tag

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.example.youyou_album.presentation.widgets.AppTextButton
import com.example.youyou_album.presentation.widgets.AppSnackbarHost
import com.example.youyou_album.presentation.widgets.BatchAction
import com.example.youyou_album.presentation.widgets.BatchActionBar
import com.example.youyou_album.presentation.widgets.BatchBarEnterMs
import com.example.youyou_album.presentation.widgets.BatchBarExitMs
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.youyou_album.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youyou_album.domain.model.MediaSyncDisplay
import com.example.youyou_album.presentation.widgets.PhotoGrid
import com.example.youyou_album.presentation.widgets.MediaDeleteLauncher
import com.example.youyou_album.presentation.widgets.deleteConfirmMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDetailPage(
    tagId: String,
    onBack: () -> Unit,
    onPhotoClick: (String) -> Unit,
    viewModel: TagViewModel = hiltViewModel(),
) {
    val photos by viewModel.getPhotosForTag(tagId).collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val tag = tags.find { it.id == tagId }

    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val deleteStep by viewModel.mediaDelete.step.collectAsStateWithLifecycle()
    val deleteMessage by viewModel.mediaDelete.message.collectAsStateWithLifecycle()
    val serverConnected by viewModel.serverConnected.collectAsStateWithLifecycle()

    MediaDeleteLauncher(
        step = deleteStep,
        onSystemConfirmation = { session, approved -> viewModel.mediaDelete.onSystemConfirmation(session, approved) },
    )

    val selectedPhotos = photos.filter { it.id in selectedIds }
    val selectedHasLocal = selectedPhotos.any { it.sourceType != "server" }
    val selectedHasRemote = selectedPhotos.any { it.syncDisplay != MediaSyncDisplay.LOCAL_ONLY }

    LaunchedEffect(deleteMessage) {
        deleteMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.mediaDelete.clearMessage()
        }
    }

    val allIds = photos.map { it.id }
    val allSelected = allIds.isNotEmpty() && allIds.size == selectedIds.size && allIds.all { it in selectedIds }

    BackHandler(enabled = selecting) {
        selecting = false
        selectedIds = emptySet()
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除原件") },
            text = {
                Text(
                    deleteConfirmMessage(
                        count = selectedIds.size,
                        online = serverConnected && com.example.youyou_album.presentation.widgets.deleteNetworkAvailable(androidx.compose.ui.platform.LocalContext.current),
                        hasLocal = selectedHasLocal,
                        hasRemote = selectedHasRemote,
                    )
                )
            },
            confirmButton = {
                AppTextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deletePhotos(selectedIds.toList())
                    selecting = false
                    selectedIds = emptySet()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("移除标签") },
            text = { Text("将这 ${selectedIds.size} 项的标签「${tag?.name ?: ""}」移除，不会删除原文件。") },
            confirmButton = {
                AppTextButton(onClick = {
                    showRemoveDialog = false
                    viewModel.removePhotosFromTag(tagId, selectedIds.toList())
                    selecting = false
                    selectedIds = emptySet()
                }) {
                    Text("移除")
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showRemoveDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("已选 ${selectedIds.size}") },
                    navigationIcon = {
                        IconButton(onClick = { selecting = false; selectedIds = emptySet() }) {
                            Icon(painterResource(R.drawable.lucide_ic_x), contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedIds = if (allSelected) emptySet() else allIds.toSet()
                        }) {
                            Icon(painterResource(R.drawable.lucide_ic_square_check), contentDescription = if (allSelected) "取消全选" else "全选")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("#${tag?.name ?: "标签"}") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回")
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selecting && selectedIds.isNotEmpty(),
                enter = fadeIn(tween(BatchBarEnterMs)) +
                    slideInVertically(tween(BatchBarEnterMs)) { it / 2 },
                exit = fadeOut(tween(BatchBarExitMs)) +
                    slideOutVertically(tween(BatchBarExitMs)) { it / 2 },
            ) {
                BatchActionBar(
                    onDelete = { showDeleteDialog = true },
                    actions = listOf(
                        BatchAction(
                            label = "移除标签",
                            icon = R.drawable.lucide_ic_tags,
                            primary = false,
                            onClick = { showRemoveDialog = true },
                        )
                    ),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (photos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text("该标签下暂无照片", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                PhotoGrid(
                    photos = photos,
                    selectedIds = selectedIds,
                    selectionMode = selecting,
                    onPhotoClick = onPhotoClick,
                    onSelectedIdsChange = { ids ->
                        selecting = ids.isNotEmpty()
                        selectedIds = ids
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

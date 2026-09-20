package com.example.youyou_album.presentation.tag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.example.youyou_album.presentation.widgets.appTextFieldColors
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.example.youyou_album.presentation.widgets.AppTextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.youyou_album.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youyou_album.domain.model.Tag
import com.example.youyou_album.presentation.widgets.EmptyStateView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagListPage(
    onBack: (() -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    viewModel: TagViewModel = hiltViewModel(),
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标签") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "返回")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(painterResource(R.drawable.lucide_ic_plus), contentDescription = "新建标签")
            }
        },
    ) { innerPadding ->
        if (tags.isEmpty()) {
            EmptyStateView(
                icon = painterResource(R.drawable.lucide_ic_tag),
                title = "还没有标签",
                message = "点击右下角新建标签，为照片分类",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(tags, key = { it.id }) { tag ->
                    TagListItem(tag = tag, onClick = { onTagClick(tag.id) })
                    Divider()
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新建标签") },
            text = {
                OutlinedTextField(
                    colors = appTextFieldColors(),
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = { Text("标签名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                AppTextButton(
                    onClick = {
                        if (newTagName.isNotBlank()) {
                            viewModel.createTag(newTagName.trim())
                            newTagName = ""
                            showAddDialog = false
                        }
                    },
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                AppTextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
fun TagListItem(tag: Tag, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(tag.name, style = MaterialTheme.typography.titleMedium)
        }
    }
}

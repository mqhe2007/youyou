package com.example.youyou_album.presentation.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.youyou_album.domain.model.Photo
import com.example.youyou_album.service.MediaDeletionService
import com.example.youyou_album.service.MediaDeletionService.DeleteScope

/** The chosen scope travels unchanged into the deletion coordinator. */
@Composable
fun DeleteScopeDialog(
    photos: List<Photo>,
    preview: List<MediaDeletionService.DeletePreviewItem>?,
    online: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (DeleteScope) -> Unit,
) {
    val localCount = preview?.count { it.hasLocal } ?: 0
    val remoteCount = preview?.count { it.hasRemote } ?: 0
    val defaultScope = when {
        online && localCount > 0 && remoteCount > 0 -> DeleteScope.BOTH
        localCount > 0 -> DeleteScope.PHONE
        else -> DeleteScope.SERVER
    }
    var chosen by remember(preview, online) { mutableStateOf(defaultScope) }
    val options = buildList {
        if (localCount > 0) add(DeleteScope.PHONE to "仅从手机移除")
        if (remoteCount > 0) add(DeleteScope.SERVER to "仅从服务器移除")
        if (localCount > 0 && remoteCount > 0) add(DeleteScope.BOTH to "从手机和服务器移除")
    }
    val applicable = preview?.count { (chosen.local && it.hasLocal) || (chosen.remote && it.hasRemote) } ?: 0
    val enabled = applicable > 0 && (!chosen.remote || online)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择移除范围") },
        text = {
            Column {
                if (preview == null) Text("正在核对手机与服务器上的目标…")
                options.forEach { (scope, label) ->
                    val optionEnabled = !scope.remote || online
                    Row(
                        modifier = Modifier.clickable(enabled = optionEnabled) { chosen = scope },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == scope, onClick = { chosen = scope }, enabled = optionEnabled)
                        Text(label)
                    }
                }
                Text(
                    "本次范围内 $applicable 项，跳过 ${photos.size - applicable} 项。" +
                        if (chosen.local) "手机原件移入系统回收机制。" else "手机原件保留。",
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (chosen.remote) Text("服务器原件进入回收站保留 30 天，可由管理员恢复。", modifier = Modifier.padding(top = 4.dp))
                if (!online && remoteCount > 0) Text(
                    "当前离线，涉及服务器的操作不可用。请连接服务端后再试。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "批量操作逐项执行；取消不会撤回已完成项，结果以实际反馈为准。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            AppTextButton(onClick = { onConfirm(chosen) }, enabled = enabled) {
                Text("确认移除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

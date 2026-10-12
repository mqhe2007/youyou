package com.example.youyou_album.presentation.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.youyou_album.R
import com.example.youyou_album.domain.model.Tag

/**
 * 标签选择弹窗，两种用法共用一套列表行：
 *
 *  * `checkedTagIds != null`：单张照片的勾选编辑——点击立即切换该标签的关联，
 *    弹窗保持打开，右侧显示已勾选状态，底部为「完成」；
 *  * `checkedTagIds == null`：批量选择——点某个标签即触发回调（调用方负责关闭），
 *    底部为「取消」。
 */
@Composable
fun TagPickerDialog(
    title: String,
    tags: List<Tag>,
    checkedTagIds: Set<String>?,
    onTagClick: (Tag) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (tags.isEmpty()) {
                Text(
                    text = "还没有标签，先到「标签」页创建一个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(tags, key = { it.id }) { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTagClick(tag) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (checkedTagIds?.contains(tag.id) == true) {
                                Icon(
                                    painter = painterResource(R.drawable.lucide_ic_check),
                                    contentDescription = "已添加",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (checkedTagIds != null) {
                AppTextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (checkedTagIds == null) {
                AppTextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

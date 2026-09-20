package com.example.youyou_album.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.example.youyou_album.ui.theme.RadiusXl

/**
 * 引导 / 门禁类页面的统一骨架：内容在剩余空间内居中（超大字号时该区域自动可滚），
 * 主 / 次操作**贴底固定**，落在拇指可达区，不会被内容挤出屏幕。
 *
 * 扫码权限门禁页与连接管理的未连接态共用这一骨架 —— 两者是同一类页面，
 * 不应各写一份相似的 Column。骨架本身不含品牌图形，由调用方通过 [content] 提供；
 * 需要方窗图标时用 [NoticeIcon]。
 */
@Composable
fun NoticeLayout(
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = contentAlignment,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(primaryLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(modifier = Modifier.height(8.dp))
            AppTextButton(
                onClick = onSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(secondaryLabel)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 品牌方窗图标：呼应标志里的相册取景窗。灰底独立表面属「状态小标」，
 * 不是页面分组卡片，不违反扁平化规范。
 */
@Composable
fun NoticeIcon(
    painter: Painter,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(104.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(RadiusXl),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

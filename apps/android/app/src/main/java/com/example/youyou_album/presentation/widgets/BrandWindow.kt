package com.example.youyou_album.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.youyou_album.ui.theme.RadiusLg

/**
 * 品牌方窗：标志里「相册取景窗」的缩小版，鲜柚黄实底 + 深色图形。
 *
 * 与 `NoticeIcon`（中性灰方窗，用于空态/门禁页）区分：这个是**品牌色块**，
 * 承担页面的品牌锚点，只用在需要给页面一个视觉重心的位置（如连接管理的状态头）。
 * 鲜柚黄在此属「品牌标志」用法，与实心主按钮同级，不用于浅底图形。
 */
@Composable
fun BrandWindow(
    painter: Painter,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    cornerRadius: Dp = RadiusLg,
    iconSize: Dp = 26.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(cornerRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

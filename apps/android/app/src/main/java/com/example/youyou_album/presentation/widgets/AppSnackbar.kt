package com.example.youyou_album.presentation.widgets

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.youyou_album.R
import com.example.youyou_album.ui.theme.ErrorFill

/**
 * 轻提示视觉语义。同一次操作的成败反馈走同一位置、同一形态，
 * 只有填充色不同，避免「成功弹药丸、失败塞页尾红字」这种两套反馈。
 */
class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    val isError: Boolean = false,
) : SnackbarVisuals

/** 页面统一入口。禁止直接调用 `SnackbarHostState.showSnackbar`，否则会丢掉错误配色。 */
suspend fun SnackbarHostState.showAppSnackbar(
    message: String,
    isError: Boolean = false,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult = showSnackbar(
    AppSnackbarVisuals(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = duration,
        isError = isError,
    )
)

/**
 * 全站统一轻提示宿主。
 *
 * Material 3 默认 Snackbar 用 `inverseSurface` 作底色，在暖象牙浅色主题下渲染成纯黑横条，
 * 与「轻量扁平化」语言冲突。这里改成**药丸**：全圆角、无描边、无阴影，只有两种填充 ——
 *
 * - 成功 / 中立：`primary`（鲜柚黄 `#FFD63A`）实底 + `onPrimary` 深字；
 * - 失败：`ErrorFill`（`#D92D20`）实底 + `onError` 白字。
 *
 * 鲜柚黄在此作为填充色使用（同实心主按钮），不给它做浅底文字。
 * 宽度按内容自适应并水平居中，贴合文案长度；仅文案本身超宽时才换行。
 * 因此提示文案保持为短动词句（如「正在扫描…」），长句说明交给任务中心承载。
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        AppSnackbar(data = data)
    }
}

@Composable
private fun AppSnackbar(data: SnackbarData) {
    val isError = (data.visuals as? AppSnackbarVisuals)?.isError == true
    val containerColor = if (isError) ErrorFill else MaterialTheme.colorScheme.primary
    val contentColor = if (isError) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary

    Surface(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            data.visuals.actionLabel?.let { label ->
                AppTextButton(
                    onClick = { data.performAction() },
                    modifier = Modifier.padding(start = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) { Text(label) }
            }
            if (data.visuals.withDismissAction) {
                IconButton(
                    onClick = { data.dismiss() },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.lucide_ic_x),
                        contentDescription = "关闭",
                        tint = if (isError) Color.White else MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

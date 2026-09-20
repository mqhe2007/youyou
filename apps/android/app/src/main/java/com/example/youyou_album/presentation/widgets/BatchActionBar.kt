package com.example.youyou_album.presentation.widgets

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.youyou_album.R

/** 批量操作条里的一个建设性动作。`primary` 为真时用鲜柚黄实底，否则是描边次级形态。 */
@Immutable
data class BatchAction(
    val label: String,
    @param:DrawableRes val icon: Int,
    val primary: Boolean,
    val onClick: () -> Unit,
)

private val CapsuleHeight = 64.dp
private val CapsulePadding = 8.dp
private val ActionHeight = 48.dp
private val CapsuleShape: Shape = RoundedCornerShape(percent = 50)

/** 自绘投影参数：等效 CSS `0 4dp 14dp rgba(28,27,26,.16)`。 */
private val ShadowOffsetY = 4.dp
private val ShadowSpreadStep = 0.6.dp
private const val ShadowLayers = 16
private const val ShadowLayerAlpha = 0.012f

/** 批量操作条进场 / 退场时长（ms）：短促、与抽屉式工具条同一节奏。 */
const val BatchBarEnterMs = 180
const val BatchBarExitMs = 120

/**
 * 批量操作条：水平居中的悬浮胶囊。
 *
 * 建设性动作走 [actions]（`primary` 动作用鲜柚黄实底，其余描边），破坏性动作固定在最右、用 1dp
 * 竖线分区；点击删除由调用方弹确认框。计数由顶栏承载，胶囊只放动作。
 *
 * 不用 tonalElevation（本主题 surfaceTint 透明，
 * 它抬不起层次），浅色靠纸白 + 阴影，深色靠更亮的 surface 容器 + 描边。
 */
@Composable
fun BatchActionBar(
    onDelete: () -> Unit,
    actions: List<BatchAction>,
    modifier: Modifier = Modifier,
    deleteContentDescription: String = "删除",
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleShadow(
            enabled = !dark,
            color = colors.onSurface,
        ) {
            Surface(
                shape = CapsuleShape,
                color = colors.surfaceBright,
                border = if (dark) BorderStroke(1.dp, colors.outlineVariant) else null,
                modifier = Modifier.animateContentSize(),
            ) {
                Row(
                    modifier = Modifier
                        .height(CapsuleHeight)
                        .padding(horizontal = CapsulePadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { action ->
                        BatchActionButton(action)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (actions.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        VerticalDivider(
                            modifier = Modifier.height(24.dp),
                            color = colors.outlineVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(ActionHeight),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.lucide_ic_trash_2),
                            contentDescription = deleteContentDescription,
                            tint = colors.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自绘柔和投影：多层同形状圆角描边，由内到外累积出渐变（每层约 1.2% 黑，峰值 ≈ 17%）。
 *
 * 不用 `shadowElevation`：平台阴影在部分机型上会被 `clipToBounds`（`animateContentSize` 自带）
 * 或 OEM 裁剪渲染成硬边残影——真机 HarmonyOS / Android 12 已复现，模拟器 API 36 复现不了。
 * 自绘与平台实现解耦，深浅色一致可控。
 */
@Composable
private fun CapsuleShadow(
    enabled: Boolean,
    color: Color,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    Box(
        modifier = Modifier.drawBehind {
            val step = ShadowSpreadStep.toPx()
            val radius = size.height / 2f
            val offsetY = ShadowOffsetY.toPx()
            repeat(ShadowLayers) { index ->
                val spread = (index + 1) * step
                drawRoundRect(
                    color = color.copy(alpha = ShadowLayerAlpha),
                    topLeft = Offset(-spread, -spread + offsetY),
                    size = Size(size.width + spread * 2f, size.height + spread * 2f),
                    cornerRadius = CornerRadius(radius + spread, radius + spread),
                    style = Stroke(width = step),
                )
            }
        },
    ) { content() }
}

@Composable
private fun BatchActionButton(action: BatchAction) {
    val colors = MaterialTheme.colorScheme
    val label: @Composable () -> Unit = {
        Icon(
            painter = painterResource(action.icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (action.primary) colors.onPrimary else colors.onSurface,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = action.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (action.primary) colors.onPrimary else colors.onSurface,
        )
    }
    // 主操作用 M3 默认色（primary 实底 + onPrimary 前景）；禁止 FilledTonalButton。
    if (action.primary) {
        Button(
            onClick = action.onClick,
            modifier = Modifier.height(ActionHeight),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(horizontal = 18.dp),
            content = { label() },
        )
    } else {
        OutlinedButton(
            onClick = action.onClick,
            modifier = Modifier.height(ActionHeight),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
            border = BorderStroke(1.dp, colors.outlineVariant),
            contentPadding = PaddingValues(horizontal = 18.dp),
            content = { label() },
        )
    }
}

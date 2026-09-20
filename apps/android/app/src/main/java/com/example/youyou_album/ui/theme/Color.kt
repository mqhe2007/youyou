package com.example.youyou_album.ui.theme

import androidx.compose.ui.graphics.Color

// ---- 主强调色：鲜柚黄 ----
val Primary = Color(0xFFFFD63A)
val PrimaryDark = Color(0xFFF0C82E)
val OnPrimary = Color(0xFF1C1B1A)

// ---- 中性色：干净暖象牙 ----
val Scaffold = Color(0xFFFFFBF5)
val Surface = Color(0xFFFFFDFA)
val SurfaceContainer = Color(0xFFF5F1EB)
val SurfaceContainerHigh = Color(0xFFEDE8E1)

// ---- 文本色 ----
val OnSurface = Color(0xFF1C1B1A)
val OnSurfaceVariant = Color(0xFF625E58)
val OnSurfaceDisabled = Color(0xFFB0ACA5)

// ---- 状态色 ----
val Error = Color(0xFFF04438)
val OnError = Color(0xFFFFFFFF)
val Success = Color(0xFF17A34A)
val Warning = Color(0xFFFF9F0A)

// 错误实底：需要「红底白字」的场合统一用它 —— 轻提示的失败形态、破坏性动作按钮。
// 不复用 Error 作底 —— #F04438 压白字只有 3.76:1，达不到正文 AA（4.5:1）。
// 同色相加深到 #D92D20 后为 4.83:1，深浅色模式通用（自底自字，与页面底无关），
// 因此按钮不必再按主题切换色值。
// 只作填充，不改 colorScheme.error（后者还承载错误文字与对话框文案）。
val ErrorFill = Color(0xFFD92D20)

// 收藏红：仅限收藏语义（心形等），与错误红 #F04438 严格区分
val Favorite = Color(0xFFFF3B30)

// 画廊黑：照片查看器 / 幻灯片 / 视频的沉浸背景
val GalleryBackground = Color(0xFF121110)

// ---- 边框/分隔线 ----
val Outline = OnSurfaceVariant.copy(alpha = 0.32f)
val OutlineVariant = OnSurfaceVariant.copy(alpha = 0.14f)

// ---- 阴影 ----
val Shadow = Primary.copy(alpha = 0.15f)

// ---- 深色主题：暖调深色 ----
val DarkSurface = Color(0xFF1A1816)
val DarkOnSurface = Color(0xFFF7F4F0)
val DarkOnSurfaceVariant = Color(0xFFC4BFB8)
val DarkSurfaceContainerHighest = Color(0xFF2A2724)
val DarkSurfaceContainerHigh = Color(0xFF24211E)
val DarkOutline = DarkOnSurfaceVariant.copy(alpha = 0.35f)
val DarkOutlineVariant = DarkOnSurfaceVariant.copy(alpha = 0.15f)

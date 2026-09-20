package com.example.youyou_album.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 等宽数字（tabular figures）
private const val TNUM = "tnum"

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W700,
        fontSize = 57.sp,
        letterSpacing = (-1.0).sp,
        lineHeight = 60.sp,
        fontFeatureSettings = TNUM,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W700,
        fontSize = 45.sp,
        letterSpacing = (-0.7).sp,
        lineHeight = 50.sp,
        fontFeatureSettings = TNUM,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 36.sp,
        letterSpacing = (-0.4).sp,
        lineHeight = 42.sp,
        fontFeatureSettings = TNUM,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 32.sp,
        letterSpacing = (-0.3).sp,
        lineHeight = 38.sp,
        fontFeatureSettings = TNUM,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 28.sp,
        letterSpacing = (-0.2).sp,
        lineHeight = 35.sp,
        fontFeatureSettings = TNUM,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        fontFeatureSettings = TNUM,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        letterSpacing = (-0.1).sp,
        lineHeight = 28.sp,
        fontFeatureSettings = TNUM,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TNUM,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TNUM,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = TNUM,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontFeatureSettings = TNUM,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontFeatureSettings = TNUM,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TNUM,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TNUM,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = TNUM,
    ),
)

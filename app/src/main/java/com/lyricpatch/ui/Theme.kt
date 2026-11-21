package com.lyricpatch.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 把 #RRGGBB 解成颜色；写坏了就用默认紫 */
fun accentColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF5B4BC6))

/** 按 HSL 从一个种子色推派生色，省掉 material-color-utilities 依赖 */
private fun hsl(seed: Color, light: Float, hueShift: Float = 0f, satScale: Float = 1f): Color {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSL(seed.toArgb(), out)
    val h = (out[0] + hueShift + 360f) % 360f
    val s = (out[1] * satScale).coerceIn(0.12f, 1f)
    return Color(android.graphics.Color.HSLToColor(floatArrayOf(h, s, light.coerceIn(0.04f, 0.97f))))
}

private fun onColor(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White

/** 自定义主题色：只覆盖强调色系列，中性色沿用 M3 默认 */
fun seededScheme(hex: String, dark: Boolean): ColorScheme {
    val seed = accentColor(hex)
    return if (dark) {
        val primary = hsl(seed, 0.78f)
        darkColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = hsl(seed, 0.30f),
            onPrimaryContainer = hsl(seed, 0.91f),
            secondary = hsl(seed, 0.74f, 22f, 0.65f),
            secondaryContainer = hsl(seed, 0.30f, 22f, 0.65f),
            onSecondaryContainer = hsl(seed, 0.91f, 22f, 0.65f),
            tertiary = hsl(seed, 0.76f, -28f),
            tertiaryContainer = hsl(seed, 0.30f, -28f),
        )
    } else {
        val primary = hsl(seed, 0.42f)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = hsl(seed, 0.90f),
            onPrimaryContainer = hsl(seed, 0.18f),
            secondary = hsl(seed, 0.40f, 22f, 0.65f),
            secondaryContainer = hsl(seed, 0.92f, 22f, 0.45f),
            onSecondaryContainer = hsl(seed, 0.20f, 22f, 0.65f),
            tertiary = hsl(seed, 0.36f, -28f),
            tertiaryContainer = hsl(seed, 0.90f, -28f),
        )
    }
}

/** Expressive 的字重 / 字距 */
private val ExpressiveTypography: Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.6).sp,
        ),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Expressive 的形状令牌 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun LyricPatchTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = if (UiPrefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        seededScheme(UiPrefs.accent, dark)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}

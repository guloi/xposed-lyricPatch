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
import com.lyricpatch.lib.color.ColorSpec
import com.lyricpatch.lib.color.ColorStyle
import com.lyricpatch.lib.color.accentColor

/** 按 HSL 从一个种子色推派生色，省掉 material-color-utilities 依赖 */
private fun hsl(seed: Color, light: Float, hueShift: Float = 0f, satScale: Float = 1f): Color {
    val out = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), out)
    val h = (out[0] + hueShift + 360f) % 360f
    // 下限放到 0.05：“单色”风格需要接近无饱和
    val s = (out[1] * satScale).coerceIn(0.05f, 1f)
    val l = light.coerceIn(0.04f, 0.97f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, l)))
}

private fun onColor(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White

/**
 * 自定义主题色：按色彩风格（色相/饱和映射）与色彩标准（容器明度）生成强调色系列，
 * 中性色（背景、卡片）沿用 M3 默认，避免整体观感跑偏。
 */
fun seededScheme(
    hex: String,
    style: ColorStyle,
    spec: ColorSpec,
    dark: Boolean,
): ColorScheme {
    val seed = accentColor(hex)
    val sat = style.satScale
    val sh = style.secondaryHue
    val th = style.tertiaryHue
    return if (dark) {
        val primary = hsl(seed, spec.darkPrimary, 0f, sat)
        darkColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = hsl(seed, spec.darkContainer, 0f, sat),
            onPrimaryContainer = hsl(seed, 0.91f, 0f, sat),
            secondary = hsl(seed, 0.74f, sh, 0.65f * sat),
            secondaryContainer = hsl(seed, spec.darkContainer, sh, 0.65f * sat),
            onSecondaryContainer = hsl(seed, 0.91f, sh, 0.65f * sat),
            tertiary = hsl(seed, 0.76f, th, sat),
            tertiaryContainer = hsl(seed, spec.darkContainer, th, sat),
        )
    } else {
        val primary = hsl(seed, spec.lightPrimary, 0f, sat)
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = hsl(seed, spec.lightContainer, 0f, sat),
            onPrimaryContainer = hsl(seed, 0.18f, 0f, sat),
            secondary = hsl(seed, 0.40f, sh, 0.65f * sat),
            secondaryContainer = hsl(seed, spec.lightContainer, sh, 0.45f * sat),
            onSecondaryContainer = hsl(seed, 0.20f, sh, 0.65f * sat),
            tertiary = hsl(seed, 0.36f, th, sat),
            tertiaryContainer = hsl(seed, spec.lightContainer, th, sat),
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
        seededScheme(UiPrefs.accent, UiPrefs.colorStyle, UiPrefs.colorSpec, dark)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}

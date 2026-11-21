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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
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

/** 在 HSL 空间里偏移一个颜色：色相旋转、饱和缩放、明度微调 */
private fun shiftColor(color: Color, hueShift: Float, satScale: Float, lightDelta: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    val h = ((hsl[0] + hueShift) % 360f + 360f) % 360f
    val s = (hsl[1] * satScale).coerceIn(0.02f, 1f)
    val l = (hsl[2] + lightDelta).coerceIn(0.02f, 0.98f)
    return Color(ColorUtils.HSLToColor(floatArrayOf(h, s, l)))
}

/**
 * 把动态取色（莫奈）的强调色系列按风格 / 规范微调，其余角色原样保留，
 * 这样壁纸带来的背景与卡片色调不会被冲掉，但色彩风格 / 标准也能生效。
 */
private fun restyle(base: ColorScheme, style: ColorStyle, spec: ColorSpec, dark: Boolean): ColorScheme {
    val delta = if (spec == ColorSpec.SPEC_2025) 0.02f else 0f
    val p = shiftColor(base.primary, 0f, style.satScale, delta * 0.5f)
    val pc = shiftColor(base.primaryContainer, 0f, style.satScale, delta)
    val s = shiftColor(base.secondary, style.secondaryHue, style.satScale, 0f)
    val sc = shiftColor(base.secondaryContainer, style.secondaryHue, style.satScale, delta)
    val t = shiftColor(base.tertiary, style.tertiaryHue, style.satScale, 0f)
    val tc = shiftColor(base.tertiaryContainer, style.tertiaryHue, style.satScale, delta)
    return if (dark) {
        darkColorScheme(
            primary = p, onPrimary = onColor(p),
            primaryContainer = pc, onPrimaryContainer = onColor(pc),
            inversePrimary = base.inversePrimary,
            secondary = s, onSecondary = base.onSecondary,
            secondaryContainer = sc, onSecondaryContainer = base.onSecondaryContainer,
            tertiary = t, onTertiary = base.onTertiary,
            tertiaryContainer = tc, onTertiaryContainer = base.onTertiaryContainer,
            background = base.background, onBackground = base.onBackground,
            surface = base.surface, onSurface = base.onSurface,
            surfaceVariant = base.surfaceVariant, onSurfaceVariant = base.onSurfaceVariant,
            surfaceTint = p,
            inverseSurface = base.inverseSurface, inverseOnSurface = base.inverseOnSurface,
            error = base.error, onError = base.onError,
            errorContainer = base.errorContainer, onErrorContainer = base.onErrorContainer,
            outline = base.outline, outlineVariant = base.outlineVariant, scrim = base.scrim,
            surfaceBright = base.surfaceBright, surfaceDim = base.surfaceDim,
            surfaceContainer = base.surfaceContainer,
            surfaceContainerHigh = base.surfaceContainerHigh,
            surfaceContainerHighest = base.surfaceContainerHighest,
            surfaceContainerLow = base.surfaceContainerLow,
            surfaceContainerLowest = base.surfaceContainerLowest,
        )
    } else {
        lightColorScheme(
            primary = p, onPrimary = onColor(p),
            primaryContainer = pc, onPrimaryContainer = onColor(pc),
            inversePrimary = base.inversePrimary,
            secondary = s, onSecondary = base.onSecondary,
            secondaryContainer = sc, onSecondaryContainer = base.onSecondaryContainer,
            tertiary = t, onTertiary = base.onTertiary,
            tertiaryContainer = tc, onTertiaryContainer = base.onTertiaryContainer,
            background = base.background, onBackground = base.onBackground,
            surface = base.surface, onSurface = base.onSurface,
            surfaceVariant = base.surfaceVariant, onSurfaceVariant = base.onSurfaceVariant,
            surfaceTint = p,
            inverseSurface = base.inverseSurface, inverseOnSurface = base.inverseOnSurface,
            error = base.error, onError = base.onError,
            errorContainer = base.errorContainer, onErrorContainer = base.onErrorContainer,
            outline = base.outline, outlineVariant = base.outlineVariant, scrim = base.scrim,
            surfaceBright = base.surfaceBright, surfaceDim = base.surfaceDim,
            surfaceContainer = base.surfaceContainer,
            surfaceContainerHigh = base.surfaceContainerHigh,
            surfaceContainerHighest = base.surfaceContainerHighest,
            surfaceContainerLow = base.surfaceContainerLow,
            surfaceContainerLowest = base.surfaceContainerLowest,
        )
    }
}

@Composable
fun LyricPatchTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = if (UiPrefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // 莫奈取色下也让色彩风格 / 标准生效：只调强调色，背景与卡片保留壁纸色调
        restyle(base, UiPrefs.colorStyle, UiPrefs.colorSpec, dark)
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

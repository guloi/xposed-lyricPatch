package com.lyricpatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import java.util.Locale

/** 调色板里的一个颜色 */
data class AccentColor(val hex: String, val name: String)

/** 一组色彩风格 */
data class AccentStyle(val key: String, val label: String, val colors: List<AccentColor>)

val ACCENT_STYLES = listOf(
    AccentStyle(
        "soft", "柔和",
        listOf(
            AccentColor("#7C7CE0", "薰衣草"),
            AccentColor("#6E93C8", "雾蓝"),
            AccentColor("#5FA8A0", "薄青"),
            AccentColor("#7FA36B", "苔绿"),
            AccentColor("#C2A24A", "沙金"),
            AccentColor("#C97C6A", "陶土"),
            AccentColor("#C98BA8", "藕粉"),
            AccentColor("#8A8D93", "石灰"),
        ),
    ),
    AccentStyle(
        "vivid", "鲜明",
        listOf(
            AccentColor("#5B4BC6", "紫罗兰"),
            AccentColor("#2563EB", "靖蓝"),
            AccentColor("#0F8C7E", "青碧"),
            AccentColor("#2E7D32", "草绿"),
            AccentColor("#D97706", "琥珀"),
            AccentColor("#C2410C", "朱橙"),
            AccentColor("#B3246B", "洋红"),
            AccentColor("#374151", "石墨"),
        ),
    ),
    AccentStyle(
        "deep", "深色",
        listOf(
            AccentColor("#4A3D9E", "深紫"),
            AccentColor("#1E3A8A", "藏蓝"),
            AccentColor("#14532D", "墨绿"),
            AccentColor("#115E59", "松石"),
            AccentColor("#7F1D1D", "酒红"),
            AccentColor("#78350F", "赭石"),
            AccentColor("#701A45", "玫紫"),
            AccentColor("#1F2937", "炭灰"),
        ),
    ),
    AccentStyle(
        "neutral", "中性",
        listOf(
            AccentColor("#3F3F46", "炭黑"),
            AccentColor("#52525B", "雾灰"),
            AccentColor("#64748B", "冷灰"),
            AccentColor("#78716C", "暖灰"),
            AccentColor("#8A6A3B", "青铜"),
            AccentColor("#6B705C", "橄榄"),
            AccentColor("#3A4A63", "藏青"),
            AccentColor("#27272A", "墨黑"),
        ),
    ),
)

/** HSL → #RRGGBB */
private fun hexOf(h: Float, s: Float, l: Float): String {
    val argb = ColorUtils.HSLToColor(floatArrayOf(h, s, l))
    return String.format(Locale.ROOT, "#%06X", 0xFFFFFF and argb)
}

/** 调色板：风格分区 + 带名字的色块 + HSL 自定义 */
@Composable
fun AccentPicker() {
    var style by remember { mutableStateOf(ACCENT_STYLES.first()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "色彩风格",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ACCENT_STYLES.forEach { item ->
                FilterChip(
                    selected = item.key == style.key,
                    onClick = { style = item },
                    label = {
                        Text(
                            item.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        style.colors.chunked(4).forEach { rowColors ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowColors.forEach { color ->
                    AccentSwatch(color, Modifier.weight(1f))
                }
                repeat(4 - rowColors.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(2.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(12.dp))

        CustomAccentSliders()
    }
}

@Composable
private fun AccentSwatch(color: AccentColor, modifier: Modifier = Modifier) {
    val selected = UiPrefs.accent.equals(color.hex, ignoreCase = true)
    val swatch = remember(color.hex) { accentColor(color.hex) }
    Column(
        modifier = modifier.clickable { UiPrefs.updateAccent(color.hex) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(40.dp)
                .clip(CircleShape)
                .background(swatch)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", color = if (swatch.luminance() > 0.5f) Color.Black else Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            color.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CustomAccentSliders() {
    val start = remember {
        val out = FloatArray(3)
        ColorUtils.colorToHSL(accentColor(UiPrefs.accent).toArgb(), out)
        out
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var light by remember { mutableFloatStateOf(start[2]) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(accentColor(UiPrefs.accent)),
        )
        Spacer(Modifier.width(12.dp))
        Text("自定义", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Text(
            UiPrefs.accent.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ColorSlider("色相", hue, 0f..360f) {
        hue = it
        UiPrefs.updateAccent(hexOf(hue, sat, light))
    }
    ColorSlider("饱和度", sat, 0f..1f) {
        sat = it
        UiPrefs.updateAccent(hexOf(hue, sat, light))
    }
    ColorSlider("明度", light, 0.08f..0.92f) {
        light = it
        UiPrefs.updateAccent(hexOf(hue, sat, light))
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}

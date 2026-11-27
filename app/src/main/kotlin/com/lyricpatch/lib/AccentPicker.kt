package com.lyricpatch.lib

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.lyricpatch.ui.UiPrefs
import com.lyricpatch.lib.color.accentColor
import java.util.Locale

/**
 * HSL → #RRGGBB。只有本文件在用，所以留在原文件，不往 lib 提。
 * ColorUtils.HSLToColor 要求 h ∈ [0,360)、s/l ∈ [0,1]，越界会抛异常，先夹一遍。
 */
private fun hslToHex(h: Float, s: Float, l: Float): String {
    val hue = ((h % 360f) + 360f) % 360f
    val argb = ColorUtils.HSLToColor(
        floatArrayOf(hue, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)),
    )
    return String.format(Locale.ROOT, "#%06X", 0xFFFFFF and argb)
}

/** 调色板：风格分区 + 带名字的色块 + HSL 自定义 */
@Composable
fun AccentPicker() {
    var style by remember { mutableStateOf(ACCENT_STYLES.first()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        // 这里是“预设色板”，与下方的“色彩风格（tonal style）”不是一回事
        Text("色板", style = MaterialTheme.typography.titleSmall)
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
        Box(Modifier.size(32.dp).clip(CircleShape).background(accentColor(UiPrefs.accent)))
        Spacer(Modifier.width(12.dp))
        Text("自定义", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Text(
            UiPrefs.accent.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 上限用 359 而不是 360：HSL 的色相是左闭右开，360 属于非法值
    ColorSlider("色相", hue, 0f..359f) {
        hue = it
        UiPrefs.updateAccent(hslToHex(hue, sat, light))
    }
    ColorSlider("饱和度", sat, 0f..1f) {
        sat = it
        UiPrefs.updateAccent(hslToHex(hue, sat, light))
    }
    ColorSlider("明度", light, 0.08f..0.92f) {
        light = it
        UiPrefs.updateAccent(hslToHex(hue, sat, light))
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

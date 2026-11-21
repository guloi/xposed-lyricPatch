package com.lyricpatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border

/** 外观设置页：取色方式 + 大标题折叠 */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        UiPrefs.load(this)
        setContent {
            LyricPatchTheme {
                AppearanceScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(4.dp))
                Text("外观", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(8.dp))

            SettingsCard {
                SwitchRow(
                    title = "莫奈取色",
                    subtitle = "跟随壁纸生成配色（Android 12 及以上）",
                    checked = UiPrefs.dynamicColor,
                    onCheckedChange = { UiPrefs.setDynamicColor(it) },
                )
                if (!UiPrefs.dynamicColor) {
                    RowDivider()
                    AccentPicker()
                }
            }
            CardNote("关闭后使用下方选定的颜色作为主题色。")

            Spacer(Modifier.height(16.dp))

            SettingsCard {
                SwitchRow(
                    title = "大标题折叠",
                    subtitle = "点击大标题可收起该分组",
                    checked = UiPrefs.collapsible,
                    onCheckedChange = { UiPrefs.setCollapsible(it) },
                )
            }
            CardNote("关闭后恢复为固定标题样式，所有分组始终展开。")

            Spacer(Modifier.height(16.dp))

            SettingsCard {
                SwitchRow(
                    title = "保存条上滑呼出",
                    subtitle = "不常驻底部；上滑显示，下滑收起",
                    checked = UiPrefs.floatingSaveBar,
                    onCheckedChange = { UiPrefs.setFloatingSaveBar(it) },
                )
            }
            CardNote("开启后设置页底部只留一条细线，上滑呼出保存与重新读取。")

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccentPicker() {
    Row(
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UiPrefs.ACCENTS.forEach { (hex, label) ->
            val color = remember(hex) { accentColor(hex) }
            val selected = UiPrefs.accent == hex
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(44.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (selected) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { UiPrefs.setAccent(hex) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Text(
                            "✓",
                            color = if (color.luminance() > 0.5f) Color.Black else Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

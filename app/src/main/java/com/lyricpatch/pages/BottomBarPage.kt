package com.lyricpatch.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyricpatch.lib.*
import com.lyricpatch.lib.color.ColorSpec
import com.lyricpatch.lib.color.ColorStyle
import com.lyricpatch.ui.UiPrefs

/** 底边栏设置：外观、底栏样式和页面动画 */
@Composable
fun BottomBarPage(onBack: () -> Unit) {
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
                Text("底边栏设置", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(8.dp))

            SettingsCard {
                SwitchRow(
                    title = "莫奈取色",
                    subtitle = "跟随壁纸生成配色（Android 12 及以上）",
                    checked = UiPrefs.dynamicColor,
                    onCheckedChange = { UiPrefs.updateDynamicColor(it) },
                )
                SectionBody(!UiPrefs.dynamicColor) {
                    RowDivider()
                    AccentPicker()
                }
                RowDivider()
                ChoiceRow(
                    title = "色彩风格",
                    subtitle = "种子色如何映射到主色与三色",
                    options = ColorStyle.entries.map { it.name to it.label },
                    selected = UiPrefs.colorStyle.name,
                    onSelect = { UiPrefs.updateColorStyle(ColorStyle.of(it)) },
                )
                RowDivider()
                ChoiceRow(
                    title = "色彩标准",
                    subtitle = "容器明度档位，2025 对应 Expressive",
                    options = ColorSpec.entries.map { it.name to it.label },
                    selected = UiPrefs.colorSpec.name,
                    onSelect = { UiPrefs.updateColorSpec(ColorSpec.of(it)) },
                )
                RowDivider()
                SwitchRow(
                    title = "MD3 样式底栏",
                    subtitle = "非悬浮时使用 Material3 底栏",
                    checked = UiPrefs.bottomBarMd3,
                    onCheckedChange = { UiPrefs.updateBottomBarMd3(it) },
                )
                RowDivider()
                SwitchRow(
                    title = "悬浮底栏",
                    subtitle = "浮在页面底部，选中项是圆角胶囊",
                    checked = UiPrefs.floatingBottomBar,
                    onCheckedChange = { UiPrefs.updateFloatingBottomBar(it) },
                )
                RowDivider()
                SwitchRow(
                    title = "显示底栏文字",
                    subtitle = "关闭后只显示图标",
                    checked = UiPrefs.bottomBarLabels,
                    onCheckedChange = { UiPrefs.updateBottomBarLabels(it) },
                )
                RowDivider()
                SwitchRow(
                    title = "首页背景渐变",
                    subtitle = "首页背景从主题色淡出到普通背景",
                    checked = UiPrefs.homeGradient,
                    onCheckedChange = { UiPrefs.updateHomeGradient(it) },
                )
                RowDivider()
                SwitchRow(
                    title = "首页切换动画",
                    subtitle = "首页和设置之间切换时淡入淡出",
                    checked = UiPrefs.tabAnimation,
                    onCheckedChange = { UiPrefs.updateTabAnimation(it) },
                )
                RowDivider()
                SwitchRow(
                    title = "页面过渡动画",
                    subtitle = "打开设置分类时淡入",
                    checked = UiPrefs.pageTransition,
                    onCheckedChange = { UiPrefs.updatePageTransition(it) },
                )
                RowDivider()
                SliderRow(
                    title = "回弹幅度",
                    subtitle = "开关和图标弹一下的幅度，0 为关闭",
                    value = UiPrefs.bouncePercent,
                    range = 0f..30f,
                    steps = 29,
                    display = if (UiPrefs.bouncePercent <= 0) "关闭" else "${UiPrefs.bouncePercent}%",
                    onChange = { UiPrefs.updateBounce(it) },
                )
            }
            CardNote("改完立即生效。")

            Spacer(Modifier.height(24.dp))
        }
    }
}

package com.lyricpatch.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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

/** 外观页：取色方式 / 大标题折叠 / 回弹幅度 / 分组动画 */
@Composable
fun AppearancePage(onBack: () -> Unit) {
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
                    onCheckedChange = { UiPrefs.updateDynamicColor(it) },
                )
                // 和设置页的分组用同一套展开逻辑（内含首帧不播 / 不误触处理）
                SectionBody(!UiPrefs.dynamicColor) {
                    RowDivider()
                    AccentPicker()
                }
                // 色彩风格 / 标准始终显示，不随莫奈取色开关隐藏
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
            }
            CardNote("关闭莫奈取色后，使用上方选定的颜色与风格作为主题色。")

            Spacer(Modifier.height(16.dp))

            SettingsCard {
                SwitchRow(
                    title = "大标题折叠",
                    subtitle = "点击大标题可收起该分组",
                    checked = UiPrefs.collapsible,
                    onCheckedChange = { UiPrefs.updateCollapsible(it) },
                )
            }
            CardNote("关闭后恢复为固定标题样式，所有分组始终展开。")

            // 只在开启「大标题折叠」时出现
            SectionBody(UiPrefs.collapsible) {
                Spacer(Modifier.height(16.dp))
                SettingsCard {
                    SwitchRow(
                        title = "首次打开默认折叠",
                        subtitle = "只在第一次启动应用时生效",
                        checked = UiPrefs.collapseByDefault,
                        onCheckedChange = { UiPrefs.updateCollapseByDefault(it) },
                    )
                }
                CardNote("仅决定进入页面时的初始状态，手动展开的分组不会写回。")
            }

            Spacer(Modifier.height(16.dp))

            SettingsCard {
                SliderRow(
                    title = "回弹幅度",
                    subtitle = "折叠、开关、箭头弹一下的幅度，0 为关闭",
                    value = UiPrefs.bouncePercent,
                    range = 0f..30f,
                    steps = 29,
                    display = if (UiPrefs.bouncePercent <= 0) "关闭" else "${UiPrefs.bouncePercent}%",
                    onChange = { UiPrefs.updateBounce(it) },
                )
                RowDivider()
                SwitchRow(
                    title = "大标题跳动",
                    subtitle = "点击大标题时整块卡片弹一下",
                    checked = UiPrefs.sectionHeaderPop,
                    onCheckedChange = { UiPrefs.updateSectionHeaderPop(it) },
                )
                RowDivider()
                SwitchRow(
                    title = "弹簧式展开",
                    subtitle = "展开用弹簧曲线，关闭则用匀速过渡",

                    checked = UiPrefs.sectionLegacyAnim,
                    onCheckedChange = { UiPrefs.updateSectionLegacyAnim(it) },
                )
            }
            CardNote("只影响界面动画，不影响歌词显示。")

            Spacer(Modifier.height(16.dp))

            CardNote("外观偏好点击即生效并写入 ui_prefs；歌词配置在设置页里也是改动即保存。")

            Spacer(Modifier.height(24.dp))
        }
    }
}

package com.lyricpatch.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyricpatch.lib.*
import com.lyricpatch.lib.color.ColorSpec
import com.lyricpatch.lib.color.ColorStyle
import com.lyricpatch.ui.UiPrefs

/** 底边栏设置：外观、底栏样式和动画 */
@Composable
fun BottomBarPage(onBack: () -> Unit) {
    var pickStyle by remember { mutableStateOf(false) }
    var pickSpec by remember { mutableStateOf(false) }

    val styleOptions = ColorStyle.entries.map { it.name to it.label }
    val specOptions = ColorSpec.entries.map { it.name to it.label }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PageTopBar(
                title = "外观",
                subtitle = "配色、底栏样式和动画",
                onBack = onBack,
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(top = 6.dp)) {
                SectionLabel("配色")
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
                    ValueRow(
                        title = "色彩风格",
                        subtitle = "种子色如何映射到主色与三色",
                        value = UiPrefs.colorStyle.label,
                        onClick = { pickStyle = true },
                    )
                    RowDivider()
                    ValueRow(
                        title = "色彩标准",
                        subtitle = "容器明度档位，2025 对应 Expressive",
                        value = UiPrefs.colorSpec.label,
                        onClick = { pickSpec = true },
                    )
                }

                SectionLabel("底栏")
                SettingsCard {
                    SwitchRow(
                        title = "MD3 样式",
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
                        title = "显示文字",
                        subtitle = "关闭后只显示图标",
                        checked = UiPrefs.bottomBarLabels,
                        onCheckedChange = { UiPrefs.updateBottomBarLabels(it) },
                    )
                }

                SectionLabel("动画")
                SettingsCard {
                    SwitchRow(
                        title = "首页背景渐变",
                        subtitle = "从主题色淡出到普通背景",
                        checked = UiPrefs.homeGradient,
                        onCheckedChange = { UiPrefs.updateHomeGradient(it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "标签切换动画",
                        subtitle = "首页和设置之间淡入淡出",
                        checked = UiPrefs.tabAnimation,
                        onCheckedChange = { UiPrefs.updateTabAnimation(it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "页面过渡",
                        subtitle = "打开分类设置时淡入",
                        checked = UiPrefs.pageTransition,
                        onCheckedChange = { UiPrefs.updatePageTransition(it) },
                    )
                    RowDivider()
                    SliderRow(
                        title = "回弹幅度",
                        subtitle = "开关拇指弹一下的幅度，0 为关闭",
                        value = UiPrefs.bouncePercent,
                        range = 0f..30f,
                        steps = 29,
                        display = if (UiPrefs.bouncePercent <= 0) "关闭"
                        else "${UiPrefs.bouncePercent}%",
                        onChange = { UiPrefs.updateBounce(it) },
                    )
                }

                CardNote("改完立即生效。")
            }
            Spacer(Modifier.height(24.dp))
        }

        if (pickStyle) {
            ChoiceDialog(
                title = "色彩风格",
                options = styleOptions,
                selected = UiPrefs.colorStyle.name,
                onDismiss = { pickStyle = false },
                onSelect = { UiPrefs.updateColorStyle(ColorStyle.of(it)) },
            )
        }
        if (pickSpec) {
            ChoiceDialog(
                title = "色彩标准",
                options = specOptions,
                selected = UiPrefs.colorSpec.name,
                onDismiss = { pickSpec = false },
                onSelect = { UiPrefs.updateColorSpec(ColorSpec.of(it)) },
            )
        }
    }
}

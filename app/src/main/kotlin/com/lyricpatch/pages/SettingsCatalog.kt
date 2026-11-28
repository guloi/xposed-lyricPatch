package com.lyricpatch.pages

/**
 * 设置分类。设置页先展示这四个入口，点进去才是该分类真正的设置项。
 * [sections] 对应 [SettingsPage] 里的分组标题。
 */
class SettingsCategory(
    val title: String,
    val subtitle: String,
    val sections: List<String>,
)

val SETTINGS_CATEGORIES = listOf(
    SettingsCategory(
        title = "全部",
        subtitle = "列出全部设置(不包括外观)",
        sections = listOf("数据源", "翻译","逐字","长句滚动","应用过滤","其他设置","调试"),
    ),
    SettingsCategory(
        title = "歌词",
        subtitle = "数据源和翻译",
        sections = listOf("数据源", "翻译"),
    ),
    SettingsCategory(
        title = "显示",
        subtitle = "逐字与长句滚动",
        sections = listOf("逐字", "长句滚动"),
    ),
    SettingsCategory(
        title = "应用",
        subtitle = "白名单与黑名单",
        sections = listOf("应用过滤"),
    ),
    SettingsCategory(
        title = "外观",
        subtitle = "配色、底栏样式和动画",
        sections = emptyList(),
    ),
    SettingsCategory(
        title = "模块",
        subtitle = "导出、重置和日志",
        sections = listOf("其他设置", "调试"),
    ),
)

fun settingsCategory(title: String?): SettingsCategory =
    SETTINGS_CATEGORIES.firstOrNull { it.title == title } ?: SETTINGS_CATEGORIES.first()

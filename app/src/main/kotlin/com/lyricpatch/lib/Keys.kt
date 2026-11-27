package com.lyricpatch.lib

/**
 * lyricpatch.json 里所有键的声明处。
 *
 * 模块侧（Java）读的是同一批字符串，改键名时要同步 Util / LyricScroll 里的字面量。
 */
object ConfKeys {
    const val PROVIDERS = "providers"
    const val HIDE_INSTRUMENTAL = "hide_instrumental"

    const val TRANSLATION = "translation"
    const val TRANSLATION_PROVIDERS = "translation_providers"
    const val MACHINE_TRANSLATION = "machine_translation"
    const val HIDE_EMPTY_TRANSLATION = "hide_empty_translation"
    const val HIDE_TRANSLATION_ALWAYS = "hide_translation_always"
    const val SKIP_TRANSLATION_FOR_CHINESE = "skip_translation_for_chinese"

    const val PARSE_WORD_FORMATS = "parse_word_formats"
    const val KUGOU_WORD_TIMING = "kugou_word_timing"
    const val ESTIMATE_WORD_TIMING = "estimate_word_timing"
    const val PREFER_NETWORK_WORD_TIMING = "prefer_network_word_timing"
    const val SYNTH_WORD_TIMING = "synth_word_timing"
    const val AUTO_WORD_TIMING = "auto_word_timing"
    const val WORD_TIMING_MAX_CHARS = "word_timing_max_chars"
    /** 逐字整体偏移（ms）：高亮比声音慢就调负，快就调正 */
    const val WORD_TIMING_OFFSET = "word_timing_offset"

    const val WORD_SCROLL = "word_scroll"
    const val WORD_SCROLL_FOLLOW = "word_scroll_follow"
    const val WORD_SCROLL_START_PCT = "word_scroll_start_pct"
    const val WORD_SCROLL_SPEED = "word_scroll_speed"
    const val WORD_SCROLL_DELAY = "word_scroll_delay"

    const val APP_WHITELIST = "app_whitelist"
    const val APP_BLACKLIST = "app_blacklist"

    const val LOG_LEVEL = "log_level"
    const val DEBUG_LOG = "debug_log"
}

/** ui_prefs（SharedPreferences）里的键 */
object UiKeys {
    const val DYNAMIC_COLOR = "dynamic_color"
    const val ACCENT = "accent"
    const val BOUNCE = "bounce_percent"
    const val COLOR_STYLE = "color_style"
    const val COLOR_SPEC = "color_spec"

    const val BOTTOM_BAR_MD3 = "bottom_bar_md3"
    const val FLOATING_BOTTOM_BAR = "floating_bottom_bar"
    const val BOTTOM_BAR_LABELS = "bottom_bar_labels"
    const val HOME_GRADIENT = "home_gradient"
    const val TAB_ANIMATION = "tab_animation"
    const val PAGE_TRANSITION = "page_transition"

    /** 是否启用更新检测（模块页底部的「检查更新」） */
    const val UPDATE_CHECK = "update_check"
    /** 把每次 push 自动更新的 ci 构建也算作“有新版” */
    const val UPDATE_CHECK_CI = "update_check_ci"
}

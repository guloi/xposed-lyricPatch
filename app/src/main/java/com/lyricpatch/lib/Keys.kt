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
    const val COLLAPSIBLE = "collapsible_sections"
    const val COLLAPSE_BY_DEFAULT = "collapse_by_default"
    const val SECTIONS_SEEN = "sections_seen"
    const val BOUNCE = "bounce_percent"
    const val COLOR_STYLE = "color_style"
    const val COLOR_SPEC = "color_spec"
    const val SECTION_LEGACY_ANIM = "section_legacy_anim"
    const val SECTION_HEADER_POP = "section_header_pop"

    /** 每个大标题的折叠状态：section_<标题> */
    const val SECTION_PREFIX = "section_"
}

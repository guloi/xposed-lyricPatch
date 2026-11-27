package com.lyricpatch.lib.list

/**
 * 设置页里「几个固定选项」。
 * first 是写进配置的值，second 是给用户看的文案。
 */

/** 机器翻译策略 */
val MACHINE_MODES = listOf(
    "auto" to "跟随 ROM",
    "source" to "源优先",
    "off" to "禁用机翻",
)

/** 日志级别 */
val LOG_LEVELS = listOf(
    "silent" to "静默",
    "info" to "信息",
    "debug" to "调试",
    "verbose" to "详细",
)

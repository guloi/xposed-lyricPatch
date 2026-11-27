package com.lyricpatch.lib

/**
 * 逗号 / 换行分隔的列表型配置（歌词源、翻译源、白黑名单）统一用这三件。
 * 页面、弹窗、显示文案都走这里，避免各处自己 split 出不一致的结果。
 */
fun splitList(raw: String): List<String> =
    raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

fun joinList(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

/** 名单类配置在右侧显示成「3 项」 */
fun entriesLabel(raw: String): String {
    val count = splitList(raw).size
    return if (count == 0) "未设置" else "$count 项"
}

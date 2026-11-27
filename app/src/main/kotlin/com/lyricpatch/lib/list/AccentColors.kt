package com.lyricpatch.lib.list

/** 调色板里的一个颜色 */
data class AccentColor(val hex: String, val name: String)

/** 一组色彩风格 */
data class AccentStyle(val key: String, val label: String, val colors: List<AccentColor>)

val ACCENT_STYLES = listOf(
    AccentStyle(
        "soft", "柔和",
        listOf(
            AccentColor("#7C7CE0", "薫衣草"),
            AccentColor("#6E93C8", "雾蓝"),
            AccentColor("#5FA8A0", "薄青"),
            AccentColor("#7FA36B", "苔绿"),
            AccentColor("#C2A24A", "沙金"),
            AccentColor("#C97C6A", "陶土"),
            AccentColor("#C98BA8", "藕粉"),
            AccentColor("#8A8D93", "石灰"),
        ),
    ),
    AccentStyle(
        "vivid", "鲜明",
        listOf(
            AccentColor("#5B4BC6", "紫罗兰"),
            AccentColor("#2563EB", "靖蓝"),
            AccentColor("#0F8C7E", "青碧"),
            AccentColor("#2E7D32", "草绿"),
            AccentColor("#D97706", "琥珀"),
            AccentColor("#C2410C", "朱橙"),
            AccentColor("#B3246B", "洋红"),
            AccentColor("#374151", "石墨"),
        ),
    ),
    AccentStyle(
        "deep", "深色",
        listOf(
            AccentColor("#4A3D9E", "深紫"),
            AccentColor("#1E3A8A", "藏蓝"),
            AccentColor("#14532D", "墨绿"),
            AccentColor("#115E59", "松石"),
            AccentColor("#7F1D1D", "酒红"),
            AccentColor("#78350F", "赭石"),
            AccentColor("#701A45", "玫紫"),
            AccentColor("#1F2937", "炭灰"),
        ),
    ),
    AccentStyle(
        "neutral", "中性",
        listOf(
            AccentColor("#3F3F46", "炭黑"),
            AccentColor("#52525B", "雾灰"),
            AccentColor("#64748B", "冷灰"),
            AccentColor("#78716C", "暖灰"),
            AccentColor("#8A6A3B", "青铜"),
            AccentColor("#6B705C", "橄榄"),
            AccentColor("#3A4A63", "藏青"),
            AccentColor("#27272A", "墨黑"),
        ),
    ),
)

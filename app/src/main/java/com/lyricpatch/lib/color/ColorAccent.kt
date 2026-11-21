package com.lyricpatch.lib.color

import androidx.compose.ui.graphics.Color

/** 把 #RRGGBB 解成颜色；写坏了就回落到默认紫 */
fun accentColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF5B4BC6))

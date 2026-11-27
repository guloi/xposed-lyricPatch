package com.lyricpatch.lib.list

import com.lyricpatch.lib.color.ColorSpec
import com.lyricpatch.lib.color.ColorStyle

/** 色彩风格选项（柔和 / 鲜明 / 表达 …） */
val COLOR_STYLE_OPTIONS: List<Pair<String, String>> =
    ColorStyle.entries.map { it.name to it.label }

/** 色彩标准选项（2021 / 2025 …） */
val COLOR_SPEC_OPTIONS: List<Pair<String, String>> =
    ColorSpec.entries.map { it.name to it.label }

package com.lyricpatch.lib.color

/**
 * 色彩风格：种子色如何映射到主色 / 次色 / 三色。
 *
 * Material 的 tonal style 需要 material-color-utilities，这里用 HSL 色相偏移近似：
 *  - 柔和：次色同色相降饱和，三色 +60°
 *  - 鲜明：高饱和，次色 +15°，三色 +75°
 *  - 表达（Expressive）：次色 +45°、三色 -45°，容器调亮
 *  - 忠实：全部取种子本身色相
 *  - 单色：几乎无饱和
 */
enum class ColorStyle(
    val label: String,
    val secondaryHue: Float,
    val tertiaryHue: Float,
    val satScale: Float,
) {
    TONAL("柔和", 0f, 60f, 0.85f),
    VIBRANT("鲜明", 15f, 75f, 1.10f),
    EXPRESSIVE("表达", 45f, -45f, 1.15f),
    FIDELITY("忠实", 0f, 0f, 1.00f),
    MONO("单色", 0f, 0f, 0.18f),
    ;

    companion object {
        fun of(name: String?): ColorStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: EXPRESSIVE
    }
}

/**
 * 色彩标准：Container 的明度档位。
 * 2025 对应 Expressive 规范（容器更亮、主色稍提明度），2021 是经典 M3。
 */
enum class ColorSpec(
    val label: String,
    val lightPrimary: Float,
    val darkPrimary: Float,
    val lightContainer: Float,
    val darkContainer: Float,
) {
    SPEC_2021("SPEC 2021", 0.42f, 0.78f, 0.90f, 0.30f),
    SPEC_2025("SPEC 2025", 0.46f, 0.80f, 0.92f, 0.34f),
    ;

    companion object {
        fun of(name: String?): ColorSpec =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SPEC_2025
    }
}

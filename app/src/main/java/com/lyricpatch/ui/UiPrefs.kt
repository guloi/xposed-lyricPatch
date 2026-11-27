package com.lyricpatch.ui

import android.content.Context
import androidx.compose.runtime.getValue
import com.lyricpatch.lib.UiKeys
import com.lyricpatch.lib.color.ColorSpec
import com.lyricpatch.lib.color.ColorStyle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 纯界面偏好，与给 SystemUI 看的 lyricpatch.json 分开存，避免污染那份配置。
 * 用 Compose 的 state 保存，改完两个 Activity 会同时刷新。
 */
object UiPrefs {

    private const val FILE = "ui_prefs"

    // 键名统一声明在 com.lyricpatch.lib.UiKeys
    private const val KEY_DYNAMIC = UiKeys.DYNAMIC_COLOR
    private const val KEY_ACCENT = UiKeys.ACCENT
    private const val KEY_COLLAPSE = UiKeys.COLLAPSIBLE
    private const val KEY_BOUNCE = UiKeys.BOUNCE
    private const val KEY_COLLAPSE_DEFAULT = UiKeys.COLLAPSE_BY_DEFAULT
    private const val KEY_SECTIONS_SEEN = UiKeys.SECTIONS_SEEN
    private const val KEY_SECTION_PREFIX = UiKeys.SECTION_PREFIX

    const val DEFAULT_ACCENT = "#5B4BC6"
    const val DEFAULT_BOUNCE = 18

    var dynamicColor by mutableStateOf(true)
        private set
    var accent by mutableStateOf(DEFAULT_ACCENT)
        private set
    var collapsible by mutableStateOf(true)
        private set

    /** 首次启动 App 时，各分组是否默认折叠 */
    var collapseByDefault by mutableStateOf(true)
        private set

    /** 已经用过一次「默认折叠」了（只生效一次） */
    var sectionsSeen by mutableStateOf(false)
        private set

    /** 回弹幅度（百分比），0 = 关闭 */
    var bouncePercent by mutableStateOf(DEFAULT_BOUNCE)
        private set

    /** 色彩风格 / 色彩标准：仅在关闭莫奈取色、使用自定义颜色时生效 */
    var colorStyle by mutableStateOf(ColorStyle.EXPRESSIVE)
        private set
    var colorSpec by mutableStateOf(ColorSpec.SPEC_2025)
        private set

    /** 分组展开/收起使用弹簧下沉式动画 */
    var sectionLegacyAnim by mutableStateOf(false)
        private set

    /** 大标题点击时整块卡片是否弹一下 */
    var sectionHeaderPop by mutableStateOf(true)
        private set

    /** 给动画用的缩放幅度 */
    val bounceScale: Float
        get() = bouncePercent / 100f

    private var prefs: android.content.SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        dynamicColor = p.getBoolean(KEY_DYNAMIC, true)
        accent = p.getString(KEY_ACCENT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT
        collapsible = p.getBoolean(KEY_COLLAPSE, true)
        bouncePercent = p.getInt(KEY_BOUNCE, DEFAULT_BOUNCE).coerceIn(0, 30)
        colorStyle = ColorStyle.of(p.getString(UiKeys.COLOR_STYLE, null))
        colorSpec = ColorSpec.of(p.getString(UiKeys.COLOR_SPEC, null))
        sectionLegacyAnim = p.getBoolean(UiKeys.SECTION_LEGACY_ANIM, false)
        sectionHeaderPop = p.getBoolean(UiKeys.SECTION_HEADER_POP, true)
        collapseByDefault = p.getBoolean(KEY_COLLAPSE_DEFAULT, true)
        sectionsSeen = p.getBoolean(KEY_SECTIONS_SEEN, false)
    }

    fun updateCollapseByDefault(on: Boolean) {
        collapseByDefault = on
        prefs?.edit()?.putBoolean(KEY_COLLAPSE_DEFAULT, on)?.apply()
    }

    /**
     * 第一次进入设置页：把「默认折叠」的结果写进去当初始存档。
     * 之后 sectionOpen() 直接读存档，所以能保留上次手动调整过的状态。
     */
    fun ensureSectionDefaults(sections: List<String>) {
        val p = prefs ?: return
        if (sectionsSeen) return
        val edit = p.edit()
        sections.forEach { edit.putBoolean(KEY_SECTION_PREFIX + it, !collapseByDefault) }
        edit.putBoolean(KEY_SECTIONS_SEEN, true)
        edit.apply()
        sectionsSeen = true
    }

    fun sectionOpen(title: String): Boolean =
        prefs?.getBoolean(KEY_SECTION_PREFIX + title, true) ?: !collapseByDefault

    fun updateSectionOpen(title: String, open: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SECTION_PREFIX + title, open)?.apply()
    }

    fun updateSectionHeaderPop(on: Boolean) {
        sectionHeaderPop = on
        prefs?.edit()?.putBoolean(UiKeys.SECTION_HEADER_POP, on)?.apply()
    }

    fun updateSectionLegacyAnim(on: Boolean) {
        sectionLegacyAnim = on
        prefs?.edit()?.putBoolean(UiKeys.SECTION_LEGACY_ANIM, on)?.apply()
    }

    fun updateColorStyle(style: ColorStyle) {
        colorStyle = style
        prefs?.edit()?.putString(UiKeys.COLOR_STYLE, style.name)?.apply()
    }

    fun updateColorSpec(spec: ColorSpec) {
        colorSpec = spec
        prefs?.edit()?.putString(UiKeys.COLOR_SPEC, spec.name)?.apply()
    }

    fun updateBounce(percent: Int) {
        bouncePercent = percent.coerceIn(0, 30)
        prefs?.edit()?.putInt(KEY_BOUNCE, bouncePercent)?.apply()
    }

    fun updateDynamicColor(on: Boolean) {
        dynamicColor = on
        prefs?.edit()?.putBoolean(KEY_DYNAMIC, on)?.apply()
    }

    fun updateAccent(hex: String) {
        accent = hex
        prefs?.edit()?.putString(KEY_ACCENT, hex)?.apply()
    }

    fun updateCollapsible(on: Boolean) {
        collapsible = on
        prefs?.edit()?.putBoolean(KEY_COLLAPSE, on)?.apply()
    }

    /**
     * 完整重置界面偏好：清掉 ui_prefs 并把内存里的状态恢复默认。
     * 分组展开记录也一起清掉，下次进设置页重新按「默认折叠」初始化。
     */
    fun reset(context: Context) {
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { prefs = it }
        p.edit().clear().apply()

        dynamicColor = true
        accent = DEFAULT_ACCENT
        collapsible = true
        collapseByDefault = true
        sectionsSeen = false
        bouncePercent = DEFAULT_BOUNCE
        colorStyle = ColorStyle.EXPRESSIVE
        colorSpec = ColorSpec.SPEC_2025
        sectionLegacyAnim = false
        sectionHeaderPop = true
    }
}

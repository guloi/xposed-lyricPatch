package com.lyricpatch.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lyricpatch.lib.UiKeys
import com.lyricpatch.lib.color.ColorSpec
import com.lyricpatch.lib.color.ColorStyle

/**
 * 纯界面偏好，和给 SystemUI 看的 lyricpatch.json 分开存。
 * 改完立即写入，两个页面共用同一份 Compose state。
 */
object UiPrefs {

    private const val FILE = "ui_prefs"

    const val DEFAULT_ACCENT = "#5B4BC6"
    const val DEFAULT_BOUNCE = 18

    var dynamicColor by mutableStateOf(true)
        private set
    var accent by mutableStateOf(DEFAULT_ACCENT)
        private set

    /** 回弹幅度（百分比），0 = 关闭 */
    var bouncePercent by mutableStateOf(DEFAULT_BOUNCE)
        private set

    /** 色彩风格 / 色彩标准：仅在关闭莫奈取色、使用自定义颜色时生效 */
    var colorStyle by mutableStateOf(ColorStyle.EXPRESSIVE)
        private set
    var colorSpec by mutableStateOf(ColorSpec.SPEC_2025)
        private set

    // 底边栏
    var bottomBarMd3 by mutableStateOf(true)
        private set
    var floatingBottomBar by mutableStateOf(false)
        private set
    var bottomBarLabels by mutableStateOf(true)
        private set

    // 首页 / 页面动画
    var homeGradient by mutableStateOf(false)
        private set
    var tabAnimation by mutableStateOf(true)
        private set
    var pageTransition by mutableStateOf(true)
        private set

    val bounceScale: Float
        get() = bouncePercent / 100f

    private var prefs: android.content.SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        dynamicColor = p.getBoolean(UiKeys.DYNAMIC_COLOR, true)
        accent = p.getString(UiKeys.ACCENT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT
        bouncePercent = p.getInt(UiKeys.BOUNCE, DEFAULT_BOUNCE).coerceIn(0, 30)
        colorStyle = ColorStyle.of(p.getString(UiKeys.COLOR_STYLE, null))
        colorSpec = ColorSpec.of(p.getString(UiKeys.COLOR_SPEC, null))
        bottomBarMd3 = p.getBoolean(UiKeys.BOTTOM_BAR_MD3, true)
        floatingBottomBar = p.getBoolean(UiKeys.FLOATING_BOTTOM_BAR, false)
        bottomBarLabels = p.getBoolean(UiKeys.BOTTOM_BAR_LABELS, true)
        homeGradient = p.getBoolean(UiKeys.HOME_GRADIENT, false)
        tabAnimation = p.getBoolean(UiKeys.TAB_ANIMATION, true)
        pageTransition = p.getBoolean(UiKeys.PAGE_TRANSITION, true)
    }

    fun updateDynamicColor(on: Boolean) {
        dynamicColor = on
        prefs?.edit()?.putBoolean(UiKeys.DYNAMIC_COLOR, on)?.apply()
    }

    fun updateAccent(hex: String) {
        accent = hex
        prefs?.edit()?.putString(UiKeys.ACCENT, hex)?.apply()
    }

    fun updateBounce(percent: Int) {
        bouncePercent = percent.coerceIn(0, 30)
        prefs?.edit()?.putInt(UiKeys.BOUNCE, bouncePercent)?.apply()
    }

    fun updateColorStyle(style: ColorStyle) {
        colorStyle = style
        prefs?.edit()?.putString(UiKeys.COLOR_STYLE, style.name)?.apply()
    }

    fun updateColorSpec(spec: ColorSpec) {
        colorSpec = spec
        prefs?.edit()?.putString(UiKeys.COLOR_SPEC, spec.name)?.apply()
    }

    fun updateBottomBarMd3(on: Boolean) {
        bottomBarMd3 = on
        prefs?.edit()?.putBoolean(UiKeys.BOTTOM_BAR_MD3, on)?.apply()
    }

    fun updateFloatingBottomBar(on: Boolean) {
        floatingBottomBar = on
        prefs?.edit()?.putBoolean(UiKeys.FLOATING_BOTTOM_BAR, on)?.apply()
    }

    fun updateBottomBarLabels(on: Boolean) {
        bottomBarLabels = on
        prefs?.edit()?.putBoolean(UiKeys.BOTTOM_BAR_LABELS, on)?.apply()
    }

    fun updateHomeGradient(on: Boolean) {
        homeGradient = on
        prefs?.edit()?.putBoolean(UiKeys.HOME_GRADIENT, on)?.apply()
    }

    fun updateTabAnimation(on: Boolean) {
        tabAnimation = on
        prefs?.edit()?.putBoolean(UiKeys.TAB_ANIMATION, on)?.apply()
    }

    fun updatePageTransition(on: Boolean) {
        pageTransition = on
        prefs?.edit()?.putBoolean(UiKeys.PAGE_TRANSITION, on)?.apply()
    }

    /** 完整重置界面偏好：清掉 ui_prefs，并把内存里的状态恢复默认 */
    fun reset(context: Context) {
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { prefs = it }
        p.edit().clear().apply()

        dynamicColor = true
        accent = DEFAULT_ACCENT
        bouncePercent = DEFAULT_BOUNCE
        colorStyle = ColorStyle.EXPRESSIVE
        colorSpec = ColorSpec.SPEC_2025
        bottomBarMd3 = true
        floatingBottomBar = false
        bottomBarLabels = true
        homeGradient = false
        tabAnimation = true
        pageTransition = true
    }
}

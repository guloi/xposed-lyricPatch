package com.lyricpatch.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 纯界面偏好，与给 SystemUI 看的 lyricpatch.conf 分开存，避免污染那份配置。
 * 用 Compose 的 state 保存，改完两个 Activity 会同时刷新。
 */
object UiPrefs {

    private const val FILE = "ui_prefs"
    private const val KEY_DYNAMIC = "dynamic_color"
    private const val KEY_ACCENT = "accent"
    private const val KEY_COLLAPSE = "collapsible_sections"
    private const val KEY_FLOATING_BAR = "floating_save_bar"

    const val DEFAULT_ACCENT = "#5B4BC6"

    /** 可选的主题种子色（关闭莫奈取色时用） */
    val ACCENTS = listOf(
        "#5B4BC6" to "紫",
        "#2F6FEB" to "蓝",
        "#0F8C7E" to "青",
        "#2E7D32" to "绿",
        "#B8860B" to "金",
        "#C2410C" to "橙",
        "#B3246B" to "粉",
        "#5F6368" to "灰",
    )

    var dynamicColor by mutableStateOf(true)
        private set
    var accent by mutableStateOf(DEFAULT_ACCENT)
        private set
    var collapsible by mutableStateOf(true)
        private set

    /** 保存条不常驻：底部上滑呼出，下滑收起 */
    var floatingSaveBar by mutableStateOf(false)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        dynamicColor = p.getBoolean(KEY_DYNAMIC, true)
        accent = p.getString(KEY_ACCENT, DEFAULT_ACCENT) ?: DEFAULT_ACCENT
        collapsible = p.getBoolean(KEY_COLLAPSE, true)
        floatingSaveBar = p.getBoolean(KEY_FLOATING_BAR, false)
    }

    fun updateFloatingSaveBar(on: Boolean) {
        floatingSaveBar = on
        prefs?.edit()?.putBoolean(KEY_FLOATING_BAR, on)?.apply()
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
}

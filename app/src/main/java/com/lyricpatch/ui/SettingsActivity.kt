package com.lyricpatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lyricpatch.lib.debug.CrashLogger
import com.lyricpatch.pages.SettingsPage

/** 分类设置详情页：由设置入口页带着分类名启动 */
class SettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CATEGORY = "category"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        UiPrefs.load(this)
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "歌词"
        setContent {
            LyricPatchTheme {
                SettingsPage(category = category, onBack = { finish() })
            }
        }
    }
}

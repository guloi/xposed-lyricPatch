package com.lyricpatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lyricpatch.pages.SettingsPage

/** 模块的设置界面：一个 Activity，内容全在 Compose 里 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        UiPrefs.load(this)
        setContent {
            LyricPatchTheme {
                SettingsPage()
            }
        }
    }
}

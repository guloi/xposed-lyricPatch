package com.lyricpatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lyricpatch.pages.AppearancePage

/** 外观页容器；内容在 pages/AppearancePage.kt */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        UiPrefs.load(this)
        setContent {
            LyricPatchTheme {
                AppearancePage(onBack = { finish() })
            }
        }
    }
}

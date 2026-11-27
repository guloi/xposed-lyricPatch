package com.lyricpatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lyricpatch.lib.debug.CrashLogger
import com.lyricpatch.pages.BottomBarPage

/** 底边栏设置页容器；内容在 pages/BottomBarPage.kt */
class BottomBarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        UiPrefs.load(this)
        setContent {
            LyricPatchTheme {
                BottomBarPage(onBack = { finish() })
            }
        }
    }
}

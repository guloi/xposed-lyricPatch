package com.lyricpatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.lyricpatch.lib.debug.CrashLogger
import com.lyricpatch.pages.MainPage

/**
 * App 主界面容器：底部导航在 [MainPage] 里，首页与设置分类共用这一个 Activity。
 *
 * onResume 递增 reloadKey，从分类设置页返回时重新读一次配置；
 * 首次 onResume 不递增，交给 MainPage 的初次加载。
 */
class HomeActivity : ComponentActivity() {

    private val reloadKey = mutableIntStateOf(0)
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        UiPrefs.load(this)
        setContent {
            LyricPatchTheme {
                MainPage(reloadKey = reloadKey.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            reloadKey.intValue++
        }
    }
}

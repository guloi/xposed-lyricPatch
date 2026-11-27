package com.lyricpatch.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.lyricpatch.lib.debug.CrashLogger
import com.lyricpatch.pages.HomePage

/**
 * 首页容器：状态、快捷开关与各页入口。
 *
 * onResume 递增 reloadKey，让从设置页返回时重新读一次配置；
 * 同一份 [ConfigSession] 也在两个页面间共用，所以大多数时候只是刷新状态。
 */
class HomeActivity : ComponentActivity() {

    private val reloadKey = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        UiPrefs.load(this)
        setContent {
            LyricPatchTheme {
                HomePage(
                    reloadKey = reloadKey.intValue,
                    onOpenSettings = {
                        startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
                    },
                    onOpenAppearance = {
                        startActivity(Intent(this@HomeActivity, AppearanceActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadKey.intValue++
    }
}

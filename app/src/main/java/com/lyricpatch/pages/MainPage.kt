package com.lyricpatch.pages

import android.app.ActivityOptions
import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lyricpatch.ui.AppearanceActivity
import com.lyricpatch.ui.ConfigSession
import com.lyricpatch.ui.SettingsActivity
import com.lyricpatch.ui.UiPrefs
import kotlinx.coroutines.delay

/**
 * App 根界面：底部导航切换「首页 / 设置」。
 *
 * 首页只读信息，设置页只列分类；真正的设置项在分类详情页里。
 * 底栏样式、动画和外观选项都在「底边栏」设置里。
 */
@Composable
fun MainPage(reloadKey: Int) {
    val context = LocalContext.current
    val session = remember { ConfigSession.also { it.attach(context) } }
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 首次进入 / 从分类设置页返回：重新读一次磁盘
    LaunchedEffect(reloadKey) { session.load() }

    // 每 10 秒探一次 hook 是否活着
    LaunchedEffect(Unit) {
        while (true) {
            session.refreshStatus()
            delay(10_000L)
        }
    }

    LaunchedEffect(session.notice) {
        val text = session.notice ?: return@LaunchedEffect
        session.consumeNotice()
        snackbar.showSnackbar(text)
    }

    var lastError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session.error) {
        val error = session.error
        if (error != null && error != lastError) {
            lastError = error
            snackbar.showSnackbar("保存失败：$error")
        }
    }

    fun openCategory(category: String) {
        val intent = if (category == "底边栏") {
            Intent(context, AppearanceActivity::class.java)
        } else {
            Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_CATEGORY, category)
        }
        if (UiPrefs.pageTransition) {
            val options = ActivityOptions.makeCustomAnimation(
                context,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            ).toBundle()
            context.startActivity(intent, options)
        } else {
            context.startActivity(intent)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        bottomBar = {
            AppBottomBar(selected = tab, onSelect = { tab = it })
        },
    ) { inner ->
        if (!session.loaded) {
            Box(
                Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val content: @Composable (Int) -> Unit = { current ->
            when (current) {
                0 -> HomePage(modifier = Modifier.padding(inner))
                else -> SettingsIndexPage(
                    modifier = Modifier.padding(inner),
                    onOpenCategory = { openCategory(it) },
                )
            }
        }

        if (UiPrefs.tabAnimation) {
            Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") {
                content(it)
            }
        } else {
            content(tab)
        }
    }
}

@Composable
private fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val md3 = UiPrefs.bottomBarMd3
    val floating = UiPrefs.floatingBottomBar
    val labels = UiPrefs.bottomBarLabels
    val homeLabel: (@Composable () -> Unit)? = if (labels) ({ Text("首页") }) else null
    val settingsLabel: (@Composable () -> Unit)? = if (labels) ({ Text("设置") }) else null

    val bar: @Composable () -> Unit = {
        if (md3) {
            NavigationBar(
                windowInsets = if (floating) {
                    WindowInsets(0, 0, 0, 0)
                } else {
                    NavigationBarDefaults.windowInsets
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                NavigationBarItem(
                    selected = selected == 0,
                    onClick = { onSelect(0) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = homeLabel,
                )
                NavigationBarItem(
                    selected = selected == 1,
                    onClick = { onSelect(1) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = settingsLabel,
                )
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (floating) Modifier else Modifier.navigationBarsPadding())
                        .height(64.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClassicItem(
                        selected = selected == 0,
                        icon = Icons.Filled.Home,
                        label = "首页",
                        showLabel = labels,
                        onClick = { onSelect(0) },
                    )
                    ClassicItem(
                        selected = selected == 1,
                        icon = Icons.Filled.Settings,
                        label = "设置",
                        showLabel = labels,
                        onClick = { onSelect(1) },
                    )
                }
            }
        }
    }

    if (floating) {
        Box(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                bar()
            }
        }
    } else {
        bar()
    }
}

@Composable
private fun ClassicItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = color)
        if (showLabel) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

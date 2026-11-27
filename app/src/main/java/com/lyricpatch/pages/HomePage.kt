package com.lyricpatch.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyricpatch.lib.ActionRow
import com.lyricpatch.lib.CardNote
import com.lyricpatch.lib.ConfKeys
import com.lyricpatch.lib.RowDivider
import com.lyricpatch.lib.SettingsCard
import com.lyricpatch.lib.SwitchRow
import com.lyricpatch.ui.ConfigSession
import com.lyricpatch.ui.UiPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页：一屏看清模块状态，并提供最常用的快捷开关、各页入口和完整重置。
 *
 * 歌词配置统一走 [ConfigSession]：这里的快捷开关和设置页改的是同一份 JSON，
 * 返回首页不需要重新加载，也不会出现两处状态对不上。
 */
@Composable
fun HomePage(
    reloadKey: Int,
    onOpenSettings: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    val context = LocalContext.current
    val session = remember { ConfigSession.also { it.attach(context) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showReset by remember { mutableStateOf(false) }

    // 首次组合 / 从设置页返回时重新读一次文件；reloadKey 由 Activity.onResume 递增
    LaunchedEffect(reloadKey) {
        if (reloadKey > 0 || !session.loaded) session.load()
    }

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

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("恢复默认设置？") },
            text = {
                Text(
                    "歌词配置会重写为默认 JSON；取色、折叠、回弹等界面偏好也会一起重置。" +
                        "此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        session.reset()
                        UiPrefs.reset(context)
                        showReset = false
                        scope.launch { snackbar.showSnackbar("已恢复默认设置") }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbar) },
    ) { inner ->
        if (!session.loaded) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item {
                HomeHero(
                    version = version,
                    active = session.active,
                    hookVersion = session.hookVersion,
                    saving = session.saving,
                    error = session.error,
                    path = session.configPath,
                    onOpenSettings = onOpenSettings,
                )
            }

            item { SectionLabel("快捷开关") }
            item {
                SettingsCard {
                    SwitchRow(
                        title = "翻译",
                        subtitle = "主源没译文时再按翻译源获取",
                        checked = session.flag(ConfKeys.TRANSLATION),
                        onCheckedChange = { session.setFlag(ConfKeys.TRANSLATION, it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "逐字",
                        subtitle = "缺少逐字歌词时自动补充",
                        checked = session.flag(ConfKeys.AUTO_WORD_TIMING),
                        onCheckedChange = { session.setFlag(ConfKeys.AUTO_WORD_TIMING, it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "长句滚动",
                        subtitle = "逐字长句超出宽度时横向滚动",
                        checked = session.flag(ConfKeys.WORD_SCROLL),
                        onCheckedChange = { session.setFlag(ConfKeys.WORD_SCROLL, it) },
                    )
                }
                CardNote("改动立即写入 JSON 配置，无需保存。")
            }

            item { SectionLabel("当前配置") }
            item {
                SettingsCard {
                    NavRow(
                        title = "歌词源",
                        value = session.raw(ConfKeys.PROVIDERS).ifBlank { "默认" },
                        onClick = onOpenSettings,
                    )
                    RowDivider()
                    NavRow(
                        title = "翻译源",
                        value = session.raw(ConfKeys.TRANSLATION_PROVIDERS).ifBlank { "未指定" },
                        onClick = onOpenSettings,
                    )
                    RowDivider()
                    NavRow(
                        title = "日志级别",
                        value = session.raw(ConfKeys.LOG_LEVEL).ifBlank { "info" },
                        onClick = onOpenSettings,
                    )
                }
            }

            item { SectionLabel("更多") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "全部设置",
                        subtitle = "数据源、翻译、逐字、滚动与调试",
                        icon = Icons.Filled.Settings,
                        onClick = onOpenSettings,
                    )
                    RowDivider()
                    ActionRow(
                        title = "外观",
                        subtitle = "取色方式、大标题折叠与动画",
                        icon = Icons.Filled.Settings,
                        onClick = onOpenAppearance,
                    )
                    RowDivider()
                    ActionRow(
                        title = "重新载入配置",
                        subtitle = "从 JSON 重新读取，丢弃未写入的改动",
                        icon = Icons.Filled.Refresh,
                        onClick = {
                            val ok = session.load()
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (ok) "已重新载入" else "正在保存，请稍后再试",
                                )
                            }
                        },
                    )
                    RowDivider()
                    ActionRow(
                        title = "恢复默认设置",
                        subtitle = "歌词配置与界面偏好全部重置",
                        icon = Icons.Filled.Warning,
                        danger = true,
                        onClick = { showReset = true },
                    )
                }
                CardNote("JSON：${session.configPath}")
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "LyricPatch v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Source code",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/guloi/xposed-lyricPatch"),
                                        ),
                                    )
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHero(
    version: String,
    active: Boolean?,
    hookVersion: String,
    saving: Boolean,
    error: String?,
    path: String,
    onOpenSettings: () -> Unit,
) {
    val gradient = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(gradient)
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "歌词补丁",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "SystemUI 状态栏歌词补源与逐字",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f),
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                modifier = Modifier.clickable(onClick = onOpenSettings),
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("v$version")
            Pill(
                text = when (active) {
                    true -> "已启用"
                    false -> "未启用"
                    null -> "检测中…"
                },
                strong = active == true,
            )
            Pill(
                text = when {
                    error != null -> "保存失败"
                    saving -> "保存中…"
                    else -> "自动保存"
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            when {
                active == true && hookVersion.isNotBlank() -> "SystemUI 侧 v$hookVersion"
                active == true -> "SystemUI 侧已连接"
                active == false -> "SystemUI 侧未响应，检查 LSPosed 作用域"
                else -> "正在检测 SystemUI…"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
        )
        if (path.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                path,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Pill(text: String, strong: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (strong) 0.32f else 0.18f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package com.lyricpatch.pages

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lyricpatch.lib.ConfKeys
import com.lyricpatch.lib.RowDivider
import com.lyricpatch.lib.SettingsCard
import com.lyricpatch.ui.ConfigSession

/**
 * 首页只做信息展示：模块状态、SystemUI 侧版本、配置文件、当前配置摘要。
 * 不提供跳转、开关或重置入口；设置入口统一放在底部导航的「设置」页。
 */
@Composable
fun HomePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = ConfigSession.also { it.attach(context) }
    val version = rememberPackageVersion()

    val savedText = if (session.savedAt > 0) {
        DateFormat.format("yyyy-MM-dd HH:mm", session.savedAt).toString()
    } else {
        "尚未写入"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
    ) {
        item {
            HomeHero(
                version = version,
                active = session.active,
                hookVersion = session.hookVersion,
                saving = session.saving,
                error = session.error,
            )
        }

        item { SectionLabel("运行状态") }
        item {
            SettingsCard {
                InfoLine(
                    "模块状态",
                    when (session.active) {
                        true -> "已启用"
                        false -> "未启用"
                        null -> "检测中…"
                    },
                )
                RowDivider()
                InfoLine(
                    "SystemUI 侧",
                    when {
                        session.active == true && session.hookVersion.isNotBlank() ->
                            "v${session.hookVersion}"
                        session.active == true -> "已连接，版本未知"
                        session.active == false -> "未响应，检查 LSPosed 作用域"
                        else -> "检测中…"
                    },
                )
                RowDivider()
                InfoLine("配置文件", session.configPath, mono = true)
                RowDivider()
                InfoLine("存储格式", "JSON · schema 2")
                RowDivider()
                InfoLine("上次保存", savedText)
            }
        }

        item { SectionLabel("当前配置") }
        item {
            SettingsCard {
                InfoLine(
                    "歌词源",
                    session.raw(ConfKeys.PROVIDERS).ifBlank { "默认" },
                    mono = true,
                )
                RowDivider()
                InfoLine(
                    "翻译源",
                    session.raw(ConfKeys.TRANSLATION_PROVIDERS).ifBlank { "未指定" },
                    mono = true,
                )
                RowDivider()
                InfoLine("翻译", onOff(session.flag(ConfKeys.TRANSLATION)))
                RowDivider()
                InfoLine("逐字", onOff(session.flag(ConfKeys.AUTO_WORD_TIMING)))
                RowDivider()
                InfoLine("长句滚动", onOff(session.flag(ConfKeys.WORD_SCROLL)))
                RowDivider()
                InfoLine("日志级别", session.raw(ConfKeys.LOG_LEVEL).ifBlank { "info" })
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "LyricPatch v$version",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "github.com/guloi/xposed-lyricPatch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun rememberPackageVersion(): String {
    val context = LocalContext.current
    return androidx.compose.runtime.remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}

@Composable
private fun HomeHero(
    version: String,
    active: Boolean?,
    hookVersion: String,
    saving: Boolean,
    error: String?,
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

        Spacer(Modifier.height(14.dp))
        Text(
            when {
                active == true && hookVersion.isNotBlank() -> "SystemUI 侧 v$hookVersion"
                active == true -> "SystemUI 侧已连接"
                active == false -> "SystemUI 侧未响应"
                else -> "正在检测 SystemUI…"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
        )
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
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value.ifBlank { "—" },
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun onOff(on: Boolean): String = if (on) "已开启" else "已关闭"

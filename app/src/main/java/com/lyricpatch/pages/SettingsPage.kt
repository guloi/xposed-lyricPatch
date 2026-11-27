package com.lyricpatch.pages

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyricpatch.lib.*
import com.lyricpatch.lib.debug.CrashLogger
import com.lyricpatch.ui.ConfigSession
import com.lyricpatch.ui.ConfigStore
import com.lyricpatch.ui.UiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模块设置界面。
 *
 * 视觉上沿用 Material 3 Expressive（超圆角容器、大标题、分级色调、spring 手感），
 * 数据面统一走 [ConfigSession]：每个控件一旦被操作就写入 JSON，
 * 不再有“保存”按钮，也没有“是否未保存”的状态。
 */
@Composable
fun SettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val session = remember { ConfigSession.also { it.attach(context) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showReset by remember { mutableStateOf(false) }

    // 首次进入才从磁盘读；首页 / 设置页共用同一份 ConfigSession
    LaunchedEffect(Unit) {
        if (!session.loaded) session.load()
    }

    // 每 10 秒探一次：在 LSPosed 里刚启用、或重启过 SystemUI，界面会自动变过来
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

    // 分组折叠状态存在 ui_prefs：第一次进入按「默认折叠」初始化并落盘，
    // 之后每次进入都恢复上次的状态。放到 LaunchedEffect 里，避免在组合期间写磁盘。
    LaunchedEffect(Unit) { UiPrefs.ensureSectionDefaults(SECTION_KEYS) }
    val openSections = remember { mutableStateMapOf<String, Boolean>() }
    val collapsible = UiPrefs.collapsible
    var category by rememberSaveable { mutableStateOf("全部") }
    val activeSections = remember(category) {
        CATEGORIES.firstOrNull { it.title == category }?.sections.orEmpty()
    }

    fun visible(section: String): Boolean =
        category == "全部" || section in activeSections

    fun open(key: String): Boolean =
        !collapsible || (openSections[key] ?: UiPrefs.sectionOpen(key))

    fun toggle(key: String) {
        val next = !open(key)
        openSections[key] = next
        UiPrefs.updateSectionOpen(key, next)
    }

    // 分类筛选时直接展开该组的设置项；「全部」视图仍保留手动折叠
    fun sectionExpanded(section: String): Boolean = category != "全部" || open(section)

    fun sectionToggle(section: String): (() -> Unit)? =
        if (category == "全部" && collapsible) ({ toggle(section) }) else null

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    // 导出到用户选择的位置（SAF），不需要存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = ConfigStore.serialize(
                    ConfigSession.values.toMap(),
                    version,
                    System.currentTimeMillis(),
                )
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } ?: error("无法创建文件")
                    }.isSuccess
                }
                snackbar.showSnackbar(if (ok) "已导出配置" else "导出失败：无法写入所选文件")
            }
        }
    }

    fun launchExport() {
        val stamp = DateFormat.format("yyyyMMdd-HHmm", System.currentTimeMillis())
        exportLauncher.launch("lyricpatch-$stamp.json")
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
                        // 重置后当前页也立即回到「默认折叠」，不用退出重进
                        UiPrefs.ensureSectionDefaults(SECTION_KEYS)
                        openSections.clear()
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

    // 上次崩过的话，把堆栈弹出来（崩溃时进程已死，只能下次显示）
    val clipboard = LocalClipboardManager.current
    var crashText by remember { mutableStateOf(CrashLogger.take(context)) }
    if (crashText != null) {
        AlertDialog(
            onDismissRequest = { crashText = null },
            confirmButton = {
                TextButton(onClick = { crashText = null }) { Text("知道了") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(crashText.orEmpty()))
                        crashText = null
                    },
                ) { Text("复制") }
            },
            title = { Text("上次运行崩溃") },
            text = {
                Text(
                    crashText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SettingsTopBar(
                subtitle = saveLabel(session.saving, session.savedAt, session.error),
                isError = session.error != null,
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
    ) { inner ->
        if (!session.loaded) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(inner)) {
            CategoryBar(selected = category, onSelect = { category = it })
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
            ) {
            if (category == "全部") item { CategoryLabel("歌词") }
            if (visible("数据源")) {
            item {
                SectionHeader(
                    "数据源",
                    "按顺序匹配，使用第一个返回歌词的源",
                    expanded = sectionExpanded("数据源"),
                    onToggle = sectionToggle("数据源"),
                )
            }
            item {
                SectionBody(sectionExpanded("数据源")) {
                    SettingsCard {
                        FieldRow(
                            label = "providers",
                            value = session.raw(ConfKeys.PROVIDERS),
                            supporting = "netease / qq / kugou / lrclib，或 https:// 自建源",
                            onChange = { session.set(ConfKeys.PROVIDERS, it) },
                        )
                        PresetChips(current = session.raw(ConfKeys.PROVIDERS)) {
                            session.set(ConfKeys.PROVIDERS, it)
                        }
                        RowDivider()
                        SwitchRow(
                            title = "hide_instrumental",
                            subtitle = "识别到纯音乐时不显示歌词",
                            checked = session.flag(ConfKeys.HIDE_INSTRUMENTAL),
                            onCheckedChange = { session.setFlag(ConfKeys.HIDE_INSTRUMENTAL, it) },
                        )
                    }
                    CardNote("「状态栏歌词源」优先，未命中时按上方顺序。")
                }
            }
            }

            if (visible("翻译")) {
            item {
                SectionHeader(
                    "翻译",
                    "控制第二行显示内容",
                    expanded = sectionExpanded("翻译"),
                    onToggle = sectionToggle("翻译"),
                )
            }
            item {
                SectionBody(sectionExpanded("翻译")) {
                    SettingsCard {
                        SwitchRow(
                            title = "translation",
                            subtitle = "关闭后不请求翻译",
                            checked = session.flag(ConfKeys.TRANSLATION),
                            onCheckedChange = { session.setFlag(ConfKeys.TRANSLATION, it) },
                        )
                        RowDivider()
                        FieldRow(
                            label = "translation_providers",
                            value = session.raw(ConfKeys.TRANSLATION_PROVIDERS),
                            supporting = "主源无译文时按此顺序获取：netease / qq / http(s)",
                            onChange = { session.set(ConfKeys.TRANSLATION_PROVIDERS, it) },
                        )
                        RowDivider()
                        ChoiceRow(
                            title = "machine_translation",
                            subtitle = "ROM 自带机翻的使用策略",
                            options = MACHINE_MODES,
                            selected = session.raw(ConfKeys.MACHINE_TRANSLATION),
                            onSelect = { session.set(ConfKeys.MACHINE_TRANSLATION, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "hide_empty_translation",
                            subtitle = "该行无译文时隐藏副标题",
                            checked = session.flag(ConfKeys.HIDE_EMPTY_TRANSLATION),
                            onCheckedChange = { session.setFlag(ConfKeys.HIDE_EMPTY_TRANSLATION, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "skip_translation_for_chinese",
                            subtitle = "中文歌词不请求翻译",
                            checked = session.flag(ConfKeys.SKIP_TRANSLATION_FOR_CHINESE),
                            onCheckedChange = {
                                session.setFlag(ConfKeys.SKIP_TRANSLATION_FOR_CHINESE, it)
                            },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "hide_translation_always",
                            subtitle = "隐藏所有译文，仅用于排查",
                            checked = session.flag(ConfKeys.HIDE_TRANSLATION_ALWAYS),
                            onCheckedChange = {
                                session.setFlag(ConfKeys.HIDE_TRANSLATION_ALWAYS, it)
                            },
                        )
                    }
                }
            }
            }

            if (category == "全部") item { CategoryLabel("显示") }
            if (visible("逐字")) {
            item {
                SectionHeader(
                    "逐字",
                    "逐字来源与补充方式",
                    expanded = sectionExpanded("逐字"),
                    onToggle = sectionToggle("逐字"),
                )
            }
            item {
                SectionBody(sectionExpanded("逐字")) {
                    SettingsCard {
                        SwitchRow(
                            title = "parse_word_formats",
                            subtitle = "解析增强 LRC 与 Lyricify Syllable 格式",
                            checked = session.flag(ConfKeys.PARSE_WORD_FORMATS),
                            onCheckedChange = { session.setFlag(ConfKeys.PARSE_WORD_FORMATS, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "kugou_word_timing",
                            subtitle = "缺少逐字时从酷狗获取 KRC 逐字",
                            checked = session.flag(ConfKeys.KUGOU_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.KUGOU_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "estimate_word_timing",
                            subtitle = "本地按字符估算逐字时间",
                            checked = session.flag(ConfKeys.ESTIMATE_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.ESTIMATE_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "prefer_network_word_timing",
                            subtitle = "优先使用联网逐字，响应变慢",
                            checked = session.flag(ConfKeys.PREFER_NETWORK_WORD_TIMING),
                            onCheckedChange = {
                                session.setFlag(ConfKeys.PREFER_NETWORK_WORD_TIMING, it)
                            },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "synth_word_timing",
                            subtitle = "按行时间轴合成逐字，节奏可能不准",
                            checked = session.flag(ConfKeys.SYNTH_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.SYNTH_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "auto_word_timing",
                            subtitle = "当前歌词缺少逐字时自动补充",
                            checked = session.flag(ConfKeys.AUTO_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.AUTO_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SliderRow(
                            title = "word_timing_max_chars",
                            subtitle = "超过该字数不估算，0 为不限",
                            value = session.number(ConfKeys.WORD_TIMING_MAX_CHARS),
                            range = 0f..40f,
                            steps = 39,
                            display = session.number(ConfKeys.WORD_TIMING_MAX_CHARS)
                                .let { if (it <= 0) "不限" else "$it 字" },
                            onChange = { session.setNumber(ConfKeys.WORD_TIMING_MAX_CHARS, it) },
                        )
                    }
                }
            }
            }

            if (visible("长句滚动")) {
            item {
                SectionHeader(
                    "长句滚动",
                    "处理超出控件宽度的歌词行",
                    expanded = sectionExpanded("长句滚动"),
                    onToggle = sectionToggle("长句滚动"),
                )
            }
            item {
                SectionBody(sectionExpanded("长句滚动")) {
                    SettingsCard {
                        SwitchRow(
                            title = "word_scroll",
                            subtitle = "为逐字模式补充横向滚动",
                            checked = session.flag(ConfKeys.WORD_SCROLL),
                            onCheckedChange = { session.setFlag(ConfKeys.WORD_SCROLL, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "word_scroll_follow",
                            subtitle = "按下方设定的时机跟着滚；关闭则从行首匀速滚动",
                            checked = session.flag(ConfKeys.WORD_SCROLL_FOLLOW),
                            onCheckedChange = { session.setFlag(ConfKeys.WORD_SCROLL_FOLLOW, it) },
                        )
                        val startOpen = remember { mutableStateOf(true) }
                        // 只在开启「跟随」时出现；展开收起和全局用同一套弹簧
                        AnimatedVisibility(
                            visible = session.flag(ConfKeys.WORD_SCROLL_FOLLOW),
                            enter = expandVertically(animationSpec = bouncy()) +
                                fadeIn(animationSpec = bouncy()),
                            exit = shrinkVertically(animationSpec = bouncy()) +
                                fadeOut(animationSpec = bouncy()),
                        ) {
                            Column {
                                RowDivider()
                                // 子折叠：不受「大标题折叠」开关影响，也不参与分组状态落盘
                                SubHeader(
                                    title = "开始滚动的时机",
                                    subtitle = "整句播放到第几成时开始跟随",
                                    expanded = startOpen.value,
                                    onToggle = { startOpen.value = !startOpen.value },
                                )
                                SectionBody(startOpen.value) {
                                    SliderRow(
                                        title = "word_scroll_start_pct",
                                        subtitle = "0 为自动（按一屏能装下多少算）",
                                        value = session.number(ConfKeys.WORD_SCROLL_START_PCT),
                                        range = 0f..90f,
                                        steps = 17,
                                        display = session.number(ConfKeys.WORD_SCROLL_START_PCT)
                                            .let { if (it <= 0) "自动" else "${it}%" },
                                        onChange = {
                                            session.setNumber(ConfKeys.WORD_SCROLL_START_PCT, it)
                                        },
                                    )
                                }
                            }
                        }
                        RowDivider()
                        SliderRow(
                            title = "word_scroll_speed",
                            subtitle = "0 为自动；其余为 px/10ms",
                            value = session.number(ConfKeys.WORD_SCROLL_SPEED),
                            range = 0f..16f,
                            steps = 15,
                            display = session.number(ConfKeys.WORD_SCROLL_SPEED)
                                .let { if (it <= 0) "自动" else "${it}px" },
                            onChange = { session.setNumber(ConfKeys.WORD_SCROLL_SPEED, it) },
                        )
                        RowDivider()
                        val delayMs = session.number(ConfKeys.WORD_SCROLL_DELAY, 500)
                        SliderRow(
                            title = "word_scroll_delay",
                            subtitle = "开始滚动前的停留时间",
                            value = delayMs / 100,
                            range = 0f..20f,
                            steps = 19,
                            display = "${delayMs}ms",
                            onChange = {
                                session.setNumber(ConfKeys.WORD_SCROLL_DELAY, it * 100)
                            },
                        )
                    }
                    CardNote("ROM 的逐字绘制不会滚动，超宽部分会被裁切。开启跟随后先静止，接近右边缘再滚动；关闭则从行首滚动。")
                }
            }
            }

            if (category == "全部") item { CategoryLabel("应用") }
            if (visible("应用过滤")) {
            item {
                SectionHeader(
                    "应用过滤",
                    "白名单与黑名单二选一",
                    expanded = sectionExpanded("应用过滤"),
                    onToggle = sectionToggle("应用过滤"),
                )
            }
            item {
                SectionBody(sectionExpanded("应用过滤")) {
                    SettingsCard {
                        val white = session.raw(ConfKeys.APP_WHITELIST)
                        val black = session.raw(ConfKeys.APP_BLACKLIST)
                        // 白名单非空时黑名单失效，反之亦然：失效且自身为空的那个置灰；
                        // 自身有内容时仍保持可编辑，否则没法清空
                        FieldRow(
                            label = "app_whitelist",
                            value = white,
                            supporting = "仅对这些包名生效，逗号分隔",
                            enabled = !(black.isNotBlank() && white.isBlank()),
                            onChange = { session.set(ConfKeys.APP_WHITELIST, it) },
                        )
                        RowDivider()
                        FieldRow(
                            label = "app_blacklist",
                            value = black,
                            supporting = "跳过这些包名；白名单非空时无效",
                            enabled = !(white.isNotBlank() && black.isBlank()),
                            onChange = { session.set(ConfKeys.APP_BLACKLIST, it) },
                        )
                    }
                }
            }
            }

            if (category == "全部") item { CategoryLabel("模块") }
            if (visible("其他设置")) {
            item {
                SectionHeader(
                    "其他设置",
                    "配置导出与恢复默认",
                    expanded = sectionExpanded("其他设置"),
                    onToggle = sectionToggle("其他设置"),
                )
            }
            item {
                SectionBody(sectionExpanded("其他设置")) {
                    SettingsCard {
                        ActionRow(
                            title = "导出配置",
                            subtitle = "把当前 JSON 配置保存到你选择的位置",
                            icon = Icons.Filled.Share,
                            onClick = { launchExport() },
                        )
                        RowDivider()
                        InfoRow(title = "配置文件", value = session.configPath)
                        RowDivider()
                        ActionRow(
                            title = "恢复默认设置",
                            subtitle = "歌词配置与界面偏好全部重置",
                            icon = Icons.Filled.Warning,
                            danger = true,
                            onClick = { showReset = true },
                        )
                    }
                    CardNote("导出的 JSON 与内部配置文件格式一致，可用于备份或手动检查。")
                }
            }
            }

            if (visible("调试")) {
            item {
                SectionHeader(
                    "调试",
                    "adb logcat -s LyricPatch",
                    expanded = sectionExpanded("调试"),
                    onToggle = sectionToggle("调试"),
                )
            }
            item {
                SectionBody(sectionExpanded("调试")) {
                    SettingsCard {
                        // 没写过 log_level 的老配置：按 debug_log 推一个当前值显示
                        val currentLevel = session.raw(ConfKeys.LOG_LEVEL).ifBlank {
                            if (session.flag(ConfKeys.DEBUG_LOG)) "debug" else "info"
                        }
                        ChoiceRow(
                            title = "log_level",
                            subtitle = "info 仅关键事件；debug 含取词细节；verbose 含每帧",
                            options = LOG_LEVELS,
                            selected = currentLevel,
                            onSelect = { session.set(ConfKeys.LOG_LEVEL, it) },
                        )
                        RowDivider()
                        InfoRow(
                            title = "SystemUI 侧",
                            value = when (session.active) {
                                true -> "已连接 · v${session.hookVersion.ifBlank { "?" }}"
                                false -> "未响应，检查 LSPosed 作用域"
                                null -> "检测中…"
                            },
                        )
                    }
                }
            }
            }

            item {
                CardNote("所有改动都会即时写入 JSON 配置并通知 SystemUI，无需手动保存，也无需重启系统界面。")
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "LyricPatch v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
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
}

/** 设置项分类：分类条决定当前显示哪几组，[SettingCategory.sections] 为空表示全部 */
private class SettingCategory(val title: String, val sections: List<String>)

private val CATEGORIES = listOf(
    SettingCategory("全部", emptyList()),
    SettingCategory("歌词", listOf("数据源", "翻译")),
    SettingCategory("显示", listOf("逐字", "长句滚动")),
    SettingCategory("应用", listOf("应用过滤")),
    SettingCategory("模块", listOf("其他设置", "调试")),
)

private val SECTION_KEYS = listOf(
    "数据源", "翻译", "逐字", "长句滚动", "应用过滤", "其他设置", "调试",
)

private val MACHINE_MODES = listOf(
    "auto" to "跟随 ROM",
    "source" to "源优先",
    "off" to "禁用机翻",
)

private val LOG_LEVELS = listOf(
    "silent" to "静默",
    "info" to "信息",
    "debug" to "调试",
    "verbose" to "详细",
)

/** 固定分类条：点击只显示该分类下的设置组 */
@Composable
private fun CategoryBar(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CATEGORIES.forEach { item ->
            FilterChip(
                selected = item.title == selected,
                onClick = { onSelect(item.title) },
                label = {
                    Text(item.title, style = MaterialTheme.typography.labelLarge)
                },
            )
        }
    }
}

/** 「全部」视图里的分组小标题 */
@Composable
private fun CategoryLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** 顶部状态条：返回 + 当前自动保存状态 */
@Composable
private fun SettingsTopBar(subtitle: String, isError: Boolean, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .padding(start = 4.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("设置", style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun saveLabel(saving: Boolean, savedAt: Long, error: String?): String = when {
    error != null -> "保存失败：$error"
    saving -> "正在保存…"
    savedAt > 0 -> "已自动保存 · " + DateFormat.format("HH:mm:ss", savedAt)
    else -> "改动会即时保存"
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    supporting: String?,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = value,
            // 这些键都是逗号/空格分隔的一行参数，粘贴带进来的换行直接吃掉
            onValueChange = { raw -> onChange(raw.replace("\n", "").replace("\r", "")) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            supportingText = if (supporting != null) {
                { Text(supporting) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun PresetChips(current: String, onPick: (String) -> Unit) {
    val presets = listOf(
        "网易云+LRCLIB" to "netease,lrclib",
        "全部内置" to "netease,qq,kugou,lrclib",
        "酷狗逐字" to "kugou",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (label, value) ->
            FilterChip(
                selected = current == value,
                onClick = { onPick(value) },
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

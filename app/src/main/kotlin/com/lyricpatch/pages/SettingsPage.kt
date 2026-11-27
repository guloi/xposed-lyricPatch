package com.lyricpatch.pages

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun SettingsPage(category: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val catalog = settingsCategory(category)
    val session = remember { ConfigSession.also { it.attach(context) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showReset by remember { mutableStateOf(false) }

    // 正常从 MainPage 进来时已经加载过；直接启动详情页时兜底读一次
    LaunchedEffect(Unit) {
        if (!session.loaded) session.load()
    }

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

    val visibleSections = catalog.sections

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

    // 导入：选一份 JSON，只认已登记的键，整份替换
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val imported = text?.let { ConfigStore.parseImported(it) }
            if (imported == null) {
                snackbar.showSnackbar("导入失败：不是有效的配置文件")
            } else {
                session.replaceAll(imported)
                snackbar.showSnackbar("已导入 ${imported.size} 项")
            }
        }
    }

    fun launchImport() {
        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("恢复默认设置？") },
            text = {
                Text(
                    "歌词配置会写回默认 JSON，取色、底栏和动画也恢复默认。不能撤销。",
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
            PageTopBar(
                title = catalog.title,
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
        ) {
                        if ("数据源" in visibleSections) {
            item { SectionLabel("数据源") }
            item {
                val providers = session.raw(ConfKeys.PROVIDERS)
                var editing by remember { mutableStateOf(false) }
                SettingsCard {
                    ValueRow(
                        title = "歌词源",
                        subtitle = "按顺序匹配，第一个返回歌词的源生效",
                        value = if (providers.isBlank()) "内置" else providers,
                        onClick = { editing = true },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "纯音乐不出词",
                        subtitle = "识别到纯音乐时直接不显示",
                        checked = session.flag(ConfKeys.HIDE_INSTRUMENTAL),
                        onCheckedChange = { session.setFlag(ConfKeys.HIDE_INSTRUMENTAL, it) },
                    )
                }
                if (editing) {
                    ListEditDialog(
                        title = "歌词源",
                        initial = providers,
                        placeholder = "netease",
                        supporting = "内置：netease / qq / kugou / lrclib；也可以填 https:// 开头的自建源。从上往下一个一个试。",
                        onDismiss = { editing = false },
                        onConfirm = { session.set(ConfKeys.PROVIDERS, it.joinToString(",")) },
                    )
                }
            }
            }

            if ("翻译" in visibleSections) {
            item { SectionLabel("翻译") }
            item {
                val providers = session.raw(ConfKeys.TRANSLATION_PROVIDERS)
                val mode = session.raw(ConfKeys.MACHINE_TRANSLATION)
                var editProviders by remember { mutableStateOf(false) }
                var editMode by remember { mutableStateOf(false) }
                SettingsCard {
                    SwitchRow(
                        title = "显示翻译",
                        subtitle = "关闭后不再请求翻译",
                        checked = session.flag(ConfKeys.TRANSLATION),
                        onCheckedChange = { session.setFlag(ConfKeys.TRANSLATION, it) },
                    )
                    RowDivider()
                    ValueRow(
                        title = "翻译源",
                        subtitle = "主源没译文时，按这个顺序去问",
                        value = providers,
                        onClick = { editProviders = true },
                    )
                    RowDivider()
                    ValueRow(
                        title = "机器翻译",
                        subtitle = "ROM 自带机翻的使用策略",
                        value = MACHINE_MODES.firstOrNull { it.first == mode }?.second
                            ?: MACHINE_MODES.first().second,
                        onClick = { editMode = true },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "无译文时隐藏副标题",
                        subtitle = "那一行没翻译就不占位置",
                        checked = session.flag(ConfKeys.HIDE_EMPTY_TRANSLATION),
                        onCheckedChange = { session.setFlag(ConfKeys.HIDE_EMPTY_TRANSLATION, it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "中文歌词不翻译",
                        subtitle = "省一次请求，也避免译文和原文一样",
                        checked = session.flag(ConfKeys.SKIP_TRANSLATION_FOR_CHINESE),
                        onCheckedChange = {
                            session.setFlag(ConfKeys.SKIP_TRANSLATION_FOR_CHINESE, it)
                        },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "隐藏所有译文",
                        subtitle = "排查用；打开后第二行永远空着",
                        checked = session.flag(ConfKeys.HIDE_TRANSLATION_ALWAYS),
                        onCheckedChange = { session.setFlag(ConfKeys.HIDE_TRANSLATION_ALWAYS, it) },
                    )
                }
                if (editProviders) {
                    ListEditDialog(
                        title = "翻译源",
                        initial = providers,
                        placeholder = "netease",
                        supporting = "内置：netease / qq；也可以填 https:// 开头的自建源。留空则只用主源自带的翻译。",
                        onDismiss = { editProviders = false },
                        onConfirm = { session.set(ConfKeys.TRANSLATION_PROVIDERS, it.joinToString(",")) },
                    )
                }
                if (editMode) {
                    ChoiceDialog(
                        title = "机器翻译",
                        options = MACHINE_MODES,
                        selected = mode,
                        onDismiss = { editMode = false },
                        onSelect = { session.set(ConfKeys.MACHINE_TRANSLATION, it) },
                    )
                }
            }
            }

                        if ("逐字" in visibleSections) {
            item { SectionLabel("逐字") }
            item {
                SettingsCard {
                        SwitchRow(
                            title = "解析增强格式",
                            subtitle = "解析增强 LRC 与 Lyricify Syllable 格式",
                            checked = session.flag(ConfKeys.PARSE_WORD_FORMATS),
                            onCheckedChange = { session.setFlag(ConfKeys.PARSE_WORD_FORMATS, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "酷狗逐字",
                            subtitle = "缺少逐字时从酷狗获取 KRC 逐字",
                            checked = session.flag(ConfKeys.KUGOU_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.KUGOU_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "本地估算逐字",
                            subtitle = "本地按字符估算逐字时间",
                            checked = session.flag(ConfKeys.ESTIMATE_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.ESTIMATE_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "优先联网逐字",
                            subtitle = "优先使用联网逐字，响应变慢",
                            checked = session.flag(ConfKeys.PREFER_NETWORK_WORD_TIMING),
                            onCheckedChange = {
                                session.setFlag(ConfKeys.PREFER_NETWORK_WORD_TIMING, it)
                            },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "按行合成逐字",
                            subtitle = "按行时间轴合成逐字，节奏可能不准",
                            checked = session.flag(ConfKeys.SYNTH_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.SYNTH_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "自动补逐字",
                            subtitle = "当前歌词缺少逐字时自动补充",
                            checked = session.flag(ConfKeys.AUTO_WORD_TIMING),
                            onCheckedChange = { session.setFlag(ConfKeys.AUTO_WORD_TIMING, it) },
                        )
                        RowDivider()
                        SliderRow(
                            title = "估算字数上限",
                            subtitle = "超过该字数不估算，0 为不限",
                            value = session.number(ConfKeys.WORD_TIMING_MAX_CHARS),
                            range = 0f..40f,
                            steps = 39,
                            display = session.number(ConfKeys.WORD_TIMING_MAX_CHARS)
                                .let { if (it <= 0) "不限" else "$it 字" },
                            onChange = { session.setNumber(ConfKeys.WORD_TIMING_MAX_CHARS, it) },
                        )
                        RowDivider()
                        val offset = session.number(ConfKeys.WORD_TIMING_OFFSET)
                        SliderRow(
                            title = "逐字偏移",
                            subtitle = "高亮比声音慢就调负数，快就调正数",
                            value = offset,
                            range = -500f..500f,
                            steps = 19,
                            display = when {
                                offset == 0 -> "±0"
                                offset > 0 -> "+${offset}ms"
                                else -> "${offset}ms"
                            },
                            onChange = {
                                session.setNumber(ConfKeys.WORD_TIMING_OFFSET, it)
                            },
                        )
                    }
            }
            }

            if ("长句滚动" in visibleSections) {
            item { SectionLabel("长句滚动") }
            item {
                SettingsCard {
                        SwitchRow(
                            title = "长句滚动",
                            subtitle = "为逐字模式补充横向滚动",
                            checked = session.flag(ConfKeys.WORD_SCROLL),
                            onCheckedChange = { session.setFlag(ConfKeys.WORD_SCROLL, it) },
                        )
                        RowDivider()
                        SwitchRow(
                            title = "跟随唱到哪",
                            subtitle = "唱到设定位置才开始移动；关闭则从行首匀速走",
                            checked = session.flag(ConfKeys.WORD_SCROLL_FOLLOW),
                            onCheckedChange = { session.setFlag(ConfKeys.WORD_SCROLL_FOLLOW, it) },
                        )
                        // 只在开启「跟随」时出现
                        AnimatedVisibility(
                            visible = session.flag(ConfKeys.WORD_SCROLL_FOLLOW),
                            enter = expandVertically(animationSpec = bouncy()) +
                                fadeIn(animationSpec = bouncy()),
                            exit = shrinkVertically(animationSpec = bouncy()) +
                                fadeOut(animationSpec = bouncy()),
                        ) {
                            Column {
                                RowDivider()
                                SliderRow(
                                    title = "开始跟随时机",
                                    subtitle = "开始跟随的时机，0 为自动",
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
                        RowDivider()
                        SliderRow(
                            title = "滚动速度",
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
                            title = "开始前停留",
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
                    CardNote("逐字模式不会自动滚动，超出部分会被裁切。")
            }
            }

                        if ("应用过滤" in visibleSections) {
            item { SectionLabel("应用过滤") }
            item {
                val white = session.raw(ConfKeys.APP_WHITELIST)
                val black = session.raw(ConfKeys.APP_BLACKLIST)
                var editing by remember { mutableStateOf<String?>(null) }
                SettingsCard {
                    ValueRow(
                        title = "仅这些应用生效",
                        subtitle = "白名单；填了就不看黑名单",
                        value = entriesLabel(white),
                        enabled = !(black.isNotBlank() && white.isBlank()),
                        onClick = { editing = "white" },
                    )
                    RowDivider()
                    ValueRow(
                        title = "跳过这些应用",
                        subtitle = "黑名单；白名单非空时无效",
                        value = entriesLabel(black),
                        enabled = !(white.isNotBlank() && black.isBlank()),
                        onClick = { editing = "black" },
                    )
                }
                val editingKey = when (editing) {
                    "white" -> ConfKeys.APP_WHITELIST
                    "black" -> ConfKeys.APP_BLACKLIST
                    else -> null
                }
                if (editingKey != null) {
                    ListEditDialog(
                        title = if (editing == "white") "仅这些应用生效" else "跳过这些应用",
                        initial = session.raw(editingKey),
                        placeholder = "moe.ouom.neriplayer",
                        supporting = "填应用包名（不是名称）。可以在系统设置 → 应用信息里看到，例如 moe.ouom.neriplayer。",
                        onDismiss = { editing = null },
                        onConfirm = { session.set(editingKey, it.joinToString(",")) },
                    )
                }
            }
            }

                        if ("其他设置" in visibleSections) {
            item { SectionLabel("其他设置") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "导入配置",
                        subtitle = "从 JSON 文件恢复，只认已登记的项",
                        icon = Icons.Filled.Refresh,
                        onClick = { launchImport() },
                    )
                    RowDivider()
                    ActionRow(
                        title = "导出配置",
                        subtitle = "存成 JSON，可以备份或手改",
                        icon = Icons.Filled.Share,
                        onClick = { launchExport() },
                    )
                    RowDivider()
                    InfoRow(title = "配置文件", value = session.configPath)
                    RowDivider()
                    ActionRow(
                        title = "恢复默认设置",
                        subtitle = "配置和界面偏好都会重置",
                        icon = Icons.Filled.Warning,
                        danger = true,
                        onClick = { showReset = true },
                    )
                }
            }
            }

            if ("调试" in visibleSections) {
            item { SectionLabel("调试") }
            item {
                // 没写过 log_level 的老配置：按 debug_log 推一个当前值显示
                val currentLevel = session.raw(ConfKeys.LOG_LEVEL).ifBlank {
                    if (session.flag(ConfKeys.DEBUG_LOG)) "debug" else "info"
                }
                var picking by remember { mutableStateOf(false) }
                SettingsCard {
                    ValueRow(
                        title = "日志级别",
                        subtitle = "info 只看关键事件；debug 含取词细节",
                        value = LOG_LEVELS.firstOrNull { it.first == currentLevel }?.second
                            ?: currentLevel,
                        onClick = { picking = true },
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
                if (picking) {
                    ChoiceDialog(
                        title = "日志级别",
                        options = LOG_LEVELS,
                        selected = currentLevel,
                        onDismiss = { picking = false },
                        onSelect = { session.set(ConfKeys.LOG_LEVEL, it) },
                    )
                }
            }
            }

            item {
                CardNote("改完就生效，不用保存，也不用重启系统界面。")
            }
            // 版本号只在「模块」页露出来，其它分类页不占地方
            if (catalog.title == "模块") {
                item {
                    Text(
                        "LyricPatch v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}


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

private fun saveLabel(saving: Boolean, savedAt: Long, error: String?): String = when {
    error != null -> "保存失败：$error"
    saving -> "正在保存…"
    savedAt > 0 -> "已自动保存 · " + DateFormat.format("HH:mm:ss", savedAt)
    else -> "改完自动保存"
}


/** 一行一个的列表编辑器：不要再多行文本框 */
@Composable
private fun ListEditDialog(
    title: String,
    initial: String,
    placeholder: String,
    supporting: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val items = remember {
        mutableStateListOf<String>().apply {
            addAll(initial.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() })
            if (isEmpty()) add("")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { items[index] = it },
                                modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                placeholder = {
                                    Text(
                                        placeholder,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            IconButton(
                                onClick = { items.removeAt(index) },
                                enabled = items.size > 1,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除这一行",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { items.add("") }) { Text("添加一行") }
                    Text(
                        "${items.count { it.isNotBlank() }} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(items.map { it.trim() }.filter { it.isNotEmpty() })
                    onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 名单类配置在右侧显示成「3 项」 */
private fun entriesLabel(raw: String): String {
    val n = raw.split(',', '\n').count { it.trim().isNotEmpty() }
    return if (n == 0) "未设置" else "$n 项"
}

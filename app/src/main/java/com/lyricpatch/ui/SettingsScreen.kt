package com.lyricpatch.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 模块设置界面。
 *
 * 视觉上是 Material 3 Expressive 那一套：超圆角容器、大标题、色调分级背景、
 * 胶囊分段选择、spring 手感（靠 Compose 自带的动画），带动态取色。
 * 数据面只做一件事：读写 lyricpatch.conf。
 */
private class UiState {
    val values = mutableStateMapOf<String, String>()
    var path by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var dirty by mutableStateOf(false)

    /** null = 还没探到；true = hook 在响应；false = 没挂上 */
    var active by mutableStateOf<Boolean?>(null)
}

private fun String.toBooleanLoose(def: Boolean): Boolean = when (trim().lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> def
}

private fun UiState.raw(key: String): String = values[key] ?: ConfigStore.DEFAULTS[key].orEmpty()

private fun UiState.flag(key: String): Boolean {
    // 没登记的键一律当 false：以前这里回退 true，害得 hide_translation_always 看着像开着
    val def = (ConfigStore.DEFAULTS[key] ?: "false").toBooleanLoose(false)
    return raw(key).toBooleanLoose(def)
}

private fun UiState.num(key: String, def: Int): Int = raw(key).toIntOrNull() ?: def

private fun UiState.put(key: String, value: String) {
    values[key] = value
    dirty = true
}

private fun UiState.putFlag(key: String, value: Boolean) = put(key, if (value) "true" else "false")

@Composable
fun SettingsScreen() {
    val state = remember { UiState() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 分组折叠状态存在 ui_prefs：第一次进入按「默认折叠」初始化并落盘，
    // 之后每次进入都恢复上次的状态。放到 LaunchedEffect 里，避免在组合期间写磁盘 / 写状态。
    LaunchedEffect(Unit) { UiPrefs.ensureSectionDefaults(SECTION_KEYS) }
    val openSections = remember { mutableStateMapOf<String, Boolean>() }
    val collapsible = UiPrefs.collapsible

    fun open(key: String): Boolean =
        !collapsible || (openSections[key] ?: UiPrefs.sectionOpen(key))

    fun toggle(key: String) {
        val next = !open(key)
        openSections[key] = next
        UiPrefs.updateSectionOpen(key, next)
    }

    // 上滑模式下的保存条是否已呼出
    val saveBarShown = remember { mutableStateOf(false) }
    // 保存条实际高度：Snackbar 要跟着一起抬高，否则会被盖住
    var saveBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val snackLift by animateDpAsState(
        targetValue = if (UiPrefs.floatingSaveBar && saveBarShown.value) {
            with(density) { saveBarHeightPx.toDp() }
        } else {
            0.dp
        },
        animationSpec = bouncy(),
        label = "snack-lift",
    )

    // 跟随列表滑动：手指往上滑（内容向上滚）呼出，往下滑收回
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (UiPrefs.floatingSaveBar) {
                    if (available.y < -2f) saveBarShown.value = true
                    else if (available.y > 2f) saveBarShown.value = false
                }
                return Offset.Zero
            }
        }
    }

    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
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

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { ConfigStore.load(context) }
        state.values.putAll(result.values)
        state.path = result.path
        state.loaded = true
    }

    // 每 10 秒探一次：在 LSPosed 里刚启用、或重启过 SystemUI，界面会自动变过来
    LaunchedEffect(Unit) {
        while (true) {
            state.active = ConfigStore.isActive(context)
            delay(10_000L)
        }
    }

    val onReload: () -> Unit = {
        scope.launch {
            state.busy = true
            val result = withContext(Dispatchers.IO) { ConfigStore.load(context) }
            state.values.clear()
            state.values.putAll(result.values)
            state.path = result.path
            state.dirty = false
            state.active = ConfigStore.isActive(context)
            state.busy = false
            snackbar.showSnackbar("已重新读取")
        }
    }

    val onSave: () -> Unit = {
        if (!state.busy) {
            scope.launch {
                state.busy = true
                val snapshot = state.values.toMap()
                val result = withContext(Dispatchers.IO) { ConfigStore.save(context, snapshot) }
                state.busy = false
                if (result.ok) {
                    state.dirty = false
                    state.path = result.path
                    snackbar.showSnackbar("已保存")
                } else {
                    snackbar.showSnackbar("保存失败：${result.message}")
                }
            }
        }
    }

    // 浮层要能贴住窗口底边（含手势条区域），所以放在 Scaffold 外层
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // 常驻模式交给 Scaffold；跟随模式那个 Host 放到外层，才能盖在保存条上面
        snackbarHost = { if (!UiPrefs.floatingSaveBar) SnackbarHost(hostState = snackbar) },
        bottomBar = {
            // 常驻模式才占 Scaffold 底栏；上滑模式走浮层，不占高度
            if (!UiPrefs.floatingSaveBar) {
                SaveBar(busy = state.busy, dirty = state.dirty, onReload = onReload, onSave = onSave)
            }
        },
    ) { inner ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).nestedScroll(scrollConnection),
            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
        ) {
            item {
                Hero(
                    version = version,
                    dirty = state.dirty,
                    active = state.active,
                    path = state.path ?: ConfigStore.file(context).absolutePath,
                    onReload = onReload,
                )
            }
            item {
                PreviewCard(
                    scrollOn = state.flag("word_scroll"),
                    speed = state.num("word_scroll_speed", 0),
                    delayMs = state.num("word_scroll_delay", 500),
                )
            }
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    Intent(context, AppearanceActivity::class.java),
                                )
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("外观", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "取色方式与大标题折叠",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionHeader(
                    "数据源",
                    "按顺序匹配，使用第一个返回歌词的源",
                    expanded = open("数据源"),
                    onToggle = if (collapsible) ({ toggle("数据源") }) else null,
                )
            }
            item {
                SectionBody(open("数据源")) {
                    SettingsCard {
                    FieldRow(
                        label = "providers",
                        value = state.raw("providers"),
                        supporting = "netease / qq / kugou / lrclib，或 https:// 自建源",
                        onChange = { state.put("providers", it) },
                    )
                    PresetChips(current = state.raw("providers")) { state.put("providers", it) }
                    RowDivider()
                    SwitchRow(
                        title = "hide_instrumental",
                        subtitle = "识别到纯音乐时不显示歌词",
                        checked = state.flag("hide_instrumental"),
                        onCheckedChange = { state.putFlag("hide_instrumental", it) },
                    )
                }
                    CardNote("「状态栏歌词源」优先，未命中时按上方顺序。")
                }
            }

            item {
                SectionHeader(
                    "翻译",
                    "控制第二行显示内容",
                    expanded = open("翻译"),
                    onToggle = if (collapsible) ({ toggle("翻译") }) else null,
                )
            }
            item {
                SectionBody(open("翻译")) {
                    SettingsCard {
                    SwitchRow(
                        title = "translation",
                        subtitle = "关闭后不请求翻译",
                        checked = state.flag("translation"),
                        onCheckedChange = { state.putFlag("translation", it) },
                    )
                    RowDivider()
                    FieldRow(
                        label = "translation_providers",
                        value = state.raw("translation_providers"),
                        supporting = "主源无译文时按此顺序获取：netease / qq / http(s)",
                        onChange = { state.put("translation_providers", it) },
                    )
                    RowDivider()
                    ChoiceRow(
                        title = "machine_translation",
                        subtitle = "ROM 自带机翻的使用策略",
                        options = MACHINE_MODES,
                        selected = state.raw("machine_translation"),
                        onSelect = { state.put("machine_translation", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "hide_empty_translation",
                        subtitle = "该行无译文时隐藏副标题",
                        checked = state.flag("hide_empty_translation"),
                        onCheckedChange = { state.putFlag("hide_empty_translation", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "skip_translation_for_chinese",
                        subtitle = "中文歌词不请求翻译",
                        checked = state.flag("skip_translation_for_chinese"),
                        onCheckedChange = { state.putFlag("skip_translation_for_chinese", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "hide_translation_always",
                        subtitle = "隐藏所有译文，仅用于排查",
                        checked = state.flag("hide_translation_always"),
                        onCheckedChange = { state.putFlag("hide_translation_always", it) },
                    )
                }
                }
            }

            item {
                SectionHeader(
                    "逐字",
                    "逐字来源与补充方式",
                    expanded = open("逐字"),
                    onToggle = if (collapsible) ({ toggle("逐字") }) else null,
                )
            }
            item {
                SectionBody(open("逐字")) {
                    SettingsCard {
                    SwitchRow(
                        title = "parse_word_formats",
                        subtitle = "解析增强 LRC 与 Lyricify Syllable 格式",
                        checked = state.flag("parse_word_formats"),
                        onCheckedChange = { state.putFlag("parse_word_formats", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "kugou_word_timing",
                        subtitle = "缺少逐字时从酷狗获取 KRC 逐字",
                        checked = state.flag("kugou_word_timing"),
                        onCheckedChange = { state.putFlag("kugou_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "estimate_word_timing",
                        subtitle = "本地按字符估算逐字时间",
                        checked = state.flag("estimate_word_timing"),
                        onCheckedChange = { state.putFlag("estimate_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "prefer_network_word_timing",
                        subtitle = "优先使用联网逐字，响应变慢",
                        checked = state.flag("prefer_network_word_timing"),
                        onCheckedChange = { state.putFlag("prefer_network_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "synth_word_timing",
                        subtitle = "按行时间轴合成逐字，节奏可能不准",
                        checked = state.flag("synth_word_timing"),
                        onCheckedChange = { state.putFlag("synth_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "auto_word_timing",
                        subtitle = "当前歌词缺少逐字时自动补充",
                        checked = state.flag("auto_word_timing"),
                        onCheckedChange = { state.putFlag("auto_word_timing", it) },
                    )
                    RowDivider()
                    SliderRow(
                        title = "word_timing_max_chars",
                        subtitle = "超过该字数不估算，0 为不限",
                        value = state.num("word_timing_max_chars", 0),
                        range = 0f..40f,
                        steps = 39,
                        display = state.num("word_timing_max_chars", 0).let { if (it <= 0) "不限" else "$it 字" },
                        onChange = { state.put("word_timing_max_chars", it.toString()) },
                    )
                }
                }
            }

            item {
                SectionHeader(
                    "长句滚动",
                    "处理超出控件宽度的歌词行",
                    expanded = open("长句滚动"),
                    onToggle = if (collapsible) ({ toggle("长句滚动") }) else null,
                )
            }
            item {
                SectionBody(open("长句滚动")) {
                    SettingsCard {
                    SwitchRow(
                        title = "word_scroll",
                        subtitle = "为逐字模式补充横向滚动",
                        checked = state.flag("word_scroll"),
                        onCheckedChange = { state.putFlag("word_scroll", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "word_scroll_follow",
                        subtitle = "按下方设定的时机跟着滚；关闭则从行首匀速滚动",
                        checked = state.flag("word_scroll_follow"),
                        onCheckedChange = { state.putFlag("word_scroll_follow", it) },
                    )
                    val startOpen = remember { mutableStateOf(true) }
                    // 只在开启「跟随」时出现；展开收起和全局用同一套弹簧
                    AnimatedVisibility(
                        visible = state.flag("word_scroll_follow"),
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
                                    value = state.num("word_scroll_start_pct", 0),
                                    range = 0f..90f,
                                    steps = 17,
                                    display = state.num("word_scroll_start_pct", 0).let {
                                        if (it <= 0) "自动" else "${it}%"
                                    },
                                    onChange = { state.put("word_scroll_start_pct", it.toString()) },
                                )
                            }
                        }
                    }
                    RowDivider()
                    SliderRow(
                        title = "word_scroll_speed",
                        subtitle = "0 为自动；其余为 px/10ms",
                        value = state.num("word_scroll_speed", 0),
                        range = 0f..16f,
                        steps = 15,
                        display = state.num("word_scroll_speed", 0).let { if (it <= 0) "自动" else "${it}px" },
                        onChange = { state.put("word_scroll_speed", it.toString()) },
                    )
                    RowDivider()
                    val delayMs = state.num("word_scroll_delay", 500)
                    SliderRow(
                        title = "word_scroll_delay",
                        subtitle = "开始滚动前的停留时间",
                        value = delayMs / 100,
                        range = 0f..20f,
                        steps = 19,
                        display = "${delayMs}ms",
                        onChange = { state.put("word_scroll_delay", (it * 100).toString()) },
                    )
                }
                    CardNote("ROM 的逐字绘制不会滚动，超宽部分会被裁切。开启跟随后先静止，接近右边缘再滚动；关闭则从行首滚动。")
                }
            }

            item {
                SectionHeader(
                    "应用过滤",
                    "白名单与黑名单二选一",
                    expanded = open("应用过滤"),
                    onToggle = if (collapsible) ({ toggle("应用过滤") }) else null,
                )
            }
            item {
                SectionBody(open("应用过滤")) {
                    SettingsCard {
                    val white = state.raw("app_whitelist")
                    val black = state.raw("app_blacklist")
                    // 白名单非空时黑名单失效，反之亦然：失效且自身为空的那个置灰；
                    // 自身有内容时仍保持可编辑，否则没法清空
                    FieldRow(
                        label = "app_whitelist",
                        value = white,
                        supporting = "仅对这些包名生效，逗号分隔",
                        enabled = !(black.isNotBlank() && white.isBlank()),
                        onChange = { state.put("app_whitelist", it) },
                    )
                    RowDivider()
                    FieldRow(
                        label = "app_blacklist",
                        value = black,
                        supporting = "跳过这些包名；白名单非空时无效",
                        enabled = !(white.isNotBlank() && black.isBlank()),
                        onChange = { state.put("app_blacklist", it) },
                    )
                }
                }
            }

            item {
                SectionHeader(
                    "调试",
                    "adb logcat -s LyricPatch",
                    expanded = open("调试"),
                    onToggle = if (collapsible) ({ toggle("调试") }) else null,
                )
            }
            item {
                SectionBody(open("调试")) {
                    SettingsCard {
                    // 没写过 log_level 的老配置：按 debug_log 推一个当前值显示
                    val currentLevel = state.raw("log_level").ifBlank {
                        if (state.flag("debug_log")) "debug" else "info"
                    }
                    ChoiceRow(
                        title = "log_level",
                        subtitle = "info 仅关键事件；debug 含取词细节；verbose 含每帧",
                        options = LOG_LEVELS,
                        selected = currentLevel,
                        onSelect = { state.put("log_level", it) },
                    )
                    RowDivider()
                    InfoRow(
                        title = "配置文件",
                        value = state.path.orEmpty(),
                    )
                }
                }
            }

            item {
                CardNote("保存后立即生效，无需重启 SystemUI。首次启用模块或更换 SystemUI 版本后需重启一次。")
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
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

    if (UiPrefs.floatingSaveBar) {
        SlideUpSaveBar(
            // 纯跟随滑动：上滑呼出、下滑收回（列表滚不动时上下划同样会触发）
            shown = saveBarShown.value,
            onShownChange = { saveBarShown.value = it },
            onHeightChange = { saveBarHeightPx = it },
            busy = state.busy,
            dirty = state.dirty,
            onReload = onReload,
            onSave = onSave,
        )

        // Snackbar 放在保存条之后，并随保存条高度抬起来
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                // 弹簧回弹时会短暂越过 0（负值），而 padding 不接受负数，必须夹住
                .padding(bottom = snackLift.coerceAtLeast(0.dp))
                .padding(horizontal = 16.dp),
        )
    }
    }
}

private val SECTION_KEYS = listOf("数据源", "翻译", "逐字", "长句滚动", "应用过滤", "调试")

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

@Composable
private fun Hero(
    version: String,
    dirty: Boolean,
    active: Boolean?,
    path: String,
    onReload: () -> Unit,
) {
    val gradient = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
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
        Spacer(Modifier.height(6.dp))
        Text(
            "SystemUI 状态栏歌词补源与逐字",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("v$version")
            Pill(if (dirty) "未保存" else "已同步")
            Pill(
                text = when (active) {
                    true -> "已启用"
                    false -> "未启用"
                    null -> "检测中…"
                },
                strong = active == true,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroAction("重新读取", onReload)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (active == true) path else "模块未启用",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
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
private fun HeroAction(text: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

/** 预览：高亮随播放进度推进 */
@Composable
private fun PreviewCard(scrollOn: Boolean, speed: Int, delayMs: Int) {
    val transition = rememberInfiniteTransition(label = "preview")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sing",
    )

    val line = "这是一句较长的歌词，用于预览滚动与逐字高亮"
    val sung = (phase * line.length).roundToInt().coerceIn(0, line.length)
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(30.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♪", style = MaterialTheme.typography.titleMedium, color = accent)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                            append(line.substring(0, sung))
                        }
                        withStyle(SpanStyle(color = dim)) {
                            append(line.substring(sung))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (scrollOn) {
                    val speedText = if (speed <= 0) "自动" else "每 10ms ${speed}px"
                    "停留 ${delayMs}ms 后滚动，$speedText"
                } else {
                    "未开启长句滚动，超宽部分会被裁切"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        "网易云+酷狗+LRCLIB" to "netease,qq,kugou,lrclib",
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

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SaveBar(
    busy: Boolean,
    dirty: Boolean,
    onReload: () -> Unit,
    onSave: () -> Unit,
    floating: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shape = if (floating) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RectangleShape,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (floating) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.width(40.dp).height(4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onReload, enabled = !busy, shape = CircleShape) {
                    Text("重新读取")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onSave,
                    // 已同步（没有未保存改动）时置灰
                    enabled = !busy && dirty,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("保存")
                    }
                }
            }
        }
    }
}

/**
 * 可上滑/下滑的保存条：平时只留底部一条细线，上滑沿原路升出，下滑沿原路收回。
 */
@Composable
private fun SlideUpSaveBar(
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
    onHeightChange: (Int) -> Unit,
    busy: Boolean,
    dirty: Boolean,
    onReload: () -> Unit,
    onSave: () -> Unit,
) {
    var panelHeight by remember { mutableIntStateOf(0) }
    // 收起时把整块面板平移到屏幕外，展开时弹簧回弹到位。
    // 不用 AnimatedVisibility：它和 Box.align 组合时在某些版本上 enter 不生效，面板会“看不见”。
    val offsetY by animateIntOffsetAsState(
        targetValue = IntOffset(0, if (shown) 0 else panelHeight),
        animationSpec = bouncy(),
        label = "savebar-offset",
    )
    val panelAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = bouncy(),
        label = "savebar-alpha",
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { offsetY }
                .graphicsLayer { alpha = panelAlpha.coerceIn(0f, 1f) }
                .onSizeChanged {
                    panelHeight = it.height
                    onHeightChange(it.height)
                }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onVerticalDrag = { _, delta -> dragged += delta },
                        onDragEnd = { if (dragged > 24f) onShownChange(false) },
                    )
                },
        ) {
            SaveBar(
                busy = busy,
                dirty = dirty,
                onReload = onReload,
                onSave = onSave,
                floating = true,
            )
        }
    }
}

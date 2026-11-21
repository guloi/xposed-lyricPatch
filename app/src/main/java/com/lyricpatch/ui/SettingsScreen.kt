package com.lyricpatch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
}

private fun String.toBooleanLoose(def: Boolean): Boolean = when (trim().lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> def
}

private fun UiState.raw(key: String): String = values[key] ?: ConfigStore.DEFAULTS[key].orEmpty()

private fun UiState.flag(key: String): Boolean {
    val def = (ConfigStore.DEFAULTS[key] ?: "").toBooleanLoose(true)
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

    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { ConfigStore.load(context) }
        state.values.putAll(result.values)
        state.path = result.path
        state.loaded = true
    }

    val onReload: () -> Unit = {
        scope.launch {
            state.busy = true
            val result = withContext(Dispatchers.IO) { ConfigStore.load(context) }
            state.values.clear()
            state.values.putAll(result.values)
            state.path = result.path
            state.dirty = false
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
                    snackbar.showSnackbar("已写入 ${result.path}")
                } else {
                    snackbar.showSnackbar("保存失败：${result.message}")
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        bottomBar = {
            SaveBar(busy = state.busy, dirty = state.dirty, onReload = onReload, onSave = onSave)
        },
    ) { inner ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
        ) {
            item {
                Hero(
                    version = version,
                    dirty = state.dirty,
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
            item { SectionHeader("数据源", "按顺序尝试，第一个出词的就用它") }
            item {
                SettingsCard {
                    FieldRow(
                        label = "providers",
                        value = state.raw("providers"),
                        supporting = "netease / qq / kugou / lrclib，也能直接写 https:// 自建源",
                        onChange = { state.put("providers", it) },
                    )
                    PresetChips(current = state.raw("providers")) { state.put("providers", it) }
                }
            }
            item {
                CardNote(
                    "SystemUI 设置里「状态栏歌词源」填了地址时，会先问它（能保住 yrc 逐字），" +
                        "再回落到上面的顺序。",
                )
            }

            item { SectionHeader("翻译", "第二行显示什么，全在这里决定") }
            item {
                SettingsCard {
                    SwitchRow(
                        title = "translation",
                        subtitle = "关掉就完全不请求翻译，只要原文",
                        checked = state.flag("translation"),
                        onCheckedChange = { state.putFlag("translation", it) },
                    )
                    RowDivider()
                    FieldRow(
                        label = "translation_providers",
                        value = state.raw("translation_providers"),
                        supporting = "主源没带翻译时单独再要一份：netease / qq / http(s)",
                        onChange = { state.put("translation_providers", it) },
                    )
                    RowDivider()
                    ChoiceRow(
                        title = "machine_translation",
                        subtitle = "ROM 自带的机翻优先级，默认跟它走",
                        options = MACHINE_MODES,
                        selected = state.raw("machine_translation"),
                        onSelect = { state.put("machine_translation", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "hide_empty_translation",
                        subtitle = "这一行没翻译就干脆不显示副标题，避开残影",
                        checked = state.flag("hide_empty_translation"),
                        onCheckedChange = { state.putFlag("hide_empty_translation", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "skip_translation_for_chinese",
                        subtitle = "中文歌词不去要翻译，避开同名错贴",
                        checked = state.flag("skip_translation_for_chinese"),
                        onCheckedChange = { state.putFlag("skip_translation_for_chinese", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "hide_translation_always",
                        subtitle = "核弹开关：任何翻译都不显示，只排障时用",
                        checked = state.flag("hide_translation_always"),
                        onCheckedChange = { state.putFlag("hide_translation_always", it) },
                    )
                }
            }

            item { SectionHeader("逐字", "word timing 从哪来、要不要补") }
            item {
                SettingsCard {
                    SwitchRow(
                        title = "parse_word_formats",
                        subtitle = "识别增强 LRC / Lyricify Syllable，转成 yrc",
                        checked = state.flag("parse_word_formats"),
                        onCheckedChange = { state.putFlag("parse_word_formats", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "kugou_word_timing",
                        subtitle = "没逐字时去酷狗要真逐字（KRC）",
                        checked = state.flag("kugou_word_timing"),
                        onCheckedChange = { state.putFlag("kugou_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "estimate_word_timing",
                        subtitle = "本地按字数估算一份，秒出但也只是估算",
                        checked = state.flag("estimate_word_timing"),
                        onCheckedChange = { state.putFlag("estimate_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "prefer_network_word_timing",
                        subtitle = "宁愿等联网真逐字，也不用估算的",
                        checked = state.flag("prefer_network_word_timing"),
                        onCheckedChange = { state.putFlag("prefer_network_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "synth_word_timing",
                        subtitle = "用行时间轴硬凑一份逐字（节奏会不准）",
                        checked = state.flag("synth_word_timing"),
                        onCheckedChange = { state.putFlag("synth_word_timing", it) },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "auto_word_timing",
                        subtitle = "最后一道兑底：发现当前歌词没逐字就自己补",
                        checked = state.flag("auto_word_timing"),
                        onCheckedChange = { state.putFlag("auto_word_timing", it) },
                    )
                    RowDivider()
                    SliderRow(
                        title = "word_timing_max_chars",
                        subtitle = "超过这个字数就不做估算（0 = 不限）",
                        value = state.num("word_timing_max_chars", 0),
                        range = 0f..40f,
                        steps = 39,
                        display = state.num("word_timing_max_chars", 0).let { if (it <= 0) "不限" else "$it 字" },
                        onChange = { state.put("word_timing_max_chars", it.toString()) },
                    )
                }
            }

            item { SectionHeader("长句滚动", "4.13 起逐字模式也有跑马灯了") }
            item {
                SettingsCard {
                    SwitchRow(
                        title = "word_scroll",
                        subtitle = "逐字自绘不放跑马灯，这一项会把整行平移补上",
                        checked = state.flag("word_scroll"),
                        onCheckedChange = { state.putFlag("word_scroll", it) },
                    )
                    RowDivider()
                    SliderRow(
                        title = "word_scroll_speed",
                        subtitle = "0 = 自动：铺满这一行的演唱时长；填数字 = 固定 px/10ms",
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
                        subtitle = "开滚前先停多久，让人看完句子开头",
                        value = delayMs / 100,
                        range = 0f..20f,
                        steps = 19,
                        display = "${delayMs}ms",
                        onChange = { state.put("word_scroll_delay", (it * 100).toString()) },
                    )
                }
            }
            item {
                CardNote(
                    "ROM 的逐字分支把文本钉在 x=0，完全不看 mOffset，所以句子比控件宽时右半边就没了。" +
                        "模块在 onDraw 外套一层 canvas.translate，按 ROM 跑马灯的节奏（默认 500ms + 4px/10ms）平移。",
                )
            }

            item { SectionHeader("应用过滤", "白名单 / 黑名单二选一") }
            item {
                SettingsCard {
                    FieldRow(
                        label = "app_whitelist",
                        value = state.raw("app_whitelist"),
                        supporting = "只给这些 App 补词，逗号分隔包名；支持前缀匹配",
                        onChange = { state.put("app_whitelist", it) },
                    )
                    RowDivider()
                    FieldRow(
                        label = "app_blacklist",
                        value = state.raw("app_blacklist"),
                        supporting = "这些 App 一律跳过（白名单非空时它不生效）",
                        onChange = { state.put("app_blacklist", it) },
                    )
                }
            }

            item { SectionHeader("调试", "adb logcat -s LyricPatch") }
            item {
                SettingsCard {
                    // 没写过 log_level 的老配置：按 debug_log 推一个当前值显示
                    val currentLevel = state.raw("log_level").ifBlank {
                        if (state.flag("debug_log")) "debug" else "info"
                    }
                    ChoiceRow(
                        title = "log_level",
                        subtitle = "info 只报关键事件；debug 才出每次取词/注入的细节，verbose 连每帧都打",
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

            item {
                CardNote(
                    "改完保存就会生效（模块按 mtime 热读，不用重启 SystemUI）；" +
                        "只有第一次启用模块或换 SystemUI 版本时，才需要重启一次 SystemUI。",
                )
            }
            item {
                Text(
                    "LyricPatch v$version · 设置界面 4.14+",
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private val MACHINE_MODES = listOf(
    "auto" to "ROM 原样",
    "source" to "源优先",
    "off" to "不机翻",
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
            "SystemUI 状态栏歌词 · 补源 / 逐字 / 翻译 / 长句滚动",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("v$version")
            Pill(if (dirty) "未保存" else "已同步")
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroAction("重新读取", onReload)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "配置存在模块内部存储，SystemUI 通过 provider 读，不需要 root",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)) {
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

/** 模拟一下状态栏那一行：高亮按播放位置往前爬 */
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

    val line = "这是一句故意写得很长的歌词，用来看看跑马灯和逐字高亮"
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
                    val speedText = if (speed <= 0) "按这一行的时长自动铺满" else "每 10ms ${speed}px"
                    "长句先停 ${delayMs}ms，然后$speedText，句尾刚好露出来"
                } else {
                    "word_scroll 关掉了：这句话在状态栏上只能看到前半段"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(start = 26.dp, end = 26.dp, top = 26.dp, bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
private fun CardNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    supporting: String?,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
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
        "三家全开" to "netease,qq,kugou,lrclib",
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
private fun SliderRow(
    title: String,
    subtitle: String?,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    display,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SaveBar(
    busy: Boolean,
    dirty: Boolean,
    onReload: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onReload, enabled = !busy, shape = CircleShape) {
                Text("重读")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onSave,
                enabled = !busy,
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
                    Text(if (dirty) "保存并生效" else "保存")
                }
            }
        }
    }
}

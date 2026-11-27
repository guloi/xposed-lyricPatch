package com.lyricpatch.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.lyricpatch.lib.ConfKeys
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 配置存储。
 *
 * 文件在模块 App 自己的内部存储：
 *   /data/user/0/com.lyricpatch/files/lyricpatch.json
 *
 * 格式（JSON，schema = 2）：
 * ```json
 * {
 *   "schema": 2,
 *   "appVersion": "4.75",
 *   "updatedAt": 1764…,
 *   "values": { "providers": "netease,lrclib", "translation": "true" }
 * }
 * ```
 *
 * 规则：
 *  - 读取时把 [ORDER] 里缺的键补成 [DEFAULTS]，页面拿到的永远是完整配置；
 *  - 写入时先写 `.tmp` 再 rename，SystemUI 侧随时来读都不会读到半截文件；
 *  - 旧版 `lyricpatch.conf` 仍能读，[LoadResult.migratedFrom] 非空表示需要迁移；
 *  - 旧文件里的未知键、手写键原样保留，不会因为一次保存被抹掉。
 *
 * SystemUI 进程不直接读这个文件（内部存储是私有的），而是通过 ConfigProvider 拿文本。
 */
object ConfigStore {

    const val FILE_NAME = "lyricpatch.json"
    const val LEGACY_FILE_NAME = "lyricpatch.conf"
    const val SCHEMA = 2

    const val AUTHORITY = "com.lyricpatch.config"
    const val METHOD_READ = "read"
    const val METHOD_REPORT = "report"
    const val METHOD_STATUS = "status"

    /** 保存后拿这个广播叫醒 SystemUI 侧的 hook，省得为了配置去轮询 */
    const val ACTION_CONF_CHANGED = "com.lyricpatch.CONFIG_CHANGED"

    /** 探测 hook 是否还活着：发 ping，hook 侧会回写一次报活 */
    const val ACTION_PING = "com.lyricpatch.PING"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"

    // JSON 顶层字段（hook 侧 Util.parseConf 认同一套）
    const val JSON_SCHEMA = "schema"
    const val JSON_APP_VERSION = "appVersion"
    const val JSON_UPDATED_AT = "updatedAt"
    const val JSON_VALUES = "values"

    private val META_KEYS = setOf(JSON_SCHEMA, JSON_APP_VERSION, JSON_UPDATED_AT, JSON_VALUES)

    /**
     * 发 ping → 等一下 → 看有没有回写。
     * 回写的时间戳必须晚于发 ping 的时刻，才算“真的在”。
     */
    suspend fun isActive(context: Context, waitMs: Long = 450L): Boolean {
        val start = SystemClock.elapsedRealtime()
        runCatching {
            context.sendBroadcast(Intent(ACTION_PING).setPackage(SYSTEMUI_PACKAGE))
        }
        delay(waitMs)
        return ConfigProvider.lastReportAt >= start
    }

    /** 写回文件时的顺序（键名声明在 ConfKeys） */
    val ORDER = listOf(
        ConfKeys.PROVIDERS,
        ConfKeys.HIDE_INSTRUMENTAL,
        ConfKeys.TRANSLATION, ConfKeys.TRANSLATION_PROVIDERS, ConfKeys.MACHINE_TRANSLATION,
        ConfKeys.HIDE_EMPTY_TRANSLATION, ConfKeys.HIDE_TRANSLATION_ALWAYS,
        ConfKeys.SKIP_TRANSLATION_FOR_CHINESE,
        ConfKeys.PARSE_WORD_FORMATS, ConfKeys.KUGOU_WORD_TIMING, ConfKeys.ESTIMATE_WORD_TIMING,
        ConfKeys.PREFER_NETWORK_WORD_TIMING, ConfKeys.SYNTH_WORD_TIMING,
        ConfKeys.AUTO_WORD_TIMING, ConfKeys.WORD_TIMING_MAX_CHARS,
        ConfKeys.WORD_SCROLL, ConfKeys.WORD_SCROLL_FOLLOW, ConfKeys.WORD_SCROLL_START_PCT,
        ConfKeys.WORD_SCROLL_SPEED, ConfKeys.WORD_SCROLL_DELAY,
        ConfKeys.APP_WHITELIST, ConfKeys.APP_BLACKLIST,
        ConfKeys.LOG_LEVEL, ConfKeys.DEBUG_LOG,
    )

    /** 和模块代码里的默认值保持一致；所有键都要在这里登记 */
    val DEFAULTS: Map<String, String> = linkedMapOf(
        ConfKeys.PROVIDERS to "netease,lrclib",
        ConfKeys.HIDE_INSTRUMENTAL to "true",
        ConfKeys.TRANSLATION to "true",
        ConfKeys.TRANSLATION_PROVIDERS to "netease",
        ConfKeys.MACHINE_TRANSLATION to "auto",
        ConfKeys.HIDE_EMPTY_TRANSLATION to "true",
        ConfKeys.HIDE_TRANSLATION_ALWAYS to "false",
        ConfKeys.SKIP_TRANSLATION_FOR_CHINESE to "true",
        ConfKeys.PARSE_WORD_FORMATS to "true",
        ConfKeys.KUGOU_WORD_TIMING to "true",
        ConfKeys.ESTIMATE_WORD_TIMING to "true",
        ConfKeys.PREFER_NETWORK_WORD_TIMING to "false",
        ConfKeys.SYNTH_WORD_TIMING to "false",
        ConfKeys.AUTO_WORD_TIMING to "true",
        ConfKeys.WORD_TIMING_MAX_CHARS to "0",
        ConfKeys.WORD_SCROLL to "true",
        ConfKeys.WORD_SCROLL_FOLLOW to "true",
        ConfKeys.WORD_SCROLL_START_PCT to "0",
        ConfKeys.WORD_SCROLL_SPEED to "0",
        ConfKeys.WORD_SCROLL_DELAY to "500",
        ConfKeys.APP_WHITELIST to "",
        ConfKeys.APP_BLACKLIST to "",
        ConfKeys.LOG_LEVEL to "info",
        ConfKeys.DEBUG_LOG to "false",
    )

    class LoadResult(
        val values: Map<String, String>,
        val path: String,
        val updatedAt: Long,
        /** 非空 = 这次读到的是旧版 .conf，调用方应尽快写一份 JSON 并删掉旧文件 */
        val migratedFrom: String? = null,
        /** 读到了但数据有问题时给页面看的提示 */
        val notice: String? = null,
    )

    class SaveResult(
        val ok: Boolean,
        val path: String?,
        val message: String,
        val updatedAt: Long = 0L,
    )

    /** Provider 给 SystemUI 的原始文本 */
    class RawFile(
        val text: String,
        val exists: Boolean,
        val mtime: Long,
        val json: Boolean,
    )

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun legacyFile(context: Context): File = File(context.filesDir, LEGACY_FILE_NAME)

    fun load(context: Context): LoadResult {
        val target = file(context)
        val legacy = legacyFile(context)

        if (target.isFile) {
            val text = runCatching { target.readText() }.getOrNull()
            val parsed = text?.let { runCatching { parseJson(it) }.getOrNull() }
            if (parsed != null) {
                return LoadResult(
                    values = normalize(parsed.values),
                    path = target.absolutePath,
                    updatedAt = parsed.updatedAt,
                )
            }
            // JSON 坏了：先用旧 conf 兜底显示，但不自动覆盖，免得把用户手改的内容冲掉
            val fallback = if (legacy.isFile) {
                runCatching { parseProperties(legacy.readText()) }.getOrNull()
            } else {
                null
            }
            return LoadResult(
                values = normalize(fallback.orEmpty()),
                path = target.absolutePath,
                updatedAt = 0L,
                notice = "配置文件不是有效 JSON，已按默认值显示；修改任意项会覆盖原文件",
            )
        }

        if (legacy.isFile) {
            val values = runCatching { parseProperties(legacy.readText()) }.getOrDefault(emptyMap())
            return LoadResult(
                values = normalize(values),
                path = target.absolutePath,
                updatedAt = legacy.lastModified(),
                migratedFrom = legacy.absolutePath,
            )
        }

        return LoadResult(normalize(emptyMap()), target.absolutePath, 0L)
    }

    /**
     * 先写 .tmp、fsync 再 rename：SystemUI 那边随时可能来要一份，
     * 不能让它读到写了一半的文件（rename 是原子的）。
     */
    fun save(context: Context, values: Map<String, String>): SaveResult {
        val target = file(context)
        val clean = normalize(values)
        val now = System.currentTimeMillis()
        val content = serialize(clean, appVersion(context), now)

        return try {
            val dir = target.parentFile ?: context.filesDir
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            } else {
                // rename 成功：旧 tmp 不残留
                if (tmp.exists()) tmp.delete()
            }
            broadcastChanged(context)
            SaveResult(true, target.absolutePath, "", now)
        } catch (t: Throwable) {
            SaveResult(false, null, t.message ?: t.javaClass.simpleName)
        }
    }

    /** 恢复默认值：写一份只含默认键的 JSON（未知键也会被清掉） */
    fun reset(context: Context): SaveResult = save(context, DEFAULTS)

    fun deleteLegacy(context: Context) {
        runCatching { legacyFile(context).delete() }
    }

    /**
     * SystemUI 通过 Provider 拿到的原始文本：优先 JSON；
     * JSON 不存在或不合法时退回旧 conf，避免一次手误让 SystemUI 读到空配置。
     */
    fun rawText(context: Context): RawFile {
        val json = file(context)
        if (json.isFile) {
            val text = runCatching { json.readText() }.getOrDefault("")
            val valid = runCatching {
                val parsed = parseJson(text)
                if (parsed == null) error("empty")
            }.isSuccess
            if (valid || !legacyFile(context).isFile) {
                return RawFile(text, true, json.lastModified(), true)
            }
        }
        val legacy = legacyFile(context)
        if (legacy.isFile) {
            val text = runCatching { legacy.readText() }.getOrDefault("")
            return RawFile(text, true, legacy.lastModified(), false)
        }
        return RawFile("", false, 0L, true)
    }

    /** 补齐缺省键 + 去掉空键名；所有值都保持字符串，SystemUI 侧不用区分类型 */
    fun normalize(values: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in ORDER) out[key] = values[key] ?: DEFAULTS[key].orEmpty()
        for ((key, value) in values) {
            if (key.isBlank() || out.containsKey(key)) continue
            out[key] = value
        }
        return out
    }

    private class Parsed(val values: Map<String, String>, val updatedAt: Long)

    /**
     * 兼容两种写法：
     *  - 标准结构：`{"values": {...}}`
     *  - 手写简化：顶层直接放键值（跳过 schema / updatedAt 等元数据）
     */
    private fun parseJson(text: String): Parsed? {
        if (text.isBlank()) return null
        val root = JSONObject(text)
        val nested = root.optJSONObject(JSON_VALUES)
        val source = nested ?: root
        val out = LinkedHashMap<String, String>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (nested == null && key in META_KEYS) continue
            out[key] = jsonText(source.opt(key))
        }
        return Parsed(out, root.optLong(JSON_UPDATED_AT, 0L))
    }

    private fun jsonText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is Boolean -> if (value) "true" else "false"
        is Number -> {
            val d = value.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else value.toString()
        }
        else -> value.toString()
    }

    /** 旧版 key=value：不用 Properties.load，省得值里的反斜杠/冒号被当转义 */
    private fun parseProperties(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return out
    }

    fun serialize(
        values: Map<String, String>,
        appVersion: String = "",
        updatedAt: Long = System.currentTimeMillis(),
    ): String {
        val clean = normalize(values)
        val root = JSONObject()
        root.put(JSON_SCHEMA, SCHEMA)
        root.put(JSON_APP_VERSION, appVersion)
        root.put(JSON_UPDATED_AT, updatedAt)
        val body = JSONObject()
        for ((key, value) in clean) body.put(key, value)
        root.put(JSON_VALUES, body)
        return root.toString(2) + "\n"
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun broadcastChanged(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION_CONF_CHANGED).setPackage(SYSTEMUI_PACKAGE))
        }
    }
}

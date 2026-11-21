package com.lyricpatch.ui

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 配置存在模块 App 自己的内部存储：
 *   /data/user/0/com.lyricpatch/files/lyricpatch.conf
 *
 * 不碰 /data/system、/data/local/tmp，不需要 root。
 * SystemUI 那边（同一个 APK 被注入进去的 hook 代码）通过 ConfigProvider 读同一份，
 * 所以跳过了“两个进程各自读文件”的权限问题。
 */
object ConfigStore {

    const val FILE_NAME = "lyricpatch.conf"
    const val AUTHORITY = "com.lyricpatch.config"
    const val METHOD_READ = "read"

    /** 保存后拿这个广播叫醒 SystemUI 侧的 hook，省得为了配置去轮询 */
    const val ACTION_CONF_CHANGED = "com.lyricpatch.CONFIG_CHANGED"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"

    /** 写回文件时的顺序，和 README 的分组一致 */
    val ORDER = listOf(
        "providers",
        "translation", "translation_providers", "machine_translation",
        "hide_empty_translation", "hide_translation_always", "skip_translation_for_chinese",
        "parse_word_formats", "kugou_word_timing", "estimate_word_timing",
        "prefer_network_word_timing", "synth_word_timing", "auto_word_timing",
        "word_timing_max_chars",
        "word_scroll", "word_scroll_speed", "word_scroll_delay",
        "app_whitelist", "app_blacklist",
        "log_level", "debug_log",
    )

    /** 和模块代码里的默认值保持一致 */
    val DEFAULTS: Map<String, String> = linkedMapOf(
        "providers" to "netease,lrclib",
        "translation" to "true",
        "translation_providers" to "netease",
        "machine_translation" to "auto",
        "hide_empty_translation" to "true",
        "skip_translation_for_chinese" to "true",
        "parse_word_formats" to "true",
        "kugou_word_timing" to "true",
        "estimate_word_timing" to "true",
        "prefer_network_word_timing" to "false",
        "synth_word_timing" to "false",
        "auto_word_timing" to "true",
        "word_timing_max_chars" to "0",
        "word_scroll" to "true",
        "word_scroll_speed" to "0",
        "word_scroll_delay" to "500",
        "debug_log" to "false",
    )

    class LoadResult(val values: Map<String, String>, val path: String)

    class SaveResult(val ok: Boolean, val path: String?, val message: String)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): LoadResult {
        val target = file(context)
        val text = runCatching {
            if (target.isFile) target.readText() else ""
        }.getOrDefault("")
        return LoadResult(parse(text), target.absolutePath)
    }

    /**
     * 先写 .tmp 再 rename：SystemUI 那边随时可能来要一份，
     * 不能让它读到写了一半的文件（rename 是原子的）。
     */
    fun save(context: Context, values: Map<String, String>): SaveResult {
        val target = file(context)
        val content = serialize(values)
        return try {
            val dir = target.parentFile ?: context.filesDir
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
            // SystemUI 那边收到就立刻重读，不用等轮询
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_CONF_CHANGED).setPackage(SYSTEMUI_PACKAGE),
                )
            }
            SaveResult(true, target.absolutePath, "")
        } catch (t: Throwable) {
            SaveResult(false, null, t.message ?: t.javaClass.simpleName)
        }
    }

    fun parse(text: String): Map<String, String> {
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

    fun serialize(values: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("# LyricPatch 配置（「歌词补丁」App 生成，手改也行）\n")
        sb.append("# 改完不用重启 SystemUI，下次取词就生效\n\n")
        val written = HashSet<String>()
        for (key in ORDER) {
            written.add(key)
            val v = values[key]
            if (!v.isNullOrBlank()) sb.append(key).append('=').append(v).append('\n')
        }
        // 手写在文件里的其它键也留着，不要写没了
        for ((k, v) in values) {
            if (k in written || v.isBlank()) continue
            sb.append(k).append('=').append(v).append('\n')
        }
        return sb.toString()
    }
}

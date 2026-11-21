package com.lyricpatch.ui

import android.content.Context
import android.content.Intent
import com.lyricpatch.lib.ConfKeys
import android.os.SystemClock
import kotlinx.coroutines.delay
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
    const val METHOD_REPORT = "report"
    const val METHOD_STATUS = "status"

    /** 保存后拿这个广播叫醒 SystemUI 侧的 hook，省得为了配置去轮询 */
    const val ACTION_CONF_CHANGED = "com.lyricpatch.CONFIG_CHANGED"

    /** 探测 hook 是否还活着：发 ping，hook 侧会回写一次报活 */
    const val ACTION_PING = "com.lyricpatch.PING"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"

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

    /** 和模块代码里的默认值保持一致 */
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
        ConfKeys.DEBUG_LOG to "false",
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

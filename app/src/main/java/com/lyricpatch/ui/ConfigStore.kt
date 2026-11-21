package com.lyricpatch.ui

import android.util.Base64
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * lyricpatch.conf 的读写。
 *
 * App 自己是普通 uid：/data/system 和 /data/local/tmp 都进不去，所以读写全走 su。
 * 没 root 时不假装成功，界面只显示默认值，保存会直接报错。
 */
object ConfigStore {

    const val SYSTEM_PATH = "/data/system/lyricpatch.conf"
    const val TMP_PATH = "/data/local/tmp/lyricpatch.conf"

    /** 写回文件时的顺序，和 README 里的分组一致 */
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
        "word_scroll_speed" to "4",
        "word_scroll_delay" to "500",
        "debug_log" to "false",
    )

    class Shell(val code: Int, val out: String) {
        val ok: Boolean get() = code == 0
    }

    class LoadResult(val values: Map<String, String>, val path: String?, val root: Boolean)

    class SaveResult(val ok: Boolean, val path: String?, val message: String)

    /** 跑一条 root 命令；拿不到 root / 超时都返回 code = -1 */
    fun su(command: String, timeoutMs: Long = 10000L): Shell {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Shell(process.exitValue(), text)
            } else {
                process.destroy()
                Shell(-1, text)
            }
        } catch (t: Throwable) {
            Shell(-1, "")
        }
    }

    fun hasRoot(): Boolean = su("id", 4000L).out.contains("uid=0")

    /** 两个位置都看一眼，挑修改时间新的那个（避免读到旧副本） */
    fun load(): LoadResult {
        val root = hasRoot()
        var best: Triple<String, String, Long>? = null
        for (path in listOf(SYSTEM_PATH, TMP_PATH)) {
            val text = read(path, root)
            if (text.isBlank()) continue
            val mtime = if (root) {
                su("stat -c %Y $path 2>/dev/null", 4000L).out.trim().toLongOrNull() ?: 0L
            } else {
                0L
            }
            val current = best
            if (current == null || mtime > current.third) best = Triple(path, text, mtime)
        }
        val found = best
        return LoadResult(found?.let { parse(it.second) } ?: emptyMap(), found?.first, root)
    }

    private fun read(path: String, root: Boolean): String {
        if (root) return su("cat $path 2>/dev/null", 4000L).out
        return runCatching { File(path).readText() }.getOrDefault("")
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

    /**
     * 写回配置：优先写已经在用的那个文件；一个都没有就两个都写
     * （/data/local/tmp 是不用折腾 SELinux 就能被 SystemUI 读到的保底位置）。
     */
    fun save(values: Map<String, String>, currentPath: String?): SaveResult {
        val content = serialize(values)
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val existing = ArrayList<String>()
        for (path in listOfNotNull(currentPath, SYSTEM_PATH, TMP_PATH).distinct()) {
            if (su("test -f $path && echo Y", 4000L).out.contains("Y")) existing.add(path)
        }
        val targets = if (existing.isEmpty()) listOf(TMP_PATH, SYSTEM_PATH) else existing

        // 两个文件都在的话两边一起写：模块是按「先 /data/system」读的，
        // 只写 tmp 的话旧的那份会把新配置盖掉
        var primary: String? = null
        var lastError = "写入失败"
        for (target in targets) {
            val write = su(
                "mkdir -p \$(dirname $target) && echo $b64 | base64 -d > $target " +
                    "&& chmod 644 $target && echo WROTE",
                12000L,
            )
            if (!write.out.contains("WROTE")) {
                lastError = write.out.trim().ifEmpty { "su 没有响应（root 未授权？）" }
                continue
            }
            // base64 是不丢字节的，回读对不上就说明写坏了
            if (su("cat $target", 6000L).out.trim() != content.trim()) {
                lastError = "回读校验对不上"
                continue
            }
            if (primary == null) primary = target
        }
        return if (primary != null) SaveResult(true, primary, primary)
        else SaveResult(false, null, lastError)
    }

    /** 重启 SystemUI：想马上看到改动时用（不重启也只是等下次取词） */
    fun restartSystemUi(): Boolean {
        return su("kill \$(pidof com.android.systemui) 2>/dev/null; true", 6000L).ok
    }
}

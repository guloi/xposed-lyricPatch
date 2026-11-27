package com.lyricpatch.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 查 GitHub 上的版本有没有更新。
 *
 * - 正式版：比对 latest release 的 tag 与当前 versionName
 * - ci：ci 是个会被反复覆盖的预发布，没有版本号可比，
 *   只能拿它的 updated_at 和「当前 APK 的安装时间」比，安装之后又有 push 就算有新版
 */
object UpdateChecker {

    private const val BASE =
        "https://api.github.com/repos/guloi/xposed-lyricPatch/releases"
    private const val LATEST = "$BASE/latest"
    private const val LIST = "$BASE?per_page=10"

    data class Release(val tag: String, val pageUrl: String) {
        val isCi: Boolean get() = tag == "ci"
    }

    /** 有更新才返回，否则 null */
    suspend fun check(current: String, installedAt: Long, includeCi: Boolean): Release? =
        withContext(Dispatchers.IO) {
            if (includeCi) {
                val ci = checkCi(current, installedAt)
                if (ci != null) return@withContext ci
            }
            checkLatest(current)
        }

    private fun checkLatest(current: String): Release? {
        val json = getJson(LATEST, current) ?: return null
        return runCatching {
            val o = JSONObject(json)
            val tag = o.optString("tag_name", "")
            val page = o.optString("html_url", "")
            val version = tag.removePrefix("v")
            if (version.isEmpty() || page.isEmpty()) null
            else if (isNewer(version, current)) Release(tag, page) else null
        }.getOrNull()
    }

    private fun checkCi(current: String, installedAt: Long): Release? {
        if (installedAt <= 0L) return null
        val json = getJson(LIST, current) ?: return null
        return runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("tag_name") != "ci") continue
                val page = o.optString("html_url", "")
                val updated = parseIso(o.optString("updated_at", ""))
                if (page.isEmpty() || updated <= 0L) return null
                return if (updated > installedAt) Release("ci", page) else null
            }
            null
        }.getOrNull()
    }

    private fun getJson(url: String, current: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LyricPatch/$current")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun parseIso(text: String): Long {
        if (text.isEmpty()) return 0L
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(text)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** a 是否比 b 新：按 . 分段比数字，非数字段按字符串比 */
    fun isNewer(a: String, b: String): Boolean {
        val left = a.split('.', '-', '_')
        val right = b.split('.', '-', '_')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)?.toIntOrNull()
            val y = right.getOrNull(i)?.toIntOrNull()
            if (x != null && y != null) {
                if (x != y) return x > y
            } else {
                val sx = left.getOrNull(i).orEmpty()
                val sy = right.getOrNull(i).orEmpty()
                if (sx != sy) return sx > sy
            }
        }
        return false
    }
}

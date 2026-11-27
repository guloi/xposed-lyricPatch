package com.lyricpatch.lib

/**
 * 模块用到的外部歌词接口。
 *
 * 只放“地址 / 参数 / 字段名”这类外部契约，请求、重试、解析仍然留在各自的源类里：
 *  - [NeteaseApi]     网易云
 *  - [LrcLibApi]      LRCLIB（网易云源里当备用源用）
 *  - [QQMusicApi]     QQ 音乐
 *  - [KugouApi]       酷狗（KRC 逐字）
 *  - [HttpLyricApi]   自定义 HTTP 源 / 翻译源共用的协议
 *  - [TranslationApi] 自定义翻译 URL 的占位符
 *
 * 改了接口地址、加了参数，只需要动这个文件。Java 侧直接引用这些 `const val`
 *（Kotlin 会编译成 public static final 字段）。
 */

object NeteaseApi {
    const val REFERER = "https://music.163.com/"

    const val SEARCH =
        "https://music.163.com/api/search/get?s=%s&type=1&offset=0&limit=10"

    const val LYRIC =
        "https://music.163.com/api/song/lyric?id=%s&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1"

    /** 新版歌词接口：部分歌只有它才返回逐字 yrc / 逐字翻译 ytlrc */
    const val LYRIC_V1 =
        "https://music.163.com/api/song/lyric/v1?id=%s&cp=false" +
            "&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1&ytv=-1&yrv=-1"

    /** PC 客户端参数：部分歌的 yrc 只在这里给 */
    const val LYRIC_PC =
        "https://music.163.com/api/song/lyric?os=pc&id=%s&lv=-1&kv=0&tv=-1&yv=-1"

    const val DETAIL = "https://music.163.com/api/song/detail?ids=%5B%s%5D"
}

object LrcLibApi {
    const val GET =
        "https://lrclib.net/api/get?track_name=%s&artist_name=%s&album_name=%s&duration=%d"
    const val SEARCH = "https://lrclib.net/api/search?q=%s"
}

object QQMusicApi {
    const val REFERER = "https://y.qq.com/"

    const val SEARCH =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
            "?w=%s&format=json&p=1&n=10&cr=1&aggr=1&lossless=0"

    const val LYRIC =
        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=%s&format=json&nobase64=1&g_tk=5381"
}

object KugouApi {
    const val REFERER = "https://www.kugou.com/"

    const val SEARCH =
        "https://krcs.kugou.com/search" +
            "?ver=1&man=yes&client=mobi&keyword=%s&duration=%d&hash="

    const val DOWNLOAD =
        "https://lyrics.kugou.com/download" +
            "?ver=1&client=pc&id=%s&accesskey=%s&fmt=krc&charset=utf8"
}

/**
 * 自定义 HTTP 歌词源用的协议，和 ROM 内置 HttpLyricSource 一致：
 *
 *   GET {base}/v1/lyrics?title=&artist=&album=&durationMs=&sourcePackage=&mediaId=
 *
 * 响应既可以是 `{"lrc":"…","tlyric":"…","yrc":"…"}`，也可以是嵌套的
 * `{"lrc":{"lyric":"…"}}`。翻译源复用同一套查询串与字段名。
 */
object HttpLyricApi {
    /** 完整查询串；参数顺序固定，换行/空格都交给调用方先 URL 编码 */
    const val QUERY =
        "/v1/lyrics?title=%s&artist=%s&album=%s&durationMs=%d" +
            "&sourcePackage=%s&mediaId=%s"

    const val KEY_LRC = "lrc"
    const val KEY_YRC = "yrc"
    const val KEY_TLYRIC = "tlyric"
    const val KEY_LYRIC = "lyric"
    const val KEY_ORIGINAL = "original"
    const val KEY_TRANSLATION = "translation"
    const val KEY_TRANSLATED = "translated"
}

/** 自定义翻译 URL 可用的占位符；只给 base 时走 [HttpLyricApi.QUERY] */
object TranslationApi {
    const val PLACEHOLDER_TITLE = "{title}"
    const val PLACEHOLDER_ARTIST = "{artist}"
    const val PLACEHOLDER_ALBUM = "{album}"
    const val PLACEHOLDER_DURATION_MS = "{durationMs}"
    const val PLACEHOLDER_MEDIA_ID = "{mediaId}"
    const val PLACEHOLDER_SOURCE_PACKAGE = "{sourcePackage}"
}

/** providers / translation_providers 里的内置源名字（大小写不敏感） */
object Providers {
    const val NETEASE = "netease"
    const val LRCLIB = "lrclib"
    const val QQ = "qq"
    const val KUGOU = "kugou"

    const val HTTP_PREFIX = "http://"
    const val HTTPS_PREFIX = "https://"

    @JvmStatic
    fun isHttp(value: String): Boolean =
        value.startsWith(HTTP_PREFIX) || value.startsWith(HTTPS_PREFIX)

    @JvmStatic
    fun isNetease(value: String): Boolean = NETEASE.equals(value, ignoreCase = true)

    @JvmStatic
    fun isLrcLib(value: String): Boolean = LRCLIB.equals(value, ignoreCase = true)

    @JvmStatic
    fun isQQ(value: String): Boolean = QQ.equals(value, ignoreCase = true)

    @JvmStatic
    fun isKugou(value: String): Boolean = KUGOU.equals(value, ignoreCase = true)
}

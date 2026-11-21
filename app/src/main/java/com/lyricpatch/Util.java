package com.lyricpatch;

import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** 网络 / 字符串归一化 / 配置 工具类 */
public final class Util {

    static final String TAG = "LyricPatch";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Mobile Safari/537.36";

    /** 可选配置，按顺序取第一个能读到的（都不存在就用默认值） */
    private static final String[] CONF_PATHS = {
            "/data/system/lyricpatch.conf",
            "/data/local/tmp/lyricpatch.conf"
    };

    private static Properties sConf;
    private static long sConfMtime = Long.MIN_VALUE;
    private static boolean sConfLogged;

    private Util() {
    }

    // ───────────────────────────── 日志 ─────────────────────────────

    /** 日志级别，数值和 android.util.Log 一致，0 = 静默 */
    static final int LOG_SILENT = 0;
    static final int LOG_ERROR = 1;
    static final int LOG_WARN = 2;
    static final int LOG_INFO = 3;
    static final int LOG_DEBUG = 4;
    static final int LOG_VERBOSE = 5;

    private static volatile int sLogLevel = LOG_INFO;
    private static long sLogLevelAt;
    private static boolean sLogBusy;
    private static final Map<String, Long> RATE_LIMIT = new ConcurrentHashMap<>();

    /**
     * 当前级别。配置是热读的，但每帧都去 stat 一遍文件太亏，所以 3 秒才看一眼。
     * log_level 不写时退回老开关：debug_log=true 等价于 debug。
     */
    static int logLevel() {
        long now = SystemClock.uptimeMillis();
        if (sLogLevelAt != 0 && now - sLogLevelAt < 3000L) return sLogLevel;
        sLogLevelAt = now;

        int level = parseLogLevel(conf("log_level", null));
        if (level < 0) level = confBool("debug_log", false) ? LOG_DEBUG : LOG_INFO;
        if (level != sLogLevel) {
            sLogLevel = level;
            Log.i(TAG, "log_level -> " + levelName(level));
        }
        return level;
    }

    private static int parseLogLevel(String v) {
        if (v == null) return -1;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "s": case "silent": case "off": case "none": case "0":
                return LOG_SILENT;
            case "e": case "error": case "1":
                return LOG_ERROR;
            case "w": case "warn": case "warning": case "2":
                return LOG_WARN;
            case "i": case "info": case "3":
                return LOG_INFO;
            case "d": case "debug": case "4":
                return LOG_DEBUG;
            case "v": case "verbose": case "all": case "5":
                return LOG_VERBOSE;
            default:
                return -1;
        }
    }

    static String levelName(int level) {
        switch (level) {
            case LOG_SILENT: return "silent";
            case LOG_ERROR: return "error";
            case LOG_WARN: return "warn";
            case LOG_DEBUG: return "debug";
            case LOG_VERBOSE: return "verbose";
            default: return "info";
        }
    }

    /** 字符串拼接有代价：这种级别的日志记得先用它挡一下 */
    static boolean isDebug() {
        return logLevel() >= LOG_DEBUG;
    }

    static boolean isVerbose() {
        return logLevel() >= LOG_VERBOSE;
    }

    /** 高频路径（每次取词 / 每帧重绘）用它节流，顺便当去重：同 key 窗口内只放行一次 */
    static boolean allow(String key, long windowMs) {
        long now = SystemClock.uptimeMillis();
        Long last = RATE_LIMIT.get(key);
        if (last != null && now - last < windowMs) return false;
        if (RATE_LIMIT.size() > 128) RATE_LIMIT.clear();
        RATE_LIMIT.put(key, now);
        return true;
    }

    static void v(String msg) {
        write(LOG_VERBOSE, msg, null);
    }

    static void d(String msg) {
        write(LOG_DEBUG, msg, null);
    }

    static void i(String msg) {
        write(LOG_INFO, msg, null);
    }

    static void w(String msg) {
        write(LOG_WARN, msg, null);
    }

    static void w(String msg, Throwable t) {
        write(LOG_WARN, msg, t);
    }

    static void e(String msg) {
        write(LOG_ERROR, msg, null);
    }

    static void e(String msg, Throwable t) {
        write(LOG_ERROR, msg, t);
    }

    /** 老调用点兼容：以前的 log() 算 debug，logw() 算 warn */
    static void log(String msg) {
        write(LOG_DEBUG, msg, null);
    }

    static void logw(String msg, Throwable t) {
        write(LOG_WARN, msg, t);
    }

    private static void write(int level, String msg, Throwable t) {
        // 配置读失败时 conf() 会反过来打日志 → 这里必须防递归
        if (sLogBusy) return;
        sLogBusy = true;
        try {
            if (level > logLevel()) return;
            switch (level) {
                case LOG_VERBOSE:
                    Log.v(TAG, nz(msg));
                    break;
                case LOG_DEBUG:
                    Log.d(TAG, nz(msg));
                    break;
                case LOG_WARN:
                    Log.w(TAG, withCause(msg, t), stackOf(t));
                    break;
                case LOG_ERROR:
                    Log.e(TAG, withCause(msg, t), stackOf(t));
                    break;
                default:
                    Log.i(TAG, nz(msg));
                    break;
            }
        } catch (Throwable ignored) {
            // 日志不能把业务流程搞挂
        } finally {
            sLogBusy = false;
        }
    }

    /** 默认级别只留一行摘要，debug 级别才把整个堆栈丢给 logcat */
    private static String withCause(String msg, Throwable t) {
        if (t == null || sLogLevel >= LOG_DEBUG) return nz(msg);
        String cause = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + brief(t.getMessage(), 120));
        return nz(msg) + " (" + cause + ")";
    }

    private static Throwable stackOf(Throwable t) {
        return t != null && sLogLevel >= LOG_DEBUG ? t : null;
    }

    /** 启用时打一行，排查先看这条就知道现在是哪套配置 */
    static String summary() {
        return "level=" + levelName(logLevel())
                + " providers=" + conf("providers", "netease,lrclib")
                + " word_scroll=" + (confBool("word_scroll", true) ? "on" : "off")
                + " translation=" + (confBool("translation", true) ? "on" : "off")
                + " whitelist=" + brief(conf("app_whitelist", ""), 32);
    }

    /** 截断 + 把换行拍平，logcat 里一行看得完 */
    static String brief(String s, int max) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static synchronized Properties conf() {
        try {
            File found = null;
            for (String path : CONF_PATHS) {
                File f = new File(path);
                if (f.canRead()) {
                    found = f;
                    break;
                }
            }
            long mtime = found == null ? -1L : found.lastModified();
            if (sConf != null && mtime == sConfMtime) return sConf;

            Properties p = new Properties();
            if (found != null) {
                try (FileInputStream in = new FileInputStream(found)) {
                    p.load(in);
                }
            }
            sConf = p;
            sConfMtime = mtime;
            if (!sConfLogged) {
                sConfLogged = true;
                log("conf: " + (found == null ? "not found (" + CONF_PATHS[0] + ")"
                        : "loaded " + found.getAbsolutePath())
                        + " keys=" + p.size());
            }
        } catch (Throwable t) {
            logw("read conf failed", t);
            if (sConf == null) sConf = new Properties();
        }
        return sConf;
    }

    static String conf(String key, String def) {
        String v = conf().getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    static int confInt(String key, int def) {
        try {
            return Integer.parseInt(conf(key, String.valueOf(def)).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean confBool(String key, boolean def) {
        String v = conf().getProperty(key);
        if (v == null) return def;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    /** 简单 GET，返回 UTF-8 文本（超过 512KB 截断） */
    static String get(String url, String referer) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json,text/plain,*/*");
            if (referer != null) c.setRequestProperty("Referer", referer);
            c.setInstanceFollowRedirects(true);

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new java.io.IOException("HTTP " + code);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 512 * 1024) break;
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString("UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 粗归一化：全角/括号内容/feat·live 等修饰/标点/空白全部干掉 */
    static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT)
                .replace('（', '(').replace('）', ')')
                .replace('【', '[').replace('】', ']')
                .replace('：', ':').replace('　', ' ');
        t = t.replaceAll("\\([^)]*\\)", " ").replaceAll("\\[[^\\]]*\\]", " ");
        t = t.replaceAll("(?i)\\b(feat|ft|featuring|remix|version|live|cover|"
                + "instrumental|inst)\\b\\.?", " ");
        t = t.replaceAll("[-－—~·,，、。!！?？'\"“”‘’/\\\\|&＋+*=]", " ");
        return t.replaceAll("\\s+", "");
    }

    /**
     * 粗判「歌词本身就是中文」（不需要再翻）：
     *  - 出现平假名/片假名 → 日文歌词，需要翻译
     *  - 无假名、汉字 ≥ 8 且明显多于拉丁字母 → 当中文处理
     * 中文歌去翻译源搜时，很容易把同名/近似歌的 tlyric 错贴上来，所以直接跳过。
     */
    static boolean looksChinese(String s) {
        if (isEmpty(s)) return false;

        int cjk = 0;
        int kana = 0;
        int latin = 0;
        int n = Math.min(s.length(), 4000);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= '\u3040' && c <= '\u30FF') {
                kana++;                                  // 平假名 / 片假名
            } else if (c >= '\u4E00' && c <= '\u9FFF') {
                cjk++;                                   // 汉字
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;                                 // 拉丁字母
            }
        }
        if (kana > 0) return false;
        return cjk >= 8 && cjk > latin * 2;
    }

    /**
     * 这个 App 的“歌名”要不要处理。
     *
     *   app_whitelist 非空 → 只处理名单内（逗号分隔包名）
     *   app_blacklist 非空 → 名单内一律跳过
     *   拿不到包名 → 放行（宁可多试，不乱拒）
     */
    static boolean pkgAllowed(String pkg) {
        String white = conf("app_whitelist", "");
        String black = conf("app_blacklist", "");

        if (isEmpty(pkg)) {
            // 包名拿不到时：没配白名单就放行；配了白名单则当不命中
            // （否则“拿不到包名”会变成绕过白名单的后门）
            return isEmpty(white);
        }
        if (!isEmpty(white)) return pkgMatches(white, pkg);
        if (!isEmpty(black)) return !pkgMatches(black, pkg);
        return true;
    }

    /** 支持完整包名或前缀，逗号分隔；例：tv.danmaku 能盖住 tv.danmaku.bili */
    private static boolean pkgMatches(String list, String pkg) {
        for (String raw : list.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            if (pkg.equals(entry) || pkg.startsWith(entry)) return true;
        }
        return false;
    }

    /** 纯数字（判断 mediaId 能不能直接当歌曲 id） */    /** 纯数字（判断 mediaId 能不能直接当歌曲 id） */
    static boolean isNumeric(String s) {
        if (isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    static double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return 0.92;
        int d = levenshtein(a, b);
        return 1.0 - (double) d / Math.max(a.length(), b.length());
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}

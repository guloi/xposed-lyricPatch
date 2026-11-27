package com.lyricpatch.lib;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 网络 / 字符串归一化 / 配置 工具类 */
public final class Util {

    static final String TAG = "LyricPatch";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Mobile Safari/537.36";

    /**
     * 配置不再放 /data/system 那种要 root 的地方：
     * 文件在模块 App 自己的内部存储里，这里通过它暴露的 ContentProvider 只读地拿。
     * 不设权限（设了 SystemUI 也拿不到），内容就是歌词源 / 开关，不含隐私。
     */
    private static final String CONF_AUTHORITY = "com.lyricpatch.config";
    private static final Uri CONF_URI = Uri.parse("content://" + CONF_AUTHORITY + "/conf");

    /** 配置变了靠广播通知；这个 TTL 只是兜底（万一广播没收到） */
    private static final long CONF_TTL = 300000L;
    private static final String ACTION_CONF_CHANGED = "com.lyricpatch.CONFIG_CHANGED";
    /** 界面发这个 ping，hook 侧回写一次报活（用来显示“已启用”） */
    private static final String ACTION_PING = "com.lyricpatch.PING";
    private static volatile String sExpVersion = "";
    private static volatile boolean sWatching;
    /** 已注册的配置/PING 接收器：热更新前要摘掉，否则旧代码一直被系统引用着 */
    private static volatile android.content.BroadcastReceiver sWatcher;
    private static final Object CONF_LOCK = new Object();
    private static final Properties EMPTY_CONF = new Properties();
    private static final AtomicBoolean sFetching = new AtomicBoolean();
    private static final ExecutorService CONF_EXEC = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "LyricPatch-conf");
        t.setDaemon(true);
        return t;
    });

    private static volatile Properties sConf;
    /** 0 = 没拿到过 / 已被作废 */
    private static volatile long sConfAt;
    private static volatile boolean sConfLogged;
    private static volatile Context sContext;

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
     * 当前级别。conf() 本身有快照缓存（保存时靠广播刷新），这里再按 3 秒节流一次，
     * 省得每帧绘制都重新解析一遍。log_level 不写时退回老开关：debug_log=true 等价于 debug。
     */
    public static int logLevel() {
        long now = SystemClock.uptimeMillis();
        if (sLogLevelAt != 0 && now - sLogLevelAt < 3000L) return sLogLevel;
        sLogLevelAt = now;

        int level = parseLogLevel(conf(ConfKeys.LOG_LEVEL, null));
        if (level < 0) level = confBool(ConfKeys.DEBUG_LOG, false) ? LOG_DEBUG : LOG_INFO;
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

    public static String levelName(int level) {
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
    public static boolean isDebug() {
        return logLevel() >= LOG_DEBUG;
    }

    public static boolean isVerbose() {
        return logLevel() >= LOG_VERBOSE;
    }

    /** 高频路径（每次取词 / 每帧重绘）用它节流，顺便当去重：同 key 窗口内只放行一次 */
    public static boolean allow(String key, long windowMs) {
        long now = SystemClock.uptimeMillis();
        Long last = RATE_LIMIT.get(key);
        if (last != null && now - last < windowMs) return false;
        if (RATE_LIMIT.size() > 128) RATE_LIMIT.clear();
        RATE_LIMIT.put(key, now);
        return true;
    }

    public static void v(String msg) {
        write(LOG_VERBOSE, msg, null);
    }

    public static void d(String msg) {
        write(LOG_DEBUG, msg, null);
    }

    public static void i(String msg) {
        write(LOG_INFO, msg, null);
    }

    public static void w(String msg) {
        write(LOG_WARN, msg, null);
    }

    public static void w(String msg, Throwable t) {
        write(LOG_WARN, msg, t);
    }

    public static void e(String msg) {
        write(LOG_ERROR, msg, null);
    }

    public static void e(String msg, Throwable t) {
        write(LOG_ERROR, msg, t);
    }

    /** 老调用点兼容：以前的 log() 算 debug，logw() 算 warn */
    public static void log(String msg) {
        write(LOG_DEBUG, msg, null);
    }

    public static void logw(String msg, Throwable t) {
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
    public static String summary() {
        return "level=" + levelName(logLevel())
                + " providers=" + conf(ConfKeys.PROVIDERS, "netease,lrclib")
                + " word_scroll=" + (confBool(ConfKeys.WORD_SCROLL, true) ? "on" : "off")
                + " translation=" + (confBool(ConfKeys.TRANSLATION, true) ? "on" : "off")
                + " whitelist=" + brief(conf(ConfKeys.APP_WHITELIST, ""), 32);
    }

    /** 截断 + 把换行拍平，logcat 里一行看得完 */
    public static String brief(String s, int max) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 让 hook 顺手把 SystemUI 的 Context 递进来（反射拿不到时的兜底） */
    public static void setContext(Context ctx) {
        if (ctx != null && sContext == null) sContext = ctx;
    }

    private static Context appContext() {
        Context ctx = sContext;
        if (ctx != null) return ctx;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getDeclaredMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // 进程还没起来，下次再试
        }
        return null;
    }

    /** 收一次广播就把快照作废，下一次读取重新拉：避免为了拿配置一直轮询 */
    private static void ensureWatcher() {
        if (sWatching) return;
        Context ctx = appContext();
        if (ctx == null) return;
        sWatching = true;
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_CONF_CHANGED);
            filter.addAction(ACTION_PING);
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent intent) {
                    if (intent != null && ACTION_PING.equals(intent.getAction())) {
                        reportAlive();
                    }
                    sConfAt = 0L;          // 0 = 作废，下次读重新拉
                    scheduleFetch();
                }
            };
            sWatcher = receiver;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            sWatcher = null;
            sWatching = false;         // 下次再试
            logw("register conf watcher failed", t);
        }
    }

    /**
     * 热更新前（旧代码里）调一次：把广播接收器摘掉、快照丢掉。
     * 不摘也能跑，只是旧一代会被系统一直引用着，热更新换不下去。
     */
    public static void stopReporting() {
        android.content.BroadcastReceiver receiver = sWatcher;
        sWatcher = null;
        sWatching = false;
        sConf = null;
        sConfAt = 0L;
        Context ctx = sContext;
        if (receiver == null || ctx == null) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
            // 没注册成功或已经被系统摘掉了都无所谓
        }
    }

    /**
     * 当前配置快照。
     *  - 后台线程（取词那一条）且还没快照：直接同步取一次，不卡 UI
     *  - 主线程（onDraw 里问 word_scroll）：绝不阻塞，先用旧快照 + 排一个后台刷新
     */
    /**
     * 模块在 hook 装好后调一次：记住版本号 + 注册 ping 监听（早于第一次取词，界面一开就能问到）。
     * 这里**不主动上报**：那会在每次 SystemUI 启动时把 App 进程拉起来，只为记一个时间戳；
     * 界面打开时会发 ping，收到再回写就够了。
     */
    public static void startReporting(String version) {
        sExpVersion = version == null ? "" : version;
        ensureWatcher();
    }

    /** 告诉模块 App：SystemUI 里的 hook 还活着；界面拿这个显示“已启用” */
    static void reportAlive() {
        Context ctx = appContext();
        if (ctx == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("version", sExpVersion);
            ctx.getContentResolver().call(CONF_URI, "report", null, extras);
        } catch (Throwable ignored) {
            // 界面收不到最多显示未启用，不影响出词
        }
    }

    private static Properties conf() {
        ensureWatcher();
        Properties snapshot = sConf;
        long now = SystemClock.uptimeMillis();
        if (snapshot != null && sConfAt != 0L && now - sConfAt < CONF_TTL) return snapshot;

        if (snapshot == null && Looper.myLooper() != Looper.getMainLooper()) {
            Properties loaded = fetchConf(snapshot);
            return loaded != null ? loaded : EMPTY_CONF;
        }

        scheduleFetch();
        return snapshot != null ? snapshot : EMPTY_CONF;
    }

    private static void scheduleFetch() {
        if (!sFetching.compareAndSet(false, true)) return;
        try {
            CONF_EXEC.execute(() -> {
                try {
                    fetchConf(null);
                } catch (Throwable ignored) {
                    // 取配置失败就当默认值，不影响出词
                } finally {
                    sFetching.set(false);
                }
            });
        } catch (Throwable t) {
            sFetching.set(false);
        }
    }

    private static Properties fetchConf(Properties fallback) {
        synchronized (CONF_LOCK) {
            long now = SystemClock.uptimeMillis();
            Properties current = sConf;
            if (current != null && sConfAt != 0L && now - sConfAt < CONF_TTL) return current;

            Properties loaded = readConf();
            if (loaded != null) {
                sConf = loaded;
                sConfAt = now;
                if (!sConfLogged) {
                    sConfLogged = true;
                    // 把关键键打出来：能直接判断“读到了”还是“读到的是默认/旧值”
                    log("conf: " + loaded.size() + " keys, log_level="
                            + loaded.getProperty(ConfKeys.LOG_LEVEL, "-")
                            + ", providers=" + loaded.getProperty(ConfKeys.PROVIDERS, "-")
                            + ", translation=" + loaded.getProperty(ConfKeys.TRANSLATION, "-"));
                }
                return loaded;
            }
            return fallback != null ? fallback : current;
        }
    }

    /** 向模块 App 的 ConfigProvider 要一份原始文本（空文本 = 还没写过，按默认值） */
    private static Properties readConf() {
        Context ctx = appContext();
        if (ctx == null) return null;
        try {
            Bundle reply = ctx.getContentResolver().call(CONF_URI, "read", null, null);
            if (reply == null) return EMPTY_CONF;
            String text = reply.getString("text");
            if (text == null || text.trim().isEmpty()) return EMPTY_CONF;
            return parseConf(text);
        } catch (Throwable t) {
            logw("read conf from provider failed", t);
            return null;
        }
    }

    /**
     * 配置文本：新版是 JSON（`{"values":{...}}`），旧版是 key=value。
     * 两种都认，所以 App 升级 / 热更新期间不会出现“读不到配置”的空窗。
     */
    private static Properties parseConf(String text) {
        String body = text == null ? "" : text.trim();
        if (body.startsWith("{")) {
            Properties json = parseJsonConf(body);
            if (json != null) return json;
        }
        // 旧版 key=value：不用 Properties.load，省得值里的反斜杠/冒号被当转义
        Properties p = new Properties();
        for (String raw : body.split("\\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            p.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return p;
    }

    /** 解析模块 App 写出的 JSON；顶层没有 values 时也兼容直接铺开的键 */
    private static Properties parseJsonConf(String text) {
        try {
            JSONObject root = new JSONObject(text);
            JSONObject values = root.optJSONObject("values");
            boolean flat = values == null;
            JSONObject source = flat ? root : values;
            Properties p = new Properties();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (flat && ("schema".equals(key) || "appVersion".equals(key)
                        || "updatedAt".equals(key) || "values".equals(key))) {
                    continue;
                }
                Object value = source.opt(key);
                if (value == null || value == JSONObject.NULL) continue;
                p.setProperty(key, jsonText(value).trim());
            }
            return p;
        } catch (Throwable t) {
            logw("parse json conf failed", t);
            return null;
        }
    }

    /** JSON 里的 bool / number 统一成字符串，后面照旧按字符串读 */
    private static String jsonText(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? "true" : "false";
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == (long) d) return Long.toString((long) d);
            return value.toString();
        }
        return String.valueOf(value);
    }

    public static String conf(String key, String def) {
        String v = conf().getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    public static int confInt(String key, int def) {
        try {
            return Integer.parseInt(conf(key, String.valueOf(def)).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean confBool(String key, boolean def) {
        String v = conf().getProperty(key);
        if (v == null) return def;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    /** 简单 GET，返回 UTF-8 文本（超过 512KB 截断） */
    public static String get(String url, String referer) throws Exception {
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
    public static String norm(String s) {
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
    public static boolean looksChinese(String s) {
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
    public static boolean pkgAllowed(String pkg) {
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
    public static boolean isNumeric(String s) {
        if (isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    public static double similarity(String a, String b) {
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

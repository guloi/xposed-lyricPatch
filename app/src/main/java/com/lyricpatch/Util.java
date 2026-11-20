package com.lyricpatch;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Properties;

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

    static void log(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    static void logw(String msg, Throwable t) {
        try {
            Log.w(TAG, msg, t);
        } catch (Throwable ignored) {
        }
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

    /** 纯数字（判断 mediaId 能不能直接当歌曲 id） */
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

package com.lyricpatch;

import com.lyricpatch.lib.ConfKeys;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立翻译源：歌词主源只管出词，翻译从这里单独拿。
 *
 * 配置（/data/local/tmp/lyricpatch.conf，chmod 644）：
 *   translation=true                 翻译总开关，false 就完全不去请求翻译
 *   translation_providers=netease    顺序尝试：netease / qq / http(s) 地址
 *
 * 为什么要单独一路：网易云的 tlyric 覆盖面最好，但它的歌词命中率不一定最好；
 * 反过来 lrclib 对日系/欧美曲库覆盖好，却基本没有翻译。
 * 于是可以做成「歌词走 lrclib，翻译走网易云」。
 *
 * 拿回来的翻译会重新贴到主歌词的时间轴上：跨源时间轴常差几十~几百 ms，
 * 而 ROM 的 findClosestLine 是按时间找最近一行的，对不上就整行没翻译。
 */
public final class TranslationSource {

    /** [mm:ss.xx] / [mm:ss:xx] / [mm:ss] */
    private static final Pattern TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** 跨源对齐时容忍的最大偏差 */
    private static final long ALIGN_WINDOW_MS = 5000L;

    private TranslationSource() {
    }

    /**
     * @param lrcForAlign 主歌词 LRC（用来对齐时间轴）；没有就传 null，那时翻译原样返回
     * @return 对齐后的 tlyric 文本；未开启/取不到返回 null
     */
    public static String resolve(String title, String artist, String album, long durationMs,
                                 String pkg, String mediaId, String lrcForAlign) {
        if (!Util.confBool(ConfKeys.TRANSLATION, true)) return null;

        String order = Util.conf(ConfKeys.TRANSLATION_PROVIDERS, "netease");
        if (Util.isEmpty(order)) return null;

        for (String raw : order.split(",")) {
            String provider = raw.trim();
            if (provider.isEmpty()) continue;
            if (Thread.currentThread().isInterrupted()) return null;
            try {
                String rawTl = fetchOne(provider, title, artist, album, durationMs, pkg, mediaId);
                if (Util.isEmpty(rawTl)) continue;

                String aligned = align(lrcForAlign, rawTl);
                if (Util.isEmpty(aligned)) continue;

                Util.i("tlyric hit(" + provider + "): " + title + " lines=" + lines(aligned));
                return aligned;
            } catch (Throwable t) {
                Util.logw("tlyric " + provider + " failed", t);
            }
        }

        Util.d("tlyric miss: " + title + " / " + artist);
        return null;
    }

    private static String fetchOne(String provider, String title, String artist, String album,
                                   long durationMs, String pkg, String mediaId) throws Exception {
        if ("netease".equalsIgnoreCase(provider)) {
            return Netease.tlyric(title, artist, durationMs, mediaId);
        }
        if ("qq".equalsIgnoreCase(provider)) {
            return QQMusic.tlyric(title, artist, durationMs);
        }
        if (provider.startsWith("http://") || provider.startsWith("https://")) {
            return http(provider, title, artist, album, durationMs, pkg, mediaId);
        }
        Util.w("unknown translation provider: " + provider);
        return null;
    }

    /**
     * 自定义 HTTP 翻译源。两种写法：
     *  1) 带占位符的完整 URL：https://host/tl?t={title}&a={artist}
     *     可用占位符：{title} {artist} {album} {durationMs} {mediaId} {sourcePackage}
     *  2) 只给 base：自动按 ROM 内置 HttpLyricSource 的协议拼 /v1/lyrics?...
     * 响应支持 JSON（tlyric / translation / translated，可嵌套 .lyric）或直接返回 LRC 文本。
     */
    private static String http(String provider, String title, String artist, String album,
                               long durationMs, String pkg, String mediaId) throws Exception {
        String base = provider.trim();
        String url;
        if (base.contains("{")) {
            url = base.replace("{title}", Util.enc(title))
                    .replace("{artist}", Util.enc(nz(artist)))
                    .replace("{album}", Util.enc(nz(album)))
                    .replace("{durationMs}", String.valueOf(Math.max(0, durationMs)))
                    .replace("{mediaId}", Util.enc(nz(mediaId)))
                    .replace("{sourcePackage}", Util.enc(nz(pkg)));
        } else {
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            url = base + "/v1/lyrics?title=" + Util.enc(title)
                    + "&artist=" + Util.enc(nz(artist))
                    + "&album=" + Util.enc(nz(album))
                    + "&durationMs=" + Math.max(0, durationMs)
                    + "&sourcePackage=" + Util.enc(nz(pkg))
                    + "&mediaId=" + Util.enc(nz(mediaId));
        }

        String res = Util.get(url, null);
        if (Util.isEmpty(res)) return null;

        String body = res.trim();
        if (!body.startsWith("{")) return body;    // 直接吐 LRC 也认

        String tl = HttpSource.pick(new JSONObject(body), "tlyric", "translation", "translated");
        return Util.isEmpty(tl) ? null : tl;
    }

    /** 把翻译按时间轴贴到主歌词的时间点上（一对多/多对一都按最近优先） */
    private static String align(String lrc, String tlyric) {
        List<Long> tlTimes = new ArrayList<>();
        List<String> tlTexts = new ArrayList<>();
        parse(tlyric, tlTimes, tlTexts);
        if (tlTexts.isEmpty()) return null;      // 没有时间轴，ROM 也吃不下

        List<Long> lrcTimes = new ArrayList<>();
        List<String> lrcTexts = new ArrayList<>();
        parse(lrc, lrcTimes, lrcTexts);
        if (lrcTimes.isEmpty()) return tlyric;   // 主歌词没轴，原样交回去

        Collections.sort(lrcTimes);

        StringBuilder out = new StringBuilder();
        boolean[] used = new boolean[tlTexts.size()];
        for (long t : lrcTimes) {
            int best = -1;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < tlTimes.size(); i++) {
                if (used[i]) continue;
                long d = Math.abs(tlTimes.get(i) - t);
                if (d < bestDiff) {
                    bestDiff = d;
                    best = i;
                }
            }
            if (best < 0 || bestDiff > ALIGN_WINDOW_MS) continue;
            used[best] = true;
            out.append(fmt(t)).append(tlTexts.get(best)).append('\n');
        }

        if (out.length() == 0) {
            // 一行都没对上：宁可原样交回去让 ROM 自己找最近行，也不要直接丢
            Util.w("tlyric 时间轴对不上，原样交回去 (" + tlTexts.size() + " lines)");
            return tlyric;
        }
        return out.toString();
    }

    /** 解出「时间 + 文本」并行列表；支持一行多个标签 [00:01.00][00:05.00]词 */
    static void parse(String text, List<Long> times, List<String> lines) {
        if (Util.isEmpty(text)) return;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            Matcher m = TAG.matcher(line);
            long last = -1L;
            int end = 0;
            boolean any = false;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String frac = m.group(3);
                long ms = 0L;
                if (frac != null) {
                    if (frac.length() == 1) ms = Long.parseLong(frac) * 100L;
                    else if (frac.length() == 2) ms = Long.parseLong(frac) * 10L;
                    else ms = Long.parseLong(frac.substring(0, 3));
                }
                last = min * 60000L + sec * 1000L + ms;
                end = m.end();
                any = true;
            }
            if (!any) continue;

            String body = line.substring(end).trim();
            if (body.isEmpty()) continue;
            times.add(last);
            lines.add(body);
        }
    }

    private static String fmt(long ms) {
        long total = Math.max(0, ms);
        long min = total / 60000L;
        long sec = (total % 60000L) / 1000L;
        long frac = total % 1000L;
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", min, sec, frac);
    }

    private static int lines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

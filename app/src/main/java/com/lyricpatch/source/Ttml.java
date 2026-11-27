package com.lyricpatch.source;

import com.lyricpatch.lib.Util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AMLL / Apple Music 风格的 TTML 逐字歌词解析（音理用的就是这套）。
 *
 * 简化结构：
 *   <p begin="12.3" end="15.6">
 *     <span begin="12.3" end="13.0">あ</span>
 *     <span begin="13.0" end="13.4">の</span>
 *     <span ttm:role="x-translation">那个</span>
 *   </p>
 *
 * 输出 lrc（行级）、yrc（逐字，转成 ROM 认的格式）、tlyric（行翻译）。
 * 只取带 span 的行：没有逐字的行在 TTML 里通常是标题/说明。
 */
final class Ttml {

    private static final Pattern P =
            Pattern.compile("<p\\b([^>]*)>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern SPAN =
            Pattern.compile("<span\\b([^>]*)>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern ATTR_BEGIN =
            Pattern.compile("\\bbegin\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_END =
            Pattern.compile("\\bend\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_ROLE =
            Pattern.compile("\\b(?:ttm:)?role\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private Ttml() {
    }

    static LyricFetcher.Bundle parse(String xml, boolean withTranslation) {
        if (Util.isEmpty(xml)) return null;
        try {
            StringBuilder lrc = new StringBuilder();
            StringBuilder yrc = new StringBuilder();
            StringBuilder tl = new StringBuilder();
            int lines = 0;

            Matcher pm = P.matcher(xml);
            while (pm.find()) {
                String attrs = pm.group(1);
                String inner = pm.group(2);

                long start = time(attr(ATTR_BEGIN, attrs));
                if (start < 0) continue;
                long end = time(attr(ATTR_END, attrs));

                StringBuilder text = new StringBuilder();
                StringBuilder words = new StringBuilder();
                String translation = null;

                Matcher sm = SPAN.matcher(inner);
                while (sm.find()) {
                    String sAttrs = sm.group(1);
                    String body = plain(sm.group(2));
                    if (body.isEmpty()) continue;

                    String role = attr(ATTR_ROLE, sAttrs);
                    if (role != null && role.contains("translation")) {
                        translation = body;
                        continue;
                    }
                    // 背景人声先不参与逐字，否则高亮会被它抢走
                    if (role != null && (role.contains("bg") || role.contains("background"))) {
                        continue;
                    }

                    long sb = time(attr(ATTR_BEGIN, sAttrs));
                    long se = time(attr(ATTR_END, sAttrs));
                    if (sb < 0) sb = start;
                    if (se <= sb) se = sb + 200L;

                    text.append(body);
                    words.append('(').append(sb).append(',').append(se - sb).append(",0)")
                            .append(body);
                }

                if (words.length() == 0) continue;

                String line = text.toString();
                if (line.isEmpty()) continue;
                if (end <= start) end = start + 3000L;

                lrc.append(fmt(start)).append(line).append('\n');
                yrc.append('[').append(start).append(',').append(Math.max(100L, end - start))
                        .append(']').append(words).append('\n');
                if (withTranslation && translation != null) {
                    tl.append(fmt(start)).append(translation).append('\n');
                }
                lines++;
            }

            if (lines == 0) return null;

            LyricFetcher.Bundle b = new LyricFetcher.Bundle();
            b.lrc = lrc.toString();
            b.yrc = yrc.toString();
            b.tlyric = tl.length() == 0 ? null : tl.toString();
            Util.i("ttml: lines=" + lines + " yrc=" + b.yrc.length());
            return b;
        } catch (Throwable t) {
            Util.logw("ttml parse failed", t);
            return null;
        }
    }

    private static String attr(Pattern p, String attrs) {
        if (attrs == null) return null;
        Matcher m = p.matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    /** 12.345 / 00:00:12.345 / 1:02.5 → ms；解析不了返回 -1 */
    private static long time(String value) {
        if (Util.isEmpty(value)) return -1L;
        String v = value.trim();
        if (v.isEmpty()) return -1L;
        try {
            if (v.indexOf(':') < 0) {
                return Math.round(Double.parseDouble(v) * 1000.0);
            }
            double seconds = 0;
            for (String part : v.split(":")) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Math.round(seconds * 1000.0);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 去掉内部标签和实体，只留可见文字 */
    private static String plain(String html) {
        if (html == null) return "";
        String s = TAG.matcher(html).replaceAll("");
        s = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
        return s.trim();
    }

    private static String fmt(long ms) {
        long total = Math.max(0L, ms);
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]",
                total / 60000L, (total % 60000L) / 1000L, total % 1000L);
    }
}

package com.lyricpatch.source;

import com.lyricpatch.lib.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把其他“真逐字”格式转成 ROM 认的网易云 yrc。
 *
 * 参考音理(NeriPlayer) 的 accompanist-lyrics-core：
 *   EnhancedLrcParser      `[00:12.34]<00:12.34>Hel<00:12.60>lo World`
 *   LyricifySyllableParser `固执(1000,200)美丽(1200,300)`
 * 这两个都是真数据（词级时间），转换后就能走 ROM 的 parseYrc 路径出卡拉OK。
 */
public final class WordFormats {

    /** 转换结果：lrc 是行级歌词，yrc 是逐字 */
    public static final class Result {
        public String lrc;
        public String yrc;
    }

    private static final Pattern TS =
            Pattern.compile("(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?");
    private static final Pattern ANGLE = Pattern.compile("<([^>]+)>([^<]*)");
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[([^\\]]*)]");
    private static final Pattern LYR_SYL = Pattern.compile("(.*?)\\((\\d+),(\\d+)\\)",
            Pattern.DOTALL);
    private static final Pattern LYR_ATTR = Pattern.compile("^\\[(\\d+)]");

    private WordFormats() {
    }

    /** @return 识别不出来返回 null */
    public static Result convert(String raw) {
        if (Util.isEmpty(raw)) return null;
        if (isLyricify(raw)) return convertLyricify(raw);
        if (isEnhancedLrc(raw)) return convertEnhancedLrc(raw);
        return null;
    }

    // ---------------------------------------------------------------- 识别

    private static boolean isEnhancedLrc(String raw) {
        return raw.contains("<") && Pattern.compile("<\\d{1,3}:\\d{1,2}[.:]\\d{1,3}>")
                .matcher(raw).find();
    }

    private static boolean isLyricify(String raw) {
        // 至少两处 `词(起,长)` 才认，避免普通歌词里的括号数字误判
        Matcher m = Pattern.compile("\\S\\(\\d+,\\d+\\)").matcher(raw);
        int n = 0;
        while (m.find()) {
            if (++n >= 2) return true;
        }
        return false;
    }

    // ---------------------------------------------------------- Enhanced LRC

    private static Result convertEnhancedLrc(String raw) {
        StringBuilder lrc = new StringBuilder();
        StringBuilder yrc = new StringBuilder();
        int lines = 0;

        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // 行首的 [mm:ss.xx] 标签（可能多个，[bg:..]/[ti:..] 不算）
            int idx = 0;
            long first = -1L;
            Matcher tag = BRACKET_TAG.matcher(line);
            while (tag.find() && tag.start() == idx) {
                String inner = tag.group(1).trim();
                long t = parseTs(inner);
                if (t < 0) break;
                if (first < 0) first = t;
                idx = tag.end();
            }
            if (first < 0) continue;

            String content = line.substring(idx).trim();
            if (content.isEmpty()) continue;

            List<long[]> syl = new ArrayList<>();
            List<String> texts = new ArrayList<>();

            Matcher m = ANGLE.matcher(content);
            while (m.find()) {
                long t = parseTs(m.group(1).trim());
                String text = m.group(2);
                if (t < 0 || text.isEmpty()) continue;
                syl.add(new long[]{t, 0L});
                texts.add(text);
            }

            // 方括号变体：[00:12.34][00:12.34]Hel[00:12.60]lo
            if (syl.isEmpty()) {
                Matcher b = BRACKET_TAG.matcher(content);
                int last = 0;
                long pending = -1L;
                while (b.find()) {
                    String between = content.substring(last, b.start());
                    if (pending >= 0L && !between.isEmpty()) {
                        syl.add(new long[]{pending, 0L});
                        texts.add(between);
                        pending = -1L;
                    }
                    long t = parseTs(b.group(1).trim());
                    if (t >= 0) pending = t;
                    last = b.end();
                }
                String tail = content.substring(last);
                if (pending >= 0L && !tail.isEmpty()) {
                    syl.add(new long[]{pending, 0L});
                    texts.add(tail);
                }
            }

            if (syl.isEmpty()) continue;
            fillDurations(syl, first);
            append(lrc, yrc, first, syl, texts);
            lines++;
        }

        if (lines == 0) return null;
        return result(lrc, yrc);
    }

    // ------------------------------------------------------ Lyricify Syllable

    private static Result convertLyricify(String raw) {
        StringBuilder lrc = new StringBuilder();
        StringBuilder yrc = new StringBuilder();
        int lines = 0;

        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher attr = LYR_ATTR.matcher(line);
            if (attr.find()) line = line.substring(attr.end()).trim();
            if (line.isEmpty()) continue;

            List<long[]> syl = new ArrayList<>();
            List<String> texts = new ArrayList<>();

            Matcher m = LYR_SYL.matcher(line);
            while (m.find()) {
                String text = m.group(1);
                long start;
                long dur;
                try {
                    start = Long.parseLong(m.group(2));
                    dur = Long.parseLong(m.group(3));
                } catch (Throwable t) {
                    continue;
                }
                if (text.isEmpty()) continue;
                syl.add(new long[]{start, Math.max(40L, dur)});
                texts.add(text);
            }
            if (syl.isEmpty()) continue;

            long first = syl.get(0)[0];
            append(lrc, yrc, first, syl, texts);
            lines++;
        }

        if (lines == 0) return null;
        return result(lrc, yrc);
    }

    // ------------------------------------------------------------------ 工具

    /** Enhanced LRC 只有开始时间：用下一个词的开始时间作为本词结束 */
    private static void fillDurations(List<long[]> syl, long lineStart) {
        for (int i = 0; i < syl.size(); i++) {
            long[] cur = syl.get(i);
            long start = cur[0];
            // 词时间比行首还早 = 相对时间
            if (start < lineStart) start = lineStart + start;
            cur[0] = start;

            if (cur[1] > 0L) continue;
            long end = i + 1 < syl.size() ? syl.get(i + 1)[0] : start + 600L;
            if (end <= start) end = start + 200L;
            cur[1] = end - start;
        }
    }

    private static void append(StringBuilder lrc, StringBuilder yrc, long lineStart,
                              List<long[]> syl, List<String> texts) {
        long begin = Math.max(0L, syl.get(0)[0]);
        long end = begin;
        StringBuilder text = new StringBuilder();
        StringBuilder words = new StringBuilder();

        for (int i = 0; i < syl.size(); i++) {
            long start = Math.max(0L, syl.get(i)[0]);
            long dur = Math.max(40L, syl.get(i)[1]);
            String s = texts.get(i);
            text.append(s);
            words.append('(').append(start).append(',').append(dur).append(",0)").append(s);
            end = Math.max(end, start + dur);
        }

        lrc.append(fmt(begin)).append(text).append('\n');
        yrc.append('[').append(begin).append(',').append(Math.max(200L, end - begin)).append(']')
                .append(words).append('\n');
    }

    private static Result result(StringBuilder lrc, StringBuilder yrc) {
        Result r = new Result();
        r.lrc = lrc.toString();
        r.yrc = yrc.toString();
        return r;
    }

    private static long parseTs(String s) {
        Matcher m = TS.matcher(s);
        if (!m.find()) return -1L;
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        String frac = m.group(3);
        long ms = 0L;
        if (frac != null) {
            if (frac.length() == 1) ms = Long.parseLong(frac) * 100L;
            else if (frac.length() == 2) ms = Long.parseLong(frac) * 10L;
            else ms = Long.parseLong(frac.substring(0, 3));
        }
        return min * 60000L + sec * 1000L + ms;
    }

    private static String fmt(long ms) {
        long total = Math.max(0L, ms);
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]",
                total / 60000L, (total % 60000L) / 1000L, total % 1000L);
    }
}

package com.lyricpatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 没拿到真逐字歌词时，按行时间轴「合成」一份逐字数据，让状态栏至少有动画。
 *
 * 输出格式必须和 ROM 的 LyricResponseParser#parseYrc 完全一致：
 *   [行开始ms,行时长ms](词开始ms,词时长ms,0)词...
 *   行正则 \[(\d+),(\d+)\](.*)，词正则 \((\d+),(\d+),\d+\)([^\(]*)
 * 且同一行所有词拼起来必须原样等于该行原文（ROM 用 words 拼出 cue.text）。
 */
public final class WordTiming {

    private WordTiming() {
    }

    /** @return 合成的 yrc 文本；行数不足/内容空返回 null */
    public static String synth(String lrc) {
        if (Util.isEmpty(lrc)) return null;

        List<Long> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        TranslationSource.parse(lrc, times, texts);
        if (texts.size() < 2) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            long begin = Math.max(0L, times.get(i));
            long end = i + 1 < texts.size() ? times.get(i + 1) : begin + 5000L;
            long dur = end - begin;
            if (dur < 600L) dur = 600L;
            if (dur > 12000L) dur = 12000L;

            List<String> words = split(texts.get(i));
            if (words.isEmpty()) continue;

            int total = 0;
            for (String w : words) total += weight(w);
            if (total <= 0) continue;

            sb.append('[').append(begin).append(',').append(dur).append(']');
            long cursor = begin;
            for (String w : words) {
                long wd = Math.max(40L, dur * weight(w) / total);
                sb.append('(').append(cursor).append(',').append(wd).append(",0)").append(w);
                cursor += wd;
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 中文/假名按字切，拉丁按词切，空格单独成词 */
    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                flush(out, latin);
                out.add(String.valueOf(c));
            } else if (c == ' ') {
                flush(out, latin);
                out.add(" ");
            } else {
                latin.append(c);
            }
        }
        flush(out, latin);
        return out;
    }

    private static void flush(List<String> out, StringBuilder latin) {
        if (latin.length() > 0) {
            out.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3040' && c <= '\u30FF');
    }

    private static int weight(String w) {
        if (w.isEmpty()) return 0;
        char c = w.charAt(0);
        if (isCjk(c)) return 2;
        if (c == ' ') return 1;
        return Math.max(1, w.length());
    }
}

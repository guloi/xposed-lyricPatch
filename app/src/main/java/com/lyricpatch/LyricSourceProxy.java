package com.lyricpatch;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 用动态代理实现 SystemUI 里的 LyricSource 接口，无需编译期依赖 */
public final class LyricSourceProxy implements InvocationHandler {

    private static final String CLS_SOURCE =
            "com.android.systemui.statusbar.phone.LyricSource";
    private static final String CLS_PARSER =
            "com.android.systemui.statusbar.phone.LyricResponseParser";
    private static final String CLS_CUE =
            "com.android.systemui.statusbar.phone.LyricSource$Cue";
    private static final String CLS_LYRICS =
            "com.android.systemui.statusbar.phone.LyricSource$Lyrics";

    /** 跨源贴翻译时容忍的最大偏差（ROM 自己是 1000ms） */
    private static final long MATCH_WINDOW_MS = 1500L;

    private static Object sSource;
    private static Method sParseLyricText;
    private static Method sParseLyrics;
    private static Constructor<?> sCueCtor;
    private static Constructor<?> sLyricsCtor;

    private LyricSourceProxy() {
    }

    public static synchronized Object get(ClassLoader cl) {
        if (sSource != null) return sSource;
        try {
            Class<?> sourceClass = Class.forName(CLS_SOURCE, false, cl);
            Class<?> parserClass = Class.forName(CLS_PARSER, false, cl);

            sParseLyricText = parserClass.getMethod("parseLyricText", String.class);
            sParseLyrics = parserClass.getMethod("parseLyrics", JSONObject.class);

            // 自己重建 Cue / Lyrics 要用（贴翻译兜底）
            sCueCtor = Class.forName(CLS_CUE, false, cl)
                    .getConstructor(long.class, String.class, String.class, List.class);
            sLyricsCtor = Class.forName(CLS_LYRICS, false, cl)
                    .getConstructor(TreeMap.class, String.class);

            ClassLoader target = sourceClass.getClassLoader();
            if (target == null) target = cl;

            sSource = Proxy.newProxyInstance(target, new Class<?>[]{sourceClass},
                    new LyricSourceProxy());
            Util.log("LyricSource proxy created");
        } catch (Throwable t) {
            Util.logw("create LyricSource proxy failed", t);
        }
        return sSource;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("fetch".equals(name)) return fetch(firstArg(args), false);
        if ("fetchEnhanced".equals(name)) return fetch(firstArg(args), true);
        if ("toString".equals(name)) return "LyricPatchSource";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == firstArg(args);
        return null;
    }

    private static Object firstArg(Object[] args) {
        return args == null || args.length == 0 ? null : args[0];
    }

    /** enhanced = true 表示 ROM 的逐字轮（fetchEnhanced） */
    private static Object fetch(Object track, boolean enhanced) {
        try {
            if (track == null || Thread.currentThread().isInterrupted()) return null;

            String title = str(track, "title");
            String artist = str(track, "artist");
            String album = str(track, "album");
            String pkg = str(track, "packageName");
            String mediaId = str(track, "mediaId");
            long duration = lng(track, "durationMs");

            if (Util.isEmpty(title)) {
                Util.log("skip(no title): pkg=" + pkg + " mediaId=" + mediaId);
                return null;
            }
            Util.log((enhanced ? "track(E): " : "track: ")
                    + "title=" + title + " | artist=" + artist + " | album=" + album
                    + " | dur=" + duration + " | pkg=" + pkg + " | mediaId=" + mediaId);

            LyricFetcher.Bundle bundle =
                    LyricFetcher.resolve(title, artist, album, duration, pkg, mediaId);
            if (bundle == null) {
                Util.log((enhanced ? "miss(E): " : "miss: ")
                        + title + " / " + artist + " dur=" + duration + " pkg=" + pkg);
                return null;
            }

            Object lyrics = build(bundle, enhanced);
            if (lyrics == null) {
                Util.log("parse failed: " + title);
                return null;
            }

            String langSample = !Util.isEmpty(bundle.lrc) ? bundle.lrc
                    : (!Util.isEmpty(bundle.yrc) ? bundle.yrc : cueText(lyrics));
            boolean chineseLyric = Util.looksChinese(langSample);

            if (chineseLyric) {
                // 中文歌词：不管翻译从哪来（源自带 / 错贴 / ROM 解析），一律抹掉
                lyrics = stripTranslations(lyrics);
            } else {
                // ROM 的 findClosestLine 只认 ±1000ms，跨源翻译常被整行丢掉；我们自己重贴一遍
                lyrics = attachTranslations(lyrics, bundle.tlyric);
            }

            // 诊断：拿到 tlyric 但解析后一行翻译都没贴上，就是时间轴对不上
            int cues = cueCount(lyrics);
            int translated = translatedCount(lyrics);
            Util.log((enhanced ? "parsed(E): " : "parsed: ") + title
                    + " cues=" + cues + " translated=" + translated
                    + " (bundle tl=" + len(bundle.tlyric) + ")");
            if (translated == 0 && len(bundle.tlyric) > 0) {
                Util.log("WARN: tlyric 拿到了，但解析后一行翻译都没贴上（findClosestLine 限制 1s）");
            }
            if (!enhanced) {
                Util.log("hit: " + title + " / " + artist
                        + " lrc=" + len(bundle.lrc)
                        + " yrc=" + len(bundle.yrc)
                        + " tl=" + len(bundle.tlyric));
            }
            // 逐字轮没有 word timing 就返回 null，让 ROM 走行级那一轮
            if (enhanced && !hasWordTiming(lyrics)) return null;
            return lyrics;
        } catch (Throwable t) {
            Util.logw("fetch failed", t);
            return null;
        }
    }

    private static Object build(LyricFetcher.Bundle bundle, boolean enhanced) throws Exception {
        if (Util.isEmpty(bundle.lrc) && Util.isEmpty(bundle.yrc)) return null;

        JSONObject json = new JSONObject();
        json.put("lrc", new JSONObject().put("lyric", nz(bundle.lrc)));
        json.put("tlyric", new JSONObject().put("lyric", nz(bundle.tlyric)));
        json.put("yrc", new JSONObject().put("lyric", enhanced ? nz(bundle.yrc) : ""));

        Object lyrics = sParseLyrics.invoke(null, json);
        if (lyrics == null) lyrics = sParseLyricText.invoke(null, nz(bundle.lrc));
        return lyrics;
    }

    /** 把前几行原文拼起来（bundle.lrc 缺失时用来判语言） */
    private static String cueText(Object lyrics) {
        Object cuesObj = field(lyrics, "mCues");
        if (!(cuesObj instanceof Map)) return null;
        StringBuilder sb = new StringBuilder();
        try {
            for (Object cue : ((Map<?, ?>) cuesObj).values()) {
                String t = readString(cue, "text");
                if (t == null) continue;
                sb.append(t).append('\n');
                if (sb.length() > 2000) break;
            }
        } catch (Throwable ignored) {
            return sb.toString();
        }
        return sb.toString();
    }

    /** 把每行的 translatedText 全置空（中文歌词不需要翻译） */
    private static Object stripTranslations(Object lyrics) {
        if (lyrics == null || sCueCtor == null || sLyricsCtor == null) return lyrics;
        try {
            Object cuesObj = field(lyrics, "mCues");
            if (!(cuesObj instanceof TreeMap)) return lyrics;

            boolean any = false;
            TreeMap<Long, Object> out = new TreeMap<>();
            for (Object cue : ((TreeMap<?, ?>) cuesObj).values()) {
                long ts = readLong(cue, "timestampMs");
                String text = readString(cue, "text");
                String tr = readString(cue, "translatedText");
                List<?> words = readList(cue, "words");
                if (!Util.isEmpty(tr)) any = true;
                out.put(ts, sCueCtor.newInstance(ts, text, null, words));
            }

            if (!any) return lyrics;   // 本来就没翻译，不用重建

            Util.log("中文歌词，抹掉 " + out.size() + " 行的翻译");
            return sLyricsCtor.newInstance(out, field(lyrics, "mUnsyncedLyrics"));
        } catch (Throwable t) {
            Util.logw("strip translations failed", t);
            return lyrics;
        }
    }

    /**
     * 自己把 tlyric 贴到 cue 上。
     *
     * ROM 的 LyricResponseParser#findClosestLine 只认最近一行偏差 ≤1000ms，
     * 跨源（lrclib 出词 + 网易云出翻译）差一点就整行丢；这里用 1.5s 窗口重贴，
     * 实在对不上但行数一致时按行号硬对。已有翻译的行不动。
     */
    private static Object attachTranslations(Object lyrics, String tlyric) {
        if (lyrics == null || Util.isEmpty(tlyric)) return lyrics;
        if (sCueCtor == null || sLyricsCtor == null) return lyrics;

        try {
            List<Long> tlTimes = new ArrayList<>();
            List<String> tlTexts = new ArrayList<>();
            TranslationSource.parse(tlyric, tlTimes, tlTexts);
            if (tlTexts.isEmpty()) return lyrics;

            Object cuesObj = field(lyrics, "mCues");
            if (!(cuesObj instanceof TreeMap)) return lyrics;

            List<Object> cues = new ArrayList<>(((TreeMap<?, ?>) cuesObj).values());
            if (cues.isEmpty()) return lyrics;

            boolean[] used = new boolean[tlTexts.size()];
            List<Object> rebuilt = new ArrayList<>(cues.size());
            int existing = 0;
            int matched = 0;

            for (Object cue : cues) {
                long ts = readLong(cue, "timestampMs");
                String text = readString(cue, "text");
                String tr = readString(cue, "translatedText");
                List<?> words = readList(cue, "words");

                if (!Util.isEmpty(tr)) {
                    existing++;
                } else {
                    int best = nearestIndex(tlTimes, used, ts);
                    if (best >= 0) {
                        used[best] = true;
                        tr = tlTexts.get(best);
                        matched++;
                    }
                }
                rebuilt.add(sCueCtor.newInstance(ts, text, tr, words));
            }

            if (matched == 0 && existing == 0 && tlTexts.size() == cues.size()) {
                // 同一首曲子的不同版本：行数一致就按行号对
                rebuilt.clear();
                for (int i = 0; i < cues.size(); i++) {
                    Object cue = cues.get(i);
                    rebuilt.add(sCueCtor.newInstance(readLong(cue, "timestampMs"),
                            readString(cue, "text"), tlTexts.get(i), readList(cue, "words")));
                }
                matched = cues.size();
                Util.log("attach: index fallback " + matched + " lines");
            }

            if (matched == 0 && existing == 0) {
                Util.log("attach: 0/" + cues.size() + " 未能在 ±" + MATCH_WINDOW_MS + "ms 内对上");
                return lyrics;
            }

            Util.log("attach: matched=" + matched + " existing=" + existing
                    + " total=" + cues.size());

            TreeMap<Long, Object> out = new TreeMap<>();
            for (Object cue : rebuilt) {
                out.put(readLong(cue, "timestampMs"), cue);
            }
            return sLyricsCtor.newInstance(out, field(lyrics, "mUnsyncedLyrics"));
        } catch (Throwable t) {
            Util.logw("attach translations failed", t);
            return lyrics;
        }
    }

    private static int nearestIndex(List<Long> times, boolean[] used, long target) {
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < times.size(); i++) {
            if (used[i]) continue;
            long d = Math.abs(times.get(i) - target);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return bestDiff <= MATCH_WINDOW_MS ? best : -1;
    }

    private static long readLong(Object target, String name) {
        Object v = field(target, name);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static String readString(Object target, String name) {
        Object v = field(target, name);
        return v instanceof String ? (String) v : null;
    }

    private static List<?> readList(Object target, String name) {
        Object v = field(target, name);
        return v instanceof List ? (List<?>) v : null;
    }

    /** Lyrics#mCues 的行数 */
    private static int cueCount(Object lyrics) {
        Object cues = field(lyrics, "mCues");
        return cues instanceof Map ? ((Map<?, ?>) cues).size() : 0;
    }

    /** 有多少行的 translatedText 非空 */
    private static int translatedCount(Object lyrics) {
        Object cues = field(lyrics, "mCues");
        if (!(cues instanceof Map)) return 0;
        int n = 0;
        try {
            for (Object cue : ((Map<?, ?>) cues).values()) {
                Object t = field(cue, "translatedText");
                if (t instanceof CharSequence && t.toString().trim().length() > 0) n++;
            }
        } catch (Throwable ignored) {
            // 读不到就当没有
        }
        return n;
    }

    /** 反射读字段（Lyrics / Cue 都是 SystemUI 的类；沿继承链找，避免子类拿不到父类字段） */
    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {
                    // 往父类找
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static boolean hasWordTiming(Object lyrics) {
        try {
            Method m = lyrics.getClass().getMethod("hasWordTiming");
            Object v = m.invoke(lyrics);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String str(Object o, String field) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(o);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long lng(Object o, String field) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.getLong(o);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}

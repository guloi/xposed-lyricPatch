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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String CLS_WORD =
            "com.android.systemui.statusbar.phone.LyricSource$Word";

    /** 跨源贴翻译时容忍的最大偏差（ROM 自己是 1000ms） */
    private static final long MATCH_WINDOW_MS = 1500L;

    private static Object sSource;
    private static Method sParseLyricText;
    private static Method sParseLyrics;
    private static Constructor<?> sCueCtor;
    private static Constructor<?> sLyricsCtor;
    private static Constructor<?> sWordCtor;

    /** 复用的空歌词实例，用来阻断 ROM 的源链 */
    private static Object sEmptyLyrics;

    private LyricSourceProxy() {
    }

    public static synchronized Object get(ClassLoader cl) {
        if (sSource != null) return sSource;
        try {
            Class<?> sourceClass;
            Class<?> parserClass;
            try {
                sourceClass = Class.forName(CLS_SOURCE, false, cl);
                parserClass = Class.forName(CLS_PARSER, false, cl);
            } catch (Throwable t) {
                Util.e("proxy fail @load classes", t);
                return null;
            }

            try {
                sParseLyricText = parserClass.getMethod("parseLyricText", String.class);
                sParseLyrics = parserClass.getMethod("parseLyrics", JSONObject.class);
            } catch (Throwable t) {
                Util.e("proxy fail @parser methods", t);
                return null;
            }

            // 自己重建 Cue / Lyrics 要用（贴翻译兜底）。
            // 单独 try：拿不到构造器也只是关掉“翻译重贴”，不能因此让整个代理变成 null，
            // 否则我们的源根本不会进列表，而且一条日志都没有。
            try {
                sCueCtor = Class.forName(CLS_CUE, false, cl)
                        .getConstructor(long.class, String.class, String.class, List.class);
                sLyricsCtor = Class.forName(CLS_LYRICS, false, cl)
                        .getConstructor(TreeMap.class, String.class);
                sWordCtor = Class.forName(CLS_WORD, false, cl)
                        .getConstructor(long.class, long.class, String.class);
            } catch (Throwable t) {
                sCueCtor = null;
                sLyricsCtor = null;
                Util.w("Cue/Lyrics 构造器拿不到，翻译重贴关掉", t);
            }

            ClassLoader target = sourceClass.getClassLoader();
            if (target == null) target = cl;

            try {
                sSource = Proxy.newProxyInstance(target, new Class<?>[]{sourceClass},
                        new LyricSourceProxy());
            } catch (Throwable t) {
                Util.e("proxy fail @newProxyInstance", t);
                return null;
            }
            Util.i("LyricSource proxy 就绪 (rewrite=" + (sCueCtor != null) + ")");
        } catch (Throwable t) {
            Util.e("create LyricSource proxy failed", t);
        }
        return sSource;
    }

    /**
     * 把 ROM 自带的源包一层：
     *  - 它返回的歌词带逐字 → 原样放行
     *  - 不带逐字（或直接 null）→ 换成我们自己的结果（含 yrc）
     *
     * 这样无论 ROM 到底调不调“我们插进去的那个源”，只要它在这个列表里取词，
     * 就一定会经过我们的拦截。
     */
    public static Object wrap(ClassLoader cl, Object original) {
        if (original == null) return null;
        try {
            Class<?> sourceClass = Class.forName(CLS_SOURCE, false, cl);
            ClassLoader target = sourceClass.getClassLoader();
            if (target == null) target = cl;
            return Proxy.newProxyInstance(target, new Class<?>[]{sourceClass},
                    new Wrapper(original));
        } catch (Throwable t) {
            Util.logw("wrap source failed", t);
            return original;
        }
    }

    /** 给外部（模块）用：这个 Lyrics 带词级时间吗 */
    public static boolean resultHasWords(Object lyrics) {
        return hasWords(lyrics);
    }

    /**
     * 只把「逐字」贴到已有歌词上：文本、翻译全保留 ROM 那份，
     * 用 yrc 里时间最近的行（±1.5s）给每个 cue 补 words。
     * 这样即使我们没匹配到同一份歌词，也能借到别人的逐字。
     */
    public static Object mergeWords(Object lyrics, String yrc) {
        if (lyrics == null || Util.isEmpty(yrc)) return null;
        if (sCueCtor == null || sLyricsCtor == null || sWordCtor == null) return null;

        try {
            List<Long> times = new ArrayList<>();
            List<long[]> spans = new ArrayList<>();
            parseYrc(yrc, times, spans);
            if (times.isEmpty()) return null;

            Object cuesObj = field(lyrics, "mCues");
            if (!(cuesObj instanceof TreeMap)) return null;

            boolean[] used = new boolean[times.size()];
            TreeMap<Long, Object> out = new TreeMap<>();
            int matched = 0;

            for (Object cue : ((TreeMap<?, ?>) cuesObj).values()) {
                long ts = readLong(cue, "timestampMs");
                String text = readString(cue, "text");
                String tr = readString(cue, "translatedText");
                Object words = readList(cue, "words");

                int best = -1;
                long bestDiff = Long.MAX_VALUE;
                for (int i = 0; i < times.size(); i++) {
                    if (used[i]) continue;
                    long d = Math.abs(times.get(i) - ts);
                    if (d < bestDiff) {
                        bestDiff = d;
                        best = i;
                    }
                }
                if (best >= 0 && bestDiff <= MATCH_WINDOW_MS) {
                    used[best] = true;
                    long[] span = spans.get(best);
                    words = spreadWords(text, span[0], span[1]);
                    matched++;
                }
                out.put(ts, sCueCtor.newInstance(ts, text, tr, words));
            }

            if (matched == 0) return null;
            Util.d("mergeWords: " + matched + "/" + out.size() + " 行贴上了逐字");
            return sLyricsCtor.newInstance(out, field(lyrics, "mUnsyncedLyrics"));
        } catch (Throwable t) {
            Util.logw("mergeWords failed", t);
            return null;
        }
    }

    /**
     * 没有真逐字时，按“行时间 + 字符切分”估算一份 words（参考音理的做法）：
     * 每字时长限在 80~220ms，行尾空白不拉长扫光，观感比“整行均分”自然很多。
     */
    public static Object estimateWords(Object lyrics) {
        if (lyrics == null || sCueCtor == null || sLyricsCtor == null || sWordCtor == null) {
            return null;
        }
        try {
            Object cuesObj = field(lyrics, "mCues");
            if (!(cuesObj instanceof TreeMap)) return null;

            List<Object> cues = new ArrayList<>(((TreeMap<?, ?>) cuesObj).values());
            if (cues.isEmpty()) return null;

            TreeMap<Long, Object> out = new TreeMap<>();
            int made = 0;

            for (int i = 0; i < cues.size(); i++) {
                Object cue = cues.get(i);
                long ts = readLong(cue, "timestampMs");
                String text = readString(cue, "text");
                String tr = readString(cue, "translatedText");
                List<?> words = readList(cue, "words");

                if ((words == null || words.isEmpty()) && !Util.isEmpty(text)) {
                    long next = i + 1 < cues.size()
                            ? readLong(cues.get(i + 1), "timestampMs") : ts + 4000L;
                    long lineDur = Math.max(600L, next - ts);
                    int chars = text.codePointCount(0, text.length());

                    // 只有超过该字数的行才不估（0 = 不限）。
                    //  ROM 的逐字自绘本身不带跑马灯，长句会显示不全 ——
                    //  这一点已经由 LyricScroll 在 onDraw 外面补上了，所以这个闸门
                    //  现在只是兼容老配置 / 想让短句才有动画时用，建议留 0。
                    int maxChars = Util.confInt("word_timing_max_chars", 0);
                    if (maxChars > 0 && chars > maxChars) {
                        out.put(ts, sCueCtor.newInstance(ts, text, tr, words));
                        continue;
                    }

                    long per = Math.max(80L, Math.min(220L, lineDur / Math.max(1, chars)));

                    List<Object> ws = new ArrayList<>();
                    int idx = 0;
                    for (int off = 0; off < text.length(); ) {
                        int cp = text.codePointAt(off);
                        int n = Character.charCount(cp);
                        String ch = text.substring(off, off + n);
                        long st = ts + idx * per;
                        ws.add(sWordCtor.newInstance(st, st + per, ch));
                        idx++;
                        off += n;
                    }
                    words = ws;
                    made++;
                }

                out.put(ts, sCueCtor.newInstance(ts, text, tr, words));
            }

            if (made == 0) return lyrics;
            Util.d("estimateWords: " + made + "/" + out.size() + " 行估算了逐字");
            return sLyricsCtor.newInstance(out, field(lyrics, "mUnsyncedLyrics"));
        } catch (Throwable t) {
            Util.logw("estimateWords failed", t);
            return null;
        }
    }

    /**
     * yrc → 每行开始时间 + 该行的 [起,止] 时间跨度。
     * 只拿时间，不拿字样：字样永远用原句，避免“别人的歌词被画出来”。
     */
    private static void parseYrc(String yrc, List<Long> times, List<long[]> spans) {
        Pattern linePat = Pattern.compile("^\\[(\\d+),\\s*(\\d+)]\\s*(.*)$");
        Pattern wordPat = Pattern.compile("\\((\\d+),\\s*(\\d+),\\s*-?\\d+\\)([^()\\r\\n]*)");

        for (String raw : yrc.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("{")) continue;

            Matcher lm = linePat.matcher(s);
            if (!lm.matches()) continue;

            long begin;
            try {
                begin = Long.parseLong(lm.group(1));
            } catch (Throwable t) {
                continue;
            }
            long first = -1L;
            long last = -1L;
            Matcher wm = wordPat.matcher(lm.group(3));
            while (wm.find()) {
                try {
                    long st = Long.parseLong(wm.group(1));
                    long dur = Long.parseLong(wm.group(2));
                    if (first < 0) first = st;
                    last = st + dur;
                } catch (Throwable ignored) {
                    // 单个词坏了就跳过
                }
            }
            if (first < 0 || last <= first) continue;

            times.add(begin);
            spans.add(new long[]{first, last});
        }
    }

    /**
     * 把 [start,end] 均分到原句的每个字符上。
     * 字样直接用原句的字符，所以画出来一定和歌词完全一致（不会截断/串字）。
     */
    private static List<Object> spreadWords(String text, long start, long end) {
        List<Object> out = new ArrayList<>();
        if (Util.isEmpty(text) || sWordCtor == null) return out;

        int chars = text.codePointCount(0, text.length());
        if (chars <= 0) return out;

        long span = Math.max(200L, end - start);
        long per = Math.max(60L, span / chars);

        int idx = 0;
        for (int off = 0; off < text.length(); ) {
            int cp = text.codePointAt(off);
            int n = Character.charCount(cp);
            String ch = text.substring(off, off + n);
            long st = start + idx * per;
            try {
                out.add(sWordCtor.newInstance(st, st + per, ch));
            } catch (Throwable ignored) {
                // 单个字坏了就跳过
            }
            idx++;
            off += n;
        }
        return out;
    }

    /** 给外部（模块）用：按 Track 拿我们自己的歌词（含 yrc） */    /** 给外部（模块）用：按 Track 拿我们自己的歌词（含 yrc） */
    public static Object fetchFor(Object track, boolean enhanced) {
        return fetch(track, enhanced);
    }

    /**
     * 给外部（模块）用：不依赖 Track 类，直接按字段取词。
     * 用于“最终 Lyrics 没逐字”时在控制层直接替换。
     */
    public static Object fetchValues(String title, String artist, String album, long durationMs,
                                    String pkg, String mediaId, boolean enhanced) {
        try {
            if (Util.isEmpty(title)) return null;
            LyricFetcher.Bundle bundle = LyricFetcher.resolve(title, artist, album,
                    durationMs, pkg, mediaId);
            if (bundle == null) return null;

            Object lyrics = build(bundle, enhanced);
            if (lyrics == null) return null;

            String sample = !Util.isEmpty(bundle.lrc) ? bundle.lrc
                    : (!Util.isEmpty(bundle.yrc) ? bundle.yrc : cueText(lyrics));
            if (Util.looksChinese(sample)) {
                lyrics = stripTranslations(lyrics);
            } else {
                lyrics = attachTranslations(lyrics, bundle.tlyric);
            }
            return lyrics;
        } catch (Throwable t) {
            Util.logw("fetchValues failed", t);
            return null;
        }
    }

    /** 歌词里是否已经有词级时间（cue.words 非空） */
    private static boolean hasWords(Object lyrics) {
        Object cues = field(lyrics, "mCues");
        if (!(cues instanceof Map)) return false;
        try {
            for (Object cue : ((Map<?, ?>) cues).values()) {
                Object w = field(cue, "words");
                if (w instanceof List && !((List<?>) w).isEmpty()) return true;
            }
        } catch (Throwable ignored) {
            // 读不到就当没有
        }
        return false;
    }

    private static final class Wrapper implements InvocationHandler {
        private final Object inner;

        Wrapper(Object inner) {
            this.inner = inner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            Object track = firstArg(args);
            // 每次源调用都会进来，只在 verbose 下打，而且同一个方法名 3 秒最多一条
            if (Util.isVerbose() && Util.allow("wrap.invoke:" + name, 3000L)) {
                Util.v("wrap.invoke: " + name);
            }

            if ("fetch".equals(name) || "fetchEnhanced".equals(name)) {
                boolean enhanced = "fetchEnhanced".equals(name);
                Object original = null;
                try {
                    original = method.invoke(inner, args);
                } catch (Throwable t) {
                    Util.logw("wrapped source failed", t);
                }

                if (hasWords(original)) return original;

                Object ours = fetch(track, enhanced);
                if (ours != null) {
                    Util.d("wrap(" + name + "): 用我们的逐字结果替换"
                            + (original == null ? "（原源 null）" : "（原源无逐字）"));
                    return ours;
                }
                return original;
            }

            try {
                return method.invoke(inner, args);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("fetch".equals(name)) {
            Util.v("proxy.fetch");
            return fetch(firstArg(args), false);
        }
        if ("fetchEnhanced".equals(name)) {
            Util.v("proxy.fetchEnhanced");
            return fetch(firstArg(args), true);
        }
        if ("toString".equals(name)) return "LyricPatchSource";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == firstArg(args);

        // 未知方法：不静默，打出来（以前这里直接 return null，会导致“源看起来在，但从不工作”）
        if (Util.allow("unexpected:" + name, 60000L)) {
            Util.w("proxy: unexpected method '" + name + "' "
                    + (method.getReturnType() == null ? "" : method.getReturnType().getSimpleName())
                    + " -> null");
        }
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
                Util.d("skip(no title): pkg=" + pkg + " mediaId=" + mediaId);
                return null;
            }

            // 白名单/黑名单：拦掉 B 站、视频 App 之类把视频标题当歌名的情况
            if (!Util.pkgAllowed(pkg)) {
                // 这里必须返回“空歌词”而不是 null：
                // null 会让 ROM 继续试内置 Apple/LrcLib 源，那两个源不认识白名单，
                // 照样会把视频标题当成歌名配上词（白名单就形同虚设）。
                Object empty = emptyLyrics();
                if (Util.allow("skip-pkg:" + pkg, 60000L)) {
                    Util.d("skip(not whitelisted): pkg=" + pkg + " title=" + title
                            + " block=" + (empty != null));
                }
                return empty;
            }
            if (Util.isDebug()) {
                Util.d((enhanced ? "track(E): " : "track: ")
                        + "title=" + title + " | artist=" + artist + " | album=" + album
                        + " | dur=" + duration + " | pkg=" + pkg + " | mediaId=" + mediaId);
            }

            LyricFetcher.Bundle bundle =
                    LyricFetcher.resolve(title, artist, album, duration, pkg, mediaId);
            if (bundle == null) {
                if (Util.isDebug()) {
                    Util.d((enhanced ? "miss(E): " : "miss: ")
                            + title + " / " + artist + " dur=" + duration + " pkg=" + pkg);
                }
                return null;
            }

            Object lyrics = build(bundle, enhanced);
            if (lyrics == null) {
                Util.w("parse failed: " + title);
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
            if (Util.isDebug()) {
                Util.d((enhanced ? "parsed(E): " : "parsed: ") + title
                        + " cues=" + cues + " translated=" + translated
                        + " (bundle tl=" + len(bundle.tlyric) + ")");
            }
            if (translated == 0 && len(bundle.tlyric) > 0) {
                Util.w("tlyric 拿到了但一行都没贴上（±1s 内对不上）: " + title);
            }
            if (!enhanced) {
                Util.i("hit: " + title + " / " + artist
                        + " lrc=" + len(bundle.lrc)
                        + " yrc=" + len(bundle.yrc)
                        + " tl=" + len(bundle.tlyric));
            }
            // 逐字轮没有 word timing 就返回 null，让 ROM 走行级那一轮
            if (enhanced && !hasWordTiming(lyrics)) {
                Util.d("enhanced: 无逐字，回退行级 (bundle yrc="
                        + (bundle.yrc == null ? 0 : bundle.yrc.length()) + ")");
                return null;
            }
            return lyrics;
        } catch (Throwable t) {
            Util.logw("fetch failed", t);
            return null;
        }
    }

    /**
     * 空歌词（空 TreeMap、无 unsynced 文本）。
     * ROM 的取词循环是“拿到非 null 就停”，所以空歌词能让后续内置源全部短路，
     * 最终 mCurrentLyrics 为空 → updateDisplayedLyric 的 floorEntry 为 null → 状态栏不出字。
     */
    private static Object emptyLyrics() {
        if (sEmptyLyrics != null) return sEmptyLyrics;
        try {
            if (sLyricsCtor == null) return null;
            sEmptyLyrics = sLyricsCtor.newInstance(new TreeMap<Long, Object>(), null);
        } catch (Throwable t) {
            Util.logw("create empty lyrics failed", t);
        }
        return sEmptyLyrics;
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

            Util.d("中文歌词，抹掉 " + out.size() + " 行的翻译");
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
                Util.d("attach: index fallback " + matched + " lines");
            }

            if (matched == 0 && existing == 0) {
                Util.w("attach: 0/" + cues.size() + " 未能在 ±" + MATCH_WINDOW_MS + "ms 内对上");
                return lyrics;
            }

            Util.d("attach: matched=" + matched + " existing=" + existing
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

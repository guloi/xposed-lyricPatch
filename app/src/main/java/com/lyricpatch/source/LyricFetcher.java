package com.lyricpatch.source;

import com.lyricpatch.lib.Util;
import com.lyricpatch.lib.Providers;

import com.lyricpatch.lib.ConfKeys;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 源调度 + 缓存 */
public final class LyricFetcher {

    /** 原始歌词文本包，解析交给 SystemUI 自己的 LyricResponseParser */
    public static final class Bundle {
        public String lrc;
        public String tlyric;
        public String yrc;

        boolean empty() {
            return Util.isEmpty(lrc) && Util.isEmpty(yrc);
        }
    }

    private static final class Entry {
        final Bundle bundle;
        final long expireAt;

        Entry(Bundle bundle, long expireAt) {
            this.bundle = bundle;
            this.expireAt = expireAt;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /**
     * SystemUI 设置里填的“状态栏歌词源”（; 分隔的 http base），由 LyricPatchModule 的
     * LyricSourceFactory#create hook 传进来。
     *
     * 音理 / lyricon 这类 App 会把自己的本地 HTTP 服务写进这个设置，
     * 而 ROM 自带的 HttpLyricSource 只读 lrc/tlyric，会把 yrc（逐字）丢掉；
     * 我们自己再请求一遍就能把 yrc 完整拿到。
     */
    private static volatile String sUpstream;

    public static void setUpstream(String setting) {
        sUpstream = setting;
    }
    private static final long OK_TTL = 6L * 3600 * 1000;
    private static final long MISS_TTL = 10L * 60 * 1000;

    /** 一次取词的等待上限；超时就先用已经拿到的结果 */
    private static final long FIRST_WAIT_MS = 4000L;

    /** 伪 provider：mediaId 当网易云 id 直取（跟其他源一起并行） */
    private static final String ID_PROVIDER = "@id";

    private static final ExecutorService FETCH_EXEC = Executors.newFixedThreadPool(4, runnable -> {
        Thread t = new Thread(runnable, "LyricPatch-fetch");
        t.setDaemon(true);
        return t;
    });

    private LyricFetcher() {
    }

    private static String firstLine(String text) {
        if (Util.isEmpty(text)) return "-";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** 去掉行首时间戳和 yrc 的逐字标签，只剩正文 */
    private static String lineBody(String line) {
        int close = line.lastIndexOf(']');
        String body = close >= 0 ? line.substring(close + 1) : line;
        int i = 0;
        while (i < body.length() && body.charAt(i) == '(') {
            int end = body.indexOf(')', i);
            if (end < 0) break;
            i = end + 1;
        }
        return body.substring(i).trim();
    }

    /**
     * 首行像歌名就删掉。
     * 网易云回的 yrc 开头经常是「歌名」或者「歌名 - 歌手」，不完全相等，所以三段判断都上。
     */
    private static String dropTitleLineIf(String text, String normalizedTitle) {
        if (Util.isEmpty(text)) return text;
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        String body = lineBody(first);
        // 空行不动，ROM 自己会跳过
        if (body.isEmpty()) return text;

        String normalized = Util.norm(body);
        double score = Util.similarity(normalized, normalizedTitle);
        boolean same = normalized.equals(normalizedTitle)
                // 容一点尾巴（「 - Artist」「【】」之类）
                || (normalized.contains(normalizedTitle)
                && normalized.length() - normalizedTitle.length() <= 12)
                || score >= 0.85f;
        if (!same) return text;

        Util.d("drop title line: «" + body + "» score=" + String.format(Locale.ROOT, "%.2f", score));
        return nl < 0 ? "" : text.substring(nl + 1);
    }

    /** 有些源第一行是歌名，丢掉，免得第一句显示成标题 */
    private static void dropTitleLine(Bundle bundle, String title) {
        String normalized = Util.norm(title);
        if (Util.isEmpty(normalized)) return;
        bundle.yrc = dropTitleLineIf(bundle.yrc, normalized);
        bundle.lrc = dropTitleLineIf(bundle.lrc, normalized);
    }

    /** 单个源取一次；异常一律当没命中，不让一个源拖垮整轮 */
    private static Bundle fetchOne(String provider, String title, String artist, String album,
                                   long durationMs, String packageName, String mediaId,
                                   boolean withTranslation) {
        try {
            if (ID_PROVIDER.equals(provider)) {
                return Netease.byIdVerified(mediaId, title, withTranslation);
            }
            if (Providers.isNetease(provider)) {
                return Netease.search(title, artist, durationMs, withTranslation);
            }
            if (Providers.isLrcLib(provider)) {
                return Netease.lrcLib(title, artist, album, durationMs);
            }
            if (Providers.isQQ(provider)) {
                return QQMusic.search(title, artist, durationMs, withTranslation);
            }
            if (Providers.isKugou(provider)) {
                return Kugou.fetch(title, artist, durationMs);
            }
            if (Providers.isHttp(provider)) {
                return HttpSource.fetch(provider, title, artist, album, durationMs,
                        packageName, mediaId, withTranslation);
            }
            Util.w("unknown provider: " + provider);
        } catch (Throwable t) {
            Util.logw("provider " + provider + " failed", t);
        }
        return null;
    }

    /**
     * 同时问所有源；拿到第一个结果后再等 250ms 收尾，
     * 让优先级更高的源也有机会补进来，最后按原顺序取第一个非空。
     */
    private static Bundle fetchFirst(List<String> providers, String title, String artist,
                                     String album, long durationMs, String packageName,
                                     String mediaId, boolean withTranslation) {
        if (providers.isEmpty()) return null;

        final Map<String, Bundle> got = new ConcurrentHashMap<>();
        CompletionService<String> cs = new ExecutorCompletionService<>(FETCH_EXEC);
        for (String provider : providers) {
            cs.submit(() -> {
                long start = SystemClock.uptimeMillis();
                Bundle b = fetchOne(provider, title, artist, album, durationMs,
                        packageName, mediaId, withTranslation);
                long cost = SystemClock.uptimeMillis() - start;
                if (b != null && !b.empty()) {
                    got.put(provider, b);
                    if (Util.isDebug()) Util.d("source " + provider + ": hit " + cost + "ms");
                } else if (Util.isDebug()) {
                    Util.d("source " + provider + ": miss " + cost + "ms");
                }
                return provider;
            });
        }

        long deadline = SystemClock.uptimeMillis() + FIRST_WAIT_MS;
        long firstOkAt = 0L;
        int done = 0;
        while (done < providers.size()) {
            long now = SystemClock.uptimeMillis();
            long limit = firstOkAt > 0L ? Math.min(deadline, firstOkAt + 250L) : deadline;
            long left = limit - now;
            if (left <= 0L) break;
            Future<String> f;
            try {
                f = cs.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) break;
            done++;
            try {
                f.get();
            } catch (Throwable ignored) {
                // fetchOne 已经自己吞过异常了
            }
            if (firstOkAt == 0L && !got.isEmpty()) firstOkAt = SystemClock.uptimeMillis();
        }

        for (String provider : providers) {
            Bundle b = got.get(provider);
            if (b != null) return b;
        }
        return null;
    }

    public static Bundle resolve(String title, String artist, String album, long durationMs,
                                 String packageName, String mediaId) {
        boolean withTranslation = Util.confBool(ConfKeys.TRANSLATION, true);

        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(title).append('\0').append(artist)
                .append('\0').append(album).append('\0').append(durationMs)
                // 配置变了就别复用旧结果（否则刚打开翻译还是会吃到无翻译的旧缓存）
                .append('\0').append(withTranslation)
                .append('\0').append(Util.conf(ConfKeys.PROVIDERS, "netease,lrclib"))
                .append('\0').append(Util.conf(ConfKeys.TRANSLATION_PROVIDERS, ""))
                .append('\0').append(nz(sUpstream));
        String key = keyBuilder.toString();

        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(key);
        if (cached != null && cached.expireAt > now) return cached.bundle;

        // 并行请求所有源，再按 providers 的原顺序挑第一个有结果的：
        // 慢的源不会把快的挡住，顺序优先级又保留
        String order = Util.conf(ConfKeys.PROVIDERS, "netease,lrclib");
        if (!Util.isEmpty(sUpstream)) {
            // 设置里填的 base（通常是音理/lyricon 的本地服务）优先，因为它们可能带 yrc
            order = sUpstream.replace(';', ',') + "," + order;
            Util.d("upstream first: " + sUpstream);
        }

        List<String> providers = new ArrayList<>();
        // mediaId 是纯数字时，“按 id 直取”也当一个候选并行跑，不再串行地先等它
        if (isNumeric(mediaId)) providers.add(ID_PROVIDER);
        for (String raw : order.split(",")) {
            String provider = raw.trim();
            if (!provider.isEmpty() && !providers.contains(provider)) providers.add(provider);
        }

        Bundle bundle = fetchFirst(providers, title, artist, album, durationMs,
                packageName, mediaId, withTranslation);

        if (bundle != null && bundle.empty()) bundle = null;

        // 有些源（yrc 尤其）会把歌名当第一句，直接显示就是错的
        if (bundle != null) {
            if (Util.isDebug()) {
                Util.d("lyric head: lrc=«" + firstLine(bundle.lrc)
                        + "» yrc=«" + firstLine(bundle.yrc) + "»");
            }
            dropTitleLine(bundle, title);
        }

        // 中文歌词不去要翻译（设置里可以关）。
        // 只清 tlyric，不动歌词本身，跟“抹掉已有翻译”不是一回事。
        if (bundle != null
                && Util.confBool(ConfKeys.SKIP_TRANSLATION_FOR_CHINESE, true)
                && Util.looksChinese(!Util.isEmpty(bundle.lrc) ? bundle.lrc : bundle.yrc)) {
            bundle.tlyric = null;
        }

        // yrc 字段非空 ≠ 真逐字：有些源把纯行级歌词塞进 yrc，所以看有没有 (起,长,0) 词标签
        // 增强 LRC / Lyricify Syllable 自带词级时间，识别到就直接转成 yrc（真逐字）
        if (bundle != null && !hasWordTags(bundle.yrc)
                && Util.confBool(ConfKeys.PARSE_WORD_FORMATS, true)) {
            String rawForm = !Util.isEmpty(bundle.lrc) ? bundle.lrc : bundle.yrc;
            WordFormats.Result fmt = WordFormats.convert(rawForm);
            if (fmt != null) {
                bundle.lrc = fmt.lrc;
                bundle.yrc = fmt.yrc;
                Util.d("word format -> yrc: " + title);
            }
        }

        // 中文歌词不需要翻译：丢掉源里自带的 tlyric，也不再去 translation_providers 搜。
        // （中文歌去搜翻译源，很容易把同名/近似歌的 tlyric 错贴到正确歌词上 → 洗不掉的残影）
        // 语言采样：lrc 为空就用 yrc（网易云对部分歌只回 yrc，逐字歌词全在里面）
        String langSample = null;
        boolean skipTranslation = false;
        if (bundle != null && Util.confBool(ConfKeys.SKIP_TRANSLATION_FOR_CHINESE, true)) {
            langSample = Util.isEmpty(bundle.lrc) ? bundle.yrc : bundle.lrc;
            skipTranslation = Util.looksChinese(langSample);
            if (Util.isDebug()) {
                Util.d("lang: chinese=" + skipTranslation + " sample="
                        + (langSample == null ? "null" : langSample.length())
                        + " (lrc=" + (bundle.lrc == null ? 0 : bundle.lrc.length())
                        + " yrc=" + (bundle.yrc == null ? 0 : bundle.yrc.length()) + ")");
            }
        }
        if (skipTranslation && !Util.isEmpty(bundle.tlyric)) {
            Util.d("中文歌词，丢弃源自带 tlyric: " + title);
            bundle.tlyric = null;
        }

        // 翻译单独兜底：主源没带 tlyric 时，按 translation_providers 再要一份
        if (bundle != null && withTranslation && !skipTranslation
                && Util.isEmpty(bundle.tlyric)) {
            try {
                String aligned = TranslationSource.resolve(title, artist, album, durationMs,
                        packageName, mediaId, bundle.lrc);
                if (!Util.isEmpty(aligned)) bundle.tlyric = aligned;
            } catch (Throwable t) {
                Util.logw("tlyric fallback failed", t);
            }
        }

        boolean hasYrc = bundle != null && hasWordTags(bundle.yrc);

        // 诊断：已经有真逐字就直接说一声
        if (hasYrc) {
            Util.d("already has word timing: " + bundle.yrc.length() + " chars, cues≈"
                    + lines(bundle.yrc));
        }

        // 还是没有真逐字？去酷狗要（KRC，参考音理的做法：只吃真数据，不算）
        if (bundle != null && !hasYrc && !Util.isEmpty(bundle.lrc)
                && Util.confBool(ConfKeys.KUGOU_WORD_TIMING, true)) {
            try {
                LyricFetcher.Bundle kg = Kugou.fetch(title, artist, durationMs);
                if (kg != null && !Util.isEmpty(kg.yrc)) {
                    int mine = lines(bundle.lrc);
                    int theirs = lines(kg.lrc);
                    // 行数差太多就不能用：说明搜到的不是同一个录音，强行套会串词
                    if (mine > 0 && theirs > 0
                            && theirs >= mine * 0.85 && theirs <= mine * 1.15) {
                        bundle.yrc = kg.yrc;
                        if (Util.isEmpty(bundle.lrc) && !Util.isEmpty(kg.lrc)) bundle.lrc = kg.lrc;
                        Util.d("kugou word timing: " + title + " lines=" + theirs);
                    } else {
                        Util.d("kugou word timing 行数不符，放弃: " + their(mine, theirs));
                    }
                }
            } catch (Throwable t) {
                Util.logw("kugou word timing failed", t);
            }
        }

        // 还是没有逐字？用行时间轴合成一份，让状态栏也有卡拉OK动画
        if (bundle != null && !hasWordTags(bundle.yrc) && !Util.isEmpty(bundle.lrc)
                && Util.confBool(ConfKeys.SYNTH_WORD_TIMING, false)) {
            String yrc = WordTiming.synth(bundle.lrc);
            if (yrc != null) {
                bundle.yrc = yrc;
                Util.d("synth word timing: " + title);
            }
        }

        CACHE.put(key, new Entry(bundle, now + (bundle == null ? MISS_TTL : OK_TTL)));
        if (CACHE.size() > 128) CACHE.clear();
        return bundle;
    }

    /** 只要逐字（不换歌词）：网易云 → 酷狗 */
    public static String wordTimingOnly(String title, String artist, long durationMs,
                                        String mediaId) {
        String yrc = null;
        try {
            if (isNumeric(mediaId)) yrc = Netease.yrcById(mediaId);
            if (Util.isEmpty(yrc)) yrc = Netease.searchYrc(title, artist, durationMs);
        } catch (Throwable t) {
            Util.logw("netease wordTimingOnly failed", t);
        }
        if (!Util.isEmpty(yrc) && hasWordTags(yrc)) {
            Util.d("wordTimingOnly: netease 命中 " + title + " (" + yrc.length() + " chars)");
            return yrc;
        }

        try {
            LyricFetcher.Bundle kg = Kugou.fetch(title, artist, durationMs);
            if (kg != null && hasWordTags(kg.yrc)) {
                Util.d("wordTimingOnly: kugou 命中 " + title);
                return kg.yrc;
            }
        } catch (Throwable t) {
            Util.logw("kugou wordTimingOnly failed", t);
        }

        Util.d("wordTimingOnly: 未命中 " + title + " / " + artist);
        return null;
    }

    /** yrc 里到底有没有真正的词标签 (起,长,0) */    /** yrc 里到底有没有真正的词标签 (起,长,0) */
    private static boolean hasWordTags(String yrc) {
        if (Util.isEmpty(yrc)) return false;
        return Pattern.compile("\\(\\d+,\\s*\\d+,\\s*-?\\d+\\)").matcher(yrc).find();
    }

    private static int lines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n + 1;
    }

    private static String their(int mine, int theirs) {
        return "mine=" + mine + " kugou=" + theirs;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isNumeric(String s) {
        if (Util.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}

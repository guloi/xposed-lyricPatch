package com.lyricpatch;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private LyricFetcher() {
    }

    public static Bundle resolve(String title, String artist, String album, long durationMs,
                                 String packageName, String mediaId) {
        boolean withTranslation = Util.confBool("translation", true);

        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(title).append('\0').append(artist)
                .append('\0').append(album).append('\0').append(durationMs)
                // 配置变了就别复用旧结果（否则刚打开翻译还是会吃到无翻译的旧缓存）
                .append('\0').append(withTranslation)
                .append('\0').append(Util.conf("providers", "netease,lrclib"))
                .append('\0').append(Util.conf("translation_providers", ""))
                .append('\0').append(nz(sUpstream));
        String key = keyBuilder.toString();

        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(key);
        if (cached != null && cached.expireAt > now) return cached.bundle;

        Bundle bundle = null;

        // 1) mediaId 是纯数字：直接当网易云歌曲 id 试（带标题校验，避免串歌）
        if (isNumeric(mediaId)) {
            try {
                bundle = Netease.byIdVerified(mediaId, title, withTranslation);
            } catch (Throwable t) {
                Util.logw("netease byId failed", t);
            }
        }

        // 2) 按 providers 顺序逐个尝试
        if (bundle == null) {
            // 注意：URL 里可能有大小写，只对内置名字做小写化
            String order = Util.conf("providers", "netease,lrclib");
            if (!Util.isEmpty(sUpstream)) {
                // 设置里填的 base（通常是音理/lyricon 的本地服务）优先，因为它们可能带 yrc
                order = sUpstream.replace(';', ',') + "," + order;
                Util.log("upstream first: " + sUpstream);
            }
            for (String raw : order.split(",")) {
                String provider = raw.trim();
                if (provider.isEmpty()) continue;
                if (Thread.currentThread().isInterrupted()) return null;
                try {
                    if ("netease".equalsIgnoreCase(provider)) {
                        bundle = Netease.search(title, artist, durationMs, withTranslation);
                    } else if ("lrclib".equalsIgnoreCase(provider)) {
                        bundle = Netease.lrcLib(title, artist, album, durationMs);
                    } else if ("qq".equalsIgnoreCase(provider)) {
                        bundle = QQMusic.search(title, artist, durationMs, withTranslation);
                    } else if ("kugou".equalsIgnoreCase(provider)) {
                        bundle = Kugou.fetch(title, artist, durationMs);
                    } else if (provider.startsWith("http://") || provider.startsWith("https://")) {
                        bundle = HttpSource.fetch(provider, title, artist, album, durationMs,
                                packageName, mediaId, withTranslation);
                    } else {
                        Util.log("unknown provider: " + provider);
                    }
                } catch (Throwable t) {
                    Util.logw("provider " + provider + " failed", t);
                }
                if (bundle != null && !bundle.empty()) break;
                bundle = null;
            }
        }

        if (bundle != null && bundle.empty()) bundle = null;

        // yrc 字段非空 ≠ 真逐字：有些源把纯行级歌词塞进 yrc，所以看有没有 (起,长,0) 词标签
        // 增强 LRC / Lyricify Syllable 自带词级时间，识别到就直接转成 yrc（真逐字）
        if (bundle != null && !hasWordTags(bundle.yrc)
                && Util.confBool("parse_word_formats", true)) {
            String rawForm = !Util.isEmpty(bundle.lrc) ? bundle.lrc : bundle.yrc;
            WordFormats.Result fmt = WordFormats.convert(rawForm);
            if (fmt != null) {
                bundle.lrc = fmt.lrc;
                bundle.yrc = fmt.yrc;
                Util.log("word format -> yrc: " + title);
            }
        }

        // 中文歌词不需要翻译：丢掉源里自带的 tlyric，也不再去 translation_providers 搜。
        // （中文歌去搜翻译源，很容易把同名/近似歌的 tlyric 错贴到正确歌词上 → 洗不掉的残影）
        // 语言采样：lrc 为空就用 yrc（网易云对部分歌只回 yrc，逐字歌词全在里面）
        String langSample = null;
        boolean skipTranslation = false;
        if (bundle != null && Util.confBool("skip_translation_for_chinese", true)) {
            langSample = Util.isEmpty(bundle.lrc) ? bundle.yrc : bundle.lrc;
            skipTranslation = Util.looksChinese(langSample);
            if (Util.confBool("debug_log", false)) {
                Util.log("lang: chinese=" + skipTranslation + " sample="
                        + (langSample == null ? "null" : langSample.length())
                        + " (lrc=" + (bundle.lrc == null ? 0 : bundle.lrc.length())
                        + " yrc=" + (bundle.yrc == null ? 0 : bundle.yrc.length()) + ")");
            }
        }
        if (skipTranslation && !Util.isEmpty(bundle.tlyric)) {
            Util.log("中文歌词，丢弃源自带 tlyric: " + title);
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
            Util.log("already has word timing: " + bundle.yrc.length() + " chars, cues≈"
                    + lines(bundle.yrc));
        }

        // 还是没有真逐字？去酷狗要（KRC，参考音理的做法：只吃真数据，不算）
        if (bundle != null && !hasYrc && !Util.isEmpty(bundle.lrc)
                && Util.confBool("kugou_word_timing", true)) {
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
                        Util.log("kugou word timing: " + title + " lines=" + theirs);
                    } else {
                        Util.log("kugou word timing 行数不符，放弃: " + their(mine, theirs));
                    }
                }
            } catch (Throwable t) {
                Util.logw("kugou word timing failed", t);
            }
        }

        // 还是没有逐字？用行时间轴合成一份，让状态栏也有卡拉OK动画
        if (bundle != null && !hasWordTags(bundle.yrc) && !Util.isEmpty(bundle.lrc)
                && Util.confBool("synth_word_timing", false)) {
            String yrc = WordTiming.synth(bundle.lrc);
            if (yrc != null) {
                bundle.yrc = yrc;
                Util.log("synth word timing: " + title);
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
            Util.log("wordTimingOnly: netease 命中 " + title + " (" + yrc.length() + " chars)");
            return yrc;
        }

        try {
            LyricFetcher.Bundle kg = Kugou.fetch(title, artist, durationMs);
            if (kg != null && hasWordTags(kg.yrc)) {
                Util.log("wordTimingOnly: kugou 命中 " + title);
                return kg.yrc;
            }
        } catch (Throwable t) {
            Util.logw("kugou wordTimingOnly failed", t);
        }

        Util.log("wordTimingOnly: 未命中 " + title + " / " + artist);
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

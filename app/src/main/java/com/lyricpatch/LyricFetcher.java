package com.lyricpatch;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
                .append('\0').append(Util.conf("translation_providers", ""));
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

        // 还是没有逐字？用行时间轴合成一份，让状态栏也有卡拉OK动画
        if (bundle != null && Util.isEmpty(bundle.yrc) && !Util.isEmpty(bundle.lrc)
                && Util.confBool("synth_word_timing", true)) {
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

    private static boolean isNumeric(String s) {
        if (Util.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}

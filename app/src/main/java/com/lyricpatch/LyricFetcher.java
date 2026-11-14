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
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(title).append('\0').append(artist)
                .append('\0').append(album).append('\0').append(durationMs);
        String key = keyBuilder.toString();

        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(key);
        if (cached != null && cached.expireAt > now) return cached.bundle;

        boolean withTranslation = Util.confBool("translation", true);
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

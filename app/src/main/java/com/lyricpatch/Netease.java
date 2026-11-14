package com.lyricpatch;

import org.json.JSONArray;
import org.json.JSONObject;

/** 网易云音乐（主） + LRCLIB（备） */
public final class Netease {

    private static final String REFERER = "https://music.163.com/";
    private static final String SEARCH =
            "https://music.163.com/api/search/get?s=%s&type=1&offset=0&limit=10";
    private static final String LYRIC =
            "https://music.163.com/api/song/lyric?id=%s&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1";
    private static final String DETAIL =
            "https://music.163.com/api/song/detail?ids=%5B%s%5D";
    private static final String LRCLIB_GET =
            "https://lrclib.net/api/get?track_name=%s&artist_name=%s&album_name=%s&duration=%d";
    private static final String LRCLIB_SEARCH = "https://lrclib.net/api/search?q=%s";

    private Netease() {
    }

    /** 直接按网易云歌曲 id 取词 */
    public static LyricFetcher.Bundle byId(String id, boolean withTranslation) {
        try {
            String res = Util.get(String.format(LYRIC, Util.enc(id)), REFERER);
            return parseLyric(res, withTranslation);
        } catch (Throwable t) {
            Util.logw("netease byId failed", t);
            return null;
        }
    }

    /** mediaId 当网易云 id 用：先查 song/detail 校验标题，避免串歌 */
    public static LyricFetcher.Bundle byIdVerified(String id, String title,
                                                   boolean withTranslation) {
        try {
            String detail = Util.get(String.format(DETAIL, Util.enc(id)), REFERER);
            if (!Util.isEmpty(detail)) {
                JSONArray arr = new JSONObject(detail).optJSONArray("songs");
                if (arr != null && arr.length() > 0) {
                    JSONObject s = arr.optJSONObject(0);
                    String name = s == null ? "" : s.optString("name", "");
                    if (Util.similarity(Util.norm(title), Util.norm(name)) < 0.5) {
                        Util.log("id " + id + " mismatch [" + name + "] vs [" + title + "], skip");
                        return null;
                    }
                }
            }
            return byId(id, withTranslation);
        } catch (Throwable t) {
            Util.logw("netease detail failed", t);
            return null;
        }
    }

    /** 带歌手搜不到时，再只用标题重试一次 */
    public static LyricFetcher.Bundle search(String title, String artist, long durationMs,
                                             boolean withTranslation) {
        String[] keywords = Util.isEmpty(artist)
                ? new String[]{title}
                : new String[]{title + " " + artist, title};

        for (String keyword : keywords) {
            LyricFetcher.Bundle b = searchOnce(keyword, title, artist, durationMs, withTranslation);
            if (b != null) return b;
        }
        return null;
    }

    private static LyricFetcher.Bundle searchOnce(String keyword, String title, String artist,
                                                  long durationMs, boolean withTranslation) {
        try {
            String res = Util.get(String.format(SEARCH, Util.enc(keyword)), REFERER);
            if (Util.isEmpty(res)) return null;

            JSONObject result = new JSONObject(res).optJSONObject("result");
            if (result == null) return null;
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;

            String id = pickBest(songs, title, artist, durationMs);
            if (id == null) return null;

            String lyric = Util.get(String.format(LYRIC, Util.enc(id)), REFERER);
            LyricFetcher.Bundle bundle = parseLyric(lyric, withTranslation);
            if (bundle != null) {
                Util.log("netease hit \"" + title + " / " + artist + "\" -> id=" + id);
            }
            return bundle;
        } catch (Throwable t) {
            Util.logw("netease search failed", t);
            return null;
        }
    }

    public static LyricFetcher.Bundle lrcLib(String title, String artist, String album,
                                             long durationMs) {
        try {
            String url = String.format(LRCLIB_GET,
                    Util.enc(title),
                    Util.enc(artist == null ? "" : artist),
                    Util.enc(album == null ? "" : album),
                    Math.max(0, durationMs / 1000));
            LyricFetcher.Bundle bundle = lrcLibParse(Util.get(url, null));
            if (bundle != null) return bundle;

            String q = Util.enc((Util.isEmpty(artist) ? "" : artist + " ") + title);
            String res = Util.get(String.format(LRCLIB_SEARCH, q), null);
            if (Util.isEmpty(res)) return null;

            JSONArray arr = new JSONArray(res);
            for (int i = 0; i < arr.length(); i++) {
                bundle = lrcLibParseObj(arr.optJSONObject(i));
                if (bundle != null) return bundle;
            }
            return null;
        } catch (Throwable t) {
            Util.logw("lrclib failed", t);
            return null;
        }
    }

    private static LyricFetcher.Bundle parseLyric(String res, boolean withTranslation) {
        if (Util.isEmpty(res)) return null;
        try {
            JSONObject root = new JSONObject(res);
            LyricFetcher.Bundle bundle = new LyricFetcher.Bundle();
            bundle.lrc = sub(root, "lrc");
            bundle.yrc = sub(root, "yrc");
            if (withTranslation) bundle.tlyric = sub(root, "tlyric");
            if (bundle.empty()) return null;
            return bundle;
        } catch (Throwable t) {
            Util.logw("parse lyric json failed", t);
            return null;
        }
    }

    private static String sub(JSONObject o, String key) {
        JSONObject n = o.optJSONObject(key);
        return n == null ? null : n.optString("lyric", "");
    }

    private static LyricFetcher.Bundle lrcLibParse(String res) {
        if (Util.isEmpty(res)) return null;
        try {
            return lrcLibParseObj(new JSONObject(res));
        } catch (Throwable t) {
            return null;
        }
    }

    private static LyricFetcher.Bundle lrcLibParseObj(JSONObject o) {
        if (o == null) return null;
        String synced = o.optString("syncedLyrics", "");
        if (Util.isEmpty(synced)) return null;   // 没时间轴的不显示，交给下一个源
        LyricFetcher.Bundle bundle = new LyricFetcher.Bundle();
        bundle.lrc = synced;
        return bundle;
    }

    /** 标题相似度为主，歌手/时长校正 */
    private static String pickBest(JSONArray songs, String title, String artist, long durationMs) {
        String t = Util.norm(title);
        String a = Util.norm(artist);

        String bestId = null;
        double bestScore = 0;

        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;

            double ts = Util.similarity(t, Util.norm(s.optString("name", "")));
            if (ts < 0.5) continue;

            double as = Util.isEmpty(a) ? 0.5
                    : Util.similarity(a, Util.norm(artistsName(s)));

            long dur = s.optLong("duration", 0L);
            double ds = 0;
            if (durationMs > 0 && dur > 0) {
                long diff = Math.abs(dur - durationMs);
                if (diff < 2000) ds = 1.0;
                else if (diff < 5000) ds = 0.6;
                else if (diff < 15000) ds = 0.2;
            }

            double score = ts * 2.0 + as * 1.2 + ds;
            if (score > bestScore) {
                bestScore = score;
                bestId = s.optString("id", null);
            }
        }
        return bestId;
    }

    private static String artistsName(JSONObject song) {
        StringBuilder sb = new StringBuilder();
        JSONArray artists = song.optJSONArray("artists");
        if (artists != null) {
            for (int i = 0; i < artists.length(); i++) {
                JSONObject ar = artists.optJSONObject(i);
                if (ar != null) sb.append(ar.optString("name", "")).append('/');
            }
        }
        return sb.toString();
    }
}

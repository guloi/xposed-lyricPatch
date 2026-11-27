package com.lyricpatch.source;

import com.lyricpatch.lib.Util;
import com.lyricpatch.lib.LrcLibApi;
import com.lyricpatch.lib.NeteaseApi;

import org.json.JSONArray;
import org.json.JSONObject;

/** 网易云音乐（主） + LRCLIB（备） */
public final class Netease {

    private Netease() {
    }

    /** 直接按网易云歌曲 id 取词 */
    public static LyricFetcher.Bundle byId(String id, boolean withTranslation) {
        try {
            String res = Util.get(String.format(NeteaseApi.LYRIC, Util.enc(id)), NeteaseApi.REFERER);
            return enrich(id, parseLyric(res, withTranslation), withTranslation);
        } catch (Throwable t) {
            Util.logw("netease byId failed", t);
            return null;
        }
    }

    /**
     * 经典接口没有逐字时，再问一次新版接口。
     * 只补缺：只在原结果缺 yrc / tlyric / lrc 时覆盖，不动已有的。
     */
    private static LyricFetcher.Bundle enrich(String id, LyricFetcher.Bundle b,
                                              boolean withTranslation) {
        if (b == null) return null;
        if (!Util.isEmpty(b.yrc) && !Util.isEmpty(b.lrc)) return b;

        // 依次试两个接口，谁先给出逐字就用谁
        if (Util.isEmpty(b.yrc)) {
            merge(b, fetchLyric(NeteaseApi.LYRIC_V1, id, withTranslation), withTranslation, "lyric/v1");
        }
        if (Util.isEmpty(b.yrc)) {
            merge(b, fetchLyric(NeteaseApi.LYRIC_PC, id, withTranslation), withTranslation, "lyric?os=pc");
        }
        if (Util.isEmpty(b.yrc)) {
            Util.d("netease 无逐字歌词: id=" + id);
        }
        return b;
    }

    private static void merge(LyricFetcher.Bundle b, LyricFetcher.Bundle extra,
                              boolean withTranslation, String from) {
        if (b == null || extra == null) return;

        if (Util.isEmpty(b.yrc) && !Util.isEmpty(extra.yrc)) {
            b.yrc = extra.yrc;
            Util.d("netease " + from + " gave word timing: id, lines=" + countLines(extra.yrc));
        }
        if (Util.isEmpty(b.lrc) && !Util.isEmpty(extra.lrc)) b.lrc = extra.lrc;
        if (withTranslation && Util.isEmpty(b.tlyric) && !Util.isEmpty(extra.tlyric)) {
            b.tlyric = extra.tlyric;
        }
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 同一套解析：优先逐字翻译 ytlrc，其次普通 tlyric */
    private static LyricFetcher.Bundle fetchLyric(String urlFmt, String id,
                                                 boolean withTranslation) {
        try {
            String res = Util.get(String.format(urlFmt, Util.enc(id)), NeteaseApi.REFERER);
            if (Util.isEmpty(res)) return null;

            JSONObject root = new JSONObject(res);
            LyricFetcher.Bundle b = new LyricFetcher.Bundle();
            b.lrc = sub(root, "lrc");
            b.yrc = sub(root, "yrc");
            if (withTranslation) {
                String ytl = sub(root, "ytlrc");
                if (Util.isEmpty(ytl)) ytl = sub(root, "ytlyric");
                b.tlyric = Util.isEmpty(ytl) ? sub(root, "tlyric") : ytl;
            }
            return b;
        } catch (Throwable t) {
            Util.logw("netease extra lyric failed", t);
            return null;
        }
    }

    /** mediaId 当网易云 id 用：先查 song/detail 校验标题，避免串歌 */
    public static LyricFetcher.Bundle byIdVerified(String id, String title,
                                                   boolean withTranslation) {
        try {
            String detail = Util.get(String.format(NeteaseApi.DETAIL, Util.enc(id)), NeteaseApi.REFERER);
            if (!Util.isEmpty(detail)) {
                JSONArray arr = new JSONObject(detail).optJSONArray("songs");
                if (arr != null && arr.length() > 0) {
                    JSONObject s = arr.optJSONObject(0);
                    String name = s == null ? "" : s.optString("name", "");
                    if (Util.similarity(Util.norm(title), Util.norm(name)) < 0.5) {
                        Util.d("id " + id + " mismatch [" + name + "] vs [" + title + "], skip");
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
            String res = Util.get(String.format(NeteaseApi.SEARCH, Util.enc(keyword)), NeteaseApi.REFERER);
            if (Util.isEmpty(res)) return null;

            JSONObject result = new JSONObject(res).optJSONObject("result");
            if (result == null) return null;
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;

            String id = pickBest(songs, title, artist, durationMs);
            if (id == null) return null;

            String lyric = Util.get(String.format(NeteaseApi.LYRIC, Util.enc(id)), NeteaseApi.REFERER);
            LyricFetcher.Bundle bundle = enrich(id, parseLyric(lyric, withTranslation),
                    withTranslation);
            if (bundle != null) {
                Util.i("netease hit \"" + title + " / " + artist + "\" -> id=" + id);
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
            String url = String.format(LrcLibApi.GET,
                    Util.enc(title),
                    Util.enc(artist == null ? "" : artist),
                    Util.enc(album == null ? "" : album),
                    Math.max(0, durationMs / 1000));
            LyricFetcher.Bundle bundle = lrcLibParse(Util.get(url, null));
            if (bundle != null) return bundle;

            String q = Util.enc((Util.isEmpty(artist) ? "" : artist + " ") + title);
            String res = Util.get(String.format(LrcLibApi.SEARCH, q), null);
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

    /**
     * 只要能翻译：主源不是网易云时也能拿到 tlyric。
     * mediaId 是纯数字先当网易云 id（带标题校验，避免串歌），否则按「标题 + 歌手」搜一次。
     */
    public static String tlyric(String title, String artist, long durationMs, String mediaId) {
        try {
            if (Util.isNumeric(mediaId)) {
                LyricFetcher.Bundle b = byIdVerified(mediaId, title, true);
                if (b != null && !Util.isEmpty(b.tlyric)) return b.tlyric;
            }

            String[] keywords = Util.isEmpty(artist)
                    ? new String[]{title}
                    : new String[]{title + " " + artist, title};

            for (String keyword : keywords) {
                String res = Util.get(String.format(NeteaseApi.SEARCH, Util.enc(keyword)), NeteaseApi.REFERER);
                if (Util.isEmpty(res)) continue;

                JSONObject result = new JSONObject(res).optJSONObject("result");
                if (result == null) continue;
                JSONArray songs = result.optJSONArray("songs");
                if (songs == null || songs.length() == 0) continue;

                String id = pickBest(songs, title, artist, durationMs);
                if (id == null) continue;

                String tl = tlyricById(id);
                if (!Util.isEmpty(tl)) {
                    Util.d("netease tlyric ok -> id=" + id + " lines=" + tl.length());
                    return tl;
                }
            }
        } catch (Throwable t) {
            Util.logw("netease tlyric failed", t);
        }
        return null;
    }

    private static String tlyricById(String id) {
        try {
            String res = Util.get(String.format(NeteaseApi.LYRIC, Util.enc(id)), NeteaseApi.REFERER);
            if (Util.isEmpty(res)) return null;
            String tl = sub(new JSONObject(res), "tlyric");
            return Util.isEmpty(tl) ? null : tl;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只要逐字：按 id 依次试三个接口 */
    public static String yrcById(String id) {
        if (Util.isEmpty(id)) return null;
        try {
            LyricFetcher.Bundle b = fetchLyric(NeteaseApi.LYRIC_V1, id, false);
            if (b != null && !Util.isEmpty(b.yrc)) return b.yrc;

            b = fetchLyric(NeteaseApi.LYRIC_PC, id, false);
            if (b != null && !Util.isEmpty(b.yrc)) return b.yrc;

            String res = Util.get(String.format(NeteaseApi.LYRIC, Util.enc(id)), NeteaseApi.REFERER);
            String y = sub(new JSONObject(res), "yrc");
            return Util.isEmpty(y) ? null : y;
        } catch (Throwable t) {
            Util.logw("netease yrcById failed", t);
            return null;
        }
    }

    /** 只要逐字：先搜索拿 id */
    public static String searchYrc(String title, String artist, long durationMs) {
        try {
            String[] keywords = Util.isEmpty(artist)
                    ? new String[]{title}
                    : new String[]{title + " " + artist, title};

            for (String keyword : keywords) {
                String res = Util.get(String.format(NeteaseApi.SEARCH, Util.enc(keyword)), NeteaseApi.REFERER);
                if (Util.isEmpty(res)) continue;

                JSONObject result = new JSONObject(res).optJSONObject("result");
                if (result == null) continue;
                JSONArray songs = result.optJSONArray("songs");
                if (songs == null || songs.length() == 0) continue;

                String id = pickBest(songs, title, artist, durationMs);
                if (id == null) continue;

                String yrc = yrcById(id);
                if (!Util.isEmpty(yrc)) return yrc;
            }
        } catch (Throwable t) {
            Util.logw("netease searchYrc failed", t);
        }
        return null;
    }

    /** 标题相似度为主，歌手/时长校正 */    /** 标题相似度为主，歌手/时长校正 */
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

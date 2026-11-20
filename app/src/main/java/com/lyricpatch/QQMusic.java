package com.lyricpatch;

import org.json.JSONArray;
import org.json.JSONObject;

/** QQ 音乐（非官方接口，只有行级歌词 + 翻译，没有逐字） */
public final class QQMusic {

    private static final String REFERER = "https://y.qq.com/";
    private static final String SEARCH =
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                    + "?w=%s&format=json&p=1&n=10&cr=1&aggr=1&lossless=0";
    private static final String LYRIC =
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                    + "?songmid=%s&format=json&nobase64=1&g_tk=5381";

    private QQMusic() {
    }

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

            JSONObject data = new JSONObject(res).optJSONObject("data");
            if (data == null) return null;
            JSONObject song = data.optJSONObject("song");
            if (song == null) return null;
            JSONArray list = song.optJSONArray("list");
            if (list == null || list.length() == 0) return null;

            String mid = pickBest(list, title, artist, durationMs);
            if (mid == null) return null;

            String lyr = Util.get(String.format(LYRIC, Util.enc(mid)), REFERER);
            if (Util.isEmpty(lyr)) return null;
            JSONObject o = new JSONObject(lyr);

            LyricFetcher.Bundle bundle = new LyricFetcher.Bundle();
            bundle.lrc = decode(o.optString("lyric", ""));
            if (withTranslation) bundle.tlyric = decode(o.optString("trans", ""));
            if (Util.isEmpty(bundle.lrc)) return null;

            Util.log("qq hit \"" + title + " / " + artist + "\" -> mid=" + mid);
            return bundle;
        } catch (Throwable t) {
            Util.logw("qq search failed", t);
            return null;
        }
    }

    /** 只要能翻译（QQ 的 trans 字段），主源不是 QQ 时也能用 */
    public static String tlyric(String title, String artist, long durationMs) {
        String[] keywords = Util.isEmpty(artist)
                ? new String[]{title}
                : new String[]{title + " " + artist, title};

        for (String keyword : keywords) {
            try {
                String res = Util.get(String.format(SEARCH, Util.enc(keyword)), REFERER);
                if (Util.isEmpty(res)) continue;

                JSONObject data = new JSONObject(res).optJSONObject("data");
                if (data == null) continue;
                JSONObject song = data.optJSONObject("song");
                if (song == null) continue;
                JSONArray list = song.optJSONArray("list");
                if (list == null || list.length() == 0) continue;

                String mid = pickBest(list, title, artist, durationMs);
                if (mid == null) continue;

                String lyr = Util.get(String.format(LYRIC, Util.enc(mid)), REFERER);
                if (Util.isEmpty(lyr)) continue;

                String tl = decode(new JSONObject(lyr).optString("trans", ""));
                if (!Util.isEmpty(tl)) {
                    Util.log("qq tlyric ok -> mid=" + mid + " lines=" + tl.length());
                    return tl;
                }
            } catch (Throwable t) {
                Util.logw("qq tlyric failed", t);
            }
        }
        return null;
    }

    private static String pickBest(JSONArray list, String title, String artist, long durationMs) {
        String t = Util.norm(title);
        String a = Util.norm(artist);

        String bestMid = null;
        double bestScore = 0;

        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;

            double ts = Util.similarity(t, Util.norm(s.optString("songname", "")));
            if (ts < 0.5) continue;

            double as = Util.isEmpty(a) ? 0.5 : Util.similarity(a, Util.norm(singers(s)));

            long dur = s.optLong("interval", 0L) * 1000L;   // interval 单位是秒
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
                bestMid = s.optString("songmid", null);
            }
        }
        return bestMid;
    }

    private static String singers(JSONObject s) {
        StringBuilder sb = new StringBuilder();
        JSONArray arr = s.optJSONArray("singer");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) sb.append(o.optString("name", "")).append('/');
            }
        }
        return sb.toString();
    }

    /** QQ 返回的是 HTML 实体（&#10; 换行、&#58; 冒号等） */
    private static String decode(String s) {
        if (s == null || s.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                int end = s.indexOf(';', i);
                if (end > i && end - i < 10) {
                    Integer cp = entityCode(s.substring(i + 1, end));
                    if (cp != null) {
                        sb.append((char) cp.intValue());
                        i = end;
                        continue;
                    }
                }
            }
            sb.append(c);
        }
        String r = sb.toString().replace("\\n", "\n").replace("\\/", "/");
        return r.isEmpty() ? null : r;
    }

    private static Integer entityCode(String ent) {
        try {
            if (ent.startsWith("#x") || ent.startsWith("#X")) {
                return Integer.parseInt(ent.substring(2), 16);
            }
            if (ent.startsWith("#")) {
                return Integer.parseInt(ent.substring(1));
            }
        } catch (Throwable ignored) {
            return null;
        }
        if ("amp".equals(ent)) return (int) '&';
        if ("lt".equals(ent)) return (int) '<';
        if ("gt".equals(ent)) return (int) '>';
        if ("quot".equals(ent)) return (int) '"';
        if ("apos".equals(ent)) return (int) '\'';
        if ("nbsp".equals(ent)) return (int) ' ';
        return null;
    }
}

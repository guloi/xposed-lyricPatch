package com.lyricpatch;

import org.json.JSONObject;

/**
 * 自定义 HTTP 歌词源。
 * 走与 ROM 内置 HttpLyricSource 完全相同的协议：
 *   GET {base}/v1/lyrics?title=&artist=&album=&durationMs=&sourcePackage=&mediaId=
 *   响应：{"lrc":"...","tlyric":"...","yrc":"..."}
 *   或者嵌套：{"lrc":{"lyric":"..."}}
 */
public final class HttpSource {

    private HttpSource() {
    }

    public static LyricFetcher.Bundle fetch(String baseUrl, String title, String artist,
                                            String album, long durationMs, String pkg,
                                            String mediaId, boolean withTranslation) {
        try {
            String base = baseUrl.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (Util.isEmpty(base)) return null;

            String url = base + "/v1/lyrics?title=" + Util.enc(title)
                    + "&artist=" + Util.enc(artist == null ? "" : artist)
                    + "&album=" + Util.enc(album == null ? "" : album)
                    + "&durationMs=" + Math.max(0, durationMs)
                    + "&sourcePackage=" + Util.enc(pkg == null ? "" : pkg)
                    + "&mediaId=" + Util.enc(mediaId == null ? "" : mediaId);

            String res = Util.get(url, null);
            if (Util.isEmpty(res)) return null;

            JSONObject o = new JSONObject(res);
            LyricFetcher.Bundle b = new LyricFetcher.Bundle();
            b.lrc = pick(o, "lrc", "lyric", "original");
            b.yrc = pick(o, "yrc");
            if (withTranslation) b.tlyric = pick(o, "tlyric", "translation", "translated");

            if (b.empty()) {
                Util.log("http source miss: " + base);
                return null;
            }
            Util.log("http source hit: " + base + " lrc=" + len(b.lrc) + " yrc=" + len(b.yrc));
            return b;
        } catch (Throwable t) {
            Util.logw("http source failed: " + baseUrl, t);
            return null;
        }
    }

    /** 从多种写法里取一个非空字符串（包内共享，TranslationSource 也用） */
    static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                String s = ((JSONObject) v).optString("lyric", "");
                if (!Util.isEmpty(s)) return s;
            } else if (v instanceof String) {
                String s = (String) v;
                if (!Util.isEmpty(s)) return s;
            }
        }
        return null;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}

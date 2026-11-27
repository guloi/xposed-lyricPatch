package com.lyricpatch.source;

import com.lyricpatch.lib.Util;
import com.lyricpatch.lib.KugouApi;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * 酷狗 KRC 逐字歌词。
 *
 * 参考音理(NeriPlayer)的 KlLyricsCore：它把 TTML / NeteaseYrc / LyricifySyllable /
 * EnhancedLrc / KugouKrc 都当“真逐字”吃。其中酷狗 KRC 覆盖面大、免费、无需登录，
 * 所以这里先把这条路搭上：
 *
 *   搜索  krcs.kugou.com/search   → candidates[{id, accesskey, song, singer, duration}]
 *   下载  lyrics.kugou.com/download?fmt=krc → {content: base64}
 *   解密  base64 → 去掉首 4 字节 "krc1" → 异或 KEY → zlib 解压 → UTF-8 KRC 文本
 *
 * KRC 行格式：`[行开始ms,行时长ms]<词偏移ms,词时长ms,0>词...`
 * ROM 只认网易云 yrc 格式，所以这里顺手转成：`[行开始ms,行时长ms](词开始ms,词时长ms,0)词...`
 */
public final class Kugou {

    /** KRC 解密密钥 */
    private static final int[] KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69
    };

    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern WORD = Pattern.compile("<(\\d+),(\\d+),(-?\\d+)>([^<]*)");

    private Kugou() {
    }

    /** @return lrc（行级）+ yrc（逐字，转成网易云格式）；拿不到返回 null */
    public static LyricFetcher.Bundle fetch(String title, String artist, long durationMs) {
        String[] keywords = Util.isEmpty(artist)
                ? new String[]{title}
                : new String[]{title + " - " + artist, title + " " + artist, title};

        for (String keyword : keywords) {
            LyricFetcher.Bundle b = tryKeyword(keyword, title, artist, durationMs);
            if (b != null) return b;
        }
        Util.d("kugou miss: " + title + " / " + artist);
        return null;
    }

    private static LyricFetcher.Bundle tryKeyword(String keyword, String title, String artist,
                                                 long durationMs) {
        try {
            String res = Util.get(String.format(Locale.ROOT, KugouApi.SEARCH,
                    Util.enc(keyword), Math.max(0L, durationMs)), KugouApi.REFERER);
            if (Util.isEmpty(res)) {
                Util.d("kugou search empty: " + keyword);
                return null;
            }

            JSONArray list = new JSONObject(res).optJSONArray("candidates");
            if (list == null || list.length() == 0) {
                Util.d("kugou no candidate: " + keyword);
                return null;
            }
            Util.v("kugou candidates=" + list.length() + " for " + keyword);

            JSONObject best = pickBest(list, title, artist, durationMs);
            if (best == null) {
                Util.d("kugou pick none: " + keyword);
                return null;
            }

            String id = best.optString("id", "");
            String accesskey = best.optString("accesskey", "");
            if (Util.isEmpty(id) || Util.isEmpty(accesskey)) {
                Util.w("kugou candidate has no id/accesskey");
                return null;
            }
            Util.d("kugou pick: " + best.optString("song", "?") + " / "
                    + best.optString("singer", "?"));

            String dl = Util.get(String.format(Locale.ROOT, KugouApi.DOWNLOAD,
                    Util.enc(id), Util.enc(accesskey)), KugouApi.REFERER);
            if (Util.isEmpty(dl)) {
                Util.d("kugou download empty");
                return null;
            }

            String b64 = new JSONObject(dl).optString("content", "");
            if (Util.isEmpty(b64)) {
                Util.d("kugou download has no content");
                return null;
            }

            String krc = decode(b64);
            if (Util.isEmpty(krc)) {
                Util.d("kugou decode empty");
                return null;
            }

            LyricFetcher.Bundle bundle = parseKrc(krc);
            if (bundle == null) {
                Util.d("kugou krc parse empty");
                return null;
            }

            Util.i("kugou hit: " + title + " / " + artist
                    + " lrcLines=" + lineCount(bundle.lrc)
                    + " yrcLines=" + lineCount(bundle.yrc));
            return bundle;
        } catch (Throwable t) {
            Util.logw("kugou failed: " + keyword, t);
            return null;
        }
    }

    private static int lineCount(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    private static JSONObject pickBest(JSONArray list, String title, String artist, long durationMs) {
        String t = Util.norm(title);
        String a = Util.norm(artist);

        JSONObject best = null;
        double bestScore = 0;

        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;

            double ts = Util.similarity(t, Util.norm(o.optString("song", "")));
            if (ts < 0.5) continue;

            double as = Util.isEmpty(a) ? 0.5
                    : Util.similarity(a, Util.norm(o.optString("singer", "")));

            long dur = o.optLong("duration", 0L);
            if (dur > 0 && dur < 10000L) dur *= 1000L;      // 有的返回秒
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
                best = o;
            }
        }
        return best;
    }

    /** base64 → 去 "krc1" 头 → 异或 → zlib 解压 */
    private static String decode(String base64) throws Exception {
        byte[] data = Base64.decode(base64, Base64.DEFAULT);
        if (data == null || data.length < 8) return null;

        // 有的接口直接回明文歌词（不是 KRC）：别硬解密，原样交给下游
        if (!(data[0] == 'k' && data[1] == 'r' && data[2] == 'c' && data[3] == '1')) {
            return new String(data, "UTF-8");
        }

        for (int i = 1; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ KEY[(i - 1) % KEY.length]);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(data, 4, data.length - 4);
        InflaterInputStream inflater = new InflaterInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = inflater.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        inflater.close();
        return out.toString("UTF-8");
    }

    /** KRC → lrc（行级） + yrc（逐字，转成 ROM 认的格式） */
    private static LyricFetcher.Bundle parseKrc(String krc) {
        StringBuilder lrc = new StringBuilder();
        StringBuilder yrc = new StringBuilder();
        int lineCount = 0;

        for (String raw : krc.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            Matcher lm = LINE.matcher(line);
            if (!lm.matches()) continue;      // [ti:] [ar:] [offset:] 之类

            long lineStart;
            long lineDur;
            try {
                lineStart = Long.parseLong(lm.group(1));
                lineDur = Long.parseLong(lm.group(2));
            } catch (Throwable t) {
                continue;
            }

            String rest = lm.group(3);
            StringBuilder text = new StringBuilder();
            StringBuilder words = new StringBuilder();

            Matcher wm = WORD.matcher(rest);
            while (wm.find()) {
                long offset;
                long dur;
                try {
                    offset = Long.parseLong(wm.group(1));
                    dur = Long.parseLong(wm.group(2));
                } catch (Throwable t) {
                    continue;
                }
                String content = wm.group(4);
                if (content.isEmpty()) continue;

                text.append(content);
                words.append('(').append(lineStart + offset).append(',').append(dur)
                        .append(",0)").append(content);
            }

            String plain = text.length() > 0 ? text.toString() : rest.trim();
            if (plain.isEmpty()) continue;

            lrc.append(fmt(lineStart)).append(plain).append('\n');
            if (words.length() > 0) {
                yrc.append('[').append(lineStart).append(',').append(Math.max(100L, lineDur))
                        .append(']').append(words).append('\n');
            }
            lineCount++;
        }

        if (lineCount == 0) return null;

        LyricFetcher.Bundle bundle = new LyricFetcher.Bundle();
        bundle.lrc = lrc.toString();
        bundle.yrc = yrc.length() == 0 ? null : yrc.toString();
        return bundle;
    }

    private static String fmt(long ms) {
        long total = Math.max(0L, ms);
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]",
                total / 60000L, (total % 60000L) / 1000L, total % 1000L);
    }
}

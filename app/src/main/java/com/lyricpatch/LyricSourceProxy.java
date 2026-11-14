package com.lyricpatch;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** 用动态代理实现 SystemUI 里的 LyricSource 接口，无需编译期依赖 */
public final class LyricSourceProxy implements InvocationHandler {

    private static final String CLS_SOURCE =
            "com.android.systemui.statusbar.phone.LyricSource";
    private static final String CLS_PARSER =
            "com.android.systemui.statusbar.phone.LyricResponseParser";

    private static Object sSource;
    private static Method sParseLyricText;
    private static Method sParseLyrics;

    private LyricSourceProxy() {
    }

    public static synchronized Object get(ClassLoader cl) {
        if (sSource != null) return sSource;
        try {
            Class<?> sourceClass = Class.forName(CLS_SOURCE, false, cl);
            Class<?> parserClass = Class.forName(CLS_PARSER, false, cl);

            sParseLyricText = parserClass.getMethod("parseLyricText", String.class);
            sParseLyrics = parserClass.getMethod("parseLyrics", JSONObject.class);

            ClassLoader target = sourceClass.getClassLoader();
            if (target == null) target = cl;

            sSource = Proxy.newProxyInstance(target, new Class<?>[]{sourceClass},
                    new LyricSourceProxy());
            Util.log("LyricSource proxy created");
        } catch (Throwable t) {
            Util.logw("create LyricSource proxy failed", t);
        }
        return sSource;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("fetch".equals(name)) return fetch(firstArg(args), false);
        if ("fetchEnhanced".equals(name)) return fetch(firstArg(args), true);
        if ("toString".equals(name)) return "LyricPatchSource";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == firstArg(args);
        return null;
    }

    private static Object firstArg(Object[] args) {
        return args == null || args.length == 0 ? null : args[0];
    }

    /** enhanced = true 时表示 ROM 的逐字轮（fetchEnhanced） */
    private static Object fetch(Object track, boolean enhanced) {
        try {
            if (track == null || Thread.currentThread().isInterrupted()) return null;

            String title = str(track, "title");
            if (Util.isEmpty(title)) return null;

            String artist = str(track, "artist");
            String album = str(track, "album");
            String pkg = str(track, "packageName");
            String mediaId = str(track, "mediaId");
            long duration = lng(track, "durationMs");

            LyricFetcher.Bundle bundle =
                    LyricFetcher.resolve(title, artist, album, duration, pkg, mediaId);
            if (bundle == null) return null;

            Object lyrics = build(bundle, enhanced);
            if (lyrics == null) return null;
            // 逐字轮没有 word timing 就返回 null，让 ROM 走行级那一轮
            if (enhanced && !hasWordTiming(lyrics)) return null;
            return lyrics;
        } catch (Throwable t) {
            Util.logw("fetch failed", t);
            return null;
        }
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
}

package com.lyricpatch;

import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 逐字（word timing）模式下「长句显示不全」的修复。
 *
 * 逆向 SystemUI 的 LyricTextView#onDraw，它有两条完全分开的分支：
 *
 *   ① mWordTiming 非空 → 卡拉OK 自绘：整句先用 45% 透明度打底，
 *      再按「已唱宽度」clipRect 把高亮部分重画一遍。这条分支把文本钉死在
 *      x = 0（LTR）/ x = getWidth()（RTL），既不看 mOffset，也不会起跑马灯。
 *      于是只要句子比控件宽，右边那一截就永远画不出来 —— 这就是「显示不完全」。
 *
 *   ② mWordTiming 为空 → ROM 自己的跑马灯：mOffset 每 10ms 减少 mScrollSpeed（默认 4）像素，
 *      直到 (getWidth() - mOffset) 装得下整句，滚完停住；起播靠 mStartScrollRunnable 延迟 500ms。
 *
 * 但 LyricViewController#setWordTiming() 每次刷新（mPositionUpdateRunnable 每 250ms 一次）
 * 都会把 mStopped 置 true、并 removeCallbacks(mStartScrollRunnable)，
 * 所以逐字模式永远进不了 ②，长句只能被硬裁。
 *
 * 这里不碰 ROM 的任何绘制代码，只在 onDraw 外面套一层 canvas.translate(dx, 0)：
 * ① 画出来的「底字 + 高亮前缀」整体跟着 dx 平移；ROM 内部的 clipRect 是在
 * 平移之后的坐标系里执行的，所以高亮被视口裁得刚刚好，观感和原生跑马灯一致。
 * dx 完全按 ② 的节奏算：延迟 word_scroll_delay（默认 500ms）后每 10ms 走
 * word_scroll_speed（默认取 ROM 的 mScrollSpeed），滚到 textWidth - getWidth() 停住；
 * 滚动期间自己 postInvalidateDelayed 维持帧率（ROM 只每 250ms 才刷一次）。
 */
final class LyricScroll {

    /** 与 ROM 一致：每 10ms 走 mScrollSpeed 像素 */
    static final long FRAME_MS = 10L;

    /** 每个 LyricTextView 的滚动状态（TextSwitcher 里 current/next 各一个） */
    private static final Map<View, State> STATES = new WeakHashMap<>();

    private static Field sText;
    private static Field sWords;
    private static Field sTextWidth;
    private static Field sRtl;
    private static Field sScrollSpeed;
    private static Field sWordPos;
    private static boolean sResolved;

    /** Word 的时间字段，第一次用到才解 */
    private static Field sWordBegin;
    private static Field sWordEnd;
    private static boolean sWordResolved;

    private static long sConfAt;
    private static boolean sConfOn = true;
    private static int sConfSpeed;
    private static int sConfDelay = 500;

    /**
     * 一帧要用的结果：
     *   dx      平移量（px，负数=向左滚）
     *   done    已经滚到底
     *   delayMs 下次自己排重绘的间隔（0 = 不排，交给 ROM 的下一次刷新）
     */
    static final class Scroll {
        final float dx;
        final boolean done;
        final long delayMs;

        Scroll(float dx, boolean done, long delayMs) {
            this.dx = dx;
            this.done = done;
            this.delayMs = delayMs;
        }
    }

    private static final class State {
        /** mWordTiming 的实例：同一行内 ROM 反复复用同一个 List，换行才变 */
        Object words;
        String text;
        long startAt;
    }

    private LyricScroll() {
    }

    /**
     * 这一帧要不要接管绘制。返回 null 表示原样交给 ROM：
     * 开关关掉 / 没有逐字 / 没有文本 / 控件还没量好 / 文本本来就装得下。
     */
    static Scroll prepare(View view) {
        if (view == null) return null;

        refreshConf();
        if (!sConfOn) return null;
        if (!resolve(view.getClass())) return null;

        Object words = read(sWords, view);
        if (!(words instanceof List) || ((List<?>) words).isEmpty()) {
            forget(view);
            return null;
        }

        Object textObj = read(sText, view);
        if (!(textObj instanceof String)) {
            forget(view);
            return null;
        }
        String text = (String) textObj;
        if (text.isEmpty()) {
            forget(view);
            return null;
        }

        int width = view.getWidth();
        if (width <= 0) return null;

        float textWidth = measuredWidth(view, text);
        if (textWidth <= width) {          // 放得下：ROM 自己左/右对齐就挺好
            forget(view);
            return null;
        }

        long now = SystemClock.uptimeMillis();
        State st;
        synchronized (STATES) {
            st = STATES.get(view);
            if (st == null || st.words != words || !text.equals(st.text)) {
                st = new State();
                st.words = words;
                st.text = text;
                // 从半路 seek 进来时别再从头滚一遍：把已经唱过的那段先补进 startAt
                long already = 0L;
                Object posObj = read(sWordPos, view);
                long pos = posObj instanceof Long ? (Long) posObj : 0L;
                long first = firstBeginMs(words);
                if (pos > 0 && first > 0) already = Math.max(0L, pos - first);
                st.startAt = now - already;
                STATES.put(view, st);
                if (Util.isDebug() && Util.allow("scroll-line:" + text, 1000L)) {
                    Util.d("word scroll: 新行 text=" + (int) textWidth + "px > view="
                            + width + "px, 需滚 " + (int) (textWidth - width) + "px: "
                            + brief(text));
                }
            }
        }

        float maxScroll = textWidth - width;
        // 滚多快由「这一行自己要唱多久」决定：整段滚动刚好在句尾铺满，
        // 而不是一上来就按 4px/10ms 冲到底（那样句首秒就没）
        long span = spanMs(words);
        float speed = speedOf(view, maxScroll, span);

        // 还没到起播时间（默认 500ms）：这一帧不动，只在那一下把自己叫醒，
        // 省的为了一个静止画面跑 100fps
        long remaining = st.startAt + sConfDelay - now;
        if (remaining > 0) return new Scroll(0f, false, remaining);

        float progress = (-remaining / (float) FRAME_MS) * speed;
        boolean done = progress >= maxScroll;
        if (done) progress = maxScroll;

        Object rtlObj = read(sRtl, view);
        boolean rtl = rtlObj instanceof Boolean && (Boolean) rtlObj;

        // RTL 文本是右对齐画的，要往右滚才能露出句尾（和 ROM 跑马灯同方向）
        return new Scroll(Math.round(rtl ? progress : -progress), done, done ? 0L : FRAME_MS);
    }

    /** 配置热读，但每帧都去问一遍配置太亏，3 秒一次就够 */
    private static void refreshConf() {
        long now = SystemClock.uptimeMillis();
        if (sConfAt != 0 && now - sConfAt < 3000L) return;
        sConfAt = now;
        sConfOn = Util.confBool("word_scroll", true);
        sConfSpeed = Math.max(0, Util.confInt("word_scroll_speed", 0));
        sConfDelay = Math.max(0, Util.confInt("word_scroll_delay", 500));
    }

    private static synchronized boolean resolve(Class<?> type) {
        if (sResolved) return sText != null && sWords != null && sTextWidth != null && sRtl != null;
        sResolved = true;

        sText = find(type, "mText");
        sWords = find(type, "mWordTiming");
        sTextWidth = find(type, "mTextWidth");
        sRtl = find(type, "mTextRtl");
        sScrollSpeed = find(type, "mScrollSpeed");
        sWordPos = find(type, "mWordPositionMs");   // 可选的：拿不到就当 0

        boolean ok = sText != null && sWords != null && sTextWidth != null && sRtl != null;
        if (!ok) Util.w("LyricTextView 字段缺失，逐字长句滚动不可用");
        return ok;
    }

    private static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    private static Object read(Field f, Object target) {
        if (f == null || target == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ROM 在 onTextChanged 里算好了 mTextWidth，直接用；拿不到才自己量一遍 */
    private static float measuredWidth(View view, String text) {
        Object v = read(sTextWidth, view);
        if (v instanceof Integer && (Integer) v > 0) return (Integer) v;
        if (view instanceof TextView) {
            try {
                return ((TextView) view).getPaint().measureText(text);
            } catch (Throwable ignored) {
                // 量不出来就当 0，不滚动
            }
        }
        return 0f;
    }

    /**
     * 滚动手感。
     *  - word_scroll_speed 写了正数：就按那个固定速度（px / 10ms），老行为
     *  - 默认（0）：自动 —— 把 maxScroll 铺满这一行的演唱时长，句尾刚好露出尾巴
     */
    private static float speedOf(View view, float maxScroll, long span) {
        if (sConfSpeed > 0) return sConfSpeed;
        float perFrame = maxScroll * (float) FRAME_MS / Math.max(1L, span);
        if (perFrame <= 0f) return 1f;
        return Math.min(12f, perFrame);   // 再快也不超 12px/10ms，否则一样看不到字
    }

    /** 这一行大概唱多久（词级时间直接给了），拿不到就当 4 秒 */
    private static long spanMs(Object words) {
        long first = -1L;
        long last = -1L;
        try {
            List<?> list = (List<?>) words;
            for (int i = 0; i < list.size(); i++) {
                Object word = list.get(i);
                resolveWord(word);
                long begin = asLong(read(sWordBegin, word), -1L);
                long end = asLong(read(sWordEnd, word), -1L);
                if (begin >= 0 && first < 0) first = begin;
                if (end > last) last = end;
            }
        } catch (Throwable ignored) {
            // 读不到就按默认时长走
        }
        if (first < 0 || last <= first) return 4000L;
        return Math.max(700L, Math.min(15000L, last - first));
    }

    private static long firstBeginMs(Object words) {
        try {
            List<?> list = (List<?>) words;
            if (!list.isEmpty()) {
                Object word = list.get(0);
                resolveWord(word);
                return asLong(read(sWordBegin, word), -1L);
            }
        } catch (Throwable ignored) {
            // 拿不到就返回 -1
        }
        return -1L;
    }

    private static void resolveWord(Object word) {
        if (sWordResolved || word == null) return;
        sWordResolved = true;
        sWordBegin = find(word.getClass(), "beginMs");
        sWordEnd = find(word.getClass(), "endMs");
    }

    private static long asLong(Object v, long def) {
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private static void forget(View view) {
        synchronized (STATES) {
            STATES.remove(view);
        }
    }

    private static String brief(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}

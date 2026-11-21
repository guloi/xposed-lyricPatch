package com.lyricpatch;

import android.content.Context;
import com.lyricpatch.lib.ConfKeys;
import android.graphics.Canvas;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextSwitcher;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口。
 * 1) hook SystemUI 的 LyricSourceFactory#create(String)，往源列表里插入自己的 LyricSource。
 * 2) hook LyricViewController#stopLyric()，顺手清掉上一首残留的翻译副标题（见 hookStopLyric）。
 * 3) hook LyricTextView#onDraw()，补上逐字模式缺的跑马灯，修长句被裁掉（见 hookWordScroll）。
 *
 * 冗余设计（都不依赖编译期 API 声明）：
 *  - classloader 用反射从 param 上取（不同框架版本方法名不一样）
 *  - 用 Class.forName 探测是不是 SystemUI，而不是读 param.getPackageName()
 */
public final class LyricPatchModule extends XposedModule {

    private static final String TAG = "LyricPatch";

    /** 日志里会打出来，方便确认装的是哪一版 */
    private static final String VERSION = "4.64";
    private static final String CLS_FACTORY =
            "com.android.systemui.statusbar.phone.LyricSourceFactory";

    /** 状态栏歌词控制器：换歌后翻译残影就出在它身上 */
    private static final String CLS_CONTROLLER =
            "com.android.systemui.statusbar.phone.LyricViewController";

    /** ROM 的「显示翻译」：设备端模型 / 联网机翻，优先级高于源里自带的 tlyric */
    private static final String CLS_TRANSLATOR =
            "com.android.systemui.statusbar.phone.LyricTranslator";

    /** 状态栏歌词的那个自绘 TextView（跑马灯 + 逐字高亮都在它的 onDraw 里） */
    private static final String CLS_LYRIC_TEXT_VIEW =
            "com.android.systemui.util.LyricTextView";

    private static final String[] LOADER_GETTERS = {
            "getClassLoader",
            "getDefaultClassLoader",
            "getModuleClassLoader",
            "getTargetClassLoader",
            "getClassloader"
    };

    private static boolean sLoggedMiss;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded v" + VERSION, null);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            ClassLoader cl = resolveClassLoader(param);
            if (cl == null) {
                if (!sLoggedMiss) {
                    sLoggedMiss = true;
                    log(Log.WARN, TAG, "no classloader getter on " + param.getClass().getName()
                            + " methods=[" + dumpMethods(param.getClass()) + "]", null);
                }
                return;
            }

            Class<?> factory;
            try {
                factory = Class.forName(CLS_FACTORY, false, cl);
            } catch (Throwable notSystemUi) {
                return;   // 不是 SystemUI，安静跳过
            }

            Method create = factory.getDeclaredMethod("create", String.class);
            final Object source = LyricSourceProxy.get(cl);

            hook(create).intercept(chain -> {
                String setting = null;
                List<?> args = chain.getArgs();
                if (args != null && !args.isEmpty() && args.get(0) instanceof CharSequence) {
                    setting = args.get(0).toString();
                }
                boolean emptySetting = setting == null || setting.trim().isEmpty();

                // 记住用户配置的状态栏歌词源（可能是音理/lyricon 的本地 HTTP 服务）
                LyricFetcher.setUpstream(emptySetting ? null : setting);

                List<Object> out = new ArrayList<>();

                // 我们的源永远排第一：
                //  - 设置为空：正常补源（内置 Apple/LrcLib 垫底）
                //  - 设置非空：我们也会按同样协议去问那个 base，但能完整保留 yrc（逐字）；
                //    ROM 自带 HttpLyricSource 只读 lrc/tlyric，会把逐字丢掉
                // ROM 自带的每个源都包一层：没逐字就用我们的替换
                Object original = chain.proceed();
                if (original instanceof List) {
                    for (Object o : (List<?>) original) {
                        out.add(LyricSourceProxy.wrap(cl, o));
                    }
                }

                // 我们自己的源依旧排第一（ROM 列表为空时也能出词）
                Object src = source != null ? source : LyricSourceProxy.get(cl);
                if (src != null) out.add(0, src);

                // 每次 create 都打一条：能直接看出“源到底插进去没”和“设置里填了什么”
                if (Util.isDebug()) {
                    log(Log.DEBUG, TAG, "create: setting='" + brief(setting)
                            + "' proxy=" + (src != null) + " total=" + out.size(), null);
                }

                return out;
            });

            log(Log.INFO, TAG, "hooked " + CLS_FACTORY + "#create", null);

            hookConcreteSources(cl);
            hookStopLyric(cl);
            hookHolderText(cl);
            hookTranslator(cl);
            hookWordScroll(cl);

            // 一行把当前配置说完，排查时先看这条（log_level 决定后面还有多少细节）
            log(Log.INFO, TAG, "ready: " + Util.summary(), null);

            // hook 都装上了，告诉 App 侧“我活着”：设置页那个「已启用」就是看这个
            Util.startReporting(VERSION);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed", t);
        }
    }

    /** ROM 自带的三个具体源：直接 hook 它们的取词方法（不依赖工厂返回值） */
    private static final String[] CLS_SOURCES = {
            "com.android.systemui.statusbar.phone.AppleMusicLyricSource",
            "com.android.systemui.statusbar.phone.LrcLibLyricSource",
            "com.android.systemui.statusbar.phone.HttpLyricSource",
    };

    /**
     * 最底层的一道保险：不管 LyricSourceFactory#create 的返回值有没有被采用，
     * 只要 ROM 真去取词，就会进这三个类的 fetch/fetchEnhanced。
     * 拿到结果后：没逐字 → 换成我们自己的（含 yrc）；有逐字 → 原样放行。
     */
    private void hookConcreteSources(ClassLoader cl) {
        for (String name : CLS_SOURCES) {
            try {
                Class<?> type = Class.forName(name, false, cl);
                int count = 0;
                for (Method m : type.getDeclaredMethods()) {
                    boolean enhanced = "fetchEnhanced".equals(m.getName());
                    if (!enhanced && !"fetch".equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;

                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        // ROM 自带源也可能返回“纯音乐，请欣赏”这种占位词
                        if (LyricSourceProxy.shouldHideInstrumental(result)) {
                            log(Log.DEBUG, TAG, "source(" + type.getSimpleName() + "): 纯音乐，不出词", null);
                            return LyricSourceProxy.blankForRom();
                        }
                        if (LyricSourceProxy.resultHasWords(result)) return result;

                        List<?> args = chain.getArgs();
                        Object track = args != null && !args.isEmpty() ? args.get(0) : null;
                        Object ours = LyricSourceProxy.fetchFor(track, enhanced);
                        if (ours != null) {
                            if (Util.isDebug()) {
                                log(Log.DEBUG, TAG, "source(" + type.getSimpleName() + ")"
                                        + (enhanced ? "#fetchEnhanced" : "#fetch")
                                        + ": 无逐字，用我们的结果替换", null);
                            }
                            return ours;
                        }
                        return result;
                    });
                    count++;
                }
                log(Log.INFO, TAG, "hooked " + name + " (" + count + ")", null);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook source failed: " + name, t);
            }
        }
    }

    /**
     * 修另一个 ROM 显示 bug：逐字（word timing）模式下的长句不会滚，右边一截直接被裁掉。
     *
     * LyricTextView#onDraw 分两条路：有 mWordTiming 就走卡拉OK 自绘（固定 x 画「底字 45%
     * 透明度 + 已唱前缀高亮」），没逐字才走 ROM 自己的跑马灯（mOffset 每帧减 mScrollSpeed）。
     * 而 LyricViewController#setWordTiming 每 250ms 刷新一次都会把 mStopped 置 true 并移掉
     * mStartScrollRunnable，所以逐字模式下永远没有滚动。
     *
     * 这里不去改它的绘制：只在 onDraw 外套一层 canvas.translate(dx, 0)，让自绘的
     * 「底字 + 高亮前缀」整体平移；dx 由 {@link LyricScroll} 按 ROM 跑马灯的节奏算出来，
     * 滚动期间自己排重绘（ROM 只每 250ms 刷一次，不自驱会很卡）。
     */
    private void hookWordScroll(ClassLoader cl) {
        try {
            Class<?> type = Class.forName(CLS_LYRIC_TEXT_VIEW, false, cl);
            Method onDraw = type.getDeclaredMethod("onDraw", Canvas.class);

            hook(onDraw).intercept(chain -> {
                List<?> args = chain.getArgs();
                Object self = chain.getThisObject();
                if (!(self instanceof View)) return chain.proceed();
                if (args == null || args.isEmpty() || !(args.get(0) instanceof Canvas)) {
                    return chain.proceed();
                }

                View view = (View) self;
                Canvas canvas = (Canvas) args.get(0);

                // 这里在 onDraw 里跑，出任何岔子都不能拖累 SystemUI 绘制
                LyricScroll.Scroll scroll;
                try {
                    scroll = LyricScroll.prepare(view);
                } catch (Throwable ignored) {
                    scroll = null;
                }
                if (scroll == null) return chain.proceed();   // 短句/无逐字：ROM 原生逻辑

                Object result = null;
                if (scroll.dx == 0f) {
                    result = chain.proceed();
                } else {
                    canvas.save();
                    canvas.translate(scroll.dx, 0f);
                    try {
                        result = chain.proceed();
                    } finally {
                        canvas.restore();
                    }
                }

                if (scroll.delayMs > 0) {
                    view.postInvalidateDelayed(scroll.delayMs);
                }
                return result;
            });

            log(Log.INFO, TAG, "hooked " + CLS_LYRIC_TEXT_VIEW + "#onDraw (长句滚动)", null);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook LyricTextView failed", t);
        }
    }

    /**
     * 修 SystemUI 自己的一个 bug：换歌后上一首的翻译会留在状态栏上。
     *
     * LyricViewController#stopLyric() 的顺序是：
     *     mCurrentLyricText = null;
     *     mCurrentTranslatedText = null;
     *     setTextForAllHolders(null, null, null, 0);
     *
     * 而 setTextForAllHolders 是靠 TextUtils.equals(旧缓存值, 新值) 来决定要不要刷新的，
     * null 和 null 相等，于是这次「清空」实际什么都没做。
     *
     * 主歌词视图后面一定会被新歌的歌词覆盖，看不出异常；翻译副标题
     * （LyricViewHolder#mSubtitleTextSwitcher）却再没人碰它。下一首没翻译时
     * Cue.translatedText 依旧是 null，又是 equals(null, null) 跳过刷新，
     * 于是上一首的翻译就一直挂在那里。
     *
     * 所以这里在 stopLyric 之后，按 ROM 自己 setSubtitle(空) 的做法把副标题清一遍。
     * 注意必须在 proceed 之后做：ROM 的方法是先置 null 再刷新的。
     */
    private void hookStopLyric(ClassLoader cl) {
        try {
            Class<?> controller = Class.forName(CLS_CONTROLLER, false, cl);
            Method stop = controller.getDeclaredMethod("stopLyric");

            hook(stop).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                clearSubtitle(readField(self, "mOverlayLyricViewHolder"));
                clearSubtitle(readField(self, "mInlineLyricViewHolder"));
                return result;
            });

            log(Log.INFO, TAG, "hooked " + CLS_CONTROLLER + "#stopLyric", null);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook stopLyric failed", t);
        }
    }

    /**
     * 复刻 setSubtitle(holder, 空) 并顺手把 TextSwitcher 的缓冲视图也清掉。
     * TextSwitcher 里有两层 TextView（current / next），只清 current 的话，
     * 下次 setText 动画会把残留的旧译文翻上来。
     */
    private void clearSubtitle(Object holder) {
        Object switcher = readField(holder, "mSubtitleTextSwitcher");
        if (!(switcher instanceof TextSwitcher)) return;
        try {
            TextSwitcher ts = (TextSwitcher) switcher;

            // 清之前先看看里面到底有没有东西（getText 和自绘字段 mText 都算）
            String before = subtitleText(ts.getCurrentView());
            if (Util.isEmpty(before)) before = subtitleText(ts.getNextView());

            silence(ts.getCurrentView());
            silence(ts.getNextView());
            ts.setVisibility(View.GONE);

            // 只在意「真的擦掉了东西」的时候打日志，平时不吵
            if (!Util.isEmpty(before) && Util.isDebug()) {
                log(Log.DEBUG, TAG, "cleared subtitle: '" + brief(before) + "'", null);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "clear subtitle failed", t);
        }
    }

    /** 读一个副标题 view 当前“看得见的内容”：getText + LyricTextView 的自绘 mText */
    private static String subtitleText(View v) {
        if (!(v instanceof TextView)) return null;
        CharSequence t = ((TextView) v).getText();
        String text = t == null ? null : t.toString().trim();
        Object mText = readField(v, "mText");
        if (mText instanceof String && !((String) mText).trim().isEmpty()) {
            return (String) mText;
        }
        return text;
    }

    /**
     * 把一个 LyricTextView 真正清空。
     * 它是自定义 View，onDraw 里是自己画的，用的是它自己的 mText / mOffset，
     * 所以光 setText("") 不一定够，这几个字段一并抹掉。
     */
    private void silence(View v) {
        if (!(v instanceof TextView)) return;
        TextView tv = (TextView) v;
        tv.setText("");
        writeField(v, "mText", "");      // LyricTextView#mText
        writeField(v, "mOffset", 0);      // LyricTextView#mOffset
        writeField(v, "mWordTiming", null);   // 逐字高亮数据也清掉
    }

    /**
     * 沿继承链找字段。
     *
     * 注意：实例的真实类型往往是子类（如 HomeStatusBarViewBinderImpl$LyricController），
     * 而 mOverlayLyricViewHolder / mCurrentTranslatedText 都声明在父类 LyricViewController 上，
     * 只用 getClass().getDeclaredField() 会全部拿不到 → 所有读写静默失败。
     */
    private static Field findField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable ignored) {
                // 没有得到就继续往父类找
            }
        }
        return null;
    }

    /** 反射读一个字段（holder / controller 都是 SystemUI 的类，不能用强类型） */
    private static Object readField(Object target, String name) {
        if (target == null) return null;
        try {
            Field f = findField(target, name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 兜死「上一首翻译残影」。
     *
     * LyricViewController#setTextForAllHolders 是用
     *     TextUtils.equals(旧缓存, 新值)
     * 来决定要不要刷新的，而 stopLyric() 会把 mCurrentTranslatedText 置 null。
     * 于是下一首没翻译时：equals(null, null) 为 true → 副标题直接跳过刷新
     * → 屏幕上留的还是上一首的翻译（主歌词因为必定不同所以看不出问题）。
     *
     * 这里在调用前把缓存字段改成空串（非 null），让 equals("", null) 为 false，
     * 内部的 setSubtitle(holder, null) 就会老老实实把副标题清空 + 隐藏。
     */
    private void hookHolderText(ClassLoader cl) {
        try {
            Class<?> controller = Class.forName(CLS_CONTROLLER, false, cl);

            Method setter = null;
            for (Method m : controller.getDeclaredMethods()) {
                if (m.getName().equals("setTextForAllHolders") && m.getParameterCount() == 4) {
                    setter = m;
                    break;
                }
            }
            if (setter == null) {
                log(Log.WARN, TAG, "setTextForAllHolders not found", null);
                return;
            }

            hook(setter).intercept(chain -> {
                List<?> args = chain.getArgs();
                Object text = args != null && args.size() > 0 ? args.get(0) : null;
                Object translated = args != null && args.size() > 1 ? args.get(1) : null;
                Object self = chain.getThisObject();

                // 顺手把 SystemUI 的 Context 递过去：配置就是靠它走 provider 读的
                Object ctx = readField(self, "mContext");
                if (ctx instanceof Context) Util.setContext((Context) ctx);

                boolean noTranslation = translated == null
                        || translated.toString().trim().isEmpty();

                if (Util.isDebug()) {
                    log(Log.DEBUG, TAG, "holder: lyric=" + brief(text)
                            + " | tl=" + brief(translated), null);
                }

                // ① 让 ROM 内部 equals(旧值, 新值) 判不出相等，逼它走清空分支
                if (noTranslation) {
                    writeField(self, "mCurrentTranslatedText", "");
                }

                Object result = chain.proceed();

                if (Util.isDebug()) {
                    maybeDumpContainer(self);
                }

                // 最终兑底：屏幕上的 mCurrentLyrics 没逐字就换成我们的
                maybeInjectWordTiming(self);

                // ② 这一行没翻译 & 机翻也没给出来 → 干脆不显示（hide_empty_translation）
                if (noTranslation && Util.confBool(ConfKeys.HIDE_EMPTY_TRANSLATION, true)) {
                    clearSubtitle(readField(self, "mOverlayLyricViewHolder"));
                    clearSubtitle(readField(self, "mInlineLyricViewHolder"));
                }
                return result;
            });

            log(Log.INFO, TAG, "hooked " + CLS_CONTROLLER + "#setTextForAllHolders", null);

            // 核弹开关：想看看到底是不是这行字在作怪时用
            // hide_translation_always=true → setSubtitle 永远收到 null，任何翻译都不显示
            Method subtitle = null;
            for (Method m : controller.getDeclaredMethods()) {
                if (m.getName().equals("setSubtitle") && m.getParameterCount() == 2) {
                    subtitle = m;
                    break;
                }
            }
            if (subtitle != null) {
                hook(subtitle).intercept(chain -> {
                    if (!Util.confBool(ConfKeys.HIDE_TRANSLATION_ALWAYS, false)) return chain.proceed();
                    List<?> args = chain.getArgs();
                    if (args == null || args.size() < 2) return chain.proceed();
                    return chain.proceed(new Object[]{args.get(0), null});
                });
                log(Log.INFO, TAG, "hooked " + CLS_CONTROLLER + "#setSubtitle", null);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook setTextForAllHolders failed", t);
        }
    }

    private static long sLastDumpAt;

    /** 每 5 秒最多 dump 一次视图树，用来看清“那行字”到底是哪个控件 */
    private void maybeDumpContainer(Object self) {
        long now = System.currentTimeMillis();
        if (now - sLastDumpAt < 5000L) return;
        sLastDumpAt = now;

        Object overlay = readField(self, "mOverlayLyricViewHolder");
        Object inline = readField(self, "mInlineLyricViewHolder");
        dumpHolder("overlay", overlay);
        if (inline != overlay) dumpHolder("inline", inline);
    }

    private void dumpHolder(String tag, Object holder) {
        Object container = readField(holder, "mLyricContainer");
        if (!(container instanceof View)) {
            log(Log.WARN, TAG, "tree(" + tag + "): 拿不到 container（holder=" + holder + "）", null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        dumpView((View) container, sb, 0, 4);
        log(Log.DEBUG, TAG, "tree(" + tag + "):" + sb, null);
    }

    private void dumpView(View v, StringBuilder sb, int depth, int maxDepth) {
        sb.append('\n');
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName())
                .append(" 0x").append(Integer.toHexString(v.getId()))
                .append(" vis=").append(v.getVisibility())
                .append(" h=").append(v.getHeight());

        if (v instanceof TextView) {
            sb.append(" text='").append(brief(((TextView) v).getText())).append('\'');
            Object mText = readField(v, "mText");
            if (mText instanceof String && !((String) mText).isEmpty()) {
                sb.append(" mText='").append(brief(mText)).append('\'');
            }
        }

        if (depth >= maxDepth || !(v instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) v;
        for (int i = 0; i < group.getChildCount(); i++) {
            dumpView(group.getChildAt(i), sb, depth + 1, maxDepth);
        }
    }

    private static String sInjectedKey;
    private static volatile boolean sInjecting;
    private static long sLastAttemptAt;

    /**
     * 最后一道兑底：ROM 显示用的就是 mCurrentLyrics，这里发现它没逐字，
     * 就按当前媒体会话的曲名去问一次我们的源（netease / lrclib / kugou…），
     * 拿到带 yrc 的 Lyrics 后替换 mCurrentLyrics 并让 ROM 重绘。
     */
    private void maybeInjectWordTiming(Object self) {
        if (self == null) return;
        if (!Util.confBool(ConfKeys.AUTO_WORD_TIMING, true)) return;

        Object lyrics = readField(self, "mCurrentLyrics");
        if (lyrics == null || LyricSourceProxy.resultHasWords(lyrics)) return;

        Object mc = readField(self, "mCurrentMediaController");
        if (mc == null) return;

        // 白名单：只给指定 App 注入（避免把 B 站视频标题当歌名）
        String pkg = callString(mc, "getPackageName");
        if (!Util.pkgAllowed(pkg)) {
            if (Util.isDebug()) {
                log(Log.DEBUG, TAG, "inject 跳过（不在白名单）: pkg=" + pkg, null);
            }
            return;
        }

        String title = metaString(mc, "android.media.metadata.TITLE");
        String artist = metaString(mc, "android.media.metadata.ARTIST");
        long dur = metaLong(mc, "android.media.metadata.DURATION");
        if (Util.isEmpty(title)) return;

        final String key = title + "|" + artist + "|" + dur;
        if (key.equals(sInjectedKey) || sInjecting) return;

        // 注意：这里不写 sInjectedKey —— 换歌瞬间 mCurrentLyrics 可能还是 null，
        // 写了就会“一次失败，整首都不再试”。成功之后才记下。
        long now = System.currentTimeMillis();
        if (now - sLastAttemptAt < 1500L) return;
        sLastAttemptAt = now;
        sInjecting = true;

        final String fTitle = title;
        final String fArtist = artist;
        final long fDur = dur;
        final Object ctrl = self;

        new Thread(() -> {
            try {
                Object base = readField(ctrl, "mCurrentLyrics");
                Object target = null;

                // ① 先做“估算逐字”：纯本地、毫秒级，不会把歌词拖慢
                if (Util.confBool("estimate_word_timing", true)) {
                    target = LyricSourceProxy.estimateWords(base);
                    if (target != null) {
                        log(Log.DEBUG, TAG, "inject: 估算逐字（本地即时） (" + fTitle + ")", null);
                    }
                }

                // ② 想要真逐字再联网（默关闭，避免显示慢）
                if (Util.confBool("prefer_network_word_timing", false)) {
                    String yrc = LyricFetcher.wordTimingOnly(fTitle, fArtist, fDur, null);
                    if (!Util.isEmpty(yrc)) {
                        Object merged = LyricSourceProxy.mergeWords(base, yrc);
                        if (merged != null) target = merged;
                    }
                }

                if (target == null) {
                    // 不记 key，下一行刷新会（限速 1.5s 后）再试一次
                    log(Log.DEBUG, TAG, "inject: 暂时没有逐字，稍后重试 (" + fTitle + ")", null);
                    return;
                }

                final Object merged = target;
                Runnable apply = () -> {
                    try {
                        writeField(ctrl, "mCurrentLyrics", merged);
                        Method update = findMethod(ctrl, "updateDisplayedLyric");
                        if (update != null) update.invoke(ctrl);
                        sInjectedKey = key;
                        log(Log.INFO, TAG, "inject: 已注入逐字 (" + fTitle + ")", null);
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "inject apply failed", t);
                    }
                };

                Object handler = readField(ctrl, "mHandler");
                if (handler instanceof Handler) {
                    ((Handler) handler).post(apply);
                } else {
                    apply.run();
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "inject failed", t);
            } finally {
                sInjecting = false;
            }
        }, "LyricPatch-inject").start();
    }

    private static String callString(Object target, String method) {
        try {
            Method m = findMethod(target, method);
            if (m == null) return null;
            Object v = m.invoke(target);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String metaString(Object controller, String metaKey) {
        try {
            Method getMd = findMethod(controller, "getMetadata");
            if (getMd == null) return null;
            Object md = getMd.invoke(controller);
            if (md == null) return null;
            Object v = md.getClass().getMethod("getString", String.class).invoke(md, metaKey);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long metaLong(Object controller, String metaKey) {
        try {
            Method getMd = findMethod(controller, "getMetadata");
            if (getMd == null) return 0L;
            Object md = getMd.invoke(controller);
            if (md == null) return 0L;
            Object v = md.getClass().getMethod("getLong", String.class).invoke(md, metaKey);
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Method findMethod(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /** 日志里只留前 16 个字符，避免刷屏 */
    private static String brief(Object o) {
        if (o == null) return "null";
        String s = o.toString().replace('\n', ' ');
        return s.length() <= 16 ? s : s.substring(0, 16) + "…";
    }

    private static void writeField(Object target, String name, Object value) {
        if (target == null) return;
        try {
            Field f = findField(target, name);
            if (f == null) return;
            f.setAccessible(true);
            f.set(target, value);
        } catch (Throwable ignored) {
            // 改不了就算了，最多保留旧行为
        }
    }

    /**
     * ROM 的「显示翻译」走 LyricTranslator.apply()：逐行先机翻，只有机翻这一行返回空、
     * 且目标语言是中文时，才回退用 Cue.translatedText（也就是我们给的网易云/QQ 翻译）。
     * 所以配了 translation_providers 却看不到效果，就是因为机翻把它盖住了。
     *
     * 一份配置（lyricpatch.conf，热读，改完下次取词生效）：
     *   machine_translation=auto     ROM 原行为：机翻优先
     *   machine_translation=source   源里有翻译就用源里的，一行都没有才机翻兜底
     *   machine_translation=off      永不机翻（源没翻译的歌第二行就是空白）
     * 老键 prefer_source_translation / disable_machine_translation 仍兼容，等价 source / off。
     *
     * 只影响「第二行显示什么」，取词逻辑不变；默认 auto，完全不碰 ROM 原本行为。
     */
    private void hookTranslator(ClassLoader cl) {
        try {
            Class<?> translator = Class.forName(CLS_TRANSLATOR, false, cl);

            // 不硬写参数类型（Context / Lyrics 都是 SystemUI 侧的），按名字+参数个数找
            Method apply = null;
            for (Method m : translator.getDeclaredMethods()) {
                if (m.getName().equals("apply") && m.getParameterCount() == 5) {
                    apply = m;
                    break;
                }
            }
            if (apply == null) {
                log(Log.WARN, TAG, "LyricTranslator#apply not found", null);
                return;
            }

            final Method target = apply;
            hook(target).intercept(chain -> {
                // 用 getArgs() 而不是 getArg(int)：不同框架版本这两个方法的可用性不一样
                List<?> args = chain.getArgs();
                Object lyrics = args != null && args.size() > 2 ? args.get(2) : null;
                if (lyrics == null) return chain.proceed();

                String mode = machineMode();
                if (MODE_AUTO.equals(mode)) return chain.proceed();

                int n = countTranslations(lyrics);
                int total = cueCount(lyrics);

                if (MODE_OFF.equals(mode)) {
                    // 彻底不机翻：源里没翻译就是没翻译
                    log(Log.DEBUG, TAG, "machine translation off, sourceTranslations="
                            + n + "/" + total, null);
                    return lyrics;
                }

                // MODE_SOURCE：源翻译够多才用它；只有零星几行就让 ROM 机翻兜底
                if (n > 0) {
                    int need = Math.max(1, (total + 2) / 3);   // 至少盖住 1/3 才值得跳机翻
                    if (n >= need) {
                        log(Log.DEBUG, TAG, "use source translation, skip machine translation: "
                                + n + "/" + total, null);
                        return lyrics;
                    }
                    log(Log.DEBUG, TAG, "source translation too sparse (" + n + "/" + total
                            + "), fallback to machine translation", null);
                }
                log(Log.DEBUG, TAG, "no source translation, fallback to machine translation", null);
                return chain.proceed();
            });

            log(Log.INFO, TAG, "hooked " + CLS_TRANSLATOR + "#apply", null);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook LyricTranslator failed", t);
        }
    }

    /** 机翻策略：auto（ROM 原行为）/ source（源翻译优先，没有才机翻）/ off（永不机翻） */
    private static final String MODE_AUTO = "auto";
    private static final String MODE_SOURCE = "source";
    private static final String MODE_OFF = "off";

    private static String machineMode() {
        // 兼容 2.1 那两个布尔开关
        if (Util.confBool("prefer_source_translation", false)) return MODE_SOURCE;
        if (Util.confBool("disable_machine_translation", false)) return MODE_OFF;

        String mode = Util.conf(ConfKeys.MACHINE_TRANSLATION, MODE_AUTO).toLowerCase(Locale.ROOT);
        if (MODE_SOURCE.equals(mode)) return MODE_SOURCE;
        if (MODE_OFF.equals(mode)) return MODE_OFF;
        return MODE_AUTO;
    }

    /** 源里已经带了几行翻译（Cue#translatedText 非空的行数） */
    private static int countTranslations(Object lyrics) {
        Object cues = readField(lyrics, "mCues");
        if (!(cues instanceof Map)) return 0;
        int n = 0;
        try {
            for (Object cue : ((Map<?, ?>) cues).values()) {
                Object t = readField(cue, "translatedText");
                if (t instanceof CharSequence && t.toString().trim().length() > 0) n++;
            }
        } catch (Throwable ignored) {
            // 读不到就当没有
        }
        return n;
    }

    private static int cueCount(Object lyrics) {
        Object cues = readField(lyrics, "mCues");
        return cues instanceof Map ? ((Map<?, ?>) cues).size() : 0;
    }

    /** 把一个类所有无参方法名拼起来，方便定位框架真实方法名 */
    private static String dumpMethods(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Method m : type.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(m.getName());
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return sb.toString();
    }

    /** 反射取 classloader，兼容各种命名 */
    private static ClassLoader resolveClassLoader(Object param) {
        if (param == null) return null;
        Class<?> type = param.getClass();

        for (String name : LOADER_GETTERS) {
            Method m = findNoArgMethod(type, name);
            if (m == null) continue;
            try {
                m.setAccessible(true);
                Object v = m.invoke(param);
                if (v instanceof ClassLoader) return (ClassLoader) v;
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
                }
            } catch (Throwable ignored) {
                // 继续找父类
            }
        }
        for (Class<?> itf : type.getInterfaces()) {
            try {
                for (Method m : itf.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return null;
    }
}

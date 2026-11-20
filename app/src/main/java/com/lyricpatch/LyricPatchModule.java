package com.lyricpatch;

import android.util.Log;
import android.view.View;
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
 *
 * 冗余设计（都不依赖编译期 API 声明）：
 *  - classloader 用反射从 param 上取（不同框架版本方法名不一样）
 *  - 用 Class.forName 探测是不是 SystemUI，而不是读 param.getPackageName()
 */
public final class LyricPatchModule extends XposedModule {

    private static final String TAG = "LyricPatch";
    private static final String CLS_FACTORY =
            "com.android.systemui.statusbar.phone.LyricSourceFactory";

    /** 状态栏歌词控制器：换歌后翻译残影就出在它身上 */
    private static final String CLS_CONTROLLER =
            "com.android.systemui.statusbar.phone.LyricViewController";

    /** ROM 的「显示翻译」：设备端模型 / 联网机翻，优先级高于源里自带的 tlyric */
    private static final String CLS_TRANSLATOR =
            "com.android.systemui.statusbar.phone.LyricTranslator";

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
        log(Log.INFO, TAG, "module loaded", null);
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

                List<Object> out = new ArrayList<>();

                // 没填自定义源：先试我们的（内置 Apple/LrcLib 垫底）
                if (emptySetting && source != null) out.add(source);

                Object original = chain.proceed();
                if (original instanceof List) out.addAll((List<?>) original);

                // 填了自定义 https 源：我们的垫底，自定义源全挂也能出词
                if (!emptySetting && source != null) out.add(source);

                return out;
            });

            log(Log.INFO, TAG, "hooked " + CLS_FACTORY + "#create", null);

            hookStopLyric(cl);
            hookHolderText(cl);
            hookTranslator(cl);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed", t);
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
            ts.setCurrentText("");
            View next = ts.getNextView();
            if (next instanceof TextView) ((TextView) next).setText("");
            ts.setVisibility(View.GONE);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "clear subtitle failed", t);
        }
    }

    /** 反射读一个字段（holder 是 SystemUI 的类，不能用强类型） */
    private static Object readField(Object target, String name) {
        if (target == null) return null;
        try {
            Field f = target.getClass().getDeclaredField(name);
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

                boolean noTranslation = translated == null
                        || translated.toString().trim().isEmpty();

                if (Util.confBool("debug_log", false)) {
                    log(Log.INFO, TAG, "holder: lyric=" + brief(text)
                            + " | tl=" + brief(translated), null);
                }

                // ① 让 ROM 内部 equals(旧值, 新值) 判不出相等，逼它走清空分支
                if (noTranslation) {
                    writeField(self, "mCurrentTranslatedText", "");
                }

                Object result = chain.proceed();

                // ② 这一行没翻译 & 机翻也没给出来 → 干脆不显示（hide_empty_translation）
                if (noTranslation && Util.confBool("hide_empty_translation", true)) {
                    clearSubtitle(readField(self, "mOverlayLyricViewHolder"));
                    clearSubtitle(readField(self, "mInlineLyricViewHolder"));
                }
                return result;
            });

            log(Log.INFO, TAG, "hooked " + CLS_CONTROLLER + "#setTextForAllHolders", null);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook setTextForAllHolders failed", t);
        }
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
            Field f = target.getClass().getDeclaredField(name);
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
                    log(Log.INFO, TAG, "machine translation off, sourceTranslations="
                            + n + "/" + total, null);
                    return lyrics;
                }

                // MODE_SOURCE：有源翻译就不让机翻盖掉，没有才让 ROM 机翻兜底
                if (n > 0) {
                    log(Log.INFO, TAG, "use source translation, skip machine translation: "
                            + n + "/" + total, null);
                    return lyrics;
                }
                log(Log.INFO, TAG, "no source translation, fallback to machine translation", null);
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

        String mode = Util.conf("machine_translation", MODE_AUTO).toLowerCase(Locale.ROOT);
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

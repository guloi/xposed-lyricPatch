package com.lyricpatch;

import android.util.Log;
import android.view.View;
import android.widget.TextSwitcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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

    /** 复刻 LyricViewController#setSubtitle(holder, 空字符串) 的行为 */
    private void clearSubtitle(Object holder) {
        Object switcher = readField(holder, "mSubtitleTextSwitcher");
        if (!(switcher instanceof TextSwitcher)) return;
        try {
            TextSwitcher ts = (TextSwitcher) switcher;
            ts.setCurrentText("");
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

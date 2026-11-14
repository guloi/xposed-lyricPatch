package com.lyricpatch;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口。
 * 只 hook SystemUI 的 LyricSourceFactory#create(String)，往源列表里插入自己的 LyricSource。
 */
public final class LyricPatchModule extends XposedModule {

    private static final String TAG = "LyricPatch";
    private static final String TARGET_PACKAGE = "com.android.systemui";
    private static final String CLS_FACTORY =
            "com.android.systemui.statusbar.phone.LyricSourceFactory";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded");
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;

        try {
            ClassLoader cl = param.getClassLoader();
            Class<?> factory = Class.forName(CLS_FACTORY, false, cl);
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

            log(Log.INFO, TAG, "hooked " + CLS_FACTORY + "#create");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed", t);
        }
    }
}

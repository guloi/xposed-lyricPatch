package io.github.libxposed.api;

import java.lang.reflect.Executable;

/** 编译期 stub */
public abstract class XposedModule implements XposedInterface {

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
    }

    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
    }

    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
    }

    public void log(int priority, String tag, String message) {
    }

    public void log(int priority, String tag, String message, Throwable throwable) {
    }

    @Override
    public HookBuilder hook(Executable executable) {
        return null;
    }
}

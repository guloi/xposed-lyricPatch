package io.github.libxposed.api;

/** 编译期 stub */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        ClassLoader getModuleClassLoader();

        boolean isSystemServer();
    }

    interface PackageLoadedParam {
        ClassLoader getClassLoader();

        ClassLoader getDefaultClassLoader();

        String getPackageName();

        boolean isFirstPackage();
    }

    interface SystemServerStartingParam {
        ClassLoader getClassLoader();
    }
}

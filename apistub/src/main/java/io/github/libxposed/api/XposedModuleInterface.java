package io.github.libxposed.api;

/**
 * 编译期 stub。
 *
 * 注意：本工程的代码刻意不在编译期调用 param 上的任何方法，
 * 因为不同框架版本的 PackageLoadedParam 方法名不一样（实测 102 上
 * getClassLoader() 就不存在，抛 NoSuchMethodError）。
 * 所有参数取值走反射探测，所以这里只保留类型本身。
 */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
    }

    interface PackageLoadedParam {
    }

    interface SystemServerStartingParam {
    }
}

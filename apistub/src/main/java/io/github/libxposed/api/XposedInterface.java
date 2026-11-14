package io.github.libxposed.api;

import java.lang.reflect.Executable;
import java.util.List;

/** 编译期 stub：仅为能编译通过，运行时由框架提供真实现 */
public interface XposedInterface {

    HookBuilder hook(Executable executable);

    interface HookBuilder {
        HookHandle intercept(Hooker hooker);
    }

    interface HookHandle {
        void unhook();
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface Chain {
        Object getThisObject();

        List<Object> getArgs();

        Object getArg(int index);

        Object getResult();

        void setResult(Object result);

        Object proceed() throws Throwable;

        Object proceed(Object[] args) throws Throwable;
    }
}

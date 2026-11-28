# R8 规则（只在 release / minifyEnabled true 时生效）

# ---- 1. LSPosed 入口 ----
# META-INF/xposed/java_init.list 里写死了类名，框架用反射 Class.forName + 构造加载，
# 类名、包名、构造器、覆写方法名一个都不能被混淆或裁掉。
-keep class com.lyricpatch.LyricPatchModule { *; }

# ---- 2. libxposed API ----
# 只用了 compileOnly，运行时由框架提供，R8 别报“找不到类”
-dontwarn io.github.libxposed.**

# ---- 3. SystemUI ----
# SystemUI 的类编译期不存在，全靠 Class.forName 反射探测，同理别报警
-dontwarn com.android.systemui.**

# ---- 4. 元数据 ----
# 注解和泛型签名要留给 Compose 运行时与框架回调；行号表留着方便看崩溃堆栈
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

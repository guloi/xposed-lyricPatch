package com.lyricpatch.lib;

/**
 * 全项目唯一的版本来源。
 *
 * build.gradle 会读这里的 CODE / NAME 去设 APK 的 versionCode / versionName，
 * 模块代码也直接引用 [NAME]（上报给设置页的「SystemUI 侧」）。
 *
 * `module.prop` 里的 version 由 build.gradle 的 syncModulePropVersion 在构建时
 * 自动写入，不用手动同步；其余地方都不用再改。
 */
public final class Version {

    public static final int CODE = 145;
    public static final String NAME = "5.14";

    private Version() {
    }
}

# LyricPatch — SystemUI 状态栏歌词补源模块

> [!WARNING]
> 这是一个针对DerpFest的系统UI的模块 由AI生成

针对 `com.android.systemui` 自带的状态栏歌词（`com.android.systemui.statusbar.phone.*`），
用 libxposed API 102 往 `LyricSourceFactory#create(String)` 的源列表里插入一个**网易云 + LRCLIB** 的歌词源。

调用链：

```
MediaSessionManager → LyricViewController.selectActiveSession()
  → updateCurrentSession()
      sources = LyricSourceFactory.create(Settings.Secure "status_bar_lyric_sources")
      ① word_timing 开：逐个 fetchEnhanced()，取第一个 hasWordTiming()
      ② 否则：逐个 fetch()
      ③ show_translation 开：LyricTranslator.apply()
  → 主线程 mCurrentLyrics
  → updateDisplayedLyric(): mCues.floorEntry(播放位置) → setTextForAllHolders()
  → HomeStatusBarViewBinderImpl$LyricController.showLyricView()
```

相关 Settings.Secure：

- `status_bar_show_lyric`（总开关，bool，默认 false）
- `status_bar_lyric_sources`（源列表，`;` 分隔的 https base）
- `status_bar_lyric_position` / `_word_timing` / `_show_translation`
  / `_translation_target` / `_translation_wifi_only` / `_hide_icon_clock_right`

## 构建

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

工具链（4.19+）：

| 项 | 版本 |
|---|---|
| compileSdk / targetSdk | **37（Android 17）**，跟 ROM 的 SystemUI 对齐 |
| minSdk | 29 |
| AGP / Gradle | 9.0.0 / 9.3.0（wrapper 与 CI 同步） |
| Kotlin | 2.2.0，由 AGP 内置提供（不引 kotlin-android 插件） |
| Compose 编译器插件 | `org.jetbrains.kotlin.plugin.compose` 2.2.0 |
| Compose BOM | 2024.12.01 |

需要 JDK 17+；`android-37` 平台由 AGP 自动补装（CI 镜像的 licenses 已接受）。

AGP 9 自带 Kotlin：再加 `org.jetbrains.kotlin.android` 会因 `kotlin` 扩展重名直接失败；
但 `buildFeatures.compose` 仍要求显式应用 Compose 编译器插件，版本需与内置 Kotlin 一致。
`jvmTarget` 取自 `compileOptions`（17）。
targetSdk 37 会强制 edge-to-edge，设置页已经用 `enableEdgeToEdge()` + Scaffold insets 处理好了。


## 安装 / 启用

测试使用derpfest v17 giuliac official
1. 装 APK
2. LSPosed 里找到「歌词补丁」→ 启用 → 勾选 **系统界面（com.android.systemui）**
3. 重启 SystemUI（或重启手机）
   - 之后改**代码**只要装新 APK：`module.prop` 里声明了 `autoHotReload=true`，
     框架会直接热更新，不用再重启系统界面；改配置更是即时生效
4. 确认总开关：`adb shell settings get secure status_bar_show_lyric` → 需要是 `1`
5. 「歌词源」弹窗建议**留空**（这样内置 Apple/LrcLib 仍作垫底）


## 调试

```bash
adb logcat -s LyricPatch LyricViewController AppleMusicLyricSource LrcLibLyricSource

# ROM 自带的逐字歌词调试命令，用来先确认 UI 本身没坏
adb shell cmd statusbar lyric sample
adb shell cmd statusbar lyric clear
```

日志默认 `info`：启用时一行 `ready: level=info providers=… word_scroll=on …`，
之后每首歌只有 `hit:` / `tlyric hit(...)` / `inject: 已注入逐字` 这类关键事件。
需要细节就把 `log_level` 改 `debug`（每次取词、解析行数、翻译对齐都出来，异常带堆栈），
再细就是 `verbose`（连每次源调用、每个滚动帧都打）。`log_level=silent` 则一条不剩。


### 独立翻译源

主源只管出词，翻译可以来自另一家。典型用法：日系曲子 `lrclib` 容易命中但没翻译，
而网易云有 tlyric —— 于是 `providers=lrclib` + `translation_providers=netease`。

- 只在**主源没带回翻译**且 `translation=true` 时才去请求，不会白跑；
- 拿回来的翻译会**重新贴到主歌词的时间轴上**（跨源时间轴常差几十~几百 ms，
  ROM 的 `findClosestLine` 按时间找最近行，不重新对齐就会整行没翻译），
  对齐窗口 5 秒，一对一映射、不重复消费；
- 自定义 HTTP 翻译源两种写法：

```properties
# ① 带占位符的完整 URL（值会自动 URL 编码）
#    可用：{title} {artist} {album} {durationMs} {mediaId} {sourcePackage}
translation_providers=https://my.server/tl?t={title}&a={artist}

# ② 只给 base，自动按内置 HttpLyricSource 协议拼 /v1/lyrics?...
translation_providers=https://my.server
```

响应可以是 JSON（取 `tlyric` / `translation` / `translated`，兼容嵌套 `{"tlyric":{"lyric":"..."}}`），
也可以直接返回 LRC 文本。

## 代码结构

```
app/src/main/java/com/lyricpatch/
├─ lib/        跨文件复用的组件与工具（一组件一文件，可继续建子目录）
├─ pages/      页面（一个页面一个文件，Activity 只做壳）
└─ ui/         Activity / 主题 / 配置读写 / 崩溃记录
```

约定：

1. 新页面放 `pages/`，Activity 留在 `ui/` 里只负责 `setContent`；
2. 只有**被多个文件使用**的东西才提到 `lib`；只服务单个文件的（哪怕调用多次）留在原文件里，用 `private`；
3. 页面独有、与页面 state 强耦合的组件留在 `pages`；
4. 新增开关 / 配置项时，先看 `lib/Keys.kt` 和其他文件已有的声明方式，按同一规则补：
   面向 SystemUI 的写进 `ConfKeys`（同步 `ConfigStore.ORDER` / `DEFAULTS`），
   纯界面偏好写进 `UiKeys`；页面里一律用 `ConfKeys.XXX` 访问，不在调用点写字面量。
   
## 自定义源

`providers` 内置可选 `netease` / `qq` / `lrclib`，可以直接写公网 HTTPS 地址，
协议与 ROM 内置 `HttpLyricSource` 完全一致：

```properties
providers=netease,https://example.com,lrclib
```

请求：`GET {base}/v1/lyrics?title=&artist=&album=&durationMs=&sourcePackage=&mediaId=`

响应（两种写法都认）：

```json
{"lrc":"[00:01.00]...","tlyric":"...","yrc":"..."}
```

```json
{"lrc":{"lyric":"..."},"tlyric":{"lyric":"..."}}
```
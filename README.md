# LyricPatch — SystemUI 状态栏歌词补源模块

> [!WARNING]
> 这是一个针对DerpFest的系统UI的模块 由AI生成

针对 `com.android.systemui` 自带的状态栏歌词（`com.android.systemui.statusbar.phone.*`），
用 libxposed API 102 往 `LyricSourceFactory#create(String)` 的源列表里插入一个**网易云 + LRCLIB** 的歌词源。

不改 UI、不改你的设置，纯换数据源。

## 逆向结论（为什么原本“极大部分没歌词”）

ROM 自带三个源：

| 源 | 请求 | 问题 |
|---|---|---|
| `AppleMusicLyricSource` | `https://test.mrerenk.tr/apple-music/lyrics?q=&title=&artist=&album=` | 第三方野生域名，随时挂；要求对上 Apple 曲库 |
| `LrcLibLyricSource` | `https://lrclib.net/api/search?q=` | 欧美取向库，中文歌覆盖低；无打分对齐 |
| `HttpLyricSource` | `{base}/v1/lyrics`、`{base}/v2/lyrics` | 只在设置 `status_bar_lyric_sources` 非空时用；**只认 `https://`** |

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

没有本地 Android SDK 的话，推到 GitHub 用 `.github/workflows/build.yml` 自动出包。

## 安装 / 启用

1. 装 APK
2. LSPosed 里找到「歌词补丁」→ 启用 → 勾选 **系统界面（com.android.systemui）**
3. 重启 SystemUI（或重启手机）
4. 确认总开关：`adb shell settings get secure status_bar_show_lyric` → 需要是 `1`
5. 「歌词源」弹窗建议**留空**（这样内置 Apple/LrcLib 仍作垫底）

## 调试

```bash
adb logcat -s LyricPatch LyricViewController AppleMusicLyricSource LrcLibLyricSource

# ROM 自带的逐字歌词调试命令，用来先确认 UI 本身没坏
adb shell cmd statusbar lyric sample
adb shell cmd statusbar lyric clear
```

## 可选配置

`/data/local/tmp/lyricpatch.conf`（系统 UI 要能读，`chmod 644`），缺失就用默认值：

```properties
# 源顺序，逗号分隔：netease / lrclib
providers=netease,lrclib

# 是否把 tlyric 作为第二行（配合 ROM 的「显示翻译」）
translation=true
```

## 实现要点

- `LyricSource` 是 SystemUI 里的接口，模块用 `Proxy` 实现，**无需 compileOnly 引用 SystemUI 类**；
  `Track` 字段用反射读，`Lyrics` 不自己拼，直接交给 ROM 的
  `LyricResponseParser#parseLyrics(JSONObject)` / `#parseLyricText(String)`，
  自动继承 `yrc` 逐字 + `tlyric` 翻译 + TTML 解析。
- `fetchEnhanced()` 拿不到 word timing 就返回 `null`，让 ROM 自然回退到行级轮。
- 网易云客户端（`*netease*`）且 `mediaId` 为纯数字时，直接按歌曲 id 取词，不搜索。
- 结果缓存：命中 6 小时，未命中 10 分钟。

## 自定义源（1.5+）

`providers` 除内置的 `netease` / `lrclib`，可以直接写公网 HTTPS 地址，
协议与 ROM 内置 `HttpLyricSource` 完全一致：

```properties
providers=netease,https://lyric-source.soiaii.workers.dev,lrclib
```

请求：`GET {base}/v1/lyrics?title=&artist=&album=&durationMs=&sourcePackage=&mediaId=`

响应（两种写法都认）：

```json
{"lrc":"[00:01.00]...","tlyric":"...","yrc":"..."}
```

```json
{"lrc":{"lyric":"..."},"tlyric":{"lyric":"..."}}
```

细节：

- 列表按顺序尝试，第一个返回非空歌词的赢；URL 失败/超时自动跳下一个
- 只支持 `http://` / `https://`，证书必须有效（默认 TrustManager）
- 大小写：内置名字不区分大小写，URL 原样使用
- 改了配置不用重启，下次取词生效（有 mtime 检测）

## 坑

- 填了自定义源（`status_bar_lyric_sources` 非空）时，ROM 会**丢掉**内置源；
  本模块会自动把自己的源垫底，所以依然有词。
- 网易云接口是第三方非官方 API，哪天挂了改 `Netease.java` 里两个常量就行。
- 网络在 SystemUI 的 `mLyricExecutor` 线程池里跑，不会卡主线程。

## 依赖说明（重要）

`io.github.libxposed:api:102` 这个坐标在 Google 仓库 / Maven Central 都拉不到，
所以本工程**不依赖远程 libxposed 包**，而是在 `apistub/` 里放了一份编译期 stub：

- `compileOnly project(':apistub')` → 只参与编译，不会打进 APK
- 运行时由 LSPosed 注入的框架类提供真实实现
- 所有 stub 方法签名都是从已安装的真实 libxposed 模块 smali 里核对出来的：
  `XposedModule#hook(Executable)`、`HookBuilder#intercept(Hooker)`、
  `Chain#getArgs():List`、`Chain#proceed(Object[]):Object`、
  `XposedModule#log(int,String,String,Throwable)`

代价：如果框架大版本改了签名，需要同步改 `apistub/` 里的声明。
如果以后官方包能正常拉取，把依赖换回 `compileOnly 'io.github.libxposed:api:xxx'` 并删掉 `:apistub` 即可。

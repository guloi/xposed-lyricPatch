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


## 安装 / 启用

测试使用derpfest v17 giuliac official
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
# 主歌词源顺序，逗号分隔：netease / qq / lrclib / https://your.server
providers=netease,lrclib

# 翻译总开关（配合 ROM 的「显示翻译」），false 则完全不请求翻译
translation=true

# 翻译单独一路：主源没带翻译时，按这个顺序再要一份（留空 = 关闭）
# 内置：netease / qq；也可以写 http(s) 地址，见下
# 不写这个键时默认就用 netease
translation_providers=netease,qq

# ROM 设置的「显示翻译」是设备端模型 / 联网机翻，优先级**高于**本模块给的翻译。
# machine_translation 三档：
#   auto   = ROM 原行为（机翻优先，源翻译只补机翻失败的行）—— 默认
#   source = 源里有翻译就用源里的（人工翻译），一行都没有才机翻兜底  ← 推荐
#   off    = 永不机翻（源没翻译的歌就是没翻译，第二行会空白）
machine_translation=auto

# 某一行没翻译（源没有 + 机翻也没给）时干脆不显示副标题，杜绝残影（默认开）
hide_empty_translation=true

# 核弹开关：setSubtitle 永远收到 null，任何翻译都不显示（排查/极度嫌麻烦时用）
# hide_translation_always=false

# 调试：每行刷新打一条 holder: lyric=… | tl=…
# debug_log=false
```

### 独立翻译源（2.0+）

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

## 实现要点

- `LyricSource` 是 SystemUI 里的接口，模块用 `Proxy` 实现，**无需 compileOnly 引用 SystemUI 类**；
  `Track` 字段用反射读，`Lyrics` 不自己拼，直接交给 ROM 的
  `LyricResponseParser#parseLyrics(JSONObject)` / `#parseLyricText(String)`，
  自动继承 `yrc` 逐字 + `tlyric` 翻译 + TTML 解析。
- `fetchEnhanced()` 拿不到 word timing 就返回 `null`，让 ROM 自然回退到行级轮。
- 网易云客户端（`*netease*`）且 `mediaId` 为纯数字时，直接按歌曲 id 取词，不搜索。
- 结果缓存：命中 6 小时，未命中 10 分钟。

## 自定义源（1.5+）

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

## 依赖说明

- 编译期：`compileOnly 'io.github.libxposed:api:102.0.0'`（Maven Central），只参与编译，不会打进 APK
- 运行时：由 LSPosed / 框架注入的真实 `libxposed` 类提供实现
- 早期官方坐标拉不到时用过的本地桩 `apistub/` 已删除（`settings.gradle` 里只剩 `include ':app'`）

万一哪天 Maven Central 又拉不到这个坐标，再临时补一份 stub 就行。


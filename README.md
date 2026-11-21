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
4. 确认总开关：`adb shell settings get secure status_bar_show_lyric` → 需要是 `1`
5. 「歌词源」弹窗建议**留空**（这样内置 Apple/LrcLib 仍作垫底）

## 设置界面（4.14+）

桌面上多了「歌词补丁」入口，点开就是全部配置（Compose + Material 3 Expressive 风格：
超大圆角容器 / 动态取色 / 胶囊分段 / 滑杆，深浅色自动跟随，底部常驻保存条）。

- **保存**：写进 App 自己的内部存储（见上面「可选配置」），先写 `.tmp` 再 rename，
  SystemUI 不会读到写了一半的文件；没有未保存改动时按钮置灰；
- **不需要 root**：没有 `su`、没有 root 检测，也没有“重启 SystemUI”这种按钮；
- 头部第二、三个胶囊：**已同步/未保存**（配置有没有存过盘）、**已启用/未启用**
  （打开界面时给 SystemUI 发一条 `com.lyricpatch.PING`，hook 侧收到就回写一次报活；
  10 秒探一次，所以在 LSPosed 里启用或重启 SystemUI 后会自动变化）；
- 头部胶囊：**重新读取**（从文件重拉一份）；
- 保存后发一条 `com.lyricpatch.CONFIG_CHANGED` 广播，SystemUI 侧的 hook 收到就把快照作废、
  重新拉一次（还有 5 分钟一次的兜底轮询）；读取永远在后台线程，不卡取词和绘制，
  所以**不用重启**，下一次取词就生效；
- 「状态栏预览」那张卡片会跑一遍逐字高亮，并标出当前的滚动参数；
- 大标题为卡片样式，点击可收起/展开；「首次打开默认折叠」只在第一次启动应用时生效一次
  （默认开），之后进入都是展开状态；
- 点预览卡下方的「外观」进入独立页面：关掉莫奈取色后可以在
  「柔和 / 鲜明 / 深色 / 中性」四组调色板里选色（每个色块带名称），也能用色相 / 饱和度 /
  明度 三个滑杆自定颜色并直接看到 HEX；可关掉「大标题折叠」回到固定标题样式；
  「回弹幅度」滑杆控制折叠/开关/箭头的弹动手感（0 可关）；
  还可把保存条改为「跟随滚动」——列表上滑时保存条升出、下滑时收回；
  列表内容不足一屏、滚不动时，上下划同样能触发。
  这些属于纯界面偏好，存在 App 自己的 SharedPreferences（`ui_prefs`），不写进 `lyricpatch.conf`。
- 文件里手写的未知键不会被删，只按面板上的键覆盖/重写。

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

## 可选配置

配置存在模块 App 自己的内部存储里，不需要 root、不用 `chmod`：

```
/data/user/0/com.lyricpatch/files/lyricpatch.conf
```

SystemUI 是另一个进程，直接读这个文件会被权限和 SELinux 拦住，所以由模块暴露一个只读的
`com.lyricpatch.config` provider 给它取（内容就是这些开关，不含隐私）。界面上改最省事；
文件在 App 私有目录，手改需要 root / root 文件管理器（改完下次取词生效）。缺失就用默认值：

```properties
# 主歌词源顺序，逗号分隔：netease / qq / kugou / lrclib / https://your.server
providers=netease,lrclib

# 纯音乐不出词（默认开）：源只给“纯音乐，请欣赏”这类占位行时，整条不显示
# hide_instrumental=true

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

# 中文歌词（无假名 & 汉字占多数）不去翻译源要翻译，避免同名错贴（默认开）
skip_translation_for_chinese=true

# 没拿到真逐字时，按行时间轴「合成」一份逐字（默认关：按字数均分，跟实际演唱对不上）
# 想要“至少有动画”可以打开，能接受节奏偏差就行
synth_word_timing=false

# 没有逐字时去酷狗要**真逐字**（KRC，解密+转成 yrc），覆盖率高，默认开
kugou_word_timing=true

# 估算逐字（行时间 + 字符切分），本地即时，不会拖慢显示（默认开）
# estimate_word_timing=true

# 想要真逐字才联网（会慢 1~2 秒，默认关）
# prefer_network_word_timing=false

# 只有不超过该字数的行才估算逐字（0 = 不限）。
# 4.13 起逐字模式下的长句也会跑马灯（见下），已不需要靠这个键避开“显示不全”，建议留 0
# word_timing_max_chars=0

# 逐字模式下长句的跑马灯（4.13+，默认开）。
# ROM 的逐字自绘把文本钉死在左边、完全不看 mOffset，句子比控件宽时右边会被裁掉；
# 模块在 onDraw 外面套一层 canvas.translate 平移整行
# 关掉（false）= 完全不平移，回到 ROM 原行为（长句右半边会被裁）
# word_scroll=true
# 跟随模式（默认开）：先让整行定住，到时机后再跟着挪；关掉就是从行首匀速滑到句尾
# word_scroll_follow=true
# 开始滚动的时机：整句播放到第几 % 时开始跟滚（0 = 自动，按一屏能装下多少算）
# word_scroll_start_pct=0
# 滚动速度：不写（或 0）= 自动，按这一行的词级时长把整段滚动铺满，句尾刚好露出来；
# 填数字 = 固定 px/10ms（4 就是 ROM 跑马灯那档，长句反而会跑得太快）
# word_scroll_speed=0
# 开滚前的停顿（ms，不写 = 500，和 ROM 一致）
# word_scroll_delay=500

# 自动识别增强 LRC（[mm:ss]<mm:ss>词）与 Lyricify Syllable（词(起,长)），转成 yrc（默认开）
parse_word_formats=true

# 只处理这些 App 的歌名（逗号分隔包名；留空 = 全部允许；写了名单就只认名单）
# 典型需求：只想给音乐 App 补词，避免 B 站/视频 App 把视频标题当歌名
# app_whitelist=moe.ouom.neriplayer,com.netease.cloudmusic,com.tencent.qqmusic,com.kugou.android

# 反过来：这些 App 一律跳过（与 whitelist 二选一）
# app_blacklist=tv.danmaku.bili

# 日志级别：silent / error / warn / info / debug / verbose（默认 info）
#   info     只报启用 / 命中 / 注入这类关键事件，一首歌两三行
#   debug    加上每次取词、解析、翻译对齐的细节；异常会带完整堆栈
#   verbose  连每次源调用、每个滚动帧都打（只死磕时开）
# log_level=info

# 老开关，仍然兼容：debug_log=true 等价于 log_level=debug（log_level 优先）
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

- 估算逐字按字重分配整行时长（汉字/假名 2、其余 1），最后一个字正好在下一行开始时结束。
- `LyricSource` 是 SystemUI 里的接口，模块用 `Proxy` 实现，**无需 compileOnly 引用 SystemUI 类**；
  `Track` 字段用反射读，`Lyrics` 不自己拼，直接交给 ROM 的
  `LyricResponseParser#parseLyrics(JSONObject)` / `#parseLyricText(String)`，
  自动继承 `yrc` 逐字 + `tlyric` 翻译 + TTML 解析。
- `fetchEnhanced()` 拿不到 word timing 就返回 `null`，让 ROM 自然回退到行级轮。
- 逐字模式的长句：`LyricTextView#onDraw` 是两条互斥分支（有 `mWordTiming` 就自绘卡拉OK、
  没有才走跑马灯），而 `LyricViewController#setWordTiming()` 每 250ms 刷新都会把
  `mStopped` 置 `true` 并移掉 `mStartScrollRunnable`，所以逐字轮永远滚不起来，
  超宽的行右半边直接被裁。模块只 hook `onDraw`，在其外面 `canvas.translate(dx, 0)`
  平移整行（ROM 内部的 `clipRect` 高亮会自动跟着裁好，观感和原生跑马灯一致），
  dx 在 `word_scroll_follow`（默认开）下先定住，等已唱位置快贴到右边（比例取
  `控件宽 / 整行宽`）才按「这一行还要唱多久」铺满剩下的路程，句尾刚好露出来；
  关掉 follow 则从行首匀速滑到句尾。两种都在后台算好，自己 `postInvalidateDelayed` 驱动帧。
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


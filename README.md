# Magnetrail — Android 磁力下载器（KMP）

Android 优先的 `magnet:` 下载器：内置 libtorrent（FrostWire jlibtorrent 2.0.15.3），支持 DHT/LSD，不依赖 tracker；带任务管理、前台下载服务、文件管理和内置视频播放器。

> ⚠️ 本项目是通用 `magnet:` 下载工具，**不提供、不索引、不推荐任何影视资源或站点**。仅用于你拥有版权或已获授权的内容；未经授权下载、分享受版权保护的作品属于侵权行为，风险自负。本项目不绕过任何 DRM（无 Widevine / PlayReady / ClearKey 支持）。

## 功能

| 模块 | 说明 |
|---|---|
| 磁力解析 | `magnet:?xt=urn:btih:…`，hex 40 位 / base32 32 位两种 infohash 都认，读取 `dn` 显示名 |
| 下载引擎 | libtorrent 2.0.15.3（DHT + LSD + PEX），magnet 元数据阶段单独显示 |
| 任务列表 | 进度、下载/上传速度、连接数/种子数、剩余时间；暂停 / 继续 / 移除（可选删文件） |
| 多文件选择 | 每个文件的开关（`Priority.IGNORE` / `NORMAL`），跳过不需要的采样、字幕等 |
| 仅视频下载 | **拿到元数据后默认只下视频**：所有非视频文件自动设为 `IGNORE`（任务卡会显示「仅视频：跳过 n 个非视频文件」），之后想下某个字幕/图片，在文件列表里手动打开开关即可；种子里完全没有视频文件时不套用，避免把整个任务暂停 |
| 后台下载 | 前台服务（`dataSync`）+ 通知栏进度 + PARTIAL_WAKE_LOCK，退到后台/锁屏继续下 |
| 断点续传 | 任务存 `SharedPreferences`；重启后重新加入，libtorrent 自动校验已有文件接着下 |
| 文件管理 | 按种子文件夹**分组**浏览（组可折叠、显示组内文件数与总大小）；组头垃圾桶**一键删除整组**（有对应任务会连任务一起删）；每个文件 **⋮ 选择打开方式**：未下完的文件提供「下载 / 边下边看 / 缓冲观看」（**下载 = 把该文件设为下载并跳回任务页看进度**），已下完的提供「本地播放 / 边下边看 / 缓冲观看」；选择会记住并成为该文件之后点击的默认方式 |
| 边下边播 | 视频文件不必等下完：本地 `127.0.0.1` HTTP Range 服务（`TorrentStreamServer`）按 piece 供给，配合顺序下载 + piece deadline；播放器可拖到已下载区域；**关掉播放器后自动清掉“顺序下载”，恢复多片并行** |
| 缓冲观看 | 「只看不保存」：种子只拉目标视频进缓存目录（其它文件 IGNORE），拿到元数据自动播放；**关闭播放器立刻删掉缓冲数据** |
| 画中画 | `PlayerActivity` 支持 PiP：16:9（按视频实际比例）自动进入、用户可拖动调整大小、Android 12+ 离屏自动进入 |
| 横屏 | 播放器 `screenOrientation=sensor`（不受系统旋转锁影响），旋转按钮一键横竖屏；横屏沉浸全屏，**顶栏与底部控制条一起淡入淡出**；返回首页后固定竖屏 |
| 播放控制 | 自绘底部控制条（横竖屏都有）：播放/暂停、**带缓冲进度的可拖动进度条**（点按也能跳）、`当前/总时长`、全屏/退出全屏；播放中 3 秒无操作自动隐藏，点画面呼出 |
| 出错重试 | 播放中断会**自动从原进度续播**（回退 5 秒，最多 5 次、1/2/4/8/15 秒退避），期间显示「第 n/5 次」提示；全失败才弹出错误页，可选「从 xx:xx 继续」或「从头播放」 |
| 保活 | 有播放器在读本地流时服务不会空闲停服（停服会关掉 `127.0.0.1` 服务并掐断播放）；关掉播放器 30 秒后才可能停 |
| 上传限速 | 顶栏右上角仪表盘：不限 / 2 MB/s / 1 MB/s / 512 / 256 / **128 KB/s（默认，合理最低值）**；立即生效并记住，128 KB/s 以下会明显影响 BT 互惠（peer 容易 choke 你） |
| 浏览器 | 内置第三个 tab：地址栏（网址 / 搜索词自动补 `https://`）、前进后退刷新、粘贴剪贴板、桌面版 UA 切换、分享/复制、系统浏览器打开、清缓存；**多标签页**（顶部标签栏，最多同时 5 个，切换不重载页面、后台标签自动暂停视频）；**广告拦截**三层（域名黑名单 + 广告路径 + JS 隐藏广告位/禁弹窗），顶栏实时显示拦截次数，可一键开关 |
| 网页视频 → 本机播放器 | 自动从网络请求（mp4 / m3u8 / mpd）+ 页面 `<video>/<source>/og:video` 里收集视频；「本页视频」列表点「播放」交给 `PlayerActivity`：**画中画、横屏全屏、拖动进度、出错自动重试全都有**，并自动带上网页的 `Referer` / `Cookie` / `User-Agent`（很多站点缺了就 403）；m3u8 走 HLS、mpd 走 DASH |
| 长按链接 | 长按网页链接：打开 / 复制链接 / 用系统浏览器打开 / 视频直接交给本机播放器；`magnet:` 链接直接加入下载并跳回任务页 |
| 视频播放 | Media3/ExoPlayer 内置播放器（保持亮屏；控制条自绘，不用 PlayerView 默认控制器） |
| 清晰度 | 播放器底部显示**源里真实存在的档位**（HLS master / DASH manifest 的每一档，ExoPlayer 解析后直接列出来）：「1080p 自动 / 720p 锁定」；点开可手动锁定任意档，或开「起播就选最高档」（默认关）。只有 1 档时会明说「站点没给更高清」。锁定后连续缓冲 8 秒自动退回自适应，不会卡死转圈 |
| 字幕 | 顶栏字幕按钮：**总开关 + 显示模式（原文 / 中文 / 双语）+ 来源（自动 / 内置字幕 / 本地文件 / AI）**。内置字幕轨（mkv、HLS WebVTT）经 ExoPlayer 解码后由自绘层统一渲染；本地文件自动匹配同目录同名 `.srt`（含 `.zh.srt` / `.ja.srt`，UTF-8 失败自动按 GBK 读，原/译按时间重叠合并成双语）。时间轴由播放器时钟驱动，暂停/拖动/倍速天然对齐 |
| AI 字幕（离线） | 「AI 生成（离线）」：本机解码音频 → 能量分段 → SenseVoice-small 识别（中/英/日/韩/粤自动判语言）→ 写成同目录 `<名字>.asr.srt`，随即自动切到本地字幕。模型 228MB，首次点「下载模型」从 hf-mirror 下载（不进 APK）；**10 分钟片约 95 秒**（888 实测）。**同一个文件只生成一次**：下次进入只解析 `.asr.srt`（毫秒级），不会重跑识别 |
| AI 字幕（双语） | 生成前可设「生成双语字幕」（默认开）+ 第二语种（中文/英文/日文，默认中文）。开启后识别完会把每句译成第二语种（ML Kit 端侧翻译模型，首次联网下载约 30–40MB，之后完全离线），另存 `<名字>.zh.srt`；播放时按时间合并成「译文在上、原文小字在下」。翻译失败只保留原文，且不会写入假译文 |
| 字幕显示 / 微调 | 播放器右上角 **⋮** 打开右侧设置侧栏：字幕开关、**翻译语言**（中文/英文/日文/韩语）、**显示方式**（原文/译文/双语）、**源语言**（自动/日语/英语/中文）、来源、AI 生成、字号/位置/时间轴、清晰度。全部持久化；**画中画里字幕随窗口等比例缩放**；超长句按标点分页 |
| 自动字幕 | 来源选「自动」时：先找内置字幕轨 → 再找同目录 `.srt` → 都没有且模型已装时，**自动改用实时生成**（不用手动切「AI 实时」） |
| 生成任务 | 长任务（1 小时片约 10 分钟）跑在 Application 作用域：**退出播放器/切到别的页面继续生成**，完成后自动读回新字幕；生成中面板显示进度和「取消生成」 |
| AI 字幕（实时） | 「AI 生成（实时）」= **逐段边看边生成**：每 60 秒一段（相邻段重叠 1.5s 去重），识别 → **合并成句** → 翻译（默认中文，中文永远主行）→ 按媒体时序立刻上屏。支持**手动指定源语言**（自动/日语/英语/中文）：自动识别偶尔会把日语判成中文，选「日语」后重跑即可。网页 HLS 支持最低码率变体 + 分段桥接 + AES-128 解密；不支持 byte-range 与直播列表 |
| 批量生成 | 文件页顶部「AI 字幕」卡片：只统计**严格判定为已下载完成**、且还没有字幕的视频（按体积升序，先出小片），点「生成」先列出清单确认，然后按顺序排队生成（已有字幕自动跳过），显示 `正在生成 i/n · 文件名` 和进度条，可随时取消；已完成的不会重跑 |
| 收藏 / 历史 | 浏览器里的 **整页**（不再是弹窗）：收藏夹一页；历史分「网页历史 / 视频历史」两个 tab。**视频历史记录网页视频的观看进度**，点一条就从上次位置继续播放；支持单条删除和清空 |
| 系统联动 | 注册 `magnet:` scheme，浏览器里点链接直接拉起 App 并填好输入框 |

## 工程结构

```
magnetrail/                               # 项目根目录
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradlew.bat + gradle/wrapper/           # 已生成 wrapper
├── local.properties                        # sdk.dir 指向本机 SDK（不要提交）
├── build.ps1                               # gradle 包装脚本（内置 JDK 或系统 JAVA_HOME）
├── libs/maven/                             # 本地 Maven 仓库（jlibtorrent + sherpa AAR，共约 102MB）
├── .toolchain/                             # 本机便携工具链（不入库）
│   ├── jdk-17.0.21+9/                      # Temurin JDK 17
│   ├── gradle-8.10.2/                      # Gradle（wrapper 也有）
│   ├── android-sdk/                        # cmdline-tools / platform 35 / build-tools 34、35 / platform-tools
│   └── _dl/                                # 安装用的原始 zip（约 600MB，可删）
├── shared/                                 # KMP 库模块
│   └── src/
│       ├── commonMain/                     # 模型、磁力解析、格式化、TorrentEngine 接口（expect）
│       ├── androidMain/                    # AndroidTorrentEngine：libtorrent 真实实现（actual）
│       ├── desktopMain/                    # DesktopTorrentEngineStub（actual，桌面暂未接引擎）
│       └── commonTest/                     # 纯 Kotlin 单元测试
└── androidApp/                             # Android 应用（Compose + Media3 + 前台服务）
    └── src/main/kotlin/app/magnetrail/
        ├── MagnetrailApp.kt / TorrentStore.kt / Notifications.kt / AppSettings.kt
        ├── DownloadService.kt              # 前台服务、通知、空闲自动停止
        ├── MainActivity.kt                 # Compose 入口、权限、粘贴、添加、三个 tab
        ├── PlayerActivity.kt               # ExoPlayer 播放器（本地/边下边看/网页视频 + PiP）
        ├── BrowserController.kt            # 浏览器：WebView、广告拦截、视频嗅探
        ├── AdBlocker.kt                    # 广告/追踪域名与路径黑名单
        ├── FileActions.kt                  # FileProvider 分享/打开/删除
        └── ui/                             # Theme / TasksTab / FilesTab / BrowserTab / Common
```

### 为什么是 KMP

`shared` 用 `kotlin("multiplatform")`：`commonMain` 放平台无关的模型、解析和 `TorrentEngine` 接口，靠 `expect/actual` 分平台。Android 端用 libtorrent 落地；`desktop` 目标只留 stub，保证共享代码和测试在任何机器上都能编译。以后加 iOS 时，在 `shared/build.gradle.kts` 里加 `iosX64/iosArm64` 目标 + 一个 `actual` 实现即可（需要 macOS + Xcode）。

## 编译 / 运行

需要 JDK 17 + Android SDK（`compileSdk 35`，build-tools 34+）。开发者本机曾使用 `.toolchain/` 便携工具链（不入库）；从仓库构建时直接用系统环境即可。

```powershell
cd magnetrail

# Debug APK
.\build.ps1 :androidApp:assembleDebug

# 单元测试（磁力解析 / 格式化）
.\build.ps1 :shared:desktopTest

# 安装到已连接的设备（USB 调试；adb 在 Android SDK 的 platform-tools 里）
adb install -r .\androidApp\build\outputs\apk\debug\androidApp-debug.apk
```

产物：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`（含 `arm64-v8a` + `armeabi-v7a`）

### 发布构建（签名）

签名配置从根目录 `keystore.properties` 读取；该文件和 `keystore/` 已被 `.gitignore` 排除，不存在时 release 会自动跳过签名（便于 CI / 开源构建）。

```powershell
# release（R8 混淆 + 签名）
.\build.ps1 :androidApp:assembleRelease
# 产物
# androidApp/build/outputs/apk/release/androidApp-release.apk
```

> 部分 MIUI 设备默认拒绝 `adb install` 非 debuggable 的 APK（`INSTALL_FAILED_USER_RESTRICTED`），与 APK 本身无关；在开发者选项里打开「USB 安装」后仍受限时，可把 APK 拷到手机用文件管理器安装。

也可以用 Android Studio 直接打开项目根目录（`local.properties` 里配置 `sdk.dir`）。

调试用：从命令行直接进「缓冲观看」（不落盘，播完删除）

```powershell
adb shell am start `
  -n app.magnetrail/.MainActivity -a android.intent.action.VIEW `
  -d "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10" --ez watch true
```

调试用：验证「出错自动重试」（仅 debug APK，播放 30 秒后注入一次假错误）

```powershell
# 在上面的命令后追加：--ez debug_fail true --ei debug_fail_delay 30000
# 直接打开文件页：--ei tab 1
# 直接打开浏览器：--ei tab 2
# 浏览器里直接载入网址（可自动扫描/自动播放第一个视频）：
#   --ei tab 2 --es browser_url "https://…" --ez browser_scan true --ez browser_play true
# 直接把远端视频交给播放器（验证 HLS/DASH）：
#   --es player_url "https://…/index.m3u8"
# 直接播放本地文件并从指定位置开始（验证字幕/边下边看）：
#   --es player_path "/sdcard/…/x.mp4" --el player_start_ms 9000
```

### 版本组合（已验证可编译）

- JDK 17.0.21（Temurin）
- Gradle 8.10.2 + AGP 8.6.1
- Kotlin 2.1.21（KMP + `org.jetbrains.kotlin.plugin.compose`）
- compileSdk 35 / targetSdk 35 / minSdk 26（jlibtorrent 原生库按 API 26 构建）
- Compose UI (BOM 2024.09.03) / Media3 1.4.1 / jlibtorrent 2.0.15.3

### 依赖镜像（国内网络）

本机 `dl.google.com`、`plugins.gradle.org`、`services.gradle.org` 不可达，已做如下替换：

- `settings.gradle.kts`：仓库优先走 `maven.aliyun.com`（google / central / gradle-plugin 三个镜像）
- jlibtorrent（52MB）与 sherpa-onnx（49MB）两个 AAR 已收进 `libs/maven/`（本地 Maven 仓库），构建不再访问 `dl.frostwire.com`
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 发行包走腾讯云镜像
- AGP 首次构建会自动补装 `build-tools;34.0.0`（已装到 `.toolchain/android-sdk`）

换到通畅网络后可把仓库声明换回官方源，并删除 `libs/maven` 那条 repo。

## 文件放在哪

- 下载目录：`/storage/emulated/0/Android/data/app.magnetrail/files/Downloads/`
- 属于 App 专属目录：**卸载 App 会一起删掉**；不需要任何存储权限；USB 连电脑能看到（Android 11+ 在 `Android/data` 下）
- 想长期保存/进相册：用文件页的「分享」另存，后续可加 MediaStore 导出

## 隐私与数据

- 无账号、无遥测、无统计 / 广告 SDK，不收集个人信息。
- 下载任务、收藏、网页历史、视频进度只保存在本机；历史类数据用 Android Keystore AES-GCM 加密（`SecurePrefs`），日志已脱敏。
- BT 协议本身会把本机 IP 暴露给 tracker / DHT / peer，这是协议特性，客户端无法隐藏；需要匿名请自行配合 VPN / 代理。
- AI 字幕识别在本机离线运行（sherpa-onnx + SenseVoice）；翻译走 Google ML Kit 端侧模型，首次需通过 Google Play 服务下载模型（约 30–40MB），之后离线，没有 GMS 的机器只能出原文。
- SenseVoice 模型（约 228MB）首次点「下载模型」时才从 hf-mirror 下载，不随 APK 分发。
- 内置浏览器是普通 WebView，你访问的网站可能按它自己的隐私政策收集信息。

## 许可

- 本项目源码采用 **Apache License 2.0**，见 [`LICENSE`](LICENSE)。
- 第三方组件、模型来源与许可见 [`NOTICE.md`](NOTICE.md)。
- 仓库不包含任何模型权重或影视资源；AAR 仅作为本地 Maven 依赖用于构建。

## 已知限制 / 下一步

- 桌面（desktop）目标只有 stub，没接引擎；iOS 目标未添加（需 macOS）
- 缓冲观看自动挑「体积最大的视频文件」；想看别的文件请用「添加任务 + 文件列表里的 ▶」
- 缓冲数据在 `cache/torrent-stream/<infohash>`，关播放器/下次启动自动清空
- 未做 resume data（`save_resume_data`）：重启后靠重新校验文件继续，超大种子首次校验会慢
- 安全：历史/收藏/视频历史/磁力任务用 Keystore AES-GCM 加密存储；本地流服务带一次性令牌；备份关闭；WebView 无 JS 桥、文件访问关闭；播放器可选防截屏（默认关）
- 浏览器的广告拦截是「规则黑名单」，不是完整拦截引擎：新出现的广告联盟可能漏拦；遇到漏拦可以在 `AdBlocker.kt` 的域名列表里补一行
- 网页视频只识别 **有扩展名的直链**（mp4/m3u8/mpd/…）：YouTube 那种 `blob:` + MSE 流拿不到直链，需要站点本身提供 m3u8
- 浏览器最多同时开 **5 个标签页**（每个标签一个 WebView，再多内存吃不消）；标签在进程被杀后不恢复
- 网页的 `target=_blank` / JS 弹窗仍被拦截（防广告自动弹窗）；想保留当前页面时，用顶栏 `+` 手动开新标签页，或长按链接用系统浏览器打开
- 没有「导出到系统下载/相册」（MediaStore）和 SAF 自定义保存目录
- 完成后的做种默认仍开启，直到你移除任务或服务空闲 30s 自动停止；上传限速见顶栏仪表盘
- 字幕：本地文件只解析 `.srt`（UTF-8/GBK 自动识别），**`.ass/.ssa` 暂不支持**；边下边看/网页视频没有同目录文件，只能靠内置字幕轨（后续 AI）
- 字幕：内置文本轨（mkv 的 `S_TEXT/UTF8`、HLS WebVTT）可用；**PGS/VobSub 这类图形字幕轨 ExoPlayer 不解码**，需要额外库或 OCR
- AI 字幕：翻译用 ML Kit 端侧模型，**需要 Google Play 服务**；没有 GMS 的机器只能出原文（不会崩，会提示翻译失败）
- AI 字幕：第二语种目前支持 中文 / 英文 / 日文；译文是逐句直译，缺上下文，质量一般（尤其口语/语气词）
- AI 字幕生成依赖 App 进程存活：播放器退出后仍继续，但系统强杀进程会中断（已生成的 `.asr.srt` 会保留）；长时间生成建议保持 App 在前台/后台不被清理

## 下一步：实时字幕（时序已就绪）

实时字幕的难点不是模型，而是**时序**：识别器只知道样本号，播放器会 seek/暂停/卡顿。

已经落地 `LiveSubtitleTimeline`（`shared/commonMain`，纯逻辑 + 6 条单测）：
- 每次取一段音频就 `beginWindow(播放器当前媒体时间)`，识别结果的样本偏移加上窗口起点 = 字幕时间；
- `addCue(..., bufferedEndMs)` 丢弃超出**缓冲窗口**的结果、截断跨窗口的句子；
- `visible(position)` 只在“已经开始”之后显示，并丢弃迟到超过 1.2s 的句子（避免“话说完字幕才蹦出来”）；
- seek/换片调用 `reset()` + 新窗口。

还差的部分：用 `TeeAudioProcessor` 从播放管线取 PCM（零额外流量、天然同步），接一个流式模型出 partial，再喂给上面这个时间轴。
- AI 字幕：模型 228MB，只放在 `Android/data/<pkg>/files/models/sensevoice`；APK 因此增加约 47MB（sherpa-onnx 的 ONNX Runtime + JNI，已按 arm64/armv7 过滤）
- AI 字幕：边下边看/网页视频暂时不能生成（需要完整音频轨），仅支持本地文件

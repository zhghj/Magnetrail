# NOTICE — Magnetrail

Magnetrail
Copyright 2026 Magnetrail contributors

本项目（源码与文档）采用 Apache License 2.0，完整文本见 [`LICENSE`](LICENSE)。

本仓库**不包含** SenseVoice 模型权重、影视资源或任何受版权保护的媒体文件。

## 第三方组件

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| libtorrent（经 FrostWire jlibtorrent 封装） | 2.0.15.3 | BSD-3-Clause | BitTorrent 引擎核心 |
| FrostWire jlibtorrent | 2.0.15.3 | MIT | libtorrent 的 Java/Android 绑定，`libs/maven` 中的 AAR |
| Boost（随 jlibtorrent 编译） | — | Boost Software License 1.0 | libtorrent 依赖 |
| sherpa-onnx | 2.0.1 | Apache-2.0 | 离线语音识别运行时，`libs/maven` 中的 AAR |
| ONNX Runtime（随 sherpa-onnx 编译） | — | MIT | 模型推理后端 |
| SenseVoice-small（模型） | 2024-07-17 | MIT | 不随仓库/APK 分发，运行时从 hf-mirror 下载；来源 FunAudioLLM/SenseVoice |
| AndroidX（core / activity / lifecycle） | 见 Gradle 版本 | Apache-2.0 | Android 基础库 |
| Jetpack Compose（UI / Material3） | BOM 2024.09.03 | Apache-2.0 | UI 框架 |
| Media3 / ExoPlayer | 1.4.1 | Apache-2.0 | 视频播放 |
| Kotlin / kotlinx-coroutines | 2.1.21 / 1.9.0 | Apache-2.0 | 语言与协程 |
| Google ML Kit Translate | 17.0.3 | Google 专有 SDK | 端侧翻译；按 Google 条款使用，不随源码再分发 |

## 素材

- 图标为项目自绘矢量图形。
- README / 测试中使用的示例磁力为 CC 授权的 Sintel 测试片，不涉及影视资源分发。

## 免责

本项目是通用 `magnet:` 下载工具，不提供、不索引、不推荐任何资源或站点，不支持任何 DRM（Widevine / PlayReady / ClearKey）。使用者需自行确保下载内容拥有版权或已获授权。

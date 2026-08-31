# NekoChatMini 项目梳理（v0.6.7）

> 一份文档看懂这个项目：是什么、有什么模块、大文件在哪、版本怎么走的、文档怎么找。
> 最后更新：2026-08-13（基于 `feat/v0.6.7-asr-live2d-perf` 分支，HEAD `562c455`）。

---

## 1. 一句话定位

**纯端侧猫娘聊天 Android APK**：全屏 Live2D 角色作背景，文本或语音（ASR）输入，
端侧 LLM 回复，自动 TTS 播放并驱动 Live2D 嘴型同步，支持运行时换背景、换角色、
换音色、打电话模式（半双工 VAD + 打断）。可选在线 LLM/TTS 后端。

| 能力 | 技术选型 | 模型存放 |
|---|---|---|
| LLM | Qwen-neko 微调模型，MNN 推理（`libmoeavatar_llm.so`） | 应用私有目录，按需下载或 adb push |
| TTS | SuperTonic-Neko（MNN 44.1kHz，多音色 v1.3） / 在线 MiniMax | 应用私有目录 |
| ASR | sherpa-mnn 流式 zipformer（中英双语） | 应用私有目录 |
| Live2D | Cubism SDK 5-r.5（Core 6.0.0001，OpenGL ES2），ATRI / FenmaoLoli 内置 | APK 内 assets |
| ABI | 仅 arm64-v8a | — |
| 包名 / 版本 | `com.neko.chat` / 0.6.7（versionCode 10） | — |

架构要点：**所有 MNN 推理（LLM/TTS/ASR）共用同一套 `libMNN.so`**，因此存在一个
全局互斥锁 `MnnGlobalLock`（见下文「并发约束」），任何集成方都不能绕过。

---

## 2. 模块结构

```
NekoChatMini/
├── app/        # 主应用：聊天 UI + LLM/TTS/ASR 胶水 + JNI 桥（本项目主要代码）
├── live2d/     # Live2D 渲染 module（Cubism SDK5 底座 + 集成代码 + 内置角色资产）
├── sherpa/     # sherpa-mnn ASR module（Kotlin 绑定 + native cxxshim + 预编译 .so）
├── docs/       # 交接文档 / 设计文档 / 版本记录 / bug 记录
├── experiments/ # 性能优化实验（TTS 分步 benchmark、native 侧实验代码）
├── scripts/    # adb push 模型、VAD/噪声工具脚本
├── patches/    # Live2D 角色补丁
└── gradle/     # gradle wrapper
```

### app 模块代码地图

| 包 / 文件 | 职责 |
|---|---|
| `chat/LlmChatActivity.kt` | 主 Activity：聊天 UI、流式渲染、设置面板、换背景、后端装配 |
| `chat/ChatAsrController.kt` | sherpa 流式 ASR：长按录音 + 持续模式 + FireRed VAD 打断 |
| `llm/LlmBackend.kt` | LLM 统一接口（`Flow<String>` 流式 token） |
| `llm/LocalLlmBackend.kt` / `OpenAiLlmBackend.kt` | 本地 MNN 后端 / 在线 OpenAI 兼容后端 |
| `llm/LlmConfig.kt` | **全局配置中心**（SharedPreferences，所有可配置项集中于此） |
| `tts/TtsBackend.kt` | TTS 统一接口（`Flow<PcmChunk>`） |
| `tts/SpeechQueue.kt` | 分句 / 顺序播放 / barge-in / 嘴型回调 |
| `tts/SuperTonicTtsBackend.kt` / `MiniMaxStreamTtsBackend.kt` | 本地 / 在线 TTS |
| `live2d/Live2DController.kt` | GLSurfaceView + 角色切换 + 嘴型 + 换背景 |
| `model/ModelManager.kt` | **模型注册中心**：所有模型 ID/仓库/文件清单/路径集中管理 |
| `model/ModelScopeDownloader.kt` | ModelScope 按需下载 / 校验 / 安装 |
| `voiceinteraction/ConversationCoordinator.kt` | 输入/回复状态机（单数据源 `StateFlow`） |
| `voiceinteraction/VoiceInputController.kt` | 语音输入编排（模式切换、会话生命周期） |
| `phonecall/PhoneCallController.kt` | 打电话模式：VAD + barge-in + 半双工控制 |
| `safety/ContentSafetyGuard.kt` | 输入/输出内容安全分类词库检查 |
| `perf/PerformanceTrace.kt` | 性能埋点（首字延迟、tok/s、TTS RTF） |
| `MnnGlobalLock.kt` | **全局 MNN 互斥锁**（并发红线，见 §6） |
| `cpp/` | JNI：`moeavatar_llm_jni`（LLM）、`supertonic`（TTS）、`firered_vad_jni`（VAD） |

### live2d / sherpa 模块

- `live2d`：`src/main/cpp/` 是 Cubism 集成层（LApp* 系列，本项目代码），
  `src/SDKRoot/` 是 **Cubism SDK5 官方框架源码**（第三方，含 Core 预编译 .a 与各平台库），
  `src/main/assets/Live2DModels/` 是内置角色（ATRI、FenmaoLoli）。
- `sherpa`：`src/main/java/com/k2fsa/sherpa/mnn/` 是 sherpa-mnn 的 Kotlin 绑定（本项目代码），
  `src/main/cpp/sherpa_cxxshim.cpp` 是一个 ~10KB 的「引子」，唯一作用是让 `libsherpa-mnn-jni.so`
  能解析 `libc++_shared.so` 符号（Android linker namespace 坑），`src/main/jniLibs/` 是预编译 .so。

---

## 3. 大文件在哪（430MB 是怎么来的）

目录实际占用（2026-08-13 实测）：

| 目录 | 大小 | 性质 | 纯净版是否保留 |
|---|---:|---|---|
| `app/build/` | 172M | Gradle 构建产物 | ✗ 删 |
| `sherpa/build/` | 96M | 构建产物 | ✗ 删 |
| `live2d/build/` | 71M | 构建产物 | ✗ 删 |
| `live2d/.cxx/` + `sherpa/.cxx/` | 37M | CMake 缓存/中间产物 | ✗ 删 |
| `.gradle/` | 11M | Gradle 缓存 | ✗ 删 |
| `live2d/src/SDKRoot/` | 17M | **Cubism SDK5 第三方框架源码** | ✓ 保留（编译必需） |
| `live2d/src/main/assets/` | 8.1M | Live2D 角色贴图/moc3 | ✓ 保留（运行必需） |
| `app/src/main/jniLibs/` + `sherpa/src/main/jniLibs/` | 8.7M | 预编译 .so（MNN 三件套 + sherpa） | ✓ 保留（编译必需） |
| `app/src/` | 4.3M | 本项目 Kotlin/C++/资源 | ✓ 保留 |
| `sherpa/src/` | 3.8M | 本项目绑定代码 + .so | ✓ 保留 |
| `docs/ + experiments/ + scripts/ + patches/` | ~3M | 文档与工具 | ✓ 保留 |

**结论：约 380MB（88%）是构建产物，真正源码 + 必需资产 + 预编译库只有 ~50MB。**
「纯净版」= 剔除 `build/`、`.cxx/`、`.gradle/`、`.git/`、APK、`local.properties` 等，
保留全部源码与编译运行必需的三方底座。

复现命令已归档为 `scripts/make_clean_zip.sh`（见 §7）。

---

## 4. 版本时间线（详见 docs/VERSION_LOG.md）

| 版本 | 日期 | 里程碑 |
|---|---|---|
| v0.1-neko | 07-09 | 首版：四件套跑通，模型全 adb push |
| v0.6~v0.9-neko | 07-09~07-10 | 打断修复、TTS 卡顿修复、性能小字 |
| v3.0~v3.2 | 07-11 | 沉浸式 UI、玻璃材质、第二个角色 FenmaoLoli |
| v3.3-online | 07-14 | 在线 LLM（OpenAI 兼容）+ MiniMax TTS |
| v0.3 | 07-15 | 自定义 Live2D 模型导入（ZIP） |
| v0.4 / v0.4.1 | 07-16~18 | Neko 能力中心：按需下载模型，免存储权限 |
| v0.5 | 07-18 | Character Engine：LLM 驱动表情/装扮（`<action>` JSON） |
| v0.6 / v0.6.1 | 07-18~21 | 语音优先交互、线上 15 轮、安全层 |
| v0.6.2 | 07-22 | 在线 LLM 客户端化 + 下载 UX |
| v0.6.3 | 07-23 | 多本地模型下载切换、TTS 提速 45% |
| v0.6.4 | 07-24 | MiniCPM5 多精度、性能面板、MIMO 服务商 |
| v0.6.5 | 07-25~26 | 打电话模式（VAD + 打断） |
| v0.6.6 | 07-28~31 | 通话硬化、默认模型 v1.1、背景裁剪、7z/RAR 导入 |
| v0.6.7 | 08-03~04 | ASR/TTS 模型选择、音色 11 选、角色删除、性能面板 |

> ⚠️ **v0.6.7 当前存在 P0 回归**：默认 ASR 1.2（zipformer2）被 `ChatAsrController`
> 硬编码 `modelType="zipformer"` 导致启动闪退，**未修复验证前禁止发布**。
> 详见 `docs/HANDOFF_v067_20260804.md`。修法：modelType 留空让 sherpa 自动探测。

---

## 5. 文档索引（怎么找资料）

| 想了解 | 看这里 |
|---|---|
| 当前状态 / 下一步 | `docs/HANDOFF_v067_20260804.md`（唯一交接入口） |
| 端到端复现（从零构建） | `README.md`「从零复现」 |
| 开发套路 / 加功能规范 | `docs/DEVELOPMENT.md` |
| UI 历史（背景/构图/玻璃） | `docs/HANDOFF.md` |
| 各版本交接 | `docs/HANDOFF_*.md`（v061~v067） |
| 设计文档 | `docs/DESIGN_*.md`（action driver / multi-llm / online llm / phonecall） |
| 版本记录表 | `docs/VERSION_LOG.md` + `CHANGELOG.txt`（对外） |
| 技术细节 | `docs/TECHNICAL_MINICPM5_QUANTIZATION_V064.md`、`docs/voice-interaction-v06/` |
| 与 Android Agent 框架集成 | `docs/AGENT_INTEGRATION.md` |

---

## 6. 集成方必知的四条红线

1. **MNN 全局互斥**：LLM/TTS/ASR 共用 `libMNN.so`，任何两个 native 调用并发即
   SIGSEGV。所有推理必须经过 `MnnGlobalLock.lock`（详见该类注释）。
2. **两棵树工作流**：git 归档树（`submit/mini-app/apps/NekoChatMini`）≠ 编译工作副本
   （`apps/Android/NekoChatMini`）。改代码后要 cp 到工作副本再 build，否则验证的是旧代码。
3. **模型路径集中管理**：不要在任何 Activity/Backend 硬编码模型路径/文件名，一律走
   `ModelManager`（注册项）+ `LlmConfig`（用户配置）。
4. **live2d/sherpa 整目录被 .gitignore**：包含本项目集成代码但**不在 git 里**，
   目前靠兄弟项目 rsync 拷贝。做干净归档时必须显式带上这两个目录（见 §7）。

---

## 7. 纯净版归档

`scripts/make_clean_zip.sh` 一键生成：剔除全部构建产物，保留可编译源码 + 必需三方底座。

```bash
cd apps/NekoChatMini && bash scripts/make_clean_zip.sh
# 产物：/home/jhx/Projects/nlp/MNN/submit/mini-app/NekoChatMini-clean-0.6.7.zip
```

zip 内含 `docs/`、`app/`、`live2d/`、`sherpa/`、`experiments/`、`scripts/`、`patches/`、
gradle 骨架与 wrapper。解压后在 Android SDK 环境可直接 `./gradlew :app:assembleDebug`
（`local.properties` 需按本机补 `sdk.dir`）。

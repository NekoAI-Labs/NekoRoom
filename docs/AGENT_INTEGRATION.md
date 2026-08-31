# NekoChatMini × Android Agent 框架：集成指南

> 目标读者：要把 NekoChatMini 的能力（LLM / TTS / ASR / Live2D）接进一个
> Android agent 框架（如自身的 Agent 运行时、Claude Code 类桌面桥、或自定义
> 手机端 agent）的开发者。本文说明模块边界、可复用接口、并发红线与三种集成路线。

---

## 1. 集成目标拆解

NekoChatMini 本质是四套可独立复用的能力 + 一个聊天 UI：

| 能力 | 核心接口 / 类 | 复用难度 |
|---|---|---|
| 文本 LLM（本地 MNN / 在线 OpenAI） | `LlmBackend` + `LlmConfig` | 低：纯接口 + Flow |
| 语音合成（本地 SuperTonic / 在线 MiniMax） | `TtsBackend` + `SpeechQueue` | 低：纯接口 + Flow |
| 语音识别（sherpa-mnn 流式） | `ChatAsrController` / `VoiceInputController` | 中：依赖 AudioRecord + 权限 |
| Live2D 渲染 | `Live2DController` + `:live2d` module | 中：GLSurfaceView + 生命周期 |
| 能力编排（打电话/打断/字幕） | `ConversationCoordinator`（StateFlow 状态机） | 高：与 UI 耦合，可只借鉴 |

**推荐做法**：agent 框架做「大脑/编排」，NekoChatMini 做「表达层（voice + avatar）+
本地推理后端」。两边通过 3~4 个窄接口对接，而不是直接依赖 Activity。

---

## 2. 架构总览（模块依赖）

```text
┌──────────────────────────────────────────────┐
│  Agent Framework（你的编排层）                 │
│  · 决定说什么、做什么、何时打断                 │
└───────┬──────────────────────────────────────┘
        │ 窄接口：LlmBackend / TtsBackend / Asr / Live2DController
┌───────▼──────────────────────────────────────┐
│  NekoChatMini core（可抽成 library/AAR）       │
│  app: LlmConfig · ModelManager · SpeechQueue   │
│       · ConversationCoordinator · VoiceInput  │
├───────────────┬──────────────────┬────────────┤
│ :live2d module│ :sherpa module   │  native    │
│ Cubism SDK5   │ sherpa-mnn 绑定  │  MNN 推理   │
└───────────────┴──────────────────┴────────────┘
            都共享同一套 libMNN.so（MnnGlobalLock 串行化）
```

现有依赖：`:app` → `:live2d`、`:app` → `:sherpa`；`:sherpa` 通过
IMPORTED 链接 `app/src/main/jniLibs` 下的 MNN 三件套（`libMNN.so`、
`libMNN_Express.so`、`libllm.so`），不要给两个模块各放一套 MNN。

---

## 3. 核心接口速查

### 3.1 LLM — `com.moeavatar.llm.LlmBackend`

```kotlin
interface LlmBackend {
    val displayName: String
    val ready: Boolean
    suspend fun prepare(): Boolean
    fun chat(history: List<ChatTurn>): Flow<String>   // 每 emit 一段 = 一个 token chunk
    fun stop()                                          // 取消 collect 也视为 stop
    fun resetSession()                                  // 清 KVCache / 断 SSE
    fun release()
}
```

实现：`LocalLlmBackend`（MNN，`libmoeavatar_llm.so`，KVCache 多轮）、
`OpenAiLlmBackend`（SSE `/v1/chat/completions`，含 `<think>` 过滤）。
UI 只跟这个接口对话。**Agent 接入点**：直接持有任一实现即可流式拿到回复；
注意本地后端 `prepare()` 耗时（模型 load），应在后台执行。

### 3.2 TTS — `com.moeavatar.tts.TtsBackend`

```kotlin
interface TtsBackend {
    val displayName: String
    suspend fun prepare(): Boolean
    fun synth(text: String, speaker: String, stopSignal: () -> Boolean): Flow<PcmChunk>
    fun cancelSynthesis()
    fun release()
}

data class PcmChunk(val samples: FloatArray, val sr: Int, val last: Boolean, val text: String = "")
```

`SpeechQueue` 负责分句、顺序播放、barge-in、嘴型回调——**agent 侧不要自己实现播放器**，
直接复用 SpeechQueue（它同时把每句文本同步给字幕和 Live2D 口型）。

### 3.3 ASR — `ChatAsrController` / `VoiceInputController`

- `ChatAsrController(ctx)`：流式识别。`prepare(modelDir)` 在 IO 线程调用；
  `startContinuousRecording(listener)` 持续模式（配合 FireRed VAD 端点检测）；
  另有长按模式。**⚠️ 当前分支 `buildConfig()` 硬编码 `modelType="zipformer"`，
  与新默认 ASR 1.2（zipformer2）冲突导致启动闪退（P0），集成前必须先修**：
  把 `modelType` 留空让 sherpa 从 MNN metadata 自动探测。
- `VoiceInputController`：长按语音输入的协程编排（press/release/cancel），
  返回 final text，可整块复用。

### 3.4 Live2D — `Live2DController`

```kotlin
class Live2DController(context, ...) {
    val view: GLSurfaceView?        // 全屏角色视图，可嵌进你的布局
    fun onCreate(presetName: String = "ATRI")
    fun switchModel(presetName: String)
    fun applyExpression(name: String)   // 表情
    fun applyEmotion(name: String)      // LLM <action> 驱动
    fun applyOutfit(name: String) / clearOutfit()
    fun setMouthOpen(v: Float) / closeMouth()   // 嘴型（TTS PCM 回调驱动）
    fun setBackground(drawable: Drawable?)      // 换背景
    fun forwardTouch(e: MotionEvent)
    fun onResume() / onPause() / onStop() / onDestroy()
}
```

角色预设（构图 scale/translate）在 `PRESETS` 常量里，换角色加一条即可。
内置角色从 assets 解压到 `filesDir/live2d_builtin/`（一次性，marker 文件判断）。

### 3.5 编排与配置

- `ConversationCoordinator`：`StateFlow<VoiceInteractionState>` 状态机
  （Idle/Preparing/Listening/Finalizing/Speaking/Interrupted/Error），
  含 session id 防串扰。agent 框架可订阅它做 UI 反馈，或只借鉴其状态设计。
- `LlmConfig(ctx)`：**所有可配置项的唯一边界**——后端选择、base/key/model、
  系统提示词、TTS 后端/音色、Live2D 角色、字幕/性能开关。SharedPreferences 持久化。
- `ModelManager`：模型注册中心。`NekoModel(id, capability, msRepo, subDir,
  requiredFiles, sizeBytes, recommended)`；`activeLlm/Asr/Tts(ctx)` 选择与回退逻辑
  都在这里，**agent 集成也必须走这里，禁止自行拼路径**。
  模型根目录 = `getExternalFilesDir(null)/models/{llm,asr,tts}`。

---

## 4. 并发与线程红线（最重要）

`MnnGlobalLock.kt`：LLM / TTS / ASR 全部链接同一份 `libMNN.so`，共享 MNN 内部
ThreadPool 与 allocator，**任意两个 native 调用并发 = SIGSEGV 闪退**。

- TTS infer（约 1~2s）持 `ReentrantLock`；ASR decode（<100ms/片）持锁。
- 集成方新增任何 MNN 调用（比如给 agent 加一个 embedding/rerank）必须经过
  `MnnGlobalLock.lock`，否则会和 TTS/ASR 撞车。
- 协程用 `withLock`，Thread 用 `lock()/unlock()`；TTS 的 infer 必须包
  `NonCancellable`，避免 cancel 时锁被释放但 infer 仍在跑。

其余线程约定：ASR 的 AudioRecord 捕获与 decode 分离（捕获线程永不等待 MNN）；
`prepare()`/模型下载在 IO 线程；Live2D 全部走 GL 线程。

---

## 5. 三种集成路线（按侵入度排序）

### 路线 A：模块内嵌（推荐起步）

把三个 module（`app` 改造为 library 或直接保留 app + 两个 library）整体纳入 agent
工程，agent 在代码里直接 `new LlmBackend` / `Live2DController`。

- 优点：改动最小、能立刻跑通四件套；`app` 里 `LlmChatActivity` 可作为 agent 的
  UI 兜底（完整交互样例）。
- 缺点：`app` 是 Application 模块（manifest/launcher 耦合），需把 `com.neko.chat`
  的 launcher Activity 摘除或加 `exported=false`，或抽成 core library。
- 步骤：
  1. 复制 `app` 的 `java/`、`cpp/`、`res/`、`assets/` 到你的 core module；
  2. 依赖 `:live2d`、`:sherpa`，去掉 `LlmChatActivity` 的 LAUNCHER intent-filter；
  3. 你的 agent 入口调用 `LlmConfig` + `ModelManager` 初始化，把 TTS 输出接到
     `SpeechQueue`，Live2D 视图加进你的根布局。

### 路线 B：抽 core AAR（干净解耦）

把「能力层」抽成独立 library module（`nekochat-core`），只暴露
`LlmBackend` / `TtsBackend` / `ChatAsrController` / `Live2DController` /
`ModelManager` / `LlmConfig`，UI 留在外面。产物是一个可发布的 AAR。

- 优点：agent 框架与聊天 UI 完全解耦；能力可被多个宿主复用。
- 代价：需要一次重构——把 `LlmChatActivity` 里的装配逻辑（`prepareBackend` 等）
  下沉到 core 的 Facade（如 `NekoChatCore.init(ctx)` 返回一个
  `NekoAgentBridge`，暴露 `chat(text)`, `speak(text)`, `startAsr()` 等）。

### 路线 C：进程外 Service（面向桌面桥 / 跨进程 agent）

把 NekoChatMini 作为独立 APK 常驻，用 Android `Service` + `Binder`（或
`MediaProjection`/socket）暴露能力，agent 框架通过 IPC 调用。

- 优点：完全隔离崩溃与内存（本地模型很占内存）；符合 v0.7 Roadmap 的
  「PC bridge」方向。
- 代价：需要做 AIDL 接口 + 序列化（PCM float 走共享内存或文件），工作量大。

> 建议：先走 A 验证效果，稳定后演进到 B；C 留给后续桌面桥需求。

---

## 6. 构建与发布注意事项

- **ABI**：三个模块都只编 `arm64-v8a`，别加其它 ABI（MNN/ sherpa .so 只有 arm64）。
- **noCompress**：app 的 `androidResources.noCompress` 已含 `mnn/bin/json/txt`；
  sherpa 模块含 `onnx`。抽 core 时保持一致，否则模型读取/内存映射异常。
- **signing**：release 需要 `~/.config/nekochat/release-signing.properties` 指向
  你的 keystore；没有该文件 release 构建会直接抛 GradleException。
- **R8**：app release 开了 minify；`live2d`/`sherpa` 未开。抽 core 后按需给
  JNI 类加 keep 规则（`consumer-rules.pro` 已有基础配置）。
- **权限**：`INTERNET`（在线后端）+ `RECORD_AUDIO`（ASR）。本地模型读应用私有
  目录，不需要存储权限（v0.4 起的设计）；Android 13+ adb 预置模型时需先让 App
  创建目录（`ModelManager.prepareTtsDirectory` 同理可推广）。
- **minSdk 26 / targetSdk 35 / compileSdk 35 / NDK 26.1.10909125 / CMake 3.22.1**
  是经过验证的组合，升级前先在真机回归四件套。

---

## 7. 给 Agent 框架的最小接入清单

1. 修掉 v0.6.7 P0（`ChatAsrController` 的 `modelType` 硬编码）——**集成前必做**。
2. 初始化：`ModelManager` 注册表确认三套模型已装（或走下载器），
   用 `LlmConfig` 读取用户选择，`prepare()` 各后端。
3. 对话：`LlmBackend.chat(history)` 收 token → 你的 agent 决定回复 → 
   `TtsBackend.synth` + `SpeechQueue` 播放（口型自动联动）。
4. 打断：任何新输入先 `stop()` 旧 LLM/TTS，再开新一轮
   （v0.6 的教训：不同步停会出破碎音/闪退）。
5. 渲染：把 `Live2DController.view` 放进宿主布局，把
   `onCreate/onResume/onPause/onStop/onDestroy` 透传给 Activity 生命周期。
6. 模型资产：保持「不进 APK、运行时可下载/可 adb push」策略，代理框架只关心
   `ModelManager` 提供的目录。

---

## 8. 相关参考

- 架构与红线：`docs/PROJECT_OVERVIEW.md`
- 开发套路：`docs/DEVELOPMENT.md`
- 当前交接：`docs/HANDOFF_v067_20260804.md`
- 打电话模式（VAD/打断参考实现）：`docs/DESIGN_v065_phonecall.md`
- Character Engine（LLM 动作驱动 Live2D）：`docs/DESIGN_action_driver.md`
- 上游底座：`apps/Android/AvatarLive2DMini`（live2d）、`apps/Android/MoeAvatarPro`
  （sherpa 绑定 + MNN 三件套）、`apps/Android/SuperTonicMini`（TTS 内核）

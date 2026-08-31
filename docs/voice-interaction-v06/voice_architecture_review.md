# Neko Companion V0.6 语音架构评审

## 1. 评审范围

本文基于当前 `NekoChatMini` 工作区实现，目标是在不改动 Live2D 渲染主链路的前提下，接入 V0.6 的“语音直发 + 文本精确模式 + AI 回复中断”。

V0.6 明确不包含：连续电话模式、唤醒词、全双工、识别文本确认、消息历史和长期记忆。

## 2. 当前语音链路

### 2.1 用户语音输入

```text
MotionEvent.ACTION_DOWN
        |
LlmChatActivity.onMicPressed()
        |
停止上一轮 LLM / TTS / 字幕
        |
ChatAsrController.startRecording()
        |
AudioRecord (16 kHz / mono / PCM 16-bit)
        |
sherpa-mnn OnlineRecognizer
        |
partial text -> EditText
        |
MotionEvent.ACTION_UP / ACTION_CANCEL
        |
ChatAsrController.stopRecordingAndFinalize()
        |
final text -> EditText
        |
用户再次点击发送按钮
```

当前已具备按下录音、流式 partial、松手 finalize，但最终结果只回填输入框，不会自动发送。`ACTION_CANCEL` 也按正常松手处理。

### 2.2 AI 回复和语音输出

```text
LlmChatActivity.sendCurrentInput()
        |
LlmBackend.chat()
        |
流式 token
        +---------------------> ActionTagFilter -> Live2D 表情/装扮
        |
TtsTextFilter + SentenceSplitter
        |
SubtitleManager + SpeechQueue.enqueue()
        |
TtsBackend.synth()
        |
Float PCM -> AudioTrack
        |
RMS 口型包络 -> Live2DController.setMouthOpen()
```

`SpeechQueue` 已支持立即暂停、清空、等待静默、播放状态回调和自然播放结束回调。现有停止入口是 `LlmChatActivity.stopGeneration()`，但停止按钮复用了发送按钮，状态表达不够清晰。

### 2.3 当前组件关系

```text
AudioRecord
    |
ChatAsrController
    |
临时布尔状态 micHeld / micRecording / asrReady
    |
LlmChatActivity
    |
LlmBackend -> SentenceSplitter
    |
SpeechQueue
    |
TtsBackend -> AudioTrack
    |
Live2D mouth / expression
```

问题不在底层能力缺失，而在 `LlmChatActivity` 同时承担输入手势、状态机、LLM 编排、TTS 打断、权限和 UI 映射。

## 3. 哪些代码应该移动

V0.6 首先做逻辑抽离，不移动 Native 或模型实现。

| 当前代码 | 建议目标 | 原因 |
|---|---|---|
| `micHeld`、`micRecording`、`micPrepareJob` | `VoiceInputController` | 录音会话状态不应属于 Activity |
| `onMicPressed/onMicReleased/startMic` | `VoiceInputController` | 统一按下、上滑取消、松手直发语义 |
| ASR partial/final 到 UI 的转换 | `VoiceInteractionCoordinator` | 语音模式不展示识别文本，文本模式才操作 EditText |
| `sendCurrentInput` 的对话启动部分 | `ConversationCoordinator.submit()` | 让文本和语音共用一个发送入口 |
| `stopGeneration` 及三处重复打断代码 | `ConversationCoordinator.interrupt()` | 保证停止、按下录音、新消息使用同一打断顺序 |
| `generating` 等页面布尔状态 | `VoiceInteractionState` | 避免互斥状态组合失效 |
| 按钮显隐和 alpha 修改 | `VoiceInteractionUiBinder` | UI 只根据状态渲染，不决定业务状态 |

暂时不要移动：

- `ChatAsrController` 的 sherpa 配置和 `AudioRecord` 实现。
- `SpeechQueue` 的合成、播放、口型包络和停止实现。
- `LlmBackend`、`TtsBackend` 及其本地/在线实现。
- `Live2DController` 和 `:live2d` Native 代码。
- 模型下载、能力中心和 `LlmConfig` 现有配置。

## 4. 建议的 V0.6 状态模型

```text
IdleVoice
  | press
  v
Preparing -> Listening -> CancelArmed
                 | release       | release
                 v               v
              Finalizing      IdleVoice
                 |
                 +-- empty/error -> IdleVoice
                 |
                 v
              Thinking -> Speaking -> IdleVoice
                 | stop      | stop/press
                 +-----------+----------> Interrupted -> IdleVoice/Listening

IdleVoice <-> TextEditing
```

建议使用单一 sealed state，而不是新增 `isListening/isCancelling/isSpeaking/isTextMode` 等独立布尔值。状态中可携带 `inputMode`、实时音量、错误原因和当前 turn id。

## 5. 最小修改方案

### 阶段 A：只抽公共接口

1. 新增 `ConversationCoordinator.submit(text, source)`，内部暂时委托现有发送链路。
2. 新增 `ConversationCoordinator.interrupt(reason)`，统一执行 LLM cancel、backend stop、TTS clear、字幕停止、闭嘴和 Live2D idle。
3. 让现有文字发送和麦克风按下都调用上述接口，保证行为不变。

### 阶段 B：替换输入交互

1. 将底部输入栏变为 `Voice` 和 `Text` 两种互斥模式。
2. 默认显示“按住和猫娘说话...”和右侧键盘按钮。
3. 按下进入 `Listening`，松手异步 finalize；结果非空时直接 `submit(..., VOICE)`。
4. 语音模式不把 partial/final 写进 `EditText`，只更新状态和音量视图。
5. 文本模式保留现有 `EditText`，补上 IME action send。
6. `ACTION_CANCEL` 或上滑越过阈值时调用 `cancelRecording()`，不发送。

### 阶段 C：显式停止

1. `Thinking` 和 `Speaking` 状态显示独立方形停止符号。
2. 停止统一调用 `interrupt(USER_STOP)`。
3. 停止后关闭嘴型、清空未播放音频、停止字幕，并让 Live2D 恢复 idle。

## 6. 不影响 Live2D 的接入点

V0.6 不修改 `GLSurfaceView`、`live2d_container`、渲染顺序和 Native 接口，只使用现有公开方法：

| 时机 | 调用 |
|---|---|
| TTS 播放 PCM | 保持 `SpeechQueue.setMouthListener -> setMouthOpen` |
| TTS 自然结束 | 保持 `closeMouth()` 和 `restoreIdle()` |
| 用户开始录音 | `interrupt()` 后调用 `closeMouth()`，可选恢复 idle |
| 用户主动停止 AI | `closeMouth()` + `restoreIdle()` |
| LLM action 到达 | 保持现有 `dispatchAction()`，不与输入状态机耦合 |

输入栏和录音提示必须继续位于 `live2d_container` 之上的普通 View 层，光晕限制在底部输入区域。不要改变 `GLSurfaceView.setZOrderMediaOverlay(true)`，不要在 Live2D 容器上增加模糊采样或新的 Surface。

## 7. 需要先处理的风险

1. `stopRecordingAndFinalize()` 当前可能在主线程等待 1.2 秒，直发前应移到 IO 协程并用 turn id 防止过期结果发送。
2. `ACTION_CANCEL` 必须和正常松手分开，否则系统手势取消也会误发。
3. 本地 TTS Native 合成不能真正抢占；进入录音前必须确认 AudioTrack 静默，并防止旧合成结果回流。
4. `MnnGlobalLock` 当前实际使用范围与注释不一致，需要在真机验证本地 ASR、LLM、TTS 的并发安全。
5. 权限首次授予后不会自动恢复本次长按，V0.6 应回到 Idle 并要求重新按住，避免授权结束即偷录。
6. 连续快速按下、松手、停止必须按 turn/session id 丢弃旧回调，不能只依赖 Job cancel。


# v0.6.5 电话模式：全双工回声与打断交接

更新时间：2026-07-26。对应提交从 `dbb30a4`（初版全双工）到待真机确认的本轮修复。

## 当前链路

电话模式不是“AI 说话时关闭麦克风”，而是持续全双工：

```text
TTS PCM -> AudioTrack 扬声器
麦克风 -> VOICE_COMMUNICATION -> 系统 AEC / NS / AGC（设备支持时）
       -> FireRed VAD（每 300ms）-> Sherpa 流式 ASR -> endpoint -> LLM
```

- 录音创建在 `ChatAsrController.startContinuousRecording()`，使用 `VOICE_COMMUNICATION`；会尝试启用 `AcousticEchoCanceler`、`NoiseSuppressor`、`AutomaticGainControl`，真机以日志中的 `aec/ns/agc=true|false` 为准。
- Android 公共 API 不能把应用 TTS PCM 显式作为 AEC reference 送给效果器；目前依赖设备 HAL 将通信采集与扬声器参考配对。因此效果有明显机型差异。
- `PhoneCallController` 在 AI 播放中使用动态 VAD：概率、连续帧、占比、RMS 相对残留基线共同判断，不能退回“任意一帧 max 即触发”。

## 本轮修复（待真机复测）

### 1. TTS 声音明显变小

此前将 `AudioTrack` 标为 `USAGE_VOICE_COMMUNICATION / CONTENT_TYPE_SPEECH`，部分厂商会把它映射到独立的、较低的通话音量流；同时通信采集路径可能带较强增益/处理，所以表现为“AI 更小、用户得大声说话”。

现在：

- 录音仍为 `VOICE_COMMUNICATION`，AEC/NS/AGC 仍尝试开启；
- TTS 回到 `USAGE_MEDIA / CONTENT_TYPE_MUSIC`，恢复正常媒体音量。

代价：少数设备上系统 AEC 可能因不再是通信播放路径而略弱。若自触发回归，优先调 VAD 门控；不能为了 AEC 再把所有用户的播放音量降到通话流。长期工业方案才是 WebRTC AEC3（需要 PCM reference，改动较大）。

### 2. 打断慢、第一句丢失

已修两处：

- 手动“打断”以前会 `stopContinuousRecording()` 后重新创建 ASR 流，用户在按键前已说出的音频必丢；现在保留原 ASR 流，只立即停止 TTS。
- AI 说话时 ASR endpoint 以前直接丢弃；现在暂存该文本，FireRed 确认真人插话后立即提交，避免 endpoint 比 VAD 300ms 窗口更早时要用户“再说一句”。

自动打断门槛由“两段 300ms、RMS 1.35 倍”放宽为“一段 300ms、至少 80ms 连续高置信、占比 30%、RMS 1.18 倍”。仍保留 450ms warm-up 和动态残留基线，防止刚开始播放的回声误触发。

## 关键文件

- `app/src/main/java/com/moeavatar/phonecall/PhoneCallController.kt`：状态机、VAD 决策、插话、尾音保护。
- `app/src/main/java/com/moeavatar/chat/ChatAsrController.kt`：AudioRecord、系统音频效果、Sherpa 连续识别。
- `app/src/main/java/com/moeavatar/tts/SpeechQueue.kt`：AudioTrack 属性和即时静音。
- `app/src/main/java/com/moeavatar/chat/LlmChatActivity.kt`：TTS 回调和电话控制器装配。

## 真机验证与日志

每台机至少测 30 次“AI 连续外放但无人说话”，记录自触发次数；再测 20 次正常音量插话，记录成功率和从起音到 TTS 停止的时间。当前未取得可靠真机统计数据，不能宣称改善比例。

建议日志：

```bash
adb logcat -v threadtime | grep -E 'PhoneCall|ChatAsr|MoeAvatar.Speech'
```

重点看：

- `continuous audio source=7 effects aec=... ns=... agc=...`：机型实际是否提供系统效果；
- `VAD decision=accept ... barge_in`：自动打断已确认；
- `ASR endpoint deferred` + `delivering deferred ASR endpoint`：第一句 endpoint 被成功保留；
- `manual barge-in accepted; retaining active ASR stream`：手动打断没有重启录音。

## 后续调参顺序

1. 先只调 `PhoneCallController` 的 `AI_MIN_FRAME_THRESHOLD`、`AI_RMS_GAIN`、`AI_MIN_CONSECUTIVE_FRAMES`，每次改一个；保存日志和机型信息。
2. 自触发高：提高 RMS gain 或连续帧；打断慢：先降低连续帧，再小幅降低概率阈值。不要使用单帧 max。
3. 系统 AEC 依然不足，再单独评估 WebRTC AEC3；它需要把 TTS PCM 作为 reference 送入音频处理链，不是简单换一个 `AudioSource`。

## 2026-07-27 架构修复（提交后待真机验证）

### AudioTrack 生命周期

`SpeechQueue` 不再让 idle coroutine 直接持有裸 `AudioTrack`。每次创建、替换或摘除 track 都更新 `playbackGeneration`；异步 idle task 持有 `(track, generation)` lease，每次读取播放头和 release 前都验证它仍是 active lease。`clear()`/`stopAndAwaitSilence()` 先原子摘除，再 release，因此旧协程不能对已释放对象调用 `getPlaybackHeadPosition()`。

这是对 2026-07-27 真机崩溃的直接修复：

```text
IllegalStateException: Unable to retrieve AudioTrack pointer for getPosition()
SpeechQueue.schedulePlaybackIdleCheck(...)
```

### 实时录音与 ASR 解耦

旧链路在同一个录音线程依次执行：`AudioRecord.read -> FireRed(MNN) -> Sherpa(MNN)`。SuperTonic 合成持有 MNN 安全锁时，连 `read()` 后的 VAD 都会等待，硬件缓冲最终覆盖用户首词。

新链路：

```text
AudioRecord producer (20ms, no MNN)
  ├─ Fast RMS barge gate（<=220ms，直接停 TTS）
  └─ 3s PCM FIFO -> ASR consumer -> FireRed/Sherpa（可安全等待 MNN）
```

不能把 FireRed/Sherpa/SuperTonic 粗暴改为并发 MNN run：本项目已有跨模型并发导致 `libMNN.so` SIGSEGV 的真实记录。这里的关键是**实时采集与快速打断不再依赖 MNN**，而非取消 native 安全串行；即使后台解码暂时等待，首词也已经在 FIFO 内保存。

### TTS 协作式取消

SuperTonic native 增加原子 cancel flag，在 DP、TE、每个 diffusion VE step、vocoder 前检查。打断不 kill thread、不释放正在执行的 MNN session；未完成句子返回空 PCM，队列不会播放后续音频。当前 Quality 3–8 下最大剩余工作为当前单个 MNN graph step（需按机型日志测量，不能在未测前承诺固定毫秒）。

### 新日志与验收

- `continuous capture started frameMs=20 queueFrames=150`：producer 已启动；
- `fast barge-in accept elapsed=...`：非 MNN 快速打断时间；目标 <=300ms；
- `playback usage=media content=music route=...`：确认 TTS 没走通信播放流；
- 不应再出现 `Unable to retrieve AudioTrack pointer`。

真机须测试：AI 播放中说“你好”、连续打断 5 次、外放 30 次无用户讲话。记录 Fast gate 时间、ASR 最终文本是否保留“你好”、crash buffer 是否为空。

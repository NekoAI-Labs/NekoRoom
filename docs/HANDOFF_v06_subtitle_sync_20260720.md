# NekoChatMini v0.6 共享字幕同步交接（2026-07-20）

> 分支：`feature/voice-interaction-v06`
>
> 目标：修复 AI 声音领先字幕，并让 ASR 最终文本短暂显示在 AI/用户共享字幕中。

## 1. 原问题

AI 字幕和 TTS 原来是两条独立时钟：

- LLM 切出句子后，`SubtitleManager` 按固定 `60ms/字` 播放打字机动画。
- 同一句同时进入 `SpeechQueue`，TTS 首个 PCM 到达后立即写入 `AudioTrack`。
- `SpeechQueue.onClauseStart` 已存在，但 Activity 没有使用，而且旧回调发生在写 PCM 之后。

本地 LLM/TTS 速度碰巧接近时不明显；线上流式 TTS 首包快、句子长时，音频会反超固定字速字幕。

## 2. 当前同步规则

```text
LLM 完整切句
    |
SpeechQueue.enqueue(text)
    |
TTS 首个有效 PCM（在线 chunk 自动补源文本）
    |
onClauseStart(text)
    |
Dispatchers.Main.immediate 显示完整字幕，alpha = 1
    |
写入 AudioTrack
```

字幕上屏成为音频写入的前置屏障，不再通过固定字速或固定等待秒数猜测同步。AI 字幕保持到下一句覆盖；TTS 自然结束后再淡出。

在线 MiniMax 的流式 `PcmChunk.text` 原本为空。`SpeechQueue` 只给每个 enqueue 句子的第一个有效 PCM 补源文本，后续网络 chunk 不重复刷新字幕。本地 SuperTonic 继续使用它返回的 clause 文本。

无可用 TTS 时仍直接显示 LLM 字幕，不依赖播放回调。

## 3. 用户 ASR 共享字幕

按住说话松手并得到 ASR 最终文本后：

1. 停止上一轮 AI/TTS 并清空旧字幕。
2. 调用 `SubtitleManager.showUser(finalText)`，在共享字幕短暂显示用户说的话。
3. 不写回输入框，也不增加确认步骤，消息照常直接发送。
4. AI 第一段音频开始前，`showAiClause()` 自然覆盖用户字幕。

键盘发送的文本沿用同一路径。设置中的“显示 AI 字幕”只控制 AI 回复，不关闭用户 ASR/键盘字幕。

## 4. 关键文件

- `app/src/main/java/com/moeavatar/chat/LlmChatActivity.kt`
- `app/src/main/java/com/moeavatar/chat/subtitle/SubtitleManager.kt`
- `app/src/main/java/com/moeavatar/tts/SpeechQueue.kt`
- `app/src/main/res/layout/activity_llm_chat.xml`（仅更新说明注释，UI 未改）

## 5. 回归重点

1. 在线 LLM + 在线 MiniMax TTS：长句、多句连续回复，字幕不得晚于对应音频。
2. 本地 LLM + SuperTonic：子句切分后字幕应随实际子句切换。
3. 长按说话：松手识别完成后，用户原话在共享字幕显示，随后被 AI 首句覆盖。
4. 关闭 AI 字幕：AI 回复不显示字幕，但用户 ASR 结果仍显示。
5. 中途停止 AI：声音、口型和字幕同时清空，不出现旧 clause 回调复活字幕。

## 6. 验证

- 完整工作副本 `:app:testDebugUnitTest`：通过。
- 完整工作副本 `:app:assembleDebug`：通过。
- 归档树因设计上不保存 `libllm.so` 和已打补丁的 Live2D module，不能作为最终构建树。

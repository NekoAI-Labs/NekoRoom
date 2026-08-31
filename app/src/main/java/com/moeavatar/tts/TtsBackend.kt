package com.moeavatar.tts

import kotlinx.coroutines.flow.Flow

/**
 * TTS 抽象：本地 BertVITS2 跟在线 MiniMax 都实现这个接口，让 [SpeechQueue] 不关心是谁合成。
 *
 * 一句文本 → 一个 [Flow]<PcmChunk>。
 * - 本地（一次合成完）：flow 只 emit 一次 (samples, sr, last=true)
 * - 在线流式：flow 边收边 emit 多个 chunk，最后一个 last=true
 *
 * 这样 SpeechQueue 的 playJob 只要顺序消费 chunk 写 AudioTrack，本地/远端代码完全相同。
 *
 * 用 Flow 而不是 Sequence 因为合成涉及 suspend（IO 阻塞 / suspend infer 调用）。
 */
interface TtsBackend {
    val displayName: String

    /** lazy 初始化（下载模型 / 验 key 等）。返回 false 表示用不了。 */
    suspend fun prepare(): Boolean

    /**
     * 合成一句话。返回 cold flow —— 每次 collect 触发一次合成。
     * 实现必须能响应 [stopSignal]（mic 按下时要立即中断）。
     */
    fun synth(text: String, speaker: String, stopSignal: () -> Boolean): Flow<PcmChunk>

    /** Stop work that has not produced PCM yet. Implementations must be cooperative and safe. */
    fun cancelSynthesis() {}

    /** 释放 native / 网络资源。本地 BertVITS2 切到远端时调这个把模型从内存丢出去。 */
    fun release()
}

/**
 * @param samples float[-1,1] 单声道
 * @param sr      采样率 (Hz)
 * @param last    序列最后一个 chunk 标志（用来给 SpeechQueue 做日志）
 * @param text    这段 PCM 对应的源文本（clause/句子级），用来给 v2 字幕同步
 */
data class PcmChunk(
    val samples: FloatArray,
    val sr: Int,
    val last: Boolean,
    val text: String = ""
)

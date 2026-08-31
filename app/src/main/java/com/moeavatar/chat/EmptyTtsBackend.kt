package com.moeavatar.chat

import com.moeavatar.tts.PcmChunk
import com.moeavatar.tts.TtsBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 占位 TtsBackend：什么都不做。
 *
 * 用于 SpeechQueue 启动时尚未确定真 backend（用户没设 LLM / TTS 模型未加载），
 * 也用于切 backend 时短暂让 synth 走空流，避免新 backend 还没 prepare 完就开始合成。
 */
object EmptyTtsBackend : TtsBackend {
    override val displayName: String = "Empty"

    override suspend fun prepare(): Boolean = true

    override fun synth(
        text: String,
        speaker: String,
        stopSignal: () -> Boolean,
    ): Flow<PcmChunk> = emptyFlow()

    override fun release() = Unit
}

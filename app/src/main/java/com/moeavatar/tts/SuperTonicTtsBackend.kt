package com.moeavatar.tts

import android.content.Context
import android.util.Log
import com.example.supertonic.SuperTonicInfer
import com.moeavatar.llm.LlmConfig
import com.moeavatar.model.ModelManager
import com.moeavatar.perf.PerformanceTrace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * SuperTonic-Neko 本地 TTS backend。单一猫娘音色，模型从设备端 [modelDir] 读（不进包）。
 *
 * SuperTonic 单次合成有 ~6.97s 上限。上游 [SentenceSplitter] 负责安全长度和语义切句，
 * Backend 不再进行第二次语义切分，避免连续短片段造成明显停顿。
 */
class SuperTonicTtsBackend(
    context: Context,
    private val model: com.moeavatar.model.NekoModel = ModelManager.activeTts(context),
    private val modelDir: String = ModelManager.ttsDir(context, model),
    private val voiceId: String = ModelManager.resolveTtsVoice(context, model),
) : TtsBackend {

    override val displayName: String = "SuperTonic-Neko"

    private val infer = SuperTonicInfer(context.applicationContext)
    private val config = LlmConfig(context)
    @Volatile private var ready = false
    @Volatile private var performanceLogging = false

    override suspend fun prepare(): Boolean {
        if (ready) return true
        ready = runCatching { infer.init(modelDir, voiceId = voiceId) }.getOrElse {
            Log.e(TAG, "SuperTonic init failed, voiceId=$voiceId", it)
            false
        }
        if (!ready) Log.e(TAG, "SuperTonic init failed, model=${model.id} modelDir=$modelDir")
        return ready
    }

    // --- 每轮回复的性能统计（首包延迟 + RTF）--------------------------------
    // firstPacketMs：本轮第一个 clause 的合成耗时（≈从有文本到出第一段音频的延迟）。
    // RTF = 累计合成耗时 / 累计音频时长。resetStats() 在每轮 LLM 回复开始时调。
    @Volatile private var firstPacketMs: Long = -1L
    @Volatile private var accSynthMs: Long = 0L
    @Volatile private var accAudioMs: Long = 0L

    fun resetStats() {
        firstPacketMs = -1L
        accSynthMs = 0L
        accAudioMs = 0L
    }

    fun setPerformanceLogging(enabled: Boolean) {
        performanceLogging = enabled
        infer.setPerformanceLogging(enabled)
    }

    data class TtsStats(val firstPacketMs: Long, val rtf: Double)

    fun snapshotStats(): TtsStats {
        val rtf = if (accAudioMs > 0L) accSynthMs.toDouble() / accAudioMs else 0.0
        return TtsStats(firstPacketMs, rtf)
    }

    override fun synth(text: String, speaker: String, stopSignal: () -> Boolean): Flow<PcmChunk> = flow {
        if (!ready) return@flow
        val clauses = text.trim().takeIf { it.any(Char::isLetterOrDigit) }
            ?.let(::listOf).orEmpty()
        for ((idx, clause) in clauses.withIndex()) {
            if (stopSignal()) return@flow
            val (pcm, ms) = infer.synth(clause, totalSteps = config.ttsSteps)
            if (pcm == null || pcm.isEmpty()) {
                Log.w(TAG, "synth returned empty for: $clause")
                continue
            }
            if (firstPacketMs < 0L) firstPacketMs = ms
            accSynthMs += ms
            accAudioMs += pcm.size * 1000L / 44100
            if (performanceLogging) {
                PerformanceTrace.i(
                    "tts_clause",
                    "chars=${clause.length} samples=${pcm.size} synth_ms=$ms rtf=${infer.rtf(pcm, ms)}",
                )
            }
            // All clauses share one AudioTrack. Do not fade every clause: a fade-in/out
            // here creates an audible 16ms volume dip (“闪音”) at each sentence join.
            emit(PcmChunk(pcm, 44100, last = idx == clauses.lastIndex, text = clause))
        }
    }.flowOn(Dispatchers.Default)

    override fun cancelSynthesis() {
        infer.cancelSynthesis()
    }

    override fun release() {
        if (ready) {
            infer.release()
            ready = false
        }
    }

    companion object {
        private const val TAG = "MoeAvatar.SuperTonic"
    }
}

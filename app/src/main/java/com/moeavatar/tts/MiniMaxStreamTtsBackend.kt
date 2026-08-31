package com.moeavatar.tts

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MiniMax 在线流式 TTS：POST /v1/t2a_v2 with stream=true，SSE 形式吐 chunk。
 *
 * 每一行 `data: {"data":{"audio":"<hex>","status":1|2}, ...}`：status=1 增量，2 是末帧。
 * audio 是 16-bit LE PCM 的 hex；配 format=pcm / sample_rate=32000，拿到 32kHz int16 mono，
 * 转成 float[-1,1] 喂 SpeechQueue，与本地 SuperTonic 路径一致。
 */
class MiniMaxStreamTtsBackend(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE,
) : TtsBackend {

    override val displayName: String = "MiniMax · $model"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)        // SSE 不超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val currentCall = AtomicReference<okhttp3.Call?>(null)

    override suspend fun prepare(): Boolean = apiKey.isNotBlank()

    override fun synth(text: String, speaker: String, stopSignal: () -> Boolean): Flow<PcmChunk> = flow {
        if (stopSignal() || apiKey.isBlank() || text.isBlank()) {
            Log.w(TAG, "synth skipped: stop=${stopSignal()} key.blank=${apiKey.isBlank()} text.blank=${text.isBlank()}")
            return@flow
        }

        val voiceId = speaker.ifBlank { DEFAULT_VOICE }
        val body = JSONObject().apply {
            put("model", model)
            put("text", text)
            put("stream", true)
            put("voice_setting", JSONObject().apply {
                put("voice_id", voiceId)
                put("speed", 1)
                put("vol", 1.6)
                put("pitch", 0)
                put("emotion", EMOTION_NORMAL)
            })
            put("audio_setting", JSONObject().apply {
                put("sample_rate", SR)
                put("bitrate", 128000)
                put("format", "pcm")
                put("channel", 1)
            })
        }.toString()

        Log.i(TAG, "POST $baseUrl model=$model voice=$voiceId text.len=${text.length}")

        val req = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(req)
        currentCall.set(call)
        val tStart = System.currentTimeMillis()
        var firstChunkMs = -1L
        var totalSamples = 0L

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(500) ?: ""
                    Log.e(TAG, "HTTP ${resp.code}: $errBody")
                    throw IllegalStateException("MiniMax TTS HTTP ${resp.code}: ${errBody.take(160)}")
                }
                val source = resp.body?.source() ?: return@use
                while (!source.exhausted()) {
                    if (stopSignal()) { call.cancel(); break }
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val (samples, status) = parseChunk(payload) ?: continue
                    // MiniMax 末帧 (status=2) 的 audio 是**整段再来一遍**，若前面已有增量则丢重复，
                    // 仅当只有 status=2（无增量）时才 emit 全量。
                    if (status == STATUS_FINAL) {
                        if (totalSamples > 0L) {
                            emit(PcmChunk(FloatArray(0), SR, last = true))
                        } else if (samples.isNotEmpty()) {
                            if (firstChunkMs < 0) firstChunkMs = System.currentTimeMillis() - tStart
                            totalSamples += samples.size
                            emit(PcmChunk(samples, SR, last = true))
                        } else {
                            emit(PcmChunk(FloatArray(0), SR, last = true))
                        }
                        break
                    }
                    if (samples.isNotEmpty()) {
                        if (firstChunkMs < 0) {
                            firstChunkMs = System.currentTimeMillis() - tStart
                            Log.i(TAG, "first chunk in ${firstChunkMs}ms (sr=$SR len=${samples.size})")
                        }
                        totalSamples += samples.size
                        emit(PcmChunk(samples, SR, last = false))
                    }
                }
            }
        } catch (t: Throwable) {
            if (!call.isCanceled()) {
                Log.e(TAG, "stream error (model=$model voice=$voiceId text.len=${text.length})", t)
                throw t
            }
        } finally {
            currentCall.compareAndSet(call, null)
            Log.i(TAG, "synth done: text=${text.take(20)} samples=$totalSamples wall=${System.currentTimeMillis() - tStart}ms")
        }
    }

    override fun release() {
        currentCall.getAndSet(null)?.cancel()
    }

    /** 把 status 行解出来 → (float samples [-1,1], status) */
    private fun parseChunk(payload: String): Pair<FloatArray, Int>? {
        return try {
            val obj = JSONObject(payload)
            val data = obj.optJSONObject("data") ?: return null
            val hex = data.optString("audio", "")
            val status = data.optInt("status", 1)
            val samples = if (hex.isNotEmpty()) pcm16HexToFloat(hex) else FloatArray(0)
            samples to status
        } catch (t: Throwable) {
            Log.w(TAG, "bad payload: ${payload.take(120)}", t); null
        }
    }

    /** hex string → int16 LE → float[-1,1]. */
    private fun pcm16HexToFloat(hex: String): FloatArray {
        val len = hex.length / 2
        if (len < 2) return FloatArray(0)
        val sampleCount = len / 2
        val out = FloatArray(sampleCount)
        var i = 0
        var s = 0
        while (s < sampleCount) {
            val lo = hexNibble(hex[i]) shl 4 or hexNibble(hex[i + 1])
            val hi = hexNibble(hex[i + 2]) shl 4 or hexNibble(hex[i + 3])
            var v = (hi shl 8) or lo
            if (v >= 0x8000) v -= 0x10000
            out[s] = v / 32768.0f
            i += 4
            s++
        }
        return out
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    companion object {
        private const val TAG = "MiniMaxTts"
        private const val DEFAULT_BASE = "https://api.minimaxi.com/v1/t2a_v2"
        const val DEFAULT_MODEL = "speech-2.6-hd"
        const val DEFAULT_VOICE = "female-shaonv"
        private const val EMOTION_NORMAL = "happy"
        private const val SR = 32000
        private const val STATUS_FINAL = 2
    }
}

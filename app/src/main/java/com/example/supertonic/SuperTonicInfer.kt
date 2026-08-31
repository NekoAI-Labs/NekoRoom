package com.example.supertonic

import android.content.Context
import com.moeavatar.MnnGlobalLock
import org.json.JSONObject
import java.io.File

/**
 * High-level SuperTonic-Neko synthesizer. Mirrors MoeAvatar's
 * BertVITS2SimpleInferImpl but simplified: single catgirl voice, models loaded
 * from a pushed directory on external storage (NOT bundled in the APK).
 *
 * Model layout (adb push to this dir — see scripts/push_models.sh):
 *   <modelDir>/te.mnn ve.mnn dp.mnn vocoder.mnn voices/catgirl_style.json
 * Default modelDir = /sdcard/supertonic-neko/fp16
 *
 * Frontend (pinyin_dict.json, unicode_indexer.json) IS bundled in APK assets
 * because it's small (~0.7MB total) and code-coupled.
 *
 * Usage:
 *   val tts = SuperTonicInfer(context)
 *   tts.init("/sdcard/supertonic-neko/fp16")
 *   val (pcm, rtfMs) = tts.synth("你好，我是猫娘")   // pcm: FloatArray @44.1kHz
 *   // play pcm via AudioTrack (PCM_FLOAT, 44100, mono)
 *   tts.release()
 */
class SuperTonicInfer(private val context: Context) {

    private val jni: ISuperTonicJNI = SuperTonicJNI()
    private val frontend = ZhFrontend(context)
    // 单一 native loader，不能并发调 synth：打断换新一轮时旧 synth 可能还在 native 里跑，
    // 加锁串行化，两个 jni.synth 永不重叠，否则抢同一 session → SIGSEGV + 破碎噪声。
    private val synthLock = Any()

    private lateinit var styleTtl: FloatArray  // [50*256]
    private lateinit var styleDp: FloatArray    // [8*16]
    private var initialized = false

    /**
     * @param modelDir absolute path to the pushed fp16 model dir.
     * @return true on success.
     */
    fun init(
        modelDir: String = "/sdcard/supertonic-neko/fp16",
        numThreads: Int = 4,
        voiceId: String = "catgirl_style",
    ): Boolean {
        val dir = File(modelDir)
        val dp = File(dir, "dp.mnn")
        val te = File(dir, "te.mnn")
        val ve = File(dir, "ve.mnn")
        val voc = File(dir, "vocoder.mnn")
        require(voiceId.matches(Regex("[A-Za-z0-9_-]+"))) { "invalid voiceId" }
        val style = File(File(dir, "voices"), "$voiceId.json")
        for (f in listOf(dp, te, ve, voc, style)) {
            if (!f.exists()) {
                android.util.Log.e("SuperTonicInfer", "missing model file: ${f.absolutePath}")
                return false
            }
        }
        // load style vectors
        val s = JSONObject(style.readText())
        styleTtl = flatten(s.getJSONObject("style_ttl").getJSONArray("data"))
        styleDp = flatten(s.getJSONObject("style_dp").getJSONArray("data"))

        // Model load/release changes MNN Express global executor state.  It must not overlap
        // sherpa recognizer or local LLM construction, even though actual synthesis has its
        // own per-instance serialization below.
        MnnGlobalLock.lock.lock()
        try {
            jni.initLoader(numThreads)
            initialized = jni.setModelPath(dp.absolutePath, te.absolutePath, ve.absolutePath, voc.absolutePath)
            if (!initialized) {
                jni.destroyLoader()
                android.util.Log.e("SuperTonicInfer", "native model load failed")
            }
        } finally {
            MnnGlobalLock.lock.unlock()
        }
        return initialized
    }

    /**
     * Synthesize one utterance.
     * @param speed duration divisor. 1.0 = model's native pace (matches our v5
     *              pipeline, which has no speed factor). >1 faster, <1 slower.
     * @return Pair(pcm, inferMillis). pcm is null on failure.
     */
    fun synth(text: String, seed: Int = 42, speed: Float = 1.0f, totalSteps: Int = 8): Pair<FloatArray?, Long> {
        if (!initialized) return Pair(null, 0L)
        val r = frontend.tokenize(text)
        val t0 = System.currentTimeMillis()
        android.util.Log.i(
            "SuperTonicInfer",
            "synth text=${text.take(24)} tokens=${r.textIds.size} " +
                "pinyin=${r.pinyinIds.count { it != 0 }}"
        )
        val pcm = synchronized(synthLock) {
            if (!initialized) return Pair(null, 0L)
            MnnGlobalLock.lock.lock()
            try {
                jni.synth(
                    r.textIds, r.pinyinIds, r.toneIds, r.prosodyIds,
                    r.textMask, styleTtl, styleDp, seed, speed, totalSteps.coerceIn(1, 8),
                )
            } finally {
                MnnGlobalLock.lock.unlock()
            }
        }
        val dt = System.currentTimeMillis() - t0
        return Pair(pcm, dt)
    }

    /** Safe from the UI/capture thread: it only flips a native atomic flag. */
    fun cancelSynthesis() {
        if (initialized) runCatching { jni.cancelSynthesis() }
    }

    /** RTF = inferMillis / (pcm.size / 44100 * 1000). */
    fun rtf(pcm: FloatArray, inferMillis: Long): Float {
        val wavSec = pcm.size / 44100.0
        return (inferMillis / 1000.0 / wavSec).toFloat()
    }

    fun release() {
        MnnGlobalLock.lock.lock()
        try {
            if (initialized) {
                jni.destroyLoader()
                initialized = false
            }
        } finally {
            MnnGlobalLock.lock.unlock()
        }
    }

    fun setPerformanceLogging(enabled: Boolean) {
        jni.setPerfLogging(enabled)
    }

    // Flatten a nested JSON array (any depth) of numbers into a FloatArray, row-major.
    private fun flatten(arr: org.json.JSONArray): FloatArray {
        val out = ArrayList<Float>()
        fun rec(a: org.json.JSONArray) {
            for (i in 0 until a.length()) {
                val v = a.get(i)
                if (v is org.json.JSONArray) rec(v) else out.add((v as Number).toFloat())
            }
        }
        rec(arr)
        return out.toFloatArray()
    }
}

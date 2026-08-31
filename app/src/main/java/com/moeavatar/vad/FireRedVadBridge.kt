package com.moeavatar.vad

import android.content.Context
import android.util.Log
import com.moeavatar.MnnGlobalLock
import java.io.File

/** Dynamic [1,T,80] FireRed graph wrapper. PCM frontend/postprocessing stay explicit. */
class FireRedVadBridge private constructor(private var handle: Long) : AutoCloseable {
    fun infer(features: FloatArray, frames: Int): FloatArray? {
        require(features.size == frames * FEATURE_DIM)
        return MnnGlobalLock.lock.run {
            lock()
            try { inferNative(handle, features, frames) } finally { unlock() }
        }
    }
    fun inferPcm(pcm: ShortArray): FloatArray? = MnnGlobalLock.lock.run {
        lock(); try { inferPcmNative(handle, pcm) } finally { unlock() }
    }

    override fun close() = MnnGlobalLock.lock.run {
        lock()
        try {
            if (handle != 0L) {
                releaseNative(handle)
                handle = 0L
            }
        } finally { unlock() }
    }

    private external fun inferNative(handle: Long, features: FloatArray, frames: Int): FloatArray?
    private external fun releaseNative(handle: Long)
    private external fun inferPcmNative(handle: Long, pcm: ShortArray): FloatArray?

    companion object {
        const val FEATURE_DIM = 80
        private const val TAG = "FireRedVad"
        private const val ASSET = "vad/fireredvad_vad_fp16.mnn"
        private const val CMVN_ASSET = "vad/fireredvad_cmvn.bin"
        private val loaded = try { System.loadLibrary("MNN"); System.loadLibrary("firered_vad"); true }
        catch (t: Throwable) { Log.w(TAG, "native unavailable", t); false }

        fun open(context: Context): FireRedVadBridge? {
            if (!loaded) return null
            val out = File(context.filesDir, "vad/fireredvad_vad_fp16.mnn")
            if (!out.exists()) {
                out.parentFile?.mkdirs()
                runCatching { context.assets.open(ASSET).use { input -> out.outputStream().use(input::copyTo) } }
                    .onFailure { Log.w(TAG, "bundled FireRed asset missing", it); return null }
            }
            val cmvn = File(context.filesDir, "vad/fireredvad_cmvn.bin")
            if (!cmvn.exists()) runCatching { context.assets.open(CMVN_ASSET).use { input -> cmvn.outputStream().use(input::copyTo) } }
                .onFailure { Log.w(TAG, "bundled CMVN asset missing", it); return null }
            val bridge = FireRedVadBridge(0)
            bridge.handle = bridge.initNative(out.absolutePath, cmvn.absolutePath)
            return bridge.takeIf { it.handle != 0L }
        }
    }

    private external fun initNative(path: String, cmvnPath: String): Long
}

package com.moeavatar.llm

import android.util.Log

object LocalLlmBridge {
    private const val TAG = "LocalLlmBridge"

    /**
     * 是否成功加载了 native 库。
     *
     * jniLibs/ 下现已替换为带 MNN_BUILD_LLM=ON + MNN_SEP_BUILD=ON 的三件套：
     *   libMNN.so  +  libMNN_Express.so  +  libllm.so
     * libmoeavatar_llm.so 只对其中的 createLLM / Llm::response 做 JNI 包装。
     *
     * 加载顺序按依赖关系：MNN → MNN_Express → llm → moeavatar_llm。
     * 任一环节失败时 LocalLlmBackend.prepare() 返回 false，UI 自动 fallback 到 OpenAI。
     */
    @JvmStatic
    val nativeAvailable: Boolean = try {
        System.loadLibrary("MNN")
        System.loadLibrary("MNN_Express")
        System.loadLibrary("llm")
        System.loadLibrary("moeavatar_llm")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "libmoeavatar_llm.so not loaded: ${t.message}")
        false
    }

    interface TokenListener {
        /** return true to request stop */
        fun onToken(token: String): Boolean
    }

    /** samplingOverrides 会在 MNN LLM load 前合并，确保 sampler 按新参数创建。 */
    external fun initNative(configPath: String, samplingOverrides: String): Long
    external fun submitNative(ptr: Long, prompt: String, listener: TokenListener): String
    external fun submitChatNative(
        ptr: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxNewTokens: Int,
        listener: TokenListener,
    ): String
    external fun resetNative(ptr: Long)
    external fun stopNative(ptr: Long)
    external fun releaseNative(ptr: Long)
}

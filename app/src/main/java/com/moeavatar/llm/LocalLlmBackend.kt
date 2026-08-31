package com.moeavatar.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 端侧 MNN-LLM 后端。给定一个 config.json 路径，load 后即可 chat。
 *
 * 每轮都把 system prompt 和完整历史交给 MNN ChatMessages，并先清空 native KV cache，
 * 从而与线上无状态请求保持一致。
 *
 * @property configPath 形如 /sdcard/.../<model_dir>/config.json
 */
class LocalLlmBackend(
    private val configPath: String,
    private val systemPromptProvider: () -> String,
    private val temperature: Float,
    private val topP: Float,
    private val maxOutputTokens: Int,
) : LlmBackend {

    override val displayName: String = "本地 · ${File(configPath).parentFile?.name ?: "?"}"

    private val nativePtr = AtomicLong(0L)
    @Volatile private var prepared = false

    // stopNative 跨线程并发会 SIGSEGV：取消 chatJob 时 callbackFlow.awaitClose 会调一次，
    // onMicPressed 又主动调 backend.stop() 一次 —— 两次同时进 native 抢内部状态 → 闪退黑屏。
    // 锁只串行化 stop/reset/release；submitNative 本身是长阻塞调用，不能进锁，否则 stop 等不到它。
    // stopNative 设计上就该能跟 submitNative 并发执行（用来打断它）—— 这是 native 层 OK 的，
    // 同 stopNative 之间互锁就够。
    private val nativeLock = Any()

    override val ready: Boolean
        get() = prepared && nativePtr.get() != 0L

    override suspend fun prepare(): Boolean {
        if (ready) return true
        if (!LocalLlmBridge.nativeAvailable) {
            Log.w(TAG, "native lib not available, local backend disabled")
            return false
        }
        val ptr = try {
            // set_config 必须发生在 Llm::load 前，load 会按 config 创建 Sampler。
            LocalLlmBridge.initNative(configPath, samplingOverridesJson())
        } catch (t: Throwable) {
            Log.e(TAG, "initNative throw", t)
            0L
        }
        if (ptr == 0L) {
            Log.e(TAG, "initNative returned 0 for $configPath")
            return false
        }
        nativePtr.set(ptr)
        prepared = true
        Log.i(TAG, "prepared sampling temperature=$temperature topP=$topP maxNewTokens=$maxOutputTokens")
        return true
    }

    override fun chat(history: List<ChatTurn>): Flow<String> = callbackFlow<String> {
        val ptr = nativePtr.get()
        if (ptr == 0L) {
            close(IllegalStateException("local llm not prepared"))
            return@callbackFlow
        }
        val messages = buildMessages(history)
        Log.i(TAG, "chat messages=${messages.size} systemChars=${messages.firstOrNull()?.second?.length ?: 0} " +
            "lastUserChars=${messages.lastOrNull { it.first == "user" }?.second?.length ?: 0} " +
            "temperature=$temperature topP=$topP maxNewTokens=$maxOutputTokens")

        val listener = object : LocalLlmBridge.TokenListener {
            override fun onToken(token: String): Boolean {
                if (token == "<eop>") return true
                // trySend 失败（下游已取消）时返回 stop=true，让 native 端尽快收尾
                val ok = trySend(token).isSuccess
                if (!ok) return true
                return false
            }
        }

        // submitNative 是阻塞的——必须放到 IO，但 callbackFlow 本身就在协程里，
        // 我们靠 flowOn(Dispatchers.IO) 切线程。
        try {
            synchronized(nativeLock) { LocalLlmBridge.resetNative(ptr) }
            LocalLlmBridge.submitChatNative(
                ptr,
                messages.map { it.first }.toTypedArray(),
                messages.map { it.second }.toTypedArray(),
                maxOutputTokens,
                listener,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "submitChatNative throw", t)
            close(t)
            return@callbackFlow
        }

        close()  // 模型说完，正常结束 Flow
        awaitClose {
            // 上游取消时通知 native 停止 —— 必须跟外部 stop()/release() 串行
            synchronized(nativeLock) {
                runCatching { LocalLlmBridge.stopNative(ptr) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        val ptr = nativePtr.get()
        if (ptr != 0L) synchronized(nativeLock) { LocalLlmBridge.stopNative(ptr) }
    }

    override fun resetSession() {
        val ptr = nativePtr.get()
        if (ptr != 0L) synchronized(nativeLock) { LocalLlmBridge.resetNative(ptr) }
    }

    override fun release() {
        val ptr = nativePtr.getAndSet(0L)
        if (ptr != 0L) synchronized(nativeLock) {
            try { LocalLlmBridge.stopNative(ptr) } catch (_: Throwable) {}
            try { LocalLlmBridge.releaseNative(ptr) } catch (_: Throwable) {}
        }
        prepared = false
    }

    private fun buildMessages(history: List<ChatTurn>): List<Pair<String, String>> = buildList {
        add("system" to systemPromptProvider())
        history.forEach { turn ->
            val role = when (turn.role) {
                ChatTurn.Role.USER -> "user"
                ChatTurn.Role.ASSISTANT -> "assistant"
                ChatTurn.Role.SYSTEM -> "system"
            }
            add(role to turn.content)
        }
    }

    private fun samplingOverridesJson(): String = """{"temperature":${formatFloat(temperature)},"top_p":${formatFloat(topP)}}"""

    private fun formatFloat(value: Float): String = "%.3f".format(java.util.Locale.US, value)

    companion object {
        private const val TAG = "LocalLlmBackend"
    }
}

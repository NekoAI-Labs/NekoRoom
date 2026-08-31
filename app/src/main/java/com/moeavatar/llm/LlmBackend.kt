package com.moeavatar.llm

import kotlinx.coroutines.flow.Flow

/**
 * LLM 对话后端的统一抽象。
 *
 * 实现：
 *   - LocalLlmBackend  → 端侧 MNN（包 LocalLlmBridge / libmoeavatar_llm.so）
 *   - OpenAiLlmBackend → HTTP 调 OpenAI 兼容 chat/completions（SSE 流式）
 *
 * UI 只跟这个接口对话。
 */
interface LlmBackend {

    /** 标识，用来在 UI 上展示当前后端 */
    val displayName: String

    /** 后端是否已就绪可以对话（例如本地模型已 load 完） */
    val ready: Boolean

    /**
     * 准备就绪。本地后端需要 load 模型；HTTP 后端通常是 no-op。
     * 可重复调用——已经就绪时立即返回 true。
     */
    suspend fun prepare(): Boolean

    /**
     * 流式对话。返回的 Flow 每发一段就是一个 token chunk（不是切好的句子，
     * 切句由调用方做）。Flow 自然结束 = 模型说完一句话。
     *
     * 取消 collect 即视为 stop。
     */
    fun chat(history: List<ChatTurn>): Flow<String>

    /** 立即停止当前生成（如果有）。 */
    fun stop()

    /** 重置对话状态（清 KVCache、重启 SSE 连接等）。 */
    fun resetSession()

    /** 释放资源（Activity onDestroy 调）。 */
    fun release()
}

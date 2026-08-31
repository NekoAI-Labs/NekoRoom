package com.moeavatar.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容后端：HTTP POST /v1/chat/completions，stream=true，自己解 SSE。
 *
 * baseUrl 可填官方（https://api.openai.com）或自建网关，结尾有没有 /v1 都行——我们补齐。
 * systemPromptProvider 每次 chat() 调一次，让能力段（v0.5 action 表情清单）能按当前
 * Live2D 状态**动态注入**，不用重建 backend。返回 null/空 = 不发 system message。
 *
 * v0.6.2：思考模式关闭走 [preset.reasoning] 驱动的 modifier 表（见 ProviderPreset.kt），
 * 老 `shouldDisableThinking()` 字符串匹配已删除。用户还可以填 [extraBodyJson] 浅合并覆盖。
 */
class OpenAiLlmBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val preset: ProviderPreset,
    private val temperature: Float = 0.7f,
    private val topP: Float = 0.9f,
    private val maxOutputTokens: Int = 80,
    private val enableThinking: Boolean = false,
    private val systemPromptProvider: () -> String? = { null },
) : LlmBackend {

    override val displayName: String = "API · $model"

    @Volatile private var prepared = false
    private val currentCall = AtomicReference<Call?>(null)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // SSE 不超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // testConnection 用短超时，避免用户点了按钮转半天没反应。
    // readTimeout 30s：给 SiliconFlow / vLLM 冷启动一点余量（实测 Qwen 首次冷启可能 15–25s）。
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override val ready: Boolean
        get() = prepared

    override suspend fun prepare(): Boolean {
        prepared = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
        Log.i(TAG, "prepare base=${redactBaseUrl(baseUrl)} model=$model preset=${preset.id} " +
                "reasoning=${preset.reasoning} thinking=$enableThinking temp=$temperature topP=$topP max=$maxOutputTokens " +
                "key.present=${apiKey.isNotBlank()}")
        return prepared
    }

    override fun chat(history: List<ChatTurn>): Flow<String> = callbackFlow<String> {
        if (!ready) {
            close(IllegalStateException("openai backend not configured"))
            return@callbackFlow
        }

        val url = buildChatUrl(baseUrl)
        // Keep the production request explicit and reproducible across providers.
        // UI still stops rendering early when the user interrupts a reply.
        val bodyJson = buildBody(history, stream = true, maxTokens = maxOutputTokens)
        val body = bodyJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(req)
        currentCall.set(call)
        val thinkFilter = ThinkTagFilter()

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string()
                    val outcome = ApiErrorMapper.fromHttpError(resp.code, raw)
                    Log.e(TAG, "chat http ${resp.code}: ${outcome.uiSummary()}")
                    close(ApiException(outcome))
                    return@callbackFlow
                }
                val source = resp.body?.source() ?: run {
                    close(ApiException(ApiOutcome.ServerError(-1, "empty response body")))
                    return@callbackFlow
                }
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val token = parseToken(payload) ?: continue
                    val visible = thinkFilter.accept(token)
                    if (visible.isNullOrEmpty()) continue
                    val ok = trySend(visible).isSuccess
                    if (!ok) break
                }
            }
            close()
        } catch (t: Throwable) {
            if (call.isCanceled()) {
                close()
            } else if (t is ApiException) {
                close(t)
            } else {
                val outcome = ApiErrorMapper.fromException(t)
                Log.e(TAG, "openai stream error: ${outcome.uiSummary()}", t)
                close(ApiException(outcome))
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }

        awaitClose {
            currentCall.get()?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 非流式 ping：发一条最小 payload 验证 base/key/model 三元组能不能打通。
     * 返回 [ApiOutcome] —— UI 拿去按类型渲染。不会抛异常。
     */
    suspend fun testConnection(): ApiOutcome = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return@withContext ApiOutcome.BadParam("base / key / model 至少一个为空")
        }
        val url = buildChatUrl(baseUrl)
        val probeHistory = listOf(ChatTurn(ChatTurn.Role.USER, "ping"))
        val bodyJson = buildBody(probeHistory, stream = false, maxTokens = 8)
        val body = bodyJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        val t0 = System.currentTimeMillis()
        try {
            probeClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string()
                if (!resp.isSuccessful) {
                    val outcome = ApiErrorMapper.fromHttpError(resp.code, bodyStr)
                    Log.w(TAG, "probe http ${resp.code}: ${outcome.uiSummary()}")
                    return@withContext outcome
                }
                val latency = System.currentTimeMillis() - t0
                val echo = runCatching {
                    JSONObject(bodyStr.orEmpty())
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                }.getOrNull()
                Log.i(TAG, "probe ok latency=${latency}ms echo=${echo?.take(40)}")
                ApiOutcome.Ok(latency, echo)
            }
        } catch (t: Throwable) {
            val outcome = ApiErrorMapper.fromException(t)
            Log.w(TAG, "probe exception: ${outcome.uiSummary()}", t)
            outcome
        }
    }

    override fun stop() {
        currentCall.getAndSet(null)?.cancel()
    }

    override fun resetSession() {
        // 无状态：每轮 chat 都把 history 整体发过去
        stop()
    }

    override fun release() {
        stop()
        prepared = false
    }

    private fun buildBody(history: List<ChatTurn>, stream: Boolean, maxTokens: Int?): JSONObject {
        val messages = JSONArray()
        val sp = systemPromptProvider()
        if (!sp.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", sp))
        }
        for (turn in history) {
            val role = when (turn.role) {
                ChatTurn.Role.USER -> "user"
                ChatTurn.Role.ASSISTANT -> "assistant"
                ChatTurn.Role.SYSTEM -> "system"
            }
            messages.put(JSONObject().put("role", role).put("content", turn.content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("stream", stream)
            .put("temperature", temperature.toDouble())
            .put("top_p", topP.toDouble())
            .put("messages", messages)
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens)
        }
        applyReasoning(body, preset.reasoning, enableThinking)
        return body
    }

    private fun parseToken(payload: String): String? {
        return try {
            val obj = JSONObject(payload)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
            if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                return null
            }
            if (!delta.has("content") || delta.isNull("content")) return null
            val content = delta.opt("content") as? String ?: return null
            if (content.isEmpty() || content == "null") null else content
        } catch (t: Throwable) {
            Log.w(TAG, "bad SSE payload: $payload", t)
            null
        }
    }

    /** 过滤 <think>…</think> 思考内容，只放行可见文本。 */
    private class ThinkTagFilter {
        private var inThink = false

        fun accept(token: String): String? {
            var rest = token
            val out = StringBuilder()
            while (rest.isNotEmpty()) {
                if (inThink) {
                    val end = rest.indexOf("</think>", ignoreCase = true)
                    if (end < 0) return out.takeIf { it.isNotEmpty() }?.toString()
                    rest = rest.substring(end + "</think>".length)
                    inThink = false
                    continue
                }
                val start = rest.indexOf("<think>", ignoreCase = true)
                val strayEnd = rest.indexOf("</think>", ignoreCase = true)
                if (strayEnd >= 0 && (start < 0 || strayEnd < start)) {
                    rest = rest.substring(strayEnd + "</think>".length)
                    inThink = false
                    continue
                }
                if (start < 0) {
                    out.append(rest)
                    break
                }
                out.append(rest.substring(0, start))
                rest = rest.substring(start + "<think>".length)
                inThink = true
            }
            return out.toString().takeIf { it.isNotEmpty() }
        }
    }

    private fun redactBaseUrl(url: String): String =
        url.replace(Regex("(?i)(api[_-]?key=)[^&]+"), "\$1***")

    private fun buildChatUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    /** 由 chat() 抛出，携带分类结果；UI 上层 catch 到就能直接展示 [ApiOutcome.uiSummary]。 */
    class ApiException(val outcome: ApiOutcome) : RuntimeException(outcome.uiSummary())

    companion object {
        private const val TAG = "OpenAiLlmBackend"
    }
}

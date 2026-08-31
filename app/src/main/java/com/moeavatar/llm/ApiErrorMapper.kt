package com.moeavatar.llm

import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * v0.6.2 · 在线 API 错误分类。
 *
 * 目的：不再把 HTTP 500 字 body（尤其是 Cloudflare / Nginx 拦截 HTML）一路糊给用户，
 * 也不让 UI 拼字符串猜错误类型。把服务端 code + body 归到有限几个语义类，UI 再按类映射文案。
 */
sealed class ApiOutcome {
    data class Ok(val latencyMs: Long, val echo: String?) : ApiOutcome()

    /** 401 / 403：Key 无效、过期、权限不足。 */
    data class AuthFail(val msg: String) : ApiOutcome()

    /** 429：限流。可能带 Retry-After 提示。 */
    data class RateLimited(val retryHint: String?) : ApiOutcome()

    /** 404 / 服务端 error.code=model_not_found：模型名错。 */
    data class ModelNotFound(val msg: String) : ApiOutcome()

    /** 400：payload 非法（如 model 未指定、messages 为空、参数越界）。 */
    data class BadParam(val msg: String) : ApiOutcome()

    /** body 是 HTML 页（Cloudflare / 反向代理拦截 / 网关落地页）。 */
    data class HtmlBlocked(val hint: String) : ApiOutcome()

    /** 底层网络失败：超时、DNS 解析失败、连接被拒。 */
    data class NetworkError(val msg: String) : ApiOutcome()

    /** 其它 5xx。 */
    data class ServerError(val code: Int, val msg: String) : ApiOutcome()
}

object ApiErrorMapper {

    private const val MAX_MSG = 200

    fun fromHttpError(code: Int, rawBody: String?): ApiOutcome {
        val body = rawBody.orEmpty()
        if (looksLikeHtml(body)) {
            return ApiOutcome.HtmlBlocked("服务器返回 HTML 页 · 检查 base URL 是否正确")
        }
        val msg = extractMessage(body)
        return when (code) {
            401, 403 -> ApiOutcome.AuthFail(msg.ifBlank { "认证失败 · 检查 API Key" })
            404      -> ApiOutcome.ModelNotFound(msg.ifBlank { "未找到模型 · 检查模型名" })
            400      -> {
                if (msg.contains("model", true) && msg.contains("not", true)) {
                    ApiOutcome.ModelNotFound(msg)
                } else {
                    ApiOutcome.BadParam(msg.ifBlank { "请求参数非法" })
                }
            }
            429      -> ApiOutcome.RateLimited(msg.ifBlank { "限流 · 稍后再试" })
            in 500..599 -> ApiOutcome.ServerError(code, msg.ifBlank { "服务器错误" })
            else     -> ApiOutcome.ServerError(code, msg.ifBlank { "HTTP $code" })
        }
    }

    fun fromException(t: Throwable): ApiOutcome {
        val cls = t::class.java.simpleName
        val raw = t.message.orEmpty()
        return when (t) {
            is SocketTimeoutException -> ApiOutcome.NetworkError("请求超时 · 网络不畅")
            is UnknownHostException   -> ApiOutcome.NetworkError("DNS 解析失败 · 检查 base URL")
            is IOException            -> ApiOutcome.NetworkError(short("网络错误 · ", raw))
            else -> ApiOutcome.ServerError(-1, short("$cls · ", raw))
        }
    }

    private fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(64).lowercase()
        return head.startsWith("<!doc") || head.startsWith("<html") || head.startsWith("<head")
    }

    /**
     * 从 JSON 错误体中抽 `error.message` 或 `message`；否则回退到原文头部。
     * 官方语义："error":{"message":"...", "code":"invalid_api_key"}（OpenAI 家族）
     *          或顶层 "message":"..."（部分兼容网关如 SiliconFlow）。
     */
    private fun extractMessage(body: String): String {
        if (body.isBlank()) return ""
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        if (parsed != null) {
            val err = parsed.optJSONObject("error")
            if (err != null) {
                val m = err.optString("message").ifBlank { err.optString("code") }
                if (m.isNotBlank()) return truncate(m)
            }
            val topLevel = parsed.optString("message")
            if (topLevel.isNotBlank()) return truncate(topLevel)
        }
        return truncate(body)
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_MSG) s.trim() else s.take(MAX_MSG).trim() + "…"

    private fun short(prefix: String, s: String): String =
        prefix + (if (s.length <= MAX_MSG) s else s.take(MAX_MSG) + "…")
}

/** 给 UI 用的短语（气泡、Snackbar、按钮反馈都走这个）。 */
fun ApiOutcome.uiSummary(): String = when (this) {
    is ApiOutcome.Ok            -> "✓ ${latencyMs} ms"
    is ApiOutcome.AuthFail      -> "认证失败 · $msg"
    is ApiOutcome.RateLimited   -> "限流 · ${retryHint ?: "稍后再试"}"
    is ApiOutcome.ModelNotFound -> "模型不存在 · $msg"
    is ApiOutcome.BadParam      -> "参数错误 · $msg"
    is ApiOutcome.HtmlBlocked   -> hint
    is ApiOutcome.NetworkError  -> msg
    is ApiOutcome.ServerError   -> "服务器错误 $code · $msg"
}

package com.moeavatar.llm

import org.json.JSONObject

/**
 * v0.6.2 · 轻量 OpenAI-Compatible Client Provider 注册表。
 *
 * 加新 Provider = 往 [ProviderRegistry.PRESETS] 加一行；加新的思考模式关闭协议
 * = 往 [ReasoningStyle] 加一枚 + [REASONING_MODIFIERS] 加一条 —— 不改调用点。
 *
 * 研究结论（见 docs/DESIGN_online_llm_v062.md §2）：
 *  - DeepSeek REST：顶层 "thinking":{"type":"disabled"}
 *  - DashScope/SiliconFlow：顶层 "enable_thinking":false
 *  - vLLM 部署的 Qwen：顶层 "chat_template_kwargs":{"enable_thinking":false}
 *  QWEN modifier 同时下发后两个，覆盖 DashScope/SiliconFlow/vLLM 三种服务端。
 *  自定义 preset 默认走 QWEN，OpenAI 官方会忽略未知字段，无副作用。
 */
enum class ReasoningStyle { NONE, DEEPSEEK, QWEN }

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val reasoning: ReasoningStyle,
)

object ProviderRegistry {
    val PRESETS: List<ProviderPreset> = listOf(
        ProviderPreset("deepseek",    "DeepSeek",         "https://api.deepseek.com/v1",    "deepseek-v4-flash", ReasoningStyle.DEEPSEEK),
        ProviderPreset("siliconflow", "SiliconFlow",      "https://api.siliconflow.cn/v1",  "Qwen/Qwen3-8B",     ReasoningStyle.QWEN),
        // MIMO 使用与 DeepSeek 相同的 thinking.type=enabled/disabled 请求格式。
        ProviderPreset("mimo",        "MIMO",             "https://api.xiaomimimo.com/v1",   "mimo-v2.5-pro",      ReasoningStyle.DEEPSEEK),
        ProviderPreset("agnes",       "Agnes 2.0",        "https://apihub.agnes-ai.com/v1", "agnes-2.0-flash",   ReasoningStyle.QWEN),
        ProviderPreset("custom",      "自定义（OpenAI 兼容）", "",                                "",                  ReasoningStyle.QWEN),
    )

    val DEFAULT: ProviderPreset = PRESETS.first { it.id == "deepseek" }

    fun byId(id: String?): ProviderPreset =
        PRESETS.firstOrNull { it.id == id } ?: DEFAULT

    fun indexOf(id: String?): Int =
        PRESETS.indexOfFirst { it.id == id }.let { if (it < 0) 0 else it }

    /** 老用户迁移：从已存 baseUrl 猜 preset id。未命中官方域名 → custom。 */
    fun inferByBaseUrl(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains("deepseek.com")   -> "deepseek"
            u.contains("siliconflow.cn") -> "siliconflow"
            u.contains("xiaomimimo.com") -> "mimo"
            u.contains("agnes-ai.com")   -> "agnes"
            else -> "custom"
        }
    }
}

private val REASONING_MODIFIERS: Map<ReasoningStyle, JSONObject.() -> Unit> = mapOf(
    ReasoningStyle.NONE     to { /* no-op */ },
    ReasoningStyle.DEEPSEEK to { put("thinking", JSONObject().put("type", "disabled")) },
    ReasoningStyle.QWEN     to {
        put("enable_thinking", false)
        put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
    },
)

/**
 * 按 preset.reasoning 往 body 里注入关闭思考模式的字段。
 * 若用户显式开启思考（enableThinking=true），无论 preset 是什么都不注入。
 */
fun applyReasoning(body: JSONObject, style: ReasoningStyle, enableThinking: Boolean) {
    if (enableThinking) return
    REASONING_MODIFIERS[style]?.invoke(body)
}

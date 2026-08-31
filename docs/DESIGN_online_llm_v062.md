# v0.6.2 · Online LLM Refactor（OpenAI-compatible Client）

> 分支 `fix/online-llm-thinking`（从 `feature/voice-interaction-v06` 切出）。
> 目的：修在线 API 使用中的三类痛点 —— 无法验证配置、Qwen 类模型思考模式拖慢首字、错误信息对用户不友好。

## 1. 用户反馈 & 现状

**User bug report（v0.6.1 内测）：**
1. 用 DeepSeek 报 `HTTP 401 authentication`（key 无效 / key 弄错）—— 用户不知道到底是 base、model 还是 key 问题。
2. 用 SiliconFlow 的 `Qwen/Qwen3-4B` 首字延迟 5–15s；日志里能看到 `reasoning_content` 大段吐 token。
3. 有时候一坨 HTML 页糊在错误弹窗里（Cloudflare 429 拦截页 500 字）。

**代码现状（`OpenAiLlmBackend.kt`）：**
- `shouldDisableThinking()` 用 baseUrl / model 字符串匹配 `deepseek/qwen/qwq`；命中就同时发 `thinking:{type:disabled}` 和 `enable_thinking:false` 两个字段在顶层。
- 错误路径直接 `resp.body.string().take(500)` 抛出 —— HTML 内容随异常信息一路冒到 Snackbar。
- 无连通性测试；用户唯一"验证方式"是发一条消息看有没有响应。

## 2. 研究：各家关闭思考模式的接口约定

通过 `curl` 直接读官方文档（`WebSearch/WebFetch` 多次被域名拦截，改用 curl 读 raw markdown 源）。

| Provider | HTTP body 字段（wire-flat） | 备注 |
|---|---|---|
| **DeepSeek** REST | `"thinking": {"type": "disabled"\|"enabled"}` 顶层 | 官方 docs 明确；`deepseek-v4-flash` / `deepseek-v4-reasoner`；`deepseek-chat/deepseek-reasoner` 2026-07-24 15:59 UTC 弃用 |
| **DashScope (Qwen 官方)** | `"enable_thinking": true\|false` 顶层 | Python SDK 用 `extra_body={"enable_thinking": True}`，SDK 展平到顶层 |
| **vLLM 部署的 Qwen** | `"chat_template_kwargs": {"enable_thinking": false}` 顶层 | 模板层参数；Qwen 官方 readthedocs 明确写这是 vLLM 部署面参数 |
| **SiliconFlow** | 同 vLLM，接受 `chat_template_kwargs` 也接受顶层 `enable_thinking`（宽松） | `thinking_budget` 是另一维度不涉及本次 |
| **Agnes 2.0** | 同时支持 `chat_template_kwargs` 和 `thinking`（宽松） | 官方 docs 页确认 |
| **OpenAI** | 未来 o 系列有 `reasoning_effort`，本次**不做**（默认 preset 里 style=NONE） | |

**关键结论**：`extra_body` 是 OpenAI Python SDK 的**客户端约定**，SDK 会把它展平到 HTTP body 顶层。App 里我们直接手写 HTTP，所以要把「用户可能想加的字段」直接放顶层就够了。`chat_template_kwargs` 不应该作为**用户产品概念**暴露（这是 vLLM 部署层的名字），但**兼容层默默同时发**成本很低、覆盖面更广。

## 3. 设计

### 3.1 定位：轻量 OpenAI-Compatible Client

参照 Open WebUI / Cherry Studio / LobeChat：预置常见 Provider（快捷）+ 自定义（兼容第三方兼容网关如 Ollama / vLLM / One-API）。

### 3.2 数据结构（新增 `llm/ProviderPreset.kt`）

```kotlin
enum class ReasoningStyle { NONE, DEEPSEEK, QWEN }

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,       // 统一带 /v1
    val defaultModel: String,
    val reasoning: ReasoningStyle,
)

object ProviderRegistry {
    val PRESETS = listOf(
        ProviderPreset("deepseek",    "DeepSeek",         "https://api.deepseek.com/v1",    "deepseek-v4-flash", DEEPSEEK),
        ProviderPreset("siliconflow", "SiliconFlow",      "https://api.siliconflow.cn/v1",  "Qwen/Qwen3-4B",     QWEN),
        ProviderPreset("agnes",       "Agnes 2.0",        "https://apihub.agnes-ai.com/v1", "agnes-2.0-flash",   QWEN),
        ProviderPreset("custom",      "自定义（OpenAI 兼容）", "",                                "",                  QWEN),
    )
}
```

**Modifier 表（关键：无 if-else 堆叠）：**

```kotlin
private val REASONING_MODIFIERS: Map<ReasoningStyle, JSONObject.() -> Unit> = mapOf(
    NONE     to { /* no-op */ },
    DEEPSEEK to { put("thinking", JSONObject().put("type", "disabled")) },
    QWEN     to {
        // Alibaba/SiliconFlow 顶层 + vLLM chat_template_kwargs 双发，覆盖两种服务端解析
        put("enable_thinking", false)
        put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
    },
)
```

未来添加 Provider = 在 `PRESETS` 加一行；添加新的 reasoning 语义 = 在 enum 加值 + modifier 表加一条。

### 3.3 用户可控参数：Switch + SeekBar，不让用户写 JSON

**思考模式开关（默认关）** —— `enableThinking:Boolean`。为 false 时按 preset.reasoning 注入关闭
字段；为 true 时**跳过注入**（用户主动放弃低延迟）。custom preset 也用 QWEN 语义，OpenAI 官方兼容
服务会忽略未知字段，无副作用；vLLM/Ollama 部署的 Qwen 能被 `chat_template_kwargs` 正确关掉。

**温度滑块** —— `SeekBar max=200` 映射 `temperature ∈ [0.00, 2.00]`，默认 0.70。每次请求
显式写入 `body.temperature`，不再靠服务端默认。

### 3.4 错误分类（新增 `llm/ApiErrorMapper.kt`）

```kotlin
sealed class ApiOutcome {
    data class Ok(val latencyMs: Long, val echo: String?) : ApiOutcome()
    data class AuthFail(val msg: String) : ApiOutcome()           // 401 / 403
    data class RateLimited(val retryHint: String?) : ApiOutcome() // 429
    data class ModelNotFound(val msg: String) : ApiOutcome()      // 404 or code=model_not_found
    data class BadParam(val msg: String) : ApiOutcome()           // 400
    data class HtmlBlocked(val hint: String) : ApiOutcome()       // body 以 <html/<!DOC 开头
    data class NetworkError(val msg: String) : ApiOutcome()       // IO/UnknownHost/Timeout
    data class ServerError(val code: Int, val msg: String) : ApiOutcome()
}
```

`fromHttpError(code, body)` / `fromException(t)`：
- HTML 页面（Cloudflare 拦截）单独归类，不再原样吐出。
- JSON error 提取 `error.message` 或 `message`，超过 200 字截断。
- Timeout / UnknownHost 归 NetworkError。

聊天流里出错也走这个 mapper，Snackbar 显示 "认证失败 · 请检查 API Key" 而不是一坨 HTML。

### 3.5 Test Connection

`OpenAiLlmBackend.testConnection()`：非流式发一条最小 payload（`stream=false, max_tokens=8, messages=[{user:"ping"}]`，reasoning modifier 也照发），15s 超时，返回 `ApiOutcome`。UI 按钮**文本一直保持"测试连通性"**，结果写在按钮下方独立的 `tv_test_result` TextView：成功用主色，失败/未连通用次色。这样按钮和反馈边界清晰。

### 3.6 配置持久化（`LlmConfig.kt` +4 字段）

```kotlin
var providerPresetId: String  // 默认 "deepseek"
var temperature: Float        // 默认 0.7
var enableThinking: Boolean   // 默认 false
```

老用户迁移：`providerPresetId==null` 且 `openAiBaseUrl` 已存在时用 `inferByBaseUrl` 推断一次。

## 4. UI 结构

`dialog_online_config.xml`：
- Provider 用 **Spinner 下拉菜单**（默认 DeepSeek），4 项：DeepSeek / SiliconFlow / Agnes 2.0 / 自定义（OpenAI 兼容）
- API 三行 EditText：hint 分别是 "API 地址（例：https://api.deepseek.com/v1）" / "sk-...（长按此处粘贴）" / "模型名（如 deepseek-v4-flash）"
- `[测试连通性]` 按钮，其下方独立 TextView 显示结果，成功主色、失败次色
- **高级**折叠区：
  - `SwitchCompat` "启用思考模式（默认关 · 首字更快）"
  - `SeekBar` 温度 0.00–2.00，默认 0.70，右侧实时数字

保存/取消按钮位置不变。

## 5. 涉及文件

| 文件 | 新增 / 修改 |
|---|---|
| `docs/DESIGN_online_llm_v062.md` | 新增（本文） |
| `llm/ProviderPreset.kt` | 新增 |
| `llm/ApiErrorMapper.kt` | 新增 |
| `llm/OpenAiLlmBackend.kt` | 改：构造增加 `preset`/`extraBodyJson`；`buildBody` 用 modifier 表；新增 `testConnection()`；错误路径走 mapper |
| `llm/LlmConfig.kt` | 改：加 `providerPresetId` / `extraBodyJson` + 迁移推断 |
| `chat/LlmChatActivity.kt` | 改：`showOnlineConfig()` 增 SiliconFlow + Test 按钮 + Advanced 折叠；`prepareBackend()` 传 preset/extraBody |
| `res/layout/dialog_online_config.xml` | 改：SiliconFlow radio + Test 按钮 + Advanced 折叠区 |
| `docs/CHANGELOG.md` | 追加 v0.6.2 段 |

**不改动**：`OnlineConversationHistory.kt`（只装 user/assistant，无 system message 重复风险）、Live2D、TTS、语音路径。

## 6. 验证计划

1. Test Connection：SiliconFlow 无效 key → AuthFail 显示 "认证失败 · Token is invalid."；DeepSeek 有效 key → Ok + 延迟毫秒数。
2. Qwen3-4B 首字：`logcat -s OpenAiLlmBackend`，确认请求体里同时含 `enable_thinking:false` 和 `chat_template_kwargs`。首字对照未改代码前 5–15s → 应 <2s。
3. HTML 错误：故意用错 baseUrl 打到 Cloudflare 拦截页 → 显示 "服务器返回 HTML 页 · 检查 base URL"，不再糊 500 字 HTML。
4. Extra Body：填 `{"temperature":0.1}` 保存，logcat 里请求 JSON 应含 `temperature:0.1`。
5. 老用户迁移：升级不清数据，打开配置面板，Provider 应按已存 baseUrl 自动定位（DeepSeek→DeepSeek，其他→Agnes/OpenAI/自定义）。
6. `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` 都过。

## 7. 不做（v0.6.2 显式 out-of-scope）

- OpenAI o1/o3 `reasoning_effort` 参数（NONE 已经不发，够用；未来需要再加）
- DeepSeek `deepseek-chat`→`deepseek-v4-flash` 自动迁移（用户要求跳过）
- DashScope 独立 preset（走 Agnes/SiliconFlow 已能覆盖 Qwen 生态）
- 429 自动重试 / 指数退避（v0.7 再看）
- API Key 加密存储（沿用 SharedPreferences；v0.7 若做云同步再上 Keystore）

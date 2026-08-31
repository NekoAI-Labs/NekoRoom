# DESIGN: NekoChat 多本地 LLM 支持 & 本地对话能力对齐 (v0.6.3-neko)

> 目标：把本地大脑从「固定单一模型」升级为「可选多模型」，并把在线后端已有的多轮 + system prompt + 表情反馈同样带到本地，让默认体验从"表情不动、答非所问、被吐槽"回到基线以上。

## 1. 现状 (研究结论)

### 1.1 模型注册
`app/src/main/java/com/moeavatar/model/ModelManager.kt` 现在把 LLM/ASR/TTS 各写死一条 `NekoModel`，`REGISTRY` 里对应 `capability = Capability.LLM` 的就一条：`jiaohui/qwen35_08b_nekoneko-MNN`（多模态 Qwen3-0.8B neko 微调，含 `visual.mnn`）。UI 侧调用 `ModelManager.byCapability(Capability.LLM)` 拿这一条，无从表达多个 LLM。

### 1.2 本地后端加载
`LlmChatActivity.prepareBackend()`（1355–1399）走的是 `ModelScanner.scan(config.localModelDir)` → 扫子目录里带 `config.json` 的模型，再匹配 `config.localModelName`。也就是说：
- 只要目录里有多个模型子目录，`ModelScanner` 本来就能识别多模型；
- 但 UI 从来没暴露"选择哪个"，`localModelName` 只从 SP 里读，没有写入界面；
- 下载器只知道 `jiaohui/qwen35_08b_nekoneko-MNN` 一个仓库。

### 1.3 本地聊天上下文（严重问题）
`LocalLlmBackend.composePrompt()`（`LocalLlmBackend.kt:121–126`）：
```kotlin
private fun composePrompt(history: List<ChatTurn>): String {
    val last = history.lastOrNull { it.role == ChatTurn.Role.USER }
    return last?.content ?: ""
}
```
调用 native 时**只发最后一条 user 消息**。这直接导致：
- **没有 system prompt** —— 猫娘人设完全靠模型自身的微调兜底，一旦换成通用 Qwen 就跑偏；
- **没有历史轮次拼进去** —— 依赖 `Llm::response` 内部 KVCache 隐式保存前文，任何 `reset()` 都会掉档；
- **表情反馈能力块不注入本地** —— `LlmChatActivity.buildEffectiveSystemPrompt()`（1661）里的 `<action>{"emotion":...}</action>` 提示只经 `OpenAiLlmBackend.systemPromptProvider` 走线上，本地永远看不到；
- `config.liveActingEnabled` 注释里明确写了 "仅对在线后端生效，本地后端始终不注入"。

### 1.4 底层能力已就位
好消息：MNN LLM 已支持结构化多轮，`transformers/llm/engine/include/llm/llm.hpp` 里有
```cpp
using ChatMessage  = std::pair<std::string, std::string>;   // <role, content>
using ChatMessages = std::vector<ChatMessage>;
void response(const ChatMessages& chat_prompts, ...);
std::string apply_chat_template(const ChatMessages& chat_prompts) const;
```
但 `app/src/main/cpp/moeavatar_llm_jni.cpp:98–181` 里的 `submitNative` 只包了 `response(const std::string&, ...)` 这一份签名 —— JNI 层不支持 role 列表。这是本地无法多轮的**根因**。

### 1.5 UI 入口
- 「设置 → 能力中心」`showCapabilityCenter()`（LlmChatActivity 附近，`buildCapCard` @ 657）为每个 `NekoModel` 建一张卡（下载/暂停/删除）。它按 `REGISTRY` 遍历，天然可以扩展成多张 LLM 卡。
- 「在线服务」`showOnlineConfig()`（443）里的 `rg_backend` 是「本地 / 在线」二选一，没有再往下拆本地是哪个模型。

## 2. 用户需求映射

用户 3 条硬需求 + 1 条软需求：

| # | 用户描述 | 拆解 |
|---|-----|-----|
| A | 默认 neko 模型效果差、答非所问 → 允许换成通用 Qwen3.5 | 需要**多 LLM 注册 + 下载 + 切换** |
| B | 支持多轮会话 | 修 `LocalLlmBackend`：往 native 发 `ChatMessages`（而不是只发 last user）|
| C | 一套提示词，表情反馈本地也生效 | 把 `buildEffectiveSystemPrompt()` 注入本地后端 |
| D | 用下拉菜单选模型；默认 Qwen3.5 0.8B | 一个 `Spinner`（或竖排单选卡）在设置里挂出来 |

**本次范围内只支持三个 LLM，不做扩展：**
- `MNN/Qwen3.5-0.8B-MNN`（默认模型，一键安装推荐）
- `jiaohui/qwen35_08b_nekoneko-MNN`（原 Neko 模型，第二项）
- `MNN/Qwen3.5-2B-MNN`（更强档）

以上仓库的 `requiredFiles` 清单和 `sizeBytes` 需要在动手前用
`curl 'https://modelscope.cn/api/v1/models/{repo}/repo/files?Recursive=1'` 各拉一份文件清单实测填入，不做猜测。

## 3. 目标架构

### 3.1 数据模型：LLM 从 Capability 单例升级为多实例

`ModelManager.REGISTRY` 保留 ASR/TTS 各一条，LLM 拆成独立列表：

```kotlin
object ModelManager {
    val LLM_MODELS: List<NekoModel> = listOf(
        NekoModel(id = "llm-neko",       capability = LLM, productName = "Neko 猫娘 (默认)",
                  msRepo = "jiaohui/qwen35_08b_nekoneko-MNN",
                  subDir = "llm/qwen35_08b_nekoneko-MNN",
                  requiredFiles = listOf("config.json","llm.mnn","llm.mnn.json","llm.mnn.weight",
                                         "llm_config.json","tokenizer.mtok",
                                         "visual.mnn","visual.mnn.weight"),
                  sizeBytes = 502_000_000L, recommended = true),
        NekoModel(id = "llm-qwen35-0.8b", productName = "Qwen3.5 0.8B (轻快)",
                  msRepo = "MNN/Qwen3.5-0.8B-MNN",
                  subDir = "llm/Qwen3.5-0.8B-MNN",
                  requiredFiles = listOf(/* TODO: 拉 repo/files 清单填入 */),
                  sizeBytes = /* TODO */ 0L, recommended = false),
        NekoModel(id = "llm-qwen35-2b",  productName = "Qwen3.5 2B (更聪明)",
                  msRepo = "MNN/Qwen3.5-2B-MNN",
                  subDir = "llm/Qwen3.5-2B-MNN",
                  requiredFiles = listOf(/* TODO: 拉 repo/files 清单填入 */),
                  sizeBytes = /* TODO */ 0L, recommended = false),
    )
    // 兼容旧 UI：byCapability(LLM) 返回 activeLlm(ctx)
    fun activeLlm(ctx: Context): NekoModel = /* SP 里读 activeLlmId，fallback 到 LLM_MODELS[0] */
    val REGISTRY: List<NekoModel> = LLM_MODELS + listOf(/* asr, tts */)
}
```

**要点**：
- **本次范围严格锁死三条 LLM**，不接自定义、不接扫盘、不接远程 registry。
- `requiredFiles` 必须 per-model 精确列（Qwen3.5 不带 `visual.mnn`，Neko 带；tokenizer 文件名也可能不同）。**动手前需要用 `curl 'https://modelscope.cn/api/v1/models/{repo}/repo/files?Recursive=1'` 各拉一份文件清单校对**，`ModelScopeDownloader.fetchSizes()` 也是照这个 API 拿。
- Neko 保持 `recommended=true`，作为一键安装推荐配置的默认项；两条 Qwen3.5 打 `recommended=false`，不占默认下载盘量。

### 3.2 配置：`activeLlmId`

`LlmConfig.kt` 增：
```kotlin
var activeLlmId: String
    get() = sp.getString(K_ACTIVE_LLM, ModelManager.LLM_MODELS[0].id)!!
    set(v) = sp.edit { putString(K_ACTIVE_LLM, v) }
```
`localModelName`（现有）保持向后兼容，只作为"用户手动放 zip 时"的旧路径 —— 新代码不再依赖它做默认。

### 3.3 本地后端：多轮 + system prompt + 表情反馈

`LocalLlmBackend` 从"接一个 config.json 路径"升级为：
```kotlin
class LocalLlmBackend(
    private val configPath: String,
    private val systemPromptProvider: () -> String,   // 每轮回调；跟 OpenAiLlmBackend 对齐
) : LlmBackend {
    override fun chat(history: List<ChatTurn>): Flow<String> = callbackFlow {
        val messages: List<Pair<String,String>> = buildList {
            add("system" to systemPromptProvider())        // 每轮都拿一次 → 支持 Live2D 表情列表热变
            for (t in history) add(
                (when (t.role) { USER -> "user"; ASSISTANT -> "assistant"; SYSTEM -> "system" })
                to t.content
            )
        }
        LocalLlmBridge.submitChatNative(ptr, messages.map{it.first}.toTypedArray(),
                                       messages.map{it.second}.toTypedArray(), listener)
        ...
    }
    override fun resetSession() { LocalLlmBridge.resetNative(ptr) }   // 每轮进 chat 前 reset，走 stateless
}
```

**stateless 策略**：每轮进 `chat()` 先 `resetNative(ptr)` —— native KVCache 清空，然后把整个 history + system 一起塞进 `ChatMessages` 走。这个策略等同于 `OpenAiLlmBackend` 的行为（无状态，历史全带上），代价是每轮首字延迟略高（模板要重新编码）；好处是行为一致、无隐藏状态、换角色/换 system prompt 立即生效。

同时可以限一个 `maxRounds`（复用 `OnlineConversationHistory(15)`）避免上下文炸。当前 UI 侧 `LlmChatActivity` 已经维护了 `onlineConversationHistory`，只是在 1471–1475 用一个 `if (be is OpenAiLlmBackend)` 决定要不要带历史 —— 这里改成**无论后端都带**，实现代码变化 ≤ 3 行。

### 3.4 JNI：多轮 API

`moeavatar_llm_jni.cpp` 新增一个 entry（保留旧的以防回滚）：
```cpp
JNIEXPORT jstring JNICALL
Java_com_moeavatar_llm_LocalLlmBridge_submitChatNative(
    JNIEnv* env, jobject, jlong ptr,
    jobjectArray jRoles, jobjectArray jContents, jobject jListener) {
    // 1. Roles/contents 打包成 ChatMessages
    ChatMessages msgs;
    int n = env->GetArrayLength(jRoles);
    for (int i = 0; i < n; ++i) {
        jstring r = (jstring)env->GetObjectArrayElement(jRoles, i);
        jstring c = (jstring)env->GetObjectArrayElement(jContents, i);
        // ... GetStringUTFChars → msgs.emplace_back({role, content})
    }
    // 2. session->llm->reset() 已由 Kotlin 层保证
    // 3. session->llm->response(msgs, &os, "<eop>", -1)  —— 其余 UTF-8 补齐/回调逻辑复用现有实现
}
```
Kotlin 端 `LocalLlmBridge`：
```kotlin
external fun submitChatNative(ptr: Long,
                              roles: Array<String>, contents: Array<String>,
                              listener: TokenListener): String
```
旧 `submitNative(prompt)` 可以保留一段时间做回滚开关，或者一步删掉 —— 全文只有 `LocalLlmBackend` 一个调用点，删净成本很低。

### 3.5 表情反馈本地生效

只需两处小改：
- `LlmChatActivity.prepareBackend()`：`LocalLlmBackend(...)` 构造时把 `{ buildEffectiveSystemPrompt() }` 传进去（1368 附近）。
- `LlmConfig.liveActingEnabled` 注释里那句 "仅对在线后端生效" 去掉。
- （可选）针对 neko 微调模型跑 `<action>` 效果可能比 Qwen3 差 —— 建议 `buildEffectiveSystemPrompt` 里额外看 `activeLlmId`：如果是 neko 就跳过 acting block（沿用现有猫娘微调），如果是 Qwen3 就注入。这条**可以 v0.6.4 再做**，先默认对所有本地统一注入。

### 3.6 UI：设置 → AI 模型 (新增一个二级页)

能力中心固定展示 3 张能力卡：本地大脑、语音输入、Neko 离线语音。LLM 卡片内部使用一个 `Spinner` 选择三个模型，下载/暂停/删除操作作用于当前选中的模型；设置页也保留一个快捷的本地模型选择入口。

新弹片结构（沿用现有 BottomSheet + drawable 骨架）：
```
[当前模型] Neko 猫娘 (默认)  · 已安装 · 502MB

切换模型
◉ Neko 猫娘 (默认)            已安装
○ Qwen3.5 0.8B (轻快)         未安装  [下载 · xxxMB]
○ Qwen3.5 2B (更聪明)         下载中 42% (xxx/xxxMB) · 12.3MB/s  [暂停]
```
- 单选：点已安装 → 直接切 `activeLlmId` + 触发 `prepareBackend()`；
- 点未安装 → 走现有 `startCap(model)` → 下完自动 `onCapReady` 里切；
- 卡片状态复用现有 `CapCard`/`refreshCap` 逻辑，改动小。

如果不想新增弹片，另一个更简的方案 —— 直接把三张 LLM 卡塞进能力中心（`REGISTRY = LLM_MODELS + asr + tts` 后已经天然显示 3+2 张卡），再在每张 LLM 卡上加一个"设为当前"按钮 —— **建议先用这个**，UI 改动约 30 行。

### 3.7 下载进度显示：完全复用现有 UI，一行不改

现有 `ModelScopeDownloader.Progress` 回调 + `LlmChatActivity.updateCapProgress()` +
`CapCard.status/pct/progress` 的显示层已经把这件事做得很好，本次**不重写**：

- **进度条**：`CapCard.progress` (`ProgressBar` 横向)，`updateCapProgress` 里 `progress.progress = pct` 已在做。
- **当前 / 总大小**：`updateCapProgress` 里的 `pair` 变量按量纲自动切 KB/MB —— 小于 1MB 用 `(%.0f / %.0fKB)`，其余用 `(%.1f / %.0fMB)`。这段逻辑照单复用。
- **实时速度**：`ModelScopeDownloader.ProgressReporter.tick()` 里 200ms 节流 + 窗口内瞬时 bps，`updateCapProgress` 里再 `<1MB/s → KB/s，否则 MB/s`。**不改**。
- **百分比独占行**：`CapCard.pct` 贴在进度条上边，`updateCapProgress` 里 `card.pct.text = "$pct%"`。**不改**。
- **重试提示**：`ProgressReporter.reportRetry("file · 重试 x/y")` → `updateCapProgress` 里 `retryHint` 分支拼到 status 上。**不改**。
- **首帧立即上报 / 收尾 100%**：`download()` 一进就 `forceEmit`，每换一个文件也 `forceEmit`，收尾再 `forceEmit(speed=0)` 收工。**不改**。

**唯一需要 verify** 的一点：新的 Qwen3.5-0.8B/2B 卡片走的是同一份 `buildCapCard(model, ...)` → 同一份 `refreshCap(card)` → 同一份 `updateCapProgress(model, ...)`，只要在 `LLM_MODELS` 里注册新条目，进度显示就自然继承，**不需要动 downloader / progress UI 任何一行**。M4 里程碑的完工标准应包括：Qwen3.5-2B 下载全程能看到与 Neko 同款的
"下载中 42% (155.3 / 1024.0MB) · 12.3MB/s" 展示。

## 4. 代码改动清单 & 行数估算

| 文件 | 改动 | 估算 (行) |
|-----|-----|-----|
| `model/ModelManager.kt` | LLM_MODELS 拆 3 条 + `activeLlm(ctx)` + `byCapability` 兼容 | +50 / -5 |
| `llm/LlmConfig.kt` | `activeLlmId` 属性 + K_ACTIVE_LLM 常量 | +8 |
| `llm/LocalLlmBackend.kt` | 构造多带一个 `systemPromptProvider`；`composePrompt` 换 `buildMessages`；调新 JNI；`chat` 里进 `resetNative` | +40 / -15 |
| `llm/LocalLlmBridge.kt` | 新增 `submitChatNative` external 声明 | +3 |
| `cpp/moeavatar_llm_jni.cpp` | 新 JNI 函数：数组入参 → ChatMessages → `response(ChatMessages)`；UTF-8 补齐逻辑抽公共 lambda | +80 / -0 |
| `chat/LlmChatActivity.kt` | `prepareBackend()` 用 `ModelManager.activeLlm(ctx)` 而非 `ModelScanner` 兜底；`onlineConversationHistory` 对本地也带；每张 LLM 卡加"设为当前"按钮；`buildCapCard` 感知 activeLlmId | +60 / -15 |
| `docs/CHANGELOG.md` | v0.6.3-neko 段落 | +25 |
| `docs/DESIGN_multi_llm_v063.md` | 本文档 | +200 |
| `res/layout/*.xml` | 如果不新增弹片，不动；新增弹片则 ~+80 行 XML | 0 或 +80 |
| **合计（不新增弹片方案）** | | **≈ 265 行改动 + 225 行文档** |

不包含 native 层重新 build 出的 so 更新（`libmoeavatar_llm.so`）—— 只 rebuild 一次 CMake、无源码增量。

## 5. 里程碑拆解（每步都能自成 PR / 都可回滚）

1. **M1 · ChatMessages JNI 打通**：只加 `submitChatNative`，Kotlin 用 last-user 单条数组回填先跑通。验收：`adb logcat -s MoeAvatarLLM` 能看到 `response(ChatMessages)` 走通，效果不劣化。
2. **M2 · 多轮 + system prompt**：`LocalLlmBackend` 接 `systemPromptProvider`，`chat()` 里 `resetNative` + 全量 history + system 一起丢。验收：跟本地大脑连问 3 轮，"我叫什么名字"能记住。
3. **M3 · 多 LLM 注册**：`LLM_MODELS` 拆 3 条，`activeLlmId` 落 SP，`prepareBackend()` 用 `activeLlm(ctx)`。验收：SP 里改 `active_llm_id` 值，重启后加载不同模型。
4. **M4 · UI 切换/下载**：能力中心 LLM 卡加"设为当前"按钮 + Radio 视觉。验收：全流程 —— 点未安装 → 下 → 自动切 → 立即能聊。
5. **M5 · 表情反馈本地生效**：`prepareBackend` 里塞 `buildEffectiveSystemPrompt`，`liveActingEnabled` 注释更新。验收：用 Qwen3 本地聊天，AI 回复里出现 `<action>{"emotion":"hearteyes"}</action>` 并且 Live2D 有反应。

M1–M2 是**必做**（否则默认 Neko 也修不了答非所问的问题）；M3–M4 是**用户显式需求**；M5 完成"一套提示词，本地也生效"闭环。

## 6. 风险 & 待确认

- **`requiredFiles` 差异 & 体积**：三个仓库实测都含 `visual.mnn`；Qwen 使用 `tokenizer.txt`，Neko 使用 `tokenizer.mtok`。Qwen 0.8B 约 548MB，2B 约 1.39GB，清单和大小已按 ModelScope API 校对。
- **KVCache 双份**：MNN `Llm::response(ChatMessages)` 内部会不会自动 reset？—— 需读源码或跑一次自测。当前保守方案是**Kotlin 层显式 `resetNative` 每轮**；如实测能省这一步再优化。
- **模型体积**：Qwen3.5-2B 约 1.39GB，Wi-Fi 下没问题、蜂窝下会被系统流量提醒 —— UI 上应保留现有 `sizeLabel` 提示。
- **`<action>` 对小模型的指令遵循**：Qwen3.5-0.8B 可能不稳定输出严格 JSON。ActionTagFilter 现在已对无 JSON 情况优雅降级（就当普通文本），不会崩，只是表情不动。这是可以接受的降级，不进 v0.6.3 阻塞项。
- **多模态 neko 模型走 `ChatMessages` 是否 OK**：`visual.mnn` 主要是给图像输入用的，纯文本 chat 应该不会调它。首次跑 M1 时观察 logcat 有没有 "visual" 相关报错。

## 7. 明确不做的事

- **不加第 4 个 LLM** —— 三个候选就是 neko + Qwen3.5-0.8B + Qwen3.5-2B，写死在 `LLM_MODELS`。
- **不重写下载进度显示** —— 现有 `ModelScopeDownloader` + `updateCapProgress` + `CapCard` 的进度条 / 当前MB / 总MB / 实时速度 / 百分比 / 重试提示 都已经很好，一行不动。新模型只需在 `LLM_MODELS` 里注册。
- 不做"模型市场"、不接 remote registry。
- 不改 ASR / TTS 的选择方式（保持单一 Capability）。
- 不做"多 LLM 并行加载" —— 内存吃不消，切模型必须 release 旧的。
- 不做"用户放自定义模型 ZIP" —— 现有 `ModelScanner` 扫盘走的是老路径，暂不接入 UI，保留为高级用户手工 SP。

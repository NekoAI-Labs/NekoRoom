package com.moeavatar.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.moeavatar.model.ModelManager

/**
 * 全局可配置项。SharedPreferences 持久化。
 *
 * 这里只放跟 LLM/TTS 选择相关的轻量配置，模型扫描结果不持久化。
 */
class LlmConfig(ctx: Context) {

    private val appCtx: Context = ctx.applicationContext

    private val sp: SharedPreferences =
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    enum class BackendKind { LOCAL, OPENAI }

    var backendKind: BackendKind
        get() = runCatching { BackendKind.valueOf(sp.getString(K_BACKEND, BackendKind.LOCAL.name)!!) }
            .getOrDefault(BackendKind.LOCAL)
        set(v) = sp.edit { putString(K_BACKEND, v.name) }

    /** 本地模型扫描根目录，默认应用私有目录 models/llm（免存储权限） */
    var localModelDir: String
        get() = sp.getString(K_LOCAL_DIR, null) ?: ModelManager.llmScanRoot(appCtx)
        set(v) = sp.edit { putString(K_LOCAL_DIR, v) }

    /** 当前选中的本地模型子目录名（不是绝对路径） */
    var localModelName: String?
        get() = sp.getString(K_LOCAL_NAME, null)
        set(v) = sp.edit { putString(K_LOCAL_NAME, v) }

    /** 当前本地 LLM。新用户默认使用训练好的 Neko 猫娘模型；已有选择保持不变。 */
    var activeLlmId: String
        get() = sp.getString(K_ACTIVE_LLM, DEFAULT_ACTIVE_LLM) ?: DEFAULT_ACTIVE_LLM
        set(v) = sp.edit { putString(K_ACTIVE_LLM, v) }

    /** 当前 ASR 模型；新版本默认推荐，旧模型保留用于回退。 */
    var activeAsrId: String
        get() = sp.getString(K_ACTIVE_ASR, DEFAULT_ACTIVE_ASR) ?: DEFAULT_ACTIVE_ASR
        set(v) = sp.edit { putString(K_ACTIVE_ASR, v) }

    /** Current local TTS model; v1.3 INT8/Mixed is the default for new installs. */
    var activeTtsId: String
        get() = sp.getString(K_ACTIVE_TTS, DEFAULT_ACTIVE_TTS) ?: DEFAULT_ACTIVE_TTS
        set(v) = sp.edit { putString(K_ACTIVE_TTS, v) }

    /** 本地模型是否携带历史消息。默认开启；用户仍可在开发者设置中关闭。 */
    var localMultiTurn: Boolean
        get() = sp.getBoolean(K_LOCAL_MULTI_TURN, true)
        set(v) = sp.edit { putBoolean(K_LOCAL_MULTI_TURN, v) }

    var openAiBaseUrl: String
        get() = sp.getString(K_OAI_BASE, DEFAULT_OAI_BASE) ?: DEFAULT_OAI_BASE
        set(v) = sp.edit { putString(K_OAI_BASE, v) }

    var openAiApiKey: String
        get() = sp.getString(K_OAI_KEY, "") ?: ""
        set(v) = sp.edit { putString(K_OAI_KEY, v) }

    var openAiModel: String
        get() = sp.getString(K_OAI_MODEL, DEFAULT_OAI_MODEL) ?: DEFAULT_OAI_MODEL
        set(v) = sp.edit { putString(K_OAI_MODEL, v) }

    /**
     * v0.6.2 新增：当前选中的 Provider preset id（`deepseek`/`siliconflow`/`mimo`/`agnes`/`custom`）。
     * 老用户首次读到未设置时，按已存 openAiBaseUrl 一次性推断并写回。
     */
    var providerPresetId: String
        get() {
            val saved = sp.getString(K_OAI_PRESET, null)
            if (!saved.isNullOrBlank()) return saved
            val inferred = ProviderRegistry.inferByBaseUrl(openAiBaseUrl)
            sp.edit { putString(K_OAI_PRESET, inferred) }
            return inferred
        }
        set(v) = sp.edit { putString(K_OAI_PRESET, v) }

    /** 本地 MNN 与在线模型共用的采样温度，默认 0.7。 */
    var temperature: Float
        get() = sp.getFloat(K_OAI_TEMP, 0.7f)
        set(v) = sp.edit { putFloat(K_OAI_TEMP, v.coerceIn(0.1f, 2f)) }

    /** 本地 MNN 与在线模型共用的 nucleus sampling 范围。 */
    var topP: Float
        get() = sp.getFloat(K_TOP_P, 0.9f).coerceIn(0.1f, 1f)
        set(v) = sp.edit { putFloat(K_TOP_P, v.coerceIn(0.1f, 1f)) }

    /** 单轮生成上限；本地传给 MNN 原生 max_new_tokens，在线传 max_tokens。 */
    var maxOutputTokens: Int
        get() = sp.getInt(K_MAX_OUTPUT_TOKENS, 80).coerceIn(16, 512)
        set(v) = sp.edit { putInt(K_MAX_OUTPUT_TOKENS, v.coerceIn(16, 512)) }

    /** v0.6.2 新增：是否启用思考模式。默认关闭（大幅降低首字延迟）。 */
    var enableThinking: Boolean
        get() = sp.getBoolean(K_OAI_THINKING, false)
        set(v) = sp.edit { putBoolean(K_OAI_THINKING, v) }

    var systemPrompt: String
        get() {
            val saved = sp.getString(K_SYS_PROMPT, null)
            // 只迁移旧包自带默认值；用户自己编辑过的人设绝不覆盖。
            if (saved == LEGACY_DEFAULT_SYS_PROMPT) {
                sp.edit { putString(K_SYS_PROMPT, DEFAULT_SYS_PROMPT) }
                return DEFAULT_SYS_PROMPT
            }
            return saved ?: DEFAULT_SYS_PROMPT
        }
        set(v) = sp.edit { putString(K_SYS_PROMPT, v) }

    /** TTS 当前音色 id（沿用 BertVITS2 的 speaker 名字） */
    var ttsSpeaker: String
        get() = sp.getString(K_TTS_SPK, "甘雨_ZH") ?: "甘雨_ZH"
        set(v) = sp.edit { putString(K_TTS_SPK, v) }

    /** SuperTonic voice id; the corresponding file is voices/<id>.json. */
    var ttsVoiceId: String
        // 默认音色为 v1.3 的 neko（默认猫娘）；v1.1 场景由 ModelManager.resolveTtsVoice 回退 catgirl_style。
        get() = sp.getString(K_TTS_VOICE, "neko") ?: "neko"
        set(v) = sp.edit { putString(K_TTS_VOICE, v) }

    enum class TtsBackendKind { LOCAL, MINIMAX }

    /** TTS 后端：本地 BertVITS2 vs 在线 MiniMax 流式 */
    var ttsBackendKind: TtsBackendKind
        get() = runCatching { TtsBackendKind.valueOf(sp.getString(K_TTS_BACKEND, TtsBackendKind.LOCAL.name)!!) }
            .getOrDefault(TtsBackendKind.LOCAL)
        set(v) = sp.edit { putString(K_TTS_BACKEND, v.name) }

    /** MiniMax API Key（自己填，不写死） */
    var minimaxApiKey: String
        get() = sp.getString(K_MINIMAX_KEY, "") ?: ""
        set(v) = sp.edit { putString(K_MINIMAX_KEY, v) }

    /** MiniMax 音色 id，默认 female-shaonv */
    var minimaxVoiceId: String
        get() = sp.getString(K_MINIMAX_VOICE, "female-shaonv") ?: "female-shaonv"
        set(v) = sp.edit { putString(K_MINIMAX_VOICE, v) }

    /** 当前 Live2D 角色名（对应 Live2DController.PRESETS 的 key） */
    var live2dModel: String
        get() {
            val saved = sp.getString(K_LIVE2D, DEFAULT_LIVE2D) ?: DEFAULT_LIVE2D
            return if (saved in REMOVED_BUILTIN_MODELS) DEFAULT_LIVE2D else saved
        }
        set(v) = sp.edit { putString(K_LIVE2D, v) }

    /** 是否显示 AI 回复字幕（用户发送文本不受此开关影响，仍短暂显示） */
    var showAiSubtitle: Boolean
        get() = sp.getBoolean(K_SHOW_AI_SUB, true)
        set(v) = sp.edit { putBoolean(K_SHOW_AI_SUB, v) }

    /** 是否显示性能小字（首字延迟/tok/s、TTS RTF）。默认关。 */
    var showPerfLine: Boolean
        get() = sp.getBoolean(K_SHOW_PERF, false)
        set(v) = sp.edit { putBoolean(K_SHOW_PERF, v) }

    /**
     * dev 模式：默认关。打开后才显示性能优化二级选项。
     * - true: 所有 MoeAvatar.* TAG 输出 Verbose 级别
     * - false: 只输出 Warn 及以上
     *
     * 后续可以加 UI 开关；当前默认开便于排查电话模式/VAD 问题。
     */
    var devMode: Boolean
        get() = sp.getBoolean(K_DEV_MODE, false)
        set(v) = sp.edit { putBoolean(K_DEV_MODE, v) }

    /** Developer-only stage timing logs, primarily for TTS logcat diagnosis. */
    var performanceLogsEnabled: Boolean
        get() = sp.getBoolean(K_PERF_LOGS, false)
        set(v) = sp.edit { putBoolean(K_PERF_LOGS, v) }

    /** Developer TTS quality control, internally mapped to denoising steps. */
    var ttsSteps: Int
        get() = sp.getInt(K_TTS_STEPS, 6).coerceIn(3, 8)
        set(v) = sp.edit { putInt(K_TTS_STEPS, v.coerceIn(3, 8)) }

    /** 首启欢迎引导是否已展示过（只在第一次打开时弹能力安装引导）。 */
    var seenWelcome: Boolean
        get() = sp.getBoolean(K_SEEN_WELCOME, false)
        set(v) = sp.edit { putBoolean(K_SEEN_WELCOME, v) }

    /** AI 虚拟角色身份说明只在安装或升级后的首次进入展示一次。 */
    var seenAiDisclosure: Boolean
        get() = sp.getBoolean(K_SEEN_AI_DISCLOSURE, false)
        set(v) = sp.edit { putBoolean(K_SEEN_AI_DISCLOSURE, v) }

    /**
     * 是否启用 LLM 的 Live2D 表情/装扮反馈。关闭时不注入能力提示词，
     * 且即使模型主动输出 <action> 也不会驱动角色；本地和线上都生效。
     */
    var liveActingEnabled: Boolean
        get() = sp.getBoolean(K_LIVE_ACTING, true)
        set(v) = sp.edit { putBoolean(K_LIVE_ACTING, v) }

    companion object {
        private const val PREF_NAME = "moeavatar_llm"
        private const val K_BACKEND = "backend"
        private const val K_LOCAL_DIR = "local_dir"
        private const val K_LOCAL_NAME = "local_name"
        private const val K_ACTIVE_LLM = "active_llm_id"
        private const val K_ACTIVE_ASR = "active_asr_id"
        private const val K_ACTIVE_TTS = "active_tts_id"
        private const val K_LOCAL_MULTI_TURN = "local_multi_turn"
        private const val K_OAI_BASE = "oai_base"
        private const val K_OAI_KEY = "oai_key"
        private const val K_OAI_MODEL = "oai_model"
        private const val K_OAI_PRESET = "oai_preset_id"
        // 保留既有 key，升级后用户已有温度设置自动成为本地/在线共用设置。
        private const val K_OAI_TEMP = "oai_temperature"
        private const val K_TOP_P = "generation_top_p"
        private const val K_MAX_OUTPUT_TOKENS = "generation_max_output_tokens"
        private const val K_OAI_THINKING = "oai_enable_thinking"
        private const val K_SYS_PROMPT = "sys_prompt"
        private const val K_TTS_SPK = "tts_spk"
        private const val K_TTS_VOICE = "tts_voice_id"
        private const val K_LIVE2D = "live2d_model"
        private const val K_TTS_BACKEND = "tts_backend"
        private const val K_MINIMAX_KEY = "minimax_key"
        private const val K_MINIMAX_VOICE = "minimax_voice"
        private const val K_DEV_MODE = "dev_mode"
        private const val K_PERF_LOGS = "performance_logs"
        private const val K_TTS_STEPS = "tts_steps"
        private const val K_SHOW_AI_SUB = "show_ai_sub"
        private const val K_SHOW_PERF = "show_perf"
        private const val K_SEEN_WELCOME = "seen_welcome"
        private const val K_SEEN_AI_DISCLOSURE = "seen_ai_disclosure_v1"
        private const val K_LIVE_ACTING = "live_acting"
        private const val DEFAULT_ACTIVE_LLM = "llm-neko-v21"
        private const val DEFAULT_ACTIVE_ASR = "asr-zipformer-medium-fp16"
        private const val DEFAULT_ACTIVE_TTS = "tts-nekovoice-v13-int8"
        private const val DEFAULT_LIVE2D = "FenmaoLoli"
        private val REMOVED_BUILTIN_MODELS = setOf("Ziyan", "ATRI", "Yuuka", "Amadeus")

        const val DEFAULT_OAI_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_OAI_MODEL = "deepseek-v4-flash"
        /** 线上与本地共用的默认身份提示词，避免两套人设逐渐漂移。 */
        const val DEFAULT_SYS_PROMPT =
            "你是奈可，一只温柔可爱的猫娘。你喜欢陪用户聊天，关注对方的感受，说话自然亲近。" +
            "普通聊天保持轻松简洁，需要安慰时更温暖一些。偶尔在句尾加“喵~”，不要刻意撒娇。" +
            "用中文聊天，不使用markdown、颜文字或长篇大论。"

        private const val LEGACY_DEFAULT_SYS_PROMPT =
            "你是一只可爱的猫娘，名字叫小喵。说话软萌、亲昵，偶尔在句尾加\"喵~\"。" +
            "回答简洁口语化，用中文聊天，不要使用 markdown、颜文字或长篇大论。"

        /** 本地模型也使用同一身份提示词；小模型不再额外叠加可能互相冲突的规则。 */
        const val DEFAULT_LOCAL_SYS_PROMPT = DEFAULT_SYS_PROMPT
    }
}

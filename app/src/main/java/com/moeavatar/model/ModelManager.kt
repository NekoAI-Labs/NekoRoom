package com.moeavatar.model

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 三大能力。对外只暴露产品名，不暴露底层模型名。 */
enum class Capability { LLM, ASR, TTS }

/**
 * 一个可下载能力的注册项。所有路径/仓库/文件清单集中在这里，不散落硬编码。
 *
 * @property subDir       相对 models/ 的目标目录（LLM 因 ModelScanner 要求带一层模型子目录）
 * @property requiredFiles 判定「已安装」及实际要下载的文件（相对 subDir，扁平存放）
 * @property sizeBytes    requiredFiles 合计大小（用于 UI 展示与进度总量）
 */
data class NekoModel(
    val id: String,
    val capability: Capability,
    val productName: String,
    val productDesc: String,
    val msRepo: String,
    val subDir: String,
    val requiredFiles: List<String>,
    val sizeBytes: Long,
    val recommended: Boolean,
) {
    val sizeLabel: String get() = "约 ${Math.round(sizeBytes / 1e6)}MB"
}

/**
 * 模型注册 / 检测 / 路径。下载与校验在 [com.moeavatar.model.ModelScopeDownloader]。
 * 模型统一落在应用私有目录 getExternalFilesDir("models")/{llm,asr,tts}，免存储权限、卸载自动清理。
 */
object ModelManager {

    fun modelsRoot(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun modelRoots(ctx: Context): List<File> = buildList {
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { add(File(it, "models")) }
        add(File("/sdcard/Android/data/${ctx.packageName}/files/models"))
    }.distinctBy { it.absolutePath }

    fun dirOf(ctx: Context, m: NekoModel): File = File(modelsRoot(ctx), m.subDir)

    /** LLM 扫描根（ModelScanner 在其下找带 config.json 的子目录） */
    fun llmScanRoot(ctx: Context): String = File(modelsRoot(ctx), "llm").absolutePath
    fun asrDir(ctx: Context): String = File(modelsRoot(ctx), "asr").absolutePath
    /** Active TTS runtime directory. Models are always loaded from NekoChat-owned storage. */
    fun ttsDir(ctx: Context, model: NekoModel = activeTts(ctx)): String =
        ttsRuntimeDirs(ctx, model).firstOrNull { hasRequiredFiles(it, model) }?.absolutePath
            // Create the app-owned target eagerly. Android 13+ blocks adb from creating new
            // children under Android/data, but adb can populate a directory the app created.
            ?: dirOf(ctx, model).apply { mkdirs() }.absolutePath

    /** Create the active TTS directory before an adb deployment on Android 13+. */
    fun prepareTtsDirectory(ctx: Context) {
        // Use the v1.3 target explicitly. activeTts() may temporarily fall back to an
        // already-installed v1.1 while this new directory is still empty.
        val model = TTS_MODELS.first { it.recommended }
        File(ctx.filesDir, "models/${model.subDir}").mkdirs()
        dirOf(ctx, model).mkdirs()
    }

    private fun ttsRuntimeDirs(ctx: Context, model: NekoModel): List<File> = buildList {
        // Manual/offline deployment target. Internal storage is readable by the native loader
        // on Android 13+ without requesting broad storage access.
        add(File(ctx.filesDir, "models/${model.subDir}"))
        modelRoots(ctx).forEach { add(File(it, model.subDir)) }
    }.distinctBy { it.absolutePath }

    private fun hasRequiredFiles(dir: File, model: NekoModel): Boolean =
        model.requiredFiles.all { relative -> File(dir, relative).let { it.isFile && it.canRead() && it.length() > 0 } }

    /** ASR 版本列表；对用户只展示产品版本，不暴露底层网络结构/量化术语。 */
    val ASR_MODELS: List<NekoModel> = listOf(
        NekoModel(
            id = "asr-zipformer-medium-fp16",
            capability = Capability.ASR,
            productName = "语音识别 1.2",
            productDesc = "新版语音识别，默认推荐",
            msRepo = "jiaohui/zipformer-medium-MNN",
            subDir = "asr/medium-fp16",
            requiredFiles = listOf("encoder.fp16.mnn", "decoder.fp16.mnn", "joiner.fp16.mnn", "tokens.txt"),
            sizeBytes = 150_000_000L,
            recommended = true,
        ),
        NekoModel(
            id = "asr-zipformer-old-int8",
            capability = Capability.ASR,
            productName = "语音识别 1.1",
            productDesc = "保留给已下载旧模型和兼容设备",
            msRepo = "MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20",
            subDir = "asr",
            requiredFiles = listOf(
                "encoder-epoch-99-avg-1.int8.mnn",
                "decoder-epoch-99-avg-1.int8.mnn",
                "joiner-epoch-99-avg-1.int8.mnn",
                "tokens.txt",
            ),
            sizeBytes = 295_000_000L,
            recommended = false,
        ),
    )

    val LLM_MODELS: List<NekoModel> = listOf(
        NekoModel(
            id = "minicpm5-1b-fp16",
            capability = Capability.LLM,
            productName = "MiniCPM5-1B-FP16",
            productDesc = "完整精度权重，质量更高但占用更大",
            msRepo = "jiaohui/MiniCPM5-1B-MNN-FP16",
            subDir = "llm/MiniCPM5-1B-MNN-FP16",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.mtok", "embeddings_bf16.bin",
            ),
            sizeBytes = 2_166_530_645L,
            recommended = false,
        ),
        NekoModel(
            id = "minicpm5-1b-q8",
            capability = Capability.LLM,
            productName = "MiniCPM5-1B-Q8",
            productDesc = "质量与内存占用平衡",
            msRepo = "jiaohui/MiniCPM5-1B-MNN-Q8",
            subDir = "llm/MiniCPM5-1B-MNN-Q8",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.mtok", "embeddings_bf16.bin",
            ),
            sizeBytes = 1_314_059_447L,
            recommended = false,
        ),
        NekoModel(
            id = "minicpm5-1b-q4",
            capability = Capability.LLM,
            productName = "MiniCPM5-1B-Q4",
            productDesc = "更省空间的量化版本",
            msRepo = "jiaohui/MiniCPM5-1B-MNN-Q4",
            subDir = "llm/MiniCPM5-1B-MNN-Q4",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.mtok", "embeddings_bf16.bin",
            ),
            sizeBytes = 1_001_781_140L,
            recommended = false,
        ),
        NekoModel(
            id = "minicpm5-1b-q4-embed8",
            capability = Capability.LLM,
            productName = "MiniCPM5-1B-Q4-Embed8",
            productDesc = "更省空间的 Embedding 版本",
            msRepo = "jiaohui/MiniCPM5-1B-MNN-Q4-embed8",
            subDir = "llm/MiniCPM5-1B-MNN-Q4-embed8",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.mtok", "embeddings_int8.bin",
            ),
            sizeBytes = 826_308_752L,
            recommended = false,
        ),
        NekoModel(
            id = "minicpm5-1b-q4-embed4",
            capability = Capability.LLM,
            productName = "MiniCPM5-1B-Q4-Embed4",
            productDesc = "最省空间，可能牺牲部分质量",
            msRepo = "jiaohui/MiniCPM5-1B-MNN-Q4-embed4",
            subDir = "llm/MiniCPM5-1B-MNN-Q4-embed4",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.mtok", "embeddings_int4.bin",
            ),
            sizeBytes = 726_038_672L,
            recommended = false,
        ),
        NekoModel(
            id = "llm-qwen35-0.8b",
            capability = Capability.LLM,
            productName = "Qwen3.5 0.8B",
            productDesc = "默认本地大脑，轻快省空间",
            msRepo = "MNN/Qwen3.5-0.8B-MNN",
            subDir = "llm/Qwen3.5-0.8B-MNN",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.txt", "visual.mnn", "visual.mnn.weight",
            ),
            sizeBytes = 547_644_437L,
            recommended = false,
        ),
        NekoModel(
            id = "llm-qwen3-1.7b",
            capability = Capability.LLM,
            productName = "Qwen3 1.7B",
            productDesc = "通用本地大脑，能力更强但占用更大",
            msRepo = "MNN/Qwen3-1.7B-MNN",
            subDir = "llm/Qwen3-1.7B-MNN",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.weight",
                "llm_config.json", "tokenizer.txt",
            ),
            sizeBytes = 1_235_520_567L,
            recommended = false,
        ),
        NekoModel(
            id = "llm-qwen3-0.6b",
            capability = Capability.LLM,
            productName = "Qwen3 0.6B",
            productDesc = "最轻量本地大脑，适合快速测试",
            msRepo = "MNN/Qwen3-0.6B-MNN",
            subDir = "llm/Qwen3-0.6B-MNN",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.weight",
                "llm_config.json", "tokenizer.txt",
            ),
            sizeBytes = 454_470_710L,
            recommended = false,
        ),
        NekoModel(
            id = "llm-neko-v21",
            capability = Capability.LLM,
            // Public display version intentionally differs from the immutable repository/id.
            // Keep id/repo/subDir unchanged so existing downloads and upgrade detection work.
            productName = "Neko 猫娘 v1.1",
            productDesc = "猫娘微调模型 v1.1，默认推荐",
            msRepo = "jiaohui/qwen35_08b_nekoneko_v2.1-MNN",
            subDir = "llm/qwen35_08b_nekoneko_v2.1-MNN",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.mtok", "visual.mnn", "visual.mnn.weight",
            ),
            sizeBytes = 501_618_350L,
            recommended = true,
        ),
        NekoModel(
            id = "llm-neko",
            capability = Capability.LLM,
            productName = "Neko 猫娘",
            productDesc = "猫娘微调模型，保留原有风格",
            msRepo = "jiaohui/qwen35_08b_nekoneko-MNN",
            subDir = "llm/qwen35_08b_nekoneko-MNN",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.mtok", "visual.mnn", "visual.mnn.weight",
            ),
            sizeBytes = 501_618_321L,
            recommended = false,
        ),
        NekoModel(
            id = "llm-qwen35-2b",
            capability = Capability.LLM,
            productName = "Qwen3.5 2B",
            productDesc = "更强本地大脑，占用更多空间",
            msRepo = "MNN/Qwen3.5-2B-MNN",
            subDir = "llm/Qwen3.5-2B-MNN",
            requiredFiles = listOf(
                "config.json", "llm.mnn", "llm.mnn.json", "llm.mnn.weight",
                "llm_config.json", "tokenizer.txt", "visual.mnn", "visual.mnn.weight",
            ),
            sizeBytes = 1_386_690_287L,
            recommended = false,
        ),
    )

    /** Public v1.3 ships an anonymous, curated voice set in this exact UI order. */
    val TTS_VOICE_IDS: List<String> = listOf(
        "neko", "F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5",
    )

    /** TTS versions; v1.3 INT8/Mixed keeps the vocoder in FP16 and is the default. */
    val TTS_MODELS: List<NekoModel> = listOf(
        NekoModel(
            id = "tts-nekovoice-v13-int8",
            capability = Capability.TTS,
            productName = "NekoVoice 1.3",
            productDesc = "新版离线语音，速度与音质平衡，支持多音色",
            msRepo = "jiaohui/NekoVoice-1.3",
            subDir = "tts/v13-int8",
            requiredFiles = listOf("dp.mnn", "te.mnn", "ve.mnn", "vocoder.mnn") +
                TTS_VOICE_IDS.map { "voices/$it.json" },
            // 2026-08-04 核仓库实际字节：4×MNN 135,305,124 + 11×voice 3,381,291 = 138,686,415
            sizeBytes = 138_686_415L,
            recommended = true,
        ),
        NekoModel(
            id = "tts-nekovoice-v11",
            capability = Capability.TTS,
            productName = "NekoVoice 1.1",
            productDesc = "旧版离线语音，保留兼容",
            msRepo = "jiaohui/NekoVoice-v1.1",
            subDir = "tts",
            requiredFiles = listOf("dp.mnn", "te.mnn", "ve.mnn", "vocoder.mnn", "voices/catgirl_style.json"),
            sizeBytes = 200_000_000L,
            recommended = false,
        ),
    )

    /** Three user-facing capabilities. Variants are selected inside the cards. */
    val REGISTRY: List<NekoModel> = listOf(LLM_MODELS.first { it.id == "llm-neko-v21" }) + listOf(
        ASR_MODELS.first { it.recommended },
        TTS_MODELS.first { it.recommended },
    )

    fun byId(id: String): NekoModel? = REGISTRY.firstOrNull { it.id == id }
    fun byIdIncludingVariants(id: String): NekoModel? =
        (REGISTRY + ASR_MODELS + TTS_MODELS + LLM_MODELS).firstOrNull { it.id == id }
    fun byCapability(c: Capability): NekoModel? = when (c) {
        Capability.ASR -> ASR_MODELS.firstOrNull { it.recommended }
        Capability.TTS -> TTS_MODELS.firstOrNull { it.recommended }
        else -> REGISTRY.firstOrNull { it.capability == c }
    }

    fun activeAsr(ctx: Context): NekoModel {
        val id = com.moeavatar.llm.LlmConfig(ctx).activeAsrId
        val preferred = ASR_MODELS.firstOrNull { it.id == id } ?: ASR_MODELS.first { it.recommended }
        if (isInstalled(ctx, preferred)) return preferred
        return ASR_MODELS.firstOrNull { isInstalled(ctx, it) } ?: preferred
    }

    fun activeTts(ctx: Context): NekoModel {
        val id = com.moeavatar.llm.LlmConfig(ctx).activeTtsId
        val preferred = TTS_MODELS.firstOrNull { it.id == id } ?: TTS_MODELS.first { it.recommended }
        if (isInstalled(ctx, preferred)) return preferred
        return TTS_MODELS.firstOrNull { isInstalled(ctx, it) } ?: preferred
    }

    fun voiceIds(model: NekoModel): List<String> =
        if (model.id == "tts-nekovoice-v13-int8") TTS_VOICE_IDS else listOf("catgirl_style")

    fun resolveTtsVoice(ctx: Context, model: NekoModel = activeTts(ctx)): String {
        val requested = com.moeavatar.llm.LlmConfig(ctx).ttsVoiceId
        return requested.takeIf { it in voiceIds(model) }
            ?: if (model.id == "tts-nekovoice-v13-int8") "neko" else "catgirl_style"
    }

    fun activeLlm(ctx: Context): NekoModel {
        val id = com.moeavatar.llm.LlmConfig(ctx).activeLlmId
        val preferred = LLM_MODELS.firstOrNull { it.id == id } ?: LLM_MODELS.first()
        if (isInstalled(ctx, preferred)) return preferred
        // Preserve a model left by an older install instead of forcing a re-download.
        return LLM_MODELS.firstOrNull { isInstalled(ctx, it) } ?: preferred
    }

    /** 必需文件都存在且非空 → 视为已安装。 */
    fun isInstalled(ctx: Context, m: NekoModel): Boolean {
        val dirs = if (m.capability == Capability.TTS) ttsRuntimeDirs(ctx, m)
        else modelRoots(ctx).map { File(it, m.subDir) }
        val matched = dirs.firstOrNull { dir -> hasRequiredFiles(dir, m) }
        if (matched == null) {
            val primary = dirs.firstOrNull()
            val missing = m.requiredFiles.filter {
                val f = primary?.let { dir -> File(dir, it) }
                f == null || !f.isFile || f.length() <= 0
            }
            Log.d(TAG, "not installed id=${m.id} roots=${dirs.map { it.absolutePath }} missing=$missing")
        } else {
            Log.d(TAG, "installed id=${m.id} dir=${matched.absolutePath}")
        }
        return matched != null
    }

    fun isInstalled(ctx: Context, c: Capability): Boolean = when (c) {
        Capability.LLM -> LLM_MODELS.any { isInstalled(ctx, it) }
        Capability.ASR -> ASR_MODELS.any { isInstalled(ctx, it) }
        Capability.TTS -> TTS_MODELS.any { isInstalled(ctx, it) }
    }

    /** Disable model-side reasoning before native load. Only MiniCPM5 gets an injected override. */
    fun disableThinking(ctx: Context, model: NekoModel) {
        if (model.capability != Capability.LLM) return
        val file = File(dirOf(ctx, model), "config.json")
        if (!file.isFile) return
        runCatching {
            val config = JSONObject(file.readText())
            val normalized = if (model.id.startsWith("minicpm5-1b-")) {
                ensureMiniCpmThinkingDisabled(config)
            } else {
                normalizeThinking(config)
            }
            file.writeText(normalized.toString())
        }
    }

    /** MiniCPM5 FP16 config may omit the context object entirely, so recursion alone is insufficient. */
    private fun ensureMiniCpmThinkingDisabled(value: JSONObject): JSONObject {
        val jinja = value.optJSONObject("jinja") ?: JSONObject().also { value.put("jinja", it) }
        val context = jinja.optJSONObject("context") ?: JSONObject().also { jinja.put("context", it) }
        context.put("enable_thinking", false)
        return normalizeThinking(value)
    }

    private fun normalizeThinking(value: JSONObject): JSONObject {
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val child = value.opt(key)) {
                is JSONObject -> normalizeThinking(child)
                is JSONArray -> for (i in 0 until child.length()) {
                    if (child.opt(i) is JSONObject) normalizeThinking(child.getJSONObject(i))
                }
            }
            if (key.equals("enable_thinking", ignoreCase = true)) value.put(key, false)
        }
        return value
    }

    /** 卸载：删除该能力目录，并返回是否真的不存在。 */
    fun delete(ctx: Context, m: NekoModel): Boolean {
        val targets = modelRoots(ctx).map { File(it, m.subDir) }
        targets.forEach { it.deleteRecursively() }
        return targets.all { !it.exists() }
    }

    private const val TAG = "MoeAvatar.Model"
}

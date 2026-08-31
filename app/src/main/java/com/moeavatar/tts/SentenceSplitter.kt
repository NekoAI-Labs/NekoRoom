package com.moeavatar.tts

/**
 * 流式文本切句器：把 LLM 一段段吐出来的 token 拼起来，按规则切成"可送进 TTS"的整句。
 *
 * 策略（按优先级）：
 *  1. 一级标点 [。？！；.?!;\n] —— 立刻切，保留完整语义片段。
 *  2. 累积达到 18 字后，优先在最近的二级标点 [，、：,;:：—] 切。
 *  3. 累积达到 [maxHard] 后才硬切，避免 Backend 再次拆分同一句。
 *
 * 长度依据（2026-08-04 核对）：
 *  - 模型按 ~30s 训练（VE/TE/Vocoder 全部动态 shape，无固定 L 上限；GetMNNInfo 已确认）。
 *  - 实测 27 token → 94 latent 帧 → 6.55s，约 0.242s/token、约 3.5 字/s。
 *  - maxHard=56 字 ≈ 60 token ≈ 14.5s 音频；首包 = 整句合成耗时 ≈ RTF(≈0.2) × 14.5s ≈ 3s，
 *    是"够长但首包不太慢"的平衡点；绝大多数句子在标点处就切了，远小于 56 字。
 *
 * 用法：
 *   val sp = SentenceSplitter()
 *   while (token in flow) for (s in sp.feed(token)) speechQueue.enqueue(s)
 *   for (s in sp.flush()) speechQueue.enqueue(s)
 */
class SentenceSplitter(
    private val minFallback: Int = 18,
    /** 无标点长句的硬切上限：56 字 ≈ 14.5s 音频 ≈ 首包 ~3s（RTF 0.2）。 */
    private val maxHard: Int = 56,
) {
    private val buf = StringBuilder()

    /** 喂入一段新 token，返回这次能切出来的若干完整句子（保留标点）。 */
    fun feed(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buf.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val s = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 流结束时调用：把残留 buffer 当作最后一句吐出来。 */
    fun flush(): List<String> {
        val s = buf.toString().trim()
        buf.setLength(0)
        return if (s.isEmpty()) emptyList() else listOf(s)
    }

    /** 返回 buf 里第一个切点的"右边界"（exclusive），找不到返回 null。 */
    private fun findCut(): Int? {
        // 1. 一级标点：扫到第一个就切
        for (i in 0 until buf.length) {
            if (PRIMARY.contains(buf[i])) return i + 1
        }
        // 2. 只有片段达到舒适长度后，二级标点才允许切分。
        if (buf.length >= minFallback) {
            for (i in (buf.length - 1) downTo (minFallback - 1)) {
                if (SECONDARY.contains(buf[i])) return i + 1
            }
        }
        // 3. SuperTonic 单句时长有上限，切句器负责安全上限，Backend 不再二次切句。
        if (buf.length >= maxHard) return maxHard
        return null
    }

    companion object {
        private val PRIMARY = setOf('。', '？', '！', '；', '.', '?', '!', ';', '\n')
        private val SECONDARY = setOf('，', '、', ',', ':', '：', '—')
    }
}

package com.moeavatar.tts

/**
 * TTS 合成前的最小文本清洗器。
 *
 * 删除 Markdown 强调符 `*`，并删除成对括号里的动作/控制内容：
 * `()`、`（）`、`[]`、`【】`、`{}`。括号外的所有文字必须保留。
 * 未闭合的括号会在流结束时恢复原文，避免一个坏标签吞掉后续回复。
 */
class TtsTextFilter {
    private val closing = ArrayDeque<Char>()
    private val hidden = StringBuilder()

    fun reset() {
        closing.clear()
        hidden.setLength(0)
    }

    /** 流式：吃一段文字，返回括号外的可合成内容。 */
    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val out = StringBuilder(chunk.length)
        for (ch in chunk) {
            if (closing.isEmpty()) {
                when (ch) {
                    '*' -> Unit
                    '(', '（' -> { closing.addLast(if (ch == '(') ')' else '）'); hidden.append(ch) }
                    '[', '【' -> { closing.addLast(if (ch == '[') ']' else '】'); hidden.append(ch) }
                    '{' -> { closing.addLast('}'); hidden.append(ch) }
                    else -> out.append(ch)
                }
            } else {
                hidden.append(ch)
                when (ch) {
                    '(', '（' -> closing.addLast(if (ch == '(') ')' else '）')
                    '[', '【' -> closing.addLast(if (ch == '[') ']' else '】')
                    '{' -> closing.addLast('}')
                    closing.lastOrNull() -> closing.removeLast()
                }
                if (closing.isEmpty()) hidden.setLength(0)
            }
        }
        return out.toString()
    }

    /** 流结束时恢复未闭合括号中的原文，避免误删正常文字。 */
    fun flush(): String {
        if (hidden.isEmpty()) return ""
        val out = hidden.toString().replace("*", "")
        reset()
        return out
    }

    /** 一次性：直接处理整个字符串（用于已切好的单句）。 */
    fun stripAll(s: String): String {
        val oneShot = TtsTextFilter()
        return oneShot.feed(s) + oneShot.flush()
    }
}

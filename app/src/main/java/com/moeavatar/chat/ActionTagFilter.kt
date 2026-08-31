package com.moeavatar.chat

import android.util.Log
import org.json.JSONObject

/**
 * v0.5 Character Engine · M1
 *
 * 流式抽取 `<action>{...}</action>` JSON 控制块，用于 LLM 驱动 Live2D 表情/装扮。
 *
 * 与 [com.moeavatar.llm.OpenAiLlmBackend.ThinkTagFilter] 的关键区别：
 * - **跨 token 缓冲**：token 可能把标签切在任何位置，`<act` + `ion>` 分两次到达也要正确处理。
 *   靠 [carry] 里保留"可能是标签前缀"的尾巴。
 * - 块内容是 JSON，等读到 `</action>` 才 parse；坏 JSON 丢弃并 log，不打断文本流。
 *
 * 使用方式：
 * ```
 * val filter = ActionTagFilter()
 * for (token in stream) {
 *     val r = filter.feed(token)
 *     r.actions.forEach { dispatchToLive2D(it) }
 *     if (r.visible.isNotEmpty()) sinkVisible(r.visible)
 * }
 * val tail = filter.flush()
 * tail.actions.forEach { dispatchToLive2D(it) }
 * if (tail.visible.isNotEmpty()) sinkVisible(tail.visible)
 * ```
 *
 * 线程性：无状态可见，但内部有可变 buffer；**同一 filter 实例只能单线程使用**
 * （LlmChatActivity 的 collect { } 里天然是单线程 collect）。
 *
 * 大小写：`<Action>` / `<ACTION>` 也认，容忍模型偶尔大小写混乱。
 * 未闭合的 `<action>` 到 [flush] 时被丢弃（不 dump 半个 JSON 到文本）。
 */
class ActionTagFilter {

    /**
     * 一个控制块的解析结果。所有字段可选（值为 null 或空串表示"这次不改这一路"）。
     * - [emotion]：情绪表情名，走 nativeApplyExpression
     * - [outfit] ：装扮组件名，同上通道（覆盖 emotion 的部分参数）
     * - [motion] ：动作名（M3 才生效，当前仅记录到 log）
     */
    data class Action(
        val emotion: String? = null,
        val outfit: String? = null,
        val motion: String? = null,
    ) {
        val isEmpty: Boolean
            get() = emotion.isNullOrBlank() && outfit.isNullOrBlank() && motion.isNullOrBlank()
    }

    /** feed / flush 的返回。visible 是给下游文本管线（ttsFilter/字幕/TTS）的可见文本。 */
    data class Result(val visible: String, val actions: List<Action>)

    private val carry = StringBuilder()
    private var inTag = false

    /** 喂一个 token，返回该 token 处理完能"确定"输出的可见文本 + 解析出的 action。 */
    fun feed(token: String): Result {
        if (token.isEmpty()) return EMPTY
        carry.append(token)
        return drain(forceFlush = false)
    }

    /**
     * 流结束时调用。把 carry 里剩余的可见文本吐出去；未闭合的 `<action>` 直接丢弃
     * （防止半个 JSON 泄漏到字幕）。
     */
    fun flush(): Result = drain(forceFlush = true)

    private fun drain(forceFlush: Boolean): Result {
        val out = StringBuilder()
        val actions = mutableListOf<Action>()

        while (true) {
            if (inTag) {
                val idx = indexOfCi(carry, TAG_CLOSE)
                if (idx < 0) {
                    // 还没读到 </action>，继续挂着
                    if (forceFlush) {
                        Log.w(TAG, "unclosed <action> block on flush, discarded: '${carry.toString().take(120)}'")
                        carry.clear()
                        inTag = false
                    }
                    break
                }
                val jsonRaw = carry.substring(0, idx)
                parseAction(jsonRaw)?.let { actions.add(it) }
                carry.delete(0, idx + TAG_CLOSE.length)
                inTag = false
                continue
            }

            // Normal 状态，找 <action>
            val open = indexOfCi(carry, TAG_OPEN)
            if (open >= 0) {
                out.append(carry, 0, open)
                carry.delete(0, open + TAG_OPEN.length)
                inTag = true
                continue
            }

            // 没完整开始标签。看尾部是不是可能的 `<act` 前缀，是则保留等下一 token。
            val tail = trailingPartialLen(carry, TAG_OPEN)
            if (tail == 0 || forceFlush) {
                out.append(carry)
                carry.clear()
            } else {
                val safe = carry.length - tail
                if (safe > 0) out.append(carry, 0, safe)
                carry.delete(0, safe)
            }
            break
        }

        return if (out.isEmpty() && actions.isEmpty()) EMPTY
        else Result(out.toString(), actions)
    }

    private fun parseAction(raw: String): Action? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = JSONObject(trimmed)
            val a = Action(
                emotion = obj.optString("emotion").takeIf { it.isNotBlank() },
                outfit  = obj.optString("outfit").takeIf { it.isNotBlank() },
                motion  = obj.optString("motion").takeIf { it.isNotBlank() },
            )
            if (a.isEmpty) null else a
        } catch (t: Throwable) {
            Log.w(TAG, "bad <action> JSON: '${trimmed.take(200)}'", t)
            null
        }
    }

    companion object {
        private const val TAG = "MoeAvatar.ActionTagFilter"
        private const val TAG_OPEN = "<action>"
        private const val TAG_CLOSE = "</action>"
        private val EMPTY = Result("", emptyList())

        /** 大小写不敏感的 indexOf。CharSequence 版避免 substring copy。 */
        internal fun indexOfCi(hay: CharSequence, needle: String): Int {
            val hlen = hay.length
            val nlen = needle.length
            if (nlen == 0 || hlen < nlen) return -1
            outer@ for (i in 0..hlen - nlen) {
                for (j in 0 until nlen) {
                    if (hay[i + j].lowercaseChar() != needle[j].lowercaseChar()) continue@outer
                }
                return i
            }
            return -1
        }

        /**
         * 返回 [buf] 尾部与 [target] 前缀能匹配的最长长度（0 = 完全不匹配）。
         * 用于判定"是不是标签前缀被 token 切断了"，如 buf 尾巴 `<ac` 之于 `<action>`。
         */
        internal fun trailingPartialLen(buf: CharSequence, target: String): Int {
            val slen = buf.length
            val tlen = target.length
            val maxCheck = minOf(slen, tlen - 1)
            var k = maxCheck
            while (k >= 1) {
                var match = true
                for (i in 0 until k) {
                    if (buf[slen - k + i].lowercaseChar() != target[i].lowercaseChar()) {
                        match = false; break
                    }
                }
                if (match) return k
                k--
            }
            return 0
        }
    }
}

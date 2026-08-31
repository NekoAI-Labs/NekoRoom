package com.moeavatar.chat.subtitle

import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * v4 共享悬浮字幕：AI 回复和用户输入复用同一条 TextView，不留历史。
 *
 * - AI（[showAiClause]）：在对应音频写入 AudioTrack 前整句上屏，下一句直接顶替。
 * - AI 播放自然结束（[finishAi]）：短暂停留后淡出。
 * - 用户（[showUser]）：整句瞬时显示，hold [USER_HOLD_MS] 淡出。
 * - [stopAll]：barge-in，立即停止淡出动画并清空。
 */
class SubtitleManager(private val tv: TextView) {

    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var holdJob: Runnable? = null

    /**
     * 显示即将播放的 AI 语句。调用方必须先完成本方法，再开始写音频。
     * 字幕保持到下一句或 [finishAi]，不再用固定每字延迟追赶音频。
     */
    fun showAiClause(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        cancelTimers()
        current = t
        // 这是音频写入前的可见性屏障，不能使用异步淡入，否则声音仍可能先于首帧字幕。
        tv.animate().cancel()
        tv.text = t
        tv.alpha = 1f
    }

    /** 无语音回复或错误消息使用的短暂 AI 字幕。 */
    fun pushSentence(text: String) {
        showAiClause(text)
        current?.let { scheduleFadeOut(it, AI_HOLD_MS) }
    }

    /** TTS 自然播放完毕后收起最后一句。 */
    fun finishAi() {
        current?.let { scheduleFadeOut(it, AI_HOLD_MS) }
    }

    /** 用户文本：瞬时整句显示，抢占当前 AI 字幕。 */
    fun showUser(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        cancelTimers()
        current = t
        setTextFadeIn(t)
        scheduleFadeOut(t, USER_HOLD_MS)
    }

    /** barge-in：立即清空。 */
    fun stopAll() {
        cancelTimers()
        current = null
        tv.animate().cancel()
        tv.alpha = 0f
        tv.text = ""
    }

    private fun scheduleFadeOut(owner: String, holdMs: Long) {
        holdJob = Runnable {
            if (current != owner) return@Runnable
            tv.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
                if (current == owner) {
                    current = null
                    tv.text = ""
                }
            }.start()
        }
        handler.postDelayed(holdJob!!, holdMs)
    }

    private fun setTextFadeIn(text: String) {
        tv.text = text
        tv.animate().cancel()
        tv.alpha = 0f
        tv.animate().alpha(1f).setDuration(FADE_IN_MS).start()
    }

    private fun cancelTimers() {
        holdJob?.let { handler.removeCallbacks(it) }
        holdJob = null
    }

    companion object {
        private const val AI_HOLD_MS = 2200L
        private const val USER_HOLD_MS = 2500L
        private const val FADE_OUT_MS = 400L
        private const val FADE_IN_MS = 150L
    }
}

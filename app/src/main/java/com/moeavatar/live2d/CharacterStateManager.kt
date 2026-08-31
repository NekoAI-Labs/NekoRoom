package com.moeavatar.live2d

import android.util.Log

/**
 * v0.5 M2 · Character 状态层。
 *
 * 目标：把「谁在扮演、当前穿了什么装扮、当前是什么情绪」这套 semantic 状态与
 * 底层 Live2D 渲染（native ExpressionManager × 2）解耦。UI/Action 只跟这里对话，
 * 由本 manager 决定"这次要下发哪些 native 调用"。
 *
 * 双通道模型（native 侧对应 M2 实装）：
 * - `emotion`  transient，回合结束 [clearEmotion] 只清零 emotion，不动 outfit
 * - `outfit`   persistent，只有显式切换或换角色才会变
 * - `accessories` 目前只占位，等 M3 加多组件（帽子/眼镜/星星尾巴...）
 *
 * 线程性：单例挂 Activity/Controller 上，Activity 主线程调用即可；
 * 内部无并发假设。
 */
class CharacterStateManager(
    private val driver: Driver,
) {
    /**
     * native 下发接口。Live2DController 实现它 —— 我们只让 state manager 通过
     * 语义化名字调用，不关心底层是 nativeApplyExpression 还是 nativeApplyOutfit。
     */
    interface Driver {
        /** 派发一条 outfit（对应 native _outfitExpressionManager）。 */
        fun nativeOutfit(name: String)
        /** 停止 outfit 通道，回到模型 moc3 的默认服装。 */
        fun nativeClearOutfit()
        /** 派发一条 emotion（对应 native _expressionManager）。 */
        fun nativeEmotion(name: String)
        /** 清空 emotion 通道：native 侧目前只能靠 apply 空表情，也可以就让它自己 fade 结束。 */
        fun nativeClearEmotion()
        /**
         * 当前角色声明的合法表情名列表。用于校验 LLM 传上来的 name 是否可用。
         * 空列表 = 该模型没 exp3，action 系统对它自动降级。
         */
        fun availableExpressions(): List<String>
    }

    /** 当前角色 id（对应 Live2DController.PRESETS 的 key 或自定义模型目录名）。 */
    @Volatile var characterId: String? = null
        private set

    /** 装扮层（跨轮次保留）。 */
    @Volatile var currentOutfit: String? = null
        private set

    /** 情绪层（本轮 transient，回合结束由 [clearEmotion] 清零）。 */
    @Volatile var currentEmotion: String? = null
        private set

    /**
     * 配饰层（M3 预留 —— 眼镜、耳饰、尾巴挂件）。当前只维护 state，不做 native 下发；
     * 等第二条以上并行 ExpressionManager 到位再接。
     */
    private val currentAccessories = mutableSetOf<String>()

    /** 切角色时清所有 state（不同模型的 exp 名不通用）。 */
    fun onCharacterChanged(id: String) {
        characterId = id
        currentOutfit = null
        currentEmotion = null
        currentAccessories.clear()
        Log.d(TAG, "onCharacterChanged: $id (state cleared)")
    }

    /** 校验一个名字是否是当前模型合法表情。空表情列表时统一返回 true（降级放行）。 */
    private fun isKnown(name: String): Boolean {
        val exps = driver.availableExpressions()
        return exps.isEmpty() || name in exps
    }

    /** 派发一个 outfit —— 持久，覆盖上一件；不动 emotion。 */
    fun applyOutfit(name: String): Boolean {
        if (!isKnown(name)) {
            Log.w(TAG, "applyOutfit: unknown '$name' (avail=${driver.availableExpressions()})")
            return false
        }
        currentOutfit = name
        Log.d(TAG, "applyOutfit: $name (emotion stays=$currentEmotion)")
        driver.nativeOutfit(name)
        return true
    }

    /** 清除持久装扮层，回到模型底模的默认服装；不影响当前 emotion。 */
    fun clearOutfit(): Boolean {
        if (currentOutfit == null) return false
        Log.d(TAG, "clearOutfit (was=$currentOutfit, emotion stays=$currentEmotion)")
        currentOutfit = null
        driver.nativeClearOutfit()
        return true
    }

    /** 派发一个 emotion —— 本轮临时，不覆盖 outfit。 */
    fun applyEmotion(name: String): Boolean {
        if (!isKnown(name)) {
            Log.w(TAG, "applyEmotion: unknown '$name' (avail=${driver.availableExpressions()})")
            return false
        }
        currentEmotion = name
        Log.d(TAG, "applyEmotion: $name (outfit stays=$currentOutfit)")
        driver.nativeEmotion(name)
        return true
    }

    /**
     * 回合结束（TTS 队列播完 / 用户中止）：清 emotion，outfit 保留。
     * native 侧 _expressionManager 会自己把 emotion 参数 fade 到 0；我们不重复 apply outfit
     * —— outfit 通道从 native 侧就没被动过。
     */
    fun clearEmotion() {
        if (currentEmotion == null) return
        Log.d(TAG, "clearEmotion (was=$currentEmotion, outfit=$currentOutfit)")
        currentEmotion = null
        driver.nativeClearEmotion()
    }

    /**
     * 完整状态快照（logcat 排查 / 后续 M3 记录到 memory 都可以用）。
     */
    fun snapshot(): String =
        "char=$characterId outfit=$currentOutfit emotion=$currentEmotion accessories=$currentAccessories"

    companion object {
        private const val TAG = "MoeAvatar.CharState"
    }
}

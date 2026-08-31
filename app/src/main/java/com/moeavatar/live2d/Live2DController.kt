package com.moeavatar.live2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import com.chatwaifu.live2d.GLRenderer
import com.chatwaifu.live2d.JniBridgeJava
import java.io.File

/**
 * Live2D 渲染 + 口型 + 触控的统一封装。Activity 只需要：
 *
 *   ```kotlin
 *   val live2d = Live2DController(this, container)
 *   live2d.onCreate(modelDir = "Live2DModels/ATRI/", modelJson = "ATRI.model3.json")
 *   ...
 *   live2d.feedAudioForLipSync(samples, sampleRate)   // TTS PCM 喂进来做嘴型
 *   ...
 *   live2d.onResume()/onPause()/onStop()/onDestroy()  // 跟 Activity 生命周期
 *   ```
 *
 * 缩放 / 平移：在加载完模型后，按预置 [ModelPreset] 调一次 transform/scale，
 * 这样 ATRI 不再"缩在画面正中"，而是像 ChatWaifu 那样头肩贴满屏幕。
 *
 * 口型同步：基于 PCM 包络（每 ~50ms 一帧的 RMS）调用 nativeProjectMouthOpenY。
 * 简单但够用 —— 真正的 viseme 对齐需要 phoneme alignment，超过当前阶段范围。
 */
class Live2DController(
    private val context: Context,
    private val container: ViewGroup,
) {
    /** 每个角色的默认 (translateX, translateY, scale) —— 来自 ChatWaifu_Mobile. */
    data class ModelPreset(
        val dirAsset: String,        // 例如 "Live2DModels/ATRI/"
        val modelJson: String,       // 例如 "ATRI.model3.json"
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val scale: Float = 1f,
        val defaultExpression: String? = null,  // 加载后默认套用的表情名（model3.json 里的 Name）
    )

    private lateinit var glView: GLSurfaceView
    private var currentPreset: ModelPreset = PRESETS[DEFAULT_NAME]!!
    private var loaded = false

    /**
     * v0.5 Character Engine：当前加载模型声明的表情名列表（即 model3.json 里
     * Expressions[].Name）。native 每加载一个表情就通过 [onLoadOneExpression]
     * 回调一次；[switchModel] / [onCreate] 时先 clear。
     *
     * LlmChatActivity 用这个列表按当前模型动态注入到 systemPrompt，让 LLM 只从合法
     * 名字里挑，不至于瞎编。列表为空时（Ziyan/ATRI 之类没声明 exp3 的模型）
     * action 系统对该角色自动降级为关闭。
     */
    private val _availableExpressions = mutableListOf<String>()
    val availableExpressions: List<String>
        get() = synchronized(_availableExpressions) { _availableExpressions.toList() }

    /** 加载失败回调（自定义模型 moc3 版本过新/文件损坏时触发，Activity 用来弹错误提示）。 */
    var onLoadErrorListener: (() -> Unit)? = null

    /** v2 字幕设计需要：让 Activity 能拿到 GLSurfaceView 调 translationY/scaleY 把脸放屏幕 40% 高度。 */
    val view: GLSurfaceView? get() = if (loaded) glView else null

    fun onCreate(presetName: String = DEFAULT_NAME) {
        currentPreset = resolvePreset(presetName) ?: PRESETS[DEFAULT_NAME]!!
        JniBridgeJava.SetContext(context.applicationContext)
        // 不调 SetActivityInstance — 这里 context 不是 Activity 也能跑
        glView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            // 无坑版（来自 AvatarLive2DMini）：GL 表面透明 + 媒体 overlay，
            // 让角色透出到聊天背景之下、气泡浮于其上。缺这三行角色不可见。
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
            setRenderer(GLRenderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            // 关键：权限弹窗等短暂 pause 不能丢 EGL context（丢了要 native 端重 init
            // 纹理/shader，否则出黑屏）。true 让 context 跨 pause/resume 保留。
            preserveEGLContextOnPause = true
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> JniBridgeJava.nativeOnTouchesBegan(e.x, e.y)
                    MotionEvent.ACTION_MOVE -> JniBridgeJava.nativeOnTouchesMoved(e.x, e.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        JniBridgeJava.nativeOnTouchesEnded(e.x, e.y)
                }
                true
            }
        }
        container.addView(
            glView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        JniBridgeJava.nativeOnStart()
        JniBridgeJava.setLive2DLoadInterface(object : JniBridgeJava.Live2DLoadInterface {
            override fun onLoadError() {
                Log.e(TAG, "live2d onLoadError")
                onLoadErrorListener?.invoke()
            }
            override fun onLoadDone() {
                Log.i(TAG, "live2d onLoadDone, applying preset ${currentPreset.dirAsset}")
                glView.queueEvent {
                    JniBridgeJava.nativeProjectTransformX(currentPreset.translateX)
                    JniBridgeJava.nativeProjectTransformY(currentPreset.translateY)
                    JniBridgeJava.nativeProjectScale(currentPreset.scale)
                    currentPreset.defaultExpression?.let { JniBridgeJava.nativeApplyExpression(it) }
                }
            }
            override fun onLoadOneMotion(group: String, index: Int, name: String) {}
            override fun onLoadOneExpression(name: String, index: Int) {
                synchronized(_availableExpressions) { _availableExpressions.add(name) }
                Log.d(TAG, "onLoadOneExpression[$index] = $name")
            }
        })
        loaded = true
        // 内置模型在 APK assets 里，native 每读一个文件都走 JNI 解压，低端机冷启动很慢
        // （现象：只有背景、角色要等很久）。后台把内置模型解到 filesDir 缓存，
        // 之后一律从文件系统加载（native 对 "/" 开头的路径走 FileInputStream）。
        if (currentPreset.dirAsset.startsWith("/")) {
            loadCurrentModelFrom(currentPreset.dirAsset)
        } else {
            val preset = currentPreset
            Thread {
                loadCurrentModelFrom(ensureBuiltinModelDir(preset))
            }.apply { name = "Live2D-EnsureExtracted"; start() }
        }
    }

    /** 把模型加载事件投递到 GL 线程；任意线程可调用。 */
    private fun loadCurrentModelFrom(dirAsset: String) {
        glView.queueEvent {
            JniBridgeJava.needRenderBack(false)
            synchronized(_availableExpressions) { _availableExpressions.clear() }
            JniBridgeJava.nativeProjectChangeTo(dirAsset, currentPreset.modelJson)
            JniBridgeJava.nativeAutoBlinkEyes(true)
        }
    }

    /**
     * 内置模型解包：assets/Live2DModels/<name>/ → filesDir/live2d_builtin/<name>/。
     * 已解过（存在 .extracted 标记）直接返回磁盘路径；解包失败回退原 assets 路径。
     */
    private fun ensureBuiltinModelDir(preset: ModelPreset): String {
        val src = preset.dirAsset
        if (src.startsWith("/")) return src
        val name = src.trimEnd('/').substringAfterLast('/')
        val dest = File(context.filesDir, "live2d_builtin/$name")
        val marker = File(dest, ".extracted")
        if (!marker.exists()) {
            try {
                dest.mkdirs()
                copyAssetTree(src, dest)
                marker.writeText(preset.modelJson)
                Log.i(TAG, "builtin live2d extracted to $dest")
            } catch (t: Throwable) {
                Log.e(TAG, "builtin live2d extract failed, fallback to assets: $src", t)
                return src
            }
        }
        return dest.absolutePath + "/"
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val base = assetPath.trimEnd('/')
        val names = context.assets.list(assetPath) ?: return
        for (name in names) {
            // 统一去掉结尾斜杠再拼，避免 "Live2DModels/FenmaoLoli//exp" 双斜杠
            // 导致 AssetManager.open 抛 FileNotFoundException。
            val childAsset = "$base/$name"
            val childDest = File(dest, name)
            val children = context.assets.list(childAsset)
            if (children != null && children.isNotEmpty()) {
                childDest.mkdirs()
                copyAssetTree(childAsset, childDest)
            } else {
                childDest.parentFile?.mkdirs()
                context.assets.open(childAsset).use { ins ->
                    childDest.outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
        }
    }

    /** 切换模型（设置里选了别的角色时调）。安全在主线程调用。支持内置 key 或自定义模型名。 */
    fun switchModel(presetName: String) {
        val preset = resolvePreset(presetName) ?: return
        currentPreset = preset
        resetActingState()
        // 内置模型同样走磁盘缓存（首次切换会同步解包；标记已存在后很快）。
        val dir = if (preset.dirAsset.startsWith("/")) preset.dirAsset else ensureBuiltinModelDir(preset)
        glView.queueEvent {
            synchronized(_availableExpressions) { _availableExpressions.clear() }
            JniBridgeJava.nativeProjectChangeTo(dir, preset.modelJson)
        }
    }

    /**
     * v0.5 Character Engine：切一个表情（用于 LLM 通过 <action> 块驱动情绪/装扮）。
     * name 必须是 [availableExpressions] 里的合法项；无效名交给 native 自己忽略，
     * 上层要过滤请先自查。安全从任意线程调用（内部 queueEvent 到 GL 线程）。
     *
     * **注意**：这条是 emotion 通道的裸调用（M2 之前的老入口）。推荐用 [applyEmotion]
     * / [applyOutfit] 双通道，让 outfit 走 [JniBridgeJava.nativeApplyOutfit] 的独立
     * ExpressionManager，避免 emotion / outfit 参数冲突。
     */
    fun applyExpression(name: String) {
        if (!loaded) return
        Log.d(TAG, "applyExpression: $name")
        glView.queueEvent { JniBridgeJava.nativeApplyExpression(name) }
    }

    // --- v0.5 M2 双通道 state --------------------------------------------
    // native 侧现在有两条独立的 ExpressionManager：
    //   emotion  → nativeApplyExpression → _expressionManager
    //   outfit   → nativeApplyOutfit     → _outfitExpressionManager
    // 两条管线每帧各自 UpdateMotion，参数加性叠加，只要 exp3.json 选到互不冲突
    // 的参数集，outfit + emotion 就可以同帧显示。restoreIdle 只需要清 emotion
    // 通道，outfit 天然保留。

    /** 当前 outfit（跨轮次保留，用户或 LLM 显式切了才会变）。null=没穿。 */
    @Volatile private var stateOutfit: String? = null
    /** 当前 emotion（本轮 transient，回合结束回归 outfit / 默认）。 */
    @Volatile private var stateEmotion: String? = null

    /** 派发一个 emotion（transient）。不改 stateOutfit。走 emotion 通道。 */
    fun applyEmotion(name: String) {
        if (!loaded) return
        stateEmotion = name
        Log.d(TAG, "applyEmotion: $name (outfit stays=$stateOutfit)")
        glView.queueEvent { JniBridgeJava.nativeApplyExpression(name) }
    }

    /**
     * 派发一个 outfit（persistent）。走独立的 outfit 通道 —— 不会 fade 掉 emotion，
     * emotion 通道保留原状。这是 M2 相对 M1.1 的核心变化。
     */
    fun applyOutfit(name: String) {
        if (!loaded) return
        stateOutfit = name
        Log.d(TAG, "applyOutfit: $name (emotion stays=$stateEmotion)")
        glView.queueEvent { JniBridgeJava.nativeApplyOutfit(name) }
    }

    /** 停止 outfit 通道，模型参数自然回到底模默认服装。emotion 通道保持不变。 */
    fun clearOutfit() {
        if (!loaded) return
        stateOutfit = null
        Log.d(TAG, "clearOutfit (emotion stays=$stateEmotion)")
        glView.queueEvent { JniBridgeJava.nativeClearOutfit() }
    }

    /**
     * M2 双通道下的 restoreIdle：**只处理 emotion 通道**。
     * - emotion 层：清空到 defaultExpression（如 stareyes）或空
     * - outfit 层：不动，靠 native _outfitExpressionManager 自动保持
     *
     * 幂等；主线程/后台线程都可调（内部 queueEvent）。
     */
    fun restoreIdle() {
        if (!loaded) return
        stateEmotion = null
        val fallback = currentPreset.defaultExpression
        if (fallback == null) {
            Log.d(TAG, "restoreIdle: no defaultExpression → emotion cleared (outfit=$stateOutfit stays)")
            return
        }
        Log.d(TAG, "restoreIdle: emotion → $fallback (outfit=$stateOutfit stays)")
        glView.queueEvent { JniBridgeJava.nativeApplyExpression(fallback) }
    }

    /** 切模型时清 state（不同角色的 exp 名不通用）。 */
    private fun resetActingState() {
        stateOutfit = null
        stateEmotion = null
    }

    /**
     * 把角色 id 解析成 [ModelPreset]：
     * - 先查内置 [PRESETS]（dirAsset 是 assets 相对路径，如 "Live2DModels/Ziyan/"）；
     * - 否则当作自定义模型名，在 [customModelsDir] 下找同名子目录里的 `*.model3.json`，
     *   用**绝对路径**构造 preset（native `LoadFile` 认 `/` 开头就走文件系统）。
     * 自定义模型没有调好的构图，统一给默认值（scale=2 / ty=-0.3），后续可加手动校准。
     */
    fun resolvePreset(id: String): ModelPreset? {
        PRESETS[id]?.let { return it }
        val dir = File(customModelsDir(), id)
        val json = dir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.name.endsWith(".model3.json", true) }
            ?.firstOrNull() ?: return null
        return ModelPreset(
            dirAsset = dir.absolutePath + "/",
            modelJson = json.name,
            translateX = 0f,
            translateY = -0.3f,
            scale = 2f,
        )
    }

    /** 自定义模型根目录：应用外部私有目录，免存储权限，可 adb push 进去。 */
    fun customModelsDir(): File =
        File(context.getExternalFilesDir(null), "live2d").apply { mkdirs() }

    /** 扫描已导入的自定义模型：返回 (目录名/id, 显示名)。只列含 model3.json 的子目录。 */
    fun scanCustomModels(): List<Pair<String, String>> =
        customModelsDir().listFiles { f -> f.isDirectory }
            ?.filter { d -> d.listFiles { f -> f.name.endsWith(".model3.json", true) }?.isNotEmpty() == true }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.name to it.name }
            ?: emptyList()

    fun onResume() { if (loaded) glView.onResume() }
    fun onPause() { if (loaded) { glView.onPause(); JniBridgeJava.nativeOnPause() } }
    fun onStop() { if (loaded) JniBridgeJava.nativeOnStop() }
    fun onDestroy() { if (loaded) JniBridgeJava.nativeOnDestroy(); loaded = false }

    /**
     * 让 GLSurfaceView 短暂 GONE→VISIBLE 一次,强制表面按当前 z-order 重新组合。
     * 修 BottomSheetDialog 弹出后媒体 overlay surface 被压到不透明主窗后面、
     * 关掉弹窗角色仍消失、要重启才回来的老 SurfaceView 层级 bug（vivo/HyperOS 尤其明显）。
     * 主线程调用即可,和一次视图属性变更一样便宜。
     */
    fun nudge() {
        if (!loaded) return
        glView.post {
            glView.visibility = android.view.View.GONE
            glView.post { glView.visibility = android.view.View.VISIBLE }
        }
    }

    /**
     * 重新断言 media-overlay z-order：会重建 Surface，强制合成器按当前 UI 层重新合成。
     * 用于清掉深色背景 + 电话模式下残留的旧 UI 缓冲（“幽灵输入框”），比 [nudge] 更彻底。
     * 主线程调用。
     */
    fun reassertZOrder() {
        if (!loaded) return
        glView.post {
            glView.setZOrderMediaOverlay(false)
            glView.setZOrderMediaOverlay(true)
        }
    }

    /**
     * 把触摸事件喂给 native 驱动 Live2D 头部/眼球参数（不 consume，调用方应自己管理 dispatch）。
     * chat 模式时挂在 chatContainer / rvMessages 的 OnTouchListener 上，让 chat 区域触摸也能追手。
     */
    fun forwardTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> JniBridgeJava.nativeOnTouchesBegan(e.x, e.y)
            MotionEvent.ACTION_MOVE -> JniBridgeJava.nativeOnTouchesMoved(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                JniBridgeJava.nativeOnTouchesEnded(e.x, e.y)
        }
    }

    // --- lip sync ---------------------------------------------------------

    /**
     * 直接设置嘴张开度 [0,1]。由 SpeechQueue 的口型 ticker 按 AudioTrack 实际播放头
     * （playbackHeadPosition）驱动，所以嘴型跟真正听到的声音同步，而不是跟"写入缓冲"的时刻。
     */
    fun setMouthOpen(value: Float) {
        if (!loaded) return
        glView.queueEvent { JniBridgeJava.nativeProjectMouthOpenY(value.coerceIn(0f, 1f)) }
    }

    /** 没声音时回到闭嘴。 */
    fun closeMouth() {
        if (!loaded) return
        glView.queueEvent { JniBridgeJava.nativeProjectMouthOpenY(0f) }
    }

    // --- background ------------------------------------------------------

    /**
     * v2-月夜猫娘: GLSurfaceView 透明 surface 在某些 GPU 上不可用（Vivo / HyperOS
     * 强制 surface 不透明），所以背景改走 native 路径 —— 把 Bitmap 转成 ARGB int[]
     * 喂进 native，native 在 Render() 把它当全屏 quad 画在模型下面。
     *
     * 推荐用法：Activity 拿 room_default.png / 用户选图后调 [setBackgroundBitmap]。
     * [setBackground] 保留 Drawable 兼容入口供 v1 老代码用。
     */
    fun setBackgroundBitmap(bitmap: Bitmap?) {
        if (!loaded || bitmap == null) return
        glView.queueEvent {
            try {
                val argb = bitmapToArgb(bitmap)
                JniBridgeJava.nativeSetBackground(argb, bitmap.width, bitmap.height)
            } catch (e: Throwable) {
                Log.e(TAG, "setBackgroundBitmap failed", e)
            }
        }
    }

    /** Drawable 兼容入口：先转 Bitmap 再走 native 路径。 */
    fun setBackground(drawable: Drawable?) {
        if (!loaded || drawable == null) return
        val bmp = drawableToBitmap(drawable, 512, 512) ?: return
        setBackgroundBitmap(bmp)
    }

    private fun bitmapToArgb(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        // ARGB_8888: 每个像素 = 0xAARRGGBB（native 端会再 swap 成 GL RGBA 字节序）
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return pixels
    }

    private fun drawableToBitmap(drawable: Drawable, maxW: Int, maxH: Int): Bitmap? {
        val w = drawable.intrinsicWidth.coerceAtMost(maxW).coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtMost(maxH).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    companion object {
        private const val TAG = "MoeAvatar.Live2D"

        const val DEFAULT_NAME = "FenmaoLoli"

        /** 设置里可切换的角色（内部 key → 对外显示名），仅列已打包资源的角色。 */
        val SWITCHABLE: List<Pair<String, String>> = listOf(
            "FenmaoLoli" to "Fenmao",
        )

        // 内测包只内置 Fenmao；其他角色仍可通过自定义 ZIP 导入。
        val PRESETS: Map<String, ModelPreset> = mapOf(
            "FenmaoLoli" to ModelPreset(
                dirAsset = "Live2DModels/FenmaoLoli/",
                modelJson = "fenmaololi.model3.json",
                translateX = 0f,
                translateY = -0.3f,
                scale = 2f,
                defaultExpression = "stareyes",
            ),
        )
    }
}

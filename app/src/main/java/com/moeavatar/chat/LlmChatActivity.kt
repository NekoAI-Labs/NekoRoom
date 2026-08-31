package com.moeavatar.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.materialswitch.MaterialSwitch
import com.moeavatar.R
import com.moeavatar.chat.subtitle.SubtitleManager
import com.moeavatar.live2d.CharacterStateManager
import com.moeavatar.live2d.Live2DController
import com.moeavatar.llm.LlmBackend
import com.moeavatar.llm.ChatTurn
import com.moeavatar.llm.LlmConfig
import com.moeavatar.llm.LocalLlmBackend
import com.moeavatar.llm.OpenAiLlmBackend
import com.moeavatar.llm.OnlineConversationHistory
import com.moeavatar.llm.ProviderPreset
import com.moeavatar.llm.ProviderRegistry
import com.moeavatar.llm.uiSummary
import com.moeavatar.llm.ApiOutcome
import com.moeavatar.model.ModelManager
import com.moeavatar.model.Capability
import com.moeavatar.model.NekoModel
import com.moeavatar.model.ModelScopeDownloader
import com.moeavatar.perf.PerformanceTrace
import com.moeavatar.perf.PerformanceDeviceInfo
import com.moeavatar.perf.PerformanceRecord
import com.moeavatar.perf.PerformanceRecordStore
import com.moeavatar.safety.ContentSafetyGuard
import com.moeavatar.safety.ContentSafetyRuleLoader
import com.moeavatar.tts.MiniMaxStreamTtsBackend
import com.moeavatar.tts.SentenceSplitter
import com.moeavatar.tts.SpeechQueue
import com.moeavatar.tts.SuperTonicTtsBackend
import com.moeavatar.tts.TtsBackend
import com.moeavatar.voiceinteraction.ConversationCoordinator
import com.moeavatar.voiceinteraction.InputMode
import com.moeavatar.voiceinteraction.InputSource
import com.moeavatar.voiceinteraction.InterruptReason
import com.moeavatar.voiceinteraction.VoiceInputController
import com.moeavatar.voiceinteraction.VoiceInteractionState
import com.moeavatar.voiceinteraction.VoiceWaveformView
import com.moeavatar.phonecall.CallPulseView
import com.moeavatar.phonecall.PhoneCallController
import com.moeavatar.vad.FireRedVadBridge
import com.hzy.libp7zip.P7ZipApi
import java.io.File
import java.util.zip.ZipInputStream
import com.moeavatar.tts.TtsTextFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 猫娘聊天（v2「月夜猫娘」UI）：
 * - 角色 60% / 字幕 15% / 用户 chip 5% / 输入 20%
 * - 背景图：默认 assets/room_default.png + #252235 45% 蒙版，用户可换图
 * - 字幕：AI/用户共享；AI 语句在对应音频写入前完整上屏，播放结束后淡出
 * - 输入：EditText(主) + mic 小图标(右长按 ASR)，文字非空时显示 send 按钮
 * - 性能小字：tv_subtitle_perf（右下角 #AFA6BD）
 */
class LlmChatActivity : AppCompatActivity() {

    // --- views ---
    private lateinit var rootContainer: View
    private lateinit var ivBackground: ImageView
    private lateinit var live2dContainer: FrameLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var tvPerf: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var llInputBar: LinearLayout
    private lateinit var voiceInputArea: FrameLayout
    private lateinit var editInput: EditText
    private lateinit var tvVoicePrompt: TextView
    private lateinit var tvVoiceGestureHint: TextView
    private lateinit var voiceWaveform: VoiceWaveformView
    private lateinit var btnMic: ImageButton
    private lateinit var btnSend: ImageButton
    private lateinit var aiDisclosureOverlay: View
    private lateinit var btnAiDisclosureConfirm: Button
    private lateinit var phoneCallControls: LinearLayout
    private lateinit var callPulse: CallPulseView
    private lateinit var tvCallStatus: TextView
    private lateinit var btnCallInterrupt: ImageButton
    private lateinit var btnCallMute: ImageButton
    private lateinit var btnCallHangup: ImageButton

    private lateinit var config: LlmConfig
    private lateinit var performanceRecords: PerformanceRecordStore

    @Volatile private var ttsBackend: TtsBackend? = null
    private var localTtsBackend: SuperTonicTtsBackend? = null
    private var ttsReady = false

    private lateinit var speechQueue: SpeechQueue
    private var backend: LlmBackend? = null
    private var chatJob: Job? = null
    private var conversationBoundaryJob: Job? = null
    private var conversationTurnId = 0L
    private val onlineConversationHistory = OnlineConversationHistory(maxRounds = 15)
    private lateinit var contentSafetyGuard: ContentSafetyGuard

    private lateinit var live2d: Live2DController
    private lateinit var characterState: CharacterStateManager
    private lateinit var subtitleManager: SubtitleManager

    private lateinit var asr: ChatAsrController
    @Volatile private var asrReady = false
    @Volatile private var asrLoading = true
    @Volatile private var bootstrapDone = false
    private var asrStatus: String = "语音输入加载中"
    private var lastBaseStatus: String = ""

    // --- 能力中心下载状态（model.id -> …）。stop 由 UI 线程写、IO 线程读，用 Concurrent。 ---
    private val capStop = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val capBusy = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var capCards: List<CapCard> = emptyList()
    private var capOverview: TextView? = null
    private var capDialog: BottomSheetDialog? = null
    private var voicePromptShown = false   // 「只有文字回复」引导每会话只弹一次
    @Volatile private var generating = false
    @Volatile private var live2dViewYOffset: Float = 0f
    @Volatile private var live2dViewScale: Float = 1f

    private lateinit var conversationCoordinator: ConversationCoordinator
    private lateinit var voiceInputController: VoiceInputController
    private lateinit var phoneCallController: PhoneCallController
    private var fireRedVad: FireRedVadBridge? = null
    private var voiceTouchDownY = 0f
    private var lastCapsuleBackground = 0
    /** 键盘（IME）当前占据的底部像素；insets 监听器维护，用于恢复输入条时保持上浮。 */
    private var lastImeBottomPx = 0
    private var imeWasVisible = false
    private var inputBarParent: ViewGroup? = null
    private var inputBarOriginalIndex = -1
    private var inputBarDetached = false

    private val pickBackgroundLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) applyBackgroundFromUri(uri)
        }

    /** 导入自定义 Live2D 模型压缩包。picker 返回 content uri，交给 importModelArchive 解压+校验。 */
    private val importModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importModelArchive(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_llm_chat)

        rootContainer = findViewById(R.id.root_container)
        ivBackground = findViewById(R.id.iv_background)
        live2dContainer = findViewById(R.id.live2d_container)
        tvSubtitle = findViewById(R.id.tv_subtitle)
        tvPerf = findViewById(R.id.tv_perf)
        btnSettings = findViewById(R.id.btn_settings)
        llInputBar = findViewById(R.id.ll_input_bar)
        voiceInputArea = findViewById(R.id.voice_input_area)
        editInput = findViewById(R.id.edit_input)
        tvVoicePrompt = findViewById(R.id.tv_voice_prompt)
        tvVoiceGestureHint = findViewById(R.id.tv_voice_gesture_hint)
        voiceWaveform = findViewById(R.id.voice_waveform)
        btnMic = findViewById(R.id.btn_mic)
        btnSend = findViewById(R.id.btn_send)
        aiDisclosureOverlay = findViewById(R.id.ai_disclosure_overlay)
        btnAiDisclosureConfirm = findViewById(R.id.btn_ai_disclosure_confirm)
        phoneCallControls = findViewById(R.id.phone_call_controls)
        callPulse = findViewById(R.id.call_pulse)
        tvCallStatus = findViewById(R.id.tv_call_status)
        btnCallInterrupt = findViewById(R.id.btn_call_interrupt)
        btnCallMute = findViewById(R.id.btn_call_mute)
        btnCallHangup = findViewById(R.id.btn_call_hangup)

        asr = ChatAsrController(applicationContext)
        config = LlmConfig(this)
        ModelManager.prepareTtsDirectory(this)
        performanceRecords = PerformanceRecordStore(this)
        PerformanceTrace.enabled = config.devMode && config.performanceLogsEnabled
        contentSafetyGuard = ContentSafetyRuleLoader.load(this)
        subtitleManager = SubtitleManager(tvSubtitle)
        conversationCoordinator = ConversationCoordinator()

        hideSystemBars()
        // 键盘弹出时，只把胶囊输入栏上浮到键盘上方（Live2D 全屏不动）。
        // 键盘收起时回到语音模式：保证"默认长按语音"始终可用；打字过程中
        // 输入条靠 translationY=-ime 保持上浮，不会掉到键盘底下。
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastImeBottomPx = ime
            llInputBar.translationY = -ime.toFloat()
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime > 0
            if (imeWasVisible && !imeVisible) {
                rootContainer.post {
                    val state = conversationCoordinator.state.value
                    if (state is VoiceInteractionState.Idle && state.mode == InputMode.TEXT) {
                        enterVoiceMode()
                    }
                }
            }
            imeWasVisible = imeVisible
            insets
        }

        speechQueue = SpeechQueue(EmptyTtsBackend, speakerProvider = {
            if (config.ttsBackendKind == LlmConfig.TtsBackendKind.MINIMAX) config.minimaxVoiceId
            else config.ttsSpeaker
        })
        speechQueue.start()

        live2d = Live2DController(this, live2dContainer)
        live2d.onLoadErrorListener = { runOnUiThread { onLive2dLoadError() } }
        live2d.onCreate(presetName = config.live2dModel)
        characterState = CharacterStateManager(object : CharacterStateManager.Driver {
            override fun nativeOutfit(name: String) = live2d.applyOutfit(name)
            override fun nativeClearOutfit() = live2d.clearOutfit()
            override fun nativeEmotion(name: String) = live2d.applyEmotion(name)
            override fun nativeClearEmotion() = live2d.restoreIdle()
            override fun availableExpressions(): List<String> = live2d.availableExpressions
        }).also { it.onCharacterChanged(config.live2dModel) }
        speechQueue.setMouthListener { mouth -> live2d.setMouthOpen(mouth) }
        speechQueue.setOnPlaybackStateChange { playing ->
            runOnUiThread {
                if (playing) conversationCoordinator.markSpeaking()
                else if (generating) conversationCoordinator.markThinkingCurrent()
                if (::phoneCallController.isInitialized && phoneCallControls.visibility == View.VISIBLE && playing) {
                    phoneCallController.onAiStarted()
                }
            }
        }
        speechQueue.setOnPlaybackExhausted {
            runOnUiThread {
                live2d.closeMouth()
                subtitleManager.finishAi()
                updatePerfLine()
                // v0.5 M2：TTS 真正讲完再清 emotion；outfit 通道由 native 独立
                // ExpressionManager 保留，不需要 re-apply。
                characterState.clearEmotion()
                if (generating) conversationCoordinator.markThinkingCurrent()
                else conversationCoordinator.markResponseComplete()
                if (::phoneCallController.isInitialized && phoneCallControls.visibility == View.VISIBLE) {
                    phoneCallController.onAiFinished()
                }
            }
        }
        speechQueue.setOnClauseStart { clause ->
            // withContext 是字幕首帧屏障：执行完成后 SpeechQueue 才会写 AudioTrack。
            withContext(Dispatchers.Main.immediate) {
                if (config.showAiSubtitle) subtitleManager.showAiClause(clause)
            }
        }
        applyDefaultBackground()
        applyLive2dYOffset()

        voiceInputController = VoiceInputController(
            asr = asr,
            scope = lifecycleScope,
            coordinator = conversationCoordinator,
            beforeListening = { prepareForVoiceCapture() },
            onFinalText = { submitMessage(it, InputSource.VOICE) },
            onEmpty = {
                Toast.makeText(this, "没有听清，再和猫娘说一次吧", Toast.LENGTH_SHORT).show()
            },
            onStartFailed = {
                Toast.makeText(this, "语音输入启动失败，请重试", Toast.LENGTH_SHORT).show()
            },
        )
        fireRedVad = FireRedVadBridge.open(this)
        phoneCallController = PhoneCallController(
            asr = asr,
            scope = lifecycleScope,
            onUtterance = { submitMessage(it, InputSource.VOICE) },
            onBargeIn = { stopGeneration() },
            isAiOutputActive = { generating || speechQueue.hasPendingWork() },
            vad = fireRedVad,
        )

        editInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderVoiceInteraction(conversationCoordinator.state.value)
            }
        })

        btnSend.setOnClickListener {
            when (conversationCoordinator.state.value) {
                is VoiceInteractionState.Thinking,
                is VoiceInteractionState.Speaking -> stopGeneration()
                is VoiceInteractionState.Idle -> {
                    if (conversationCoordinator.state.value.mode == InputMode.VOICE) enterTextMode()
                    else sendCurrentInput()
                }
                else -> Unit
            }
        }

        btnMic.setOnClickListener {
            if (phoneCallControls.visibility == View.VISIBLE) return@setOnClickListener
            if (!ensureVoiceInputAvailable()) return@setOnClickListener
            beginModeConversationBoundary()
            speechQueue.setPhoneCallAudioMode(true)
            setPhoneControlsVisible(true)
            phoneCallController.enter()
        }

        btnCallMute.setOnClickListener {
            val muted = phoneCallController.state.value !is PhoneCallController.State.Muted
            phoneCallController.setMuted(muted)
        }
        btnCallHangup.setOnClickListener {
            beginModeConversationBoundary()
            phoneCallController.hangup()
            speechQueue.setPhoneCallAudioMode(false)
            setPhoneControlsVisible(false)
        }
        btnCallInterrupt.setOnClickListener { phoneCallController.interrupt() }

        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else false
        }

        btnSettings.setOnClickListener { showSettings() }

        val voiceTouchListener = View.OnTouchListener { view, event -> handleVoiceTouch(view, event) }
        voiceInputArea.setOnTouchListener(voiceTouchListener)

        lifecycleScope.launch {
            conversationCoordinator.state.collect { renderVoiceInteraction(it) }
        }
        lifecycleScope.launch {
            phoneCallController.state.collect { state ->
                val mode = when (state) {
                    is PhoneCallController.State.Hearing -> CallPulseView.Mode.HEARING
                    is PhoneCallController.State.Speaking -> CallPulseView.Mode.SPEAKING
                    is PhoneCallController.State.Muted, is PhoneCallController.State.Off -> CallPulseView.Mode.HIDDEN
                    else -> CallPulseView.Mode.WAITING
                }
                btnCallInterrupt.visibility = if (state is PhoneCallController.State.Speaking) View.VISIBLE else View.GONE
                callPulse.visibility = if (mode == CallPulseView.Mode.HIDDEN) View.GONE else View.VISIBLE
                callPulse.update((state as? PhoneCallController.State.Hearing)?.level ?: 0f, mode)
                tvCallStatus.visibility = if (phoneCallControls.visibility == View.VISIBLE && state !is PhoneCallController.State.Off) View.VISIBLE else View.GONE
                tvCallStatus.text = when (state) {
                    is PhoneCallController.State.Listening -> "正在聆听…"
                    is PhoneCallController.State.Hearing -> "嗯嗯，我在听哦…"
                    is PhoneCallController.State.Understanding -> "让我想想喵…"
                    is PhoneCallController.State.Speaking -> "正在回应…"
                    is PhoneCallController.State.Settling -> "正在收尾…"
                    is PhoneCallController.State.Muted -> "麦克风已关闭"
                    is PhoneCallController.State.Error -> state.message
                    else -> ""
                }
                btnCallMute.imageTintList = ContextCompat.getColorStateList(this@LlmChatActivity,
                    if (state is PhoneCallController.State.Muted) R.color.error_red else R.color.text_primary)
            }
        }

        // Live2D 视线跟随：把整个 root_container 上半部分触摸转发给 native。
        // 字幕/输入条都是 view，Android 自身事件分发不冲突。
        rootContainer.setOnTouchListener { _, ev ->
            // 不 consume，让子 view 正常处理（EditText 拿 IME、按钮拿 click、ScrollView 拿 scroll）
            live2d.forwardTouch(ev)
            false
        }

        bootstrap()
    }

    /** The call controls replace the capsule at the exact same bottom position. */
    /**
     * tv_perf 锚定在 ll_input_bar 上；电话模式 detach 输入条后约束失效会掉到左上角
     * 且被状态栏截断。进电话模式时改锚父容器右下角，退出时恢复。
     */
    private fun anchorPerfLine(anchorToBar: Boolean) {
        val root = rootContainer as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val d = resources.displayMetrics.density
        val cs = androidx.constraintlayout.widget.ConstraintSet()
        cs.clone(root)
        // 先清掉 tv_perf 现有的所有水平/垂直锚，再用 ConstraintSet 重新连接。
        // 直接改 LayoutParams 在部分 ConstraintLayout 版本/时序下不生效（实测电话模式仍在左边）。
        cs.clear(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.START)
        cs.clear(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.LEFT)
        cs.clear(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.END)
        cs.clear(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.RIGHT)
        cs.clear(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.TOP)
        if (anchorToBar) {
            cs.connect(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.END, R.id.ll_input_bar, androidx.constraintlayout.widget.ConstraintSet.END, (24 * d).toInt())
            cs.connect(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, R.id.ll_input_bar, androidx.constraintlayout.widget.ConstraintSet.TOP, (6 * d).toInt())
        } else {
            cs.connect(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END, (24 * d).toInt())
            // 电话控件胶囊：bottom 20dp + 高 56dp → 顶部在 76dp，性能行放它上方 8dp。
            cs.connect(R.id.tv_perf, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, (84 * d).toInt())
        }
        cs.setHorizontalBias(R.id.tv_perf, 1f)
        cs.applyTo(root)
        Log.i(TAG, "anchorPerfLine toBar=$anchorToBar (end→${if (anchorToBar) "ll_input_bar" else "parent"}, bottom→${if (anchorToBar) "ll_input_bar.top" else "parent.bottom"})")
    }

    private fun setPhoneControlsVisible(visible: Boolean) {
        phoneCallControls.visibility = if (visible) View.VISIBLE else View.GONE
        setNormalInputBarVisible(!visible)
        tvVoiceGestureHint.visibility = View.GONE
        tvCallStatus.visibility = View.GONE
        if (visible) {
            // 电话模式保留共享字幕：用户原话 / AI 回复仍走 tv_subtitle 上屏。
            // 字幕条自身没有 elevation，不会产生输入胶囊那种残影；只确保高度场为 0。
            tvSubtitle.elevation = 0f
            // 性能行改锚右下角，避免因输入条 detach 而掉到左上角。
            anchorPerfLine(anchorToBar = false)
        } else {
            // SubtitleManager 需要一个可见的宿主来显示下一条字幕；保持 alpha=0，
            // 因此不会在退出通话的瞬间闪出空白字幕条。
            tvSubtitle.clearAnimation()
            tvSubtitle.translationX = 0f
            tvSubtitle.translationY = 0f
            tvSubtitle.elevation = 0f
            tvSubtitle.setBackgroundResource(R.drawable.bg_subtitle_strip)
            tvSubtitle.alpha = 0f
            tvSubtitle.visibility = View.VISIBLE
            anchorPerfLine(anchorToBar = true)
        }
        // 让 ConstraintLayout 和硬件合成器在同一帧确认旧的 elevated RenderNode 已撤销；
        // 部分机型在 SurfaceView 存在时还需要下一帧再次提交一次空的 UI 层。
        rootContainer.requestLayout()
        rootContainer.invalidate()
        window.decorView.invalidate()
        val flushGhostLayers = {
            rootContainer.invalidate()
            window.decorView.invalidate()
            if (::live2d.isInitialized) {
                // uiautomator 已确认电话模式下 ll_input_bar 不在 View Tree；若屏幕仍显示
                // 胶囊，只能是 GLSurfaceView 的 media-overlay 保留了旧合成缓冲。
                // nudge 重建 Surface；reassertZOrder 重新断言 z-order，连表面都重建，
                // 对深色背景下“幽灵输入框”顽固残留的机型更彻底。
                live2d.nudge()
                live2d.reassertZOrder()
            }
        }
        rootContainer.post { flushGhostLayers() }
        // 部分机型（深色背景 + SurfaceView）要等合成器多提交一帧才撤销旧的
        // elevated RenderNode，延后一拍再 invalidate + nudge 一次，把残留
        // “幽灵输入框”彻底刷掉。
        rootContainer.postDelayed({ flushGhostLayers() }, 150L)
    }

    /**
     * The normal frosted capsule has elevation and can leave a stale hardware-composed frame on
     * some devices when it is replaced by phone controls. Detach it from the hierarchy rather
     * than relying on GONE alone, then restore it at its original z-order after hanging up.
     */
    private fun setNormalInputBarVisible(visible: Boolean) {
        llInputBar.clearAnimation()
        if (visible) {
            if (inputBarDetached) {
                val parent = inputBarParent
                if (parent != null) {
                    parent.addView(llInputBar, inputBarOriginalIndex.coerceIn(0, parent.childCount))
                }
                inputBarDetached = false
                inputBarParent = null
                inputBarOriginalIndex = -1
            }
            llInputBar.translationX = 0f
            // 恢复时保持键盘上浮量，不能清 0：否则键盘还开着时输入条会掉到屏幕底部被盖住。
            llInputBar.translationY = -lastImeBottomPx.toFloat()
            llInputBar.elevation = dp(8).toFloat()
            // 恢复隐藏时被摘掉的阴影 outline（背景变化时会重新按背景生成轮廓）。
            llInputBar.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND)
            llInputBar.setLayerType(View.LAYER_TYPE_NONE, null)
            llInputBar.setBackgroundResource(R.drawable.bg_capsule_input)
            llInputBar.alpha = 1f
            llInputBar.visibility = View.VISIBLE
            lastCapsuleBackground = 0
        } else {
            if (!inputBarDetached) {
                val parent = llInputBar.parent as? ViewGroup
                if (parent != null) {
                    inputBarParent = parent
                    inputBarOriginalIndex = parent.indexOfChild(llInputBar)
                    parent.removeView(llInputBar)
                    inputBarDetached = true
                }
            }
            llInputBar.translationX = 0f
            llInputBar.translationY = 0f
            // 先撤销 elevation，再 detach。否则 RenderNode 的阴影可能被 SurfaceView
            // 合成器保留到下一次完整重绘，黑色背景下会像“幽灵输入框”。
            llInputBar.elevation = 0f
            // 摘掉阴影 outline 并清掉任何视图缓存层，强制走主合成路径。
            llInputBar.setOutlineProvider(null)
            llInputBar.setLayerType(View.LAYER_TYPE_NONE, null)
            llInputBar.alpha = 0f
            llInputBar.background = null
            llInputBar.visibility = View.GONE
            lastCapsuleBackground = 0
        }
        llInputBar.invalidate()
        rootContainer.invalidate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, rootContainer).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- settings ---------------------------------------------------------

    private fun showSettings() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)

        val switchSub = view.findViewById<MaterialSwitch>(R.id.switch_ai_subtitle)
        switchSub.isChecked = config.showAiSubtitle
        switchSub.setOnCheckedChangeListener { _, checked -> config.showAiSubtitle = checked }

        val switchDeveloper = view.findViewById<MaterialSwitch>(R.id.switch_developer_mode)
        val developerOptions = view.findViewById<LinearLayout>(R.id.developer_options)
        val switchOverlay = view.findViewById<MaterialSwitch>(R.id.switch_perf_overlay)
        val switchLogs = view.findViewById<MaterialSwitch>(R.id.switch_perf_logs)
        val switchLiveActing = view.findViewById<MaterialSwitch>(R.id.switch_live_acting)
        val switchLocalMultiTurn = view.findViewById<MaterialSwitch>(R.id.switch_local_multi_turn)
        val ttsStepsLabel = view.findViewById<TextView>(R.id.tv_tts_steps)
        val ttsStepsSeek = view.findViewById<SeekBar>(R.id.seek_tts_steps)

        switchDeveloper.isChecked = config.devMode
        developerOptions.visibility = if (config.devMode) View.VISIBLE else View.GONE
        switchOverlay.isChecked = config.showPerfLine
        switchLogs.isChecked = config.performanceLogsEnabled
        switchLiveActing.isChecked = config.liveActingEnabled
        switchLocalMultiTurn.isChecked = config.localMultiTurn
        fun renderTtsSteps() { ttsStepsLabel.text = "TTS Quality：${config.ttsSteps}（3–8，默认 6）" }
        ttsStepsSeek.progress = config.ttsSteps - 3
        renderTtsSteps()
        ttsStepsSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.ttsSteps = progress + 3
                renderTtsSteps()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        view.findViewById<TextView>(R.id.row_performance_panel).setOnClickListener {
            dialog.dismiss()
            showPerformancePanel()
        }
        switchDeveloper.setOnCheckedChangeListener { _, checked ->
            config.devMode = checked
            developerOptions.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) {
                config.showPerfLine = false
                config.performanceLogsEnabled = false
                tvPerf.visibility = View.GONE
                tvPerf.text = ""
            }
            syncPerformanceTracing()
        }
        switchOverlay.setOnCheckedChangeListener { _, checked ->
            config.showPerfLine = checked
            if (!checked) { tvPerf.visibility = View.GONE; tvPerf.text = "" }
        }
        switchLogs.setOnCheckedChangeListener { _, checked ->
            config.performanceLogsEnabled = checked
            syncPerformanceTracing()
        }
        switchLiveActing.setOnCheckedChangeListener { _, checked ->
            config.liveActingEnabled = checked
        }
        switchLocalMultiTurn.setOnCheckedChangeListener { _, checked ->
            config.localMultiTurn = checked
        }

        view.findViewById<TextView>(R.id.row_switch_avatar).setOnClickListener {
            dialog.dismiss()
            showAvatarPicker()
        }
        view.findViewById<TextView>(R.id.row_background).setOnClickListener {
            dialog.dismiss()
            pickBackgroundLauncher.launch("image/*")
        }
        view.findViewById<TextView>(R.id.row_capability_center).setOnClickListener {
            dialog.dismiss()
            showCapabilityCenter()
        }
        view.findViewById<TextView>(R.id.row_online_config).setOnClickListener {
            dialog.dismiss()
            showOnlineConfig()
        }
        view.findViewById<TextView>(R.id.row_reset).setOnClickListener {
            dialog.dismiss()
            clearChat()
        }
        view.findViewById<TextView>(R.id.tv_model_info).text = when {
            config.backendKind == LlmConfig.BackendKind.OPENAI -> "大脑：在线服务"
            ModelManager.isInstalled(this, Capability.LLM) -> "大脑：本地大脑（离线）"
            else -> "大脑：未安装"
        }

        dialog.behavior.skipCollapsed = true
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun showPerformancePanel() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_performance, null)
        val deviceText = runCatching { PerformanceDeviceInfo.collect().display() }
            .getOrElse { "设备信息暂不可用\n${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        view.findViewById<TextView>(R.id.tv_perf_device).text = deviceText
        val status = view.findViewById<TextView>(R.id.tv_perf_status)
        val recent = view.findViewById<TextView>(R.id.tv_perf_recent)
        if (!config.performanceLogsEnabled) {
            status.text = "性能记录：未开启\n在开发者模式中开启后，这里显示最近数据的平均结果。"
            recent.text = ""
        } else {
            val records = performanceRecords.recent()
            status.text = "性能记录：已开启"
            recent.text = if (records.isEmpty()) "暂无平均数据，完成一次 LLM/TTS 对话后显示。"
            else performanceRecords.summary(records)
        }
        view.findViewById<TextView>(R.id.btn_copy_perf).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("NekoChat Performance", buildPerformanceCopyText())
            )
            Toast.makeText(this, "已复制性能参数", Toast.LENGTH_SHORT).show()
        }
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun buildPerformanceCopyText(): String = buildString {
        append("NekoChat Performance\n\nLLM/TTS:\n")
        append(if (config.performanceLogsEnabled) {
            performanceRecords.summary().ifBlank { "暂无性能数据" }
        } else "性能记录未开启")
        append("\n\nDevice:\n")
        append(runCatching { PerformanceDeviceInfo.collect().display() }
            .getOrElse { "设备信息暂不可用: ${it.message.orEmpty()}" })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styledModelAdapter(labels: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, 0, labels) {
            private fun row(): TextView = TextView(this@LlmChatActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_primary, theme))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minHeight = dp(40)
                // Spinner 自己负责选中态外框；行视图保持透明，避免出现“框中套框”。
                setBackgroundColor(0x00000000)
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                row().apply { text = getItem(position).orEmpty() }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                row().apply { text = getItem(position).orEmpty() }
        }

    /** Runtime trace switches; SME2 itself is selected when building libMNN.so. */
    private fun syncPerformanceTracing() {
        val enabled = config.devMode && config.performanceLogsEnabled
        PerformanceTrace.enabled = enabled
        localTtsBackend?.setPerformanceLogging(enabled)
        PerformanceTrace.i("config", "enabled=$enabled sme2=compile-time-auto")
    }

    /**
     * 在线模型 / 语音配置：LLM 后端（本地 / 在线 API）+ base/key/model + 猫娘提示词，
     * TTS 后端（本地 SuperTonic / MiniMax）+ key/voice。保存即持久化并热切换后端。
     * 提示词只在在线 LLM 生效（本地后端忽略）。
     */
    private fun showOnlineConfig() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_online_config, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val rgLlm = view.findViewById<RadioGroup>(R.id.rg_backend)
        val spProvider = view.findViewById<Spinner>(R.id.sp_provider)
        val etBase = view.findViewById<EditText>(R.id.et_oai_base)
        val etKey = view.findViewById<EditText>(R.id.et_oai_key)
        val etModel = view.findViewById<EditText>(R.id.et_oai_model)
        val btnTest = view.findViewById<Button>(R.id.btn_test_conn)
        val tvTestResult = view.findViewById<TextView>(R.id.tv_test_result)
        val tvAdvanced = view.findViewById<TextView>(R.id.tv_advanced_toggle)
        val lyAdvanced = view.findViewById<LinearLayout>(R.id.ly_advanced)
        val swThinking = view.findViewById<SwitchCompat>(R.id.sw_thinking)
        val skTemp = view.findViewById<SeekBar>(R.id.sk_temperature)
        val tvTemp = view.findViewById<TextView>(R.id.tv_temperature)
        val skTopP = view.findViewById<SeekBar>(R.id.sk_top_p)
        val tvTopP = view.findViewById<TextView>(R.id.tv_top_p)
        val skMaxTokens = view.findViewById<SeekBar>(R.id.sk_max_tokens)
        val tvMaxTokens = view.findViewById<TextView>(R.id.tv_max_tokens)
        val etSys = view.findViewById<EditText>(R.id.et_sys_prompt)
        val rgTts = view.findViewById<RadioGroup>(R.id.rg_tts_backend)
        val etMmKey = view.findViewById<EditText>(R.id.et_minimax_key)
        val etMmVoice = view.findViewById<EditText>(R.id.et_minimax_voice)

        rgLlm.check(if (config.backendKind == LlmConfig.BackendKind.OPENAI) R.id.rb_openai else R.id.rb_local)
        etBase.setText(config.openAiBaseUrl)
        etKey.setText(config.openAiApiKey)
        etModel.setText(config.openAiModel)
        etSys.setText(config.systemPrompt)
        rgTts.check(if (config.ttsBackendKind == LlmConfig.TtsBackendKind.MINIMAX) R.id.rb_tts_minimax else R.id.rb_tts_local)
        etMmKey.setText(config.minimaxApiKey)
        etMmVoice.setText(config.minimaxVoiceId)

        // Provider 下拉：使用统一深色圆角适配器，避免系统 simple_spinner 的白色弹出菜单。
        val presets = ProviderRegistry.PRESETS
        val adapter = styledModelAdapter(presets.map { it.displayName })
        spProvider.adapter = adapter
        val curIdx = ProviderRegistry.indexOf(config.providerPresetId)
        // 监听器先注册，再设置初始位置；初始化期间可能触发多次回调，不能吞掉用户第一次选择。
        var initializingProvider = true
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (initializingProvider) return
                val p = presets[position]
                if (p.id != "custom") {
                    etBase.setText(p.baseUrl)
                    etModel.setText(p.defaultModel)
                    rgLlm.check(R.id.rb_openai)
                }
                // 切换 provider 后旧的测试结果不再有效
                tvTestResult.visibility = View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spProvider.setSelection(curIdx, false)
        // 确保初始回调处理完后，下一次才是用户真实选择。
        spProvider.post { initializingProvider = false }

        // 高级折叠区。已改过温度 / 开启思考 → 默认展开。
        val thinkingOn = config.enableThinking
        // 0 会让部分 sampler 退化为近似贪婪采样，量化小模型尤其容易复读；UI 从 0.10 起。
        val tempInit = config.temperature.coerceIn(0.1f, 2f)
        val topPInit = config.topP
        val maxTokensInit = config.maxOutputTokens
        val startExpanded = thinkingOn || kotlin.math.abs(tempInit - 0.7f) > 1e-3f ||
            kotlin.math.abs(topPInit - 0.9f) > 1e-3f || maxTokensInit != 80
        lyAdvanced.visibility = if (startExpanded) View.VISIBLE else View.GONE
        tvAdvanced.text = if (startExpanded) "▾ 高级" else "▸ 高级"
        tvAdvanced.setOnClickListener {
            val expand = lyAdvanced.visibility != View.VISIBLE
            lyAdvanced.visibility = if (expand) View.VISIBLE else View.GONE
            tvAdvanced.text = if (expand) "▾ 高级" else "▸ 高级"
        }
        swThinking.isChecked = thinkingOn
        skTemp.progress = ((tempInit - 0.1f) * 100).toInt().coerceIn(0, 190)
        tvTemp.text = String.format("%.2f", tempInit)
        skTemp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTemp.text = String.format("%.2f", 0.1f + progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        skTopP.progress = ((topPInit - 0.1f) * 100).toInt().coerceIn(0, 90)
        tvTopP.text = String.format("%.2f", topPInit)
        skTopP.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTopP.text = String.format("%.2f", 0.1f + progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        skMaxTokens.progress = (maxTokensInit - 16).coerceIn(0, 496)
        tvMaxTokens.text = maxTokensInit.toString()
        skMaxTokens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMaxTokens.text = (progress + 16).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 测试连通性：按钮文本不变，结果显示在下方独立 TextView。
        btnTest.setOnClickListener {
            val base = etBase.text.toString().trim()
            val key = etKey.text.toString().trim()
            val modelName = etModel.text.toString().trim()
            val preset = presets[spProvider.selectedItemPosition]
            val temp = 0.1f + skTemp.progress / 100f
            val thinking = swThinking.isChecked

            btnTest.isEnabled = false
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.setTextColor(getColor(R.color.text_secondary))
            tvTestResult.text = "测试中…"
            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    val probe = OpenAiLlmBackend(
                        baseUrl = base,
                        apiKey = key,
                        model = modelName,
                        preset = preset,
                        temperature = temp,
                        topP = 0.1f + skTopP.progress / 100f,
                        maxOutputTokens = 8,
                        enableThinking = thinking,
                    )
                    probe.prepare()
                    probe.testConnection().also { probe.release() }
                }
                btnTest.isEnabled = true
                tvTestResult.text = outcome.uiSummary()
                tvTestResult.setTextColor(
                    getColor(
                        if (outcome is ApiOutcome.Ok) R.color.accent_pink else R.color.text_secondary
                    )
                )
            }
        }

        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_save).setOnClickListener {
            config.backendKind = if (rgLlm.checkedRadioButtonId == R.id.rb_openai)
                LlmConfig.BackendKind.OPENAI else LlmConfig.BackendKind.LOCAL
            config.providerPresetId = presets[spProvider.selectedItemPosition].id
            config.openAiBaseUrl = etBase.text.toString().trim().ifEmpty { LlmConfig.DEFAULT_OAI_BASE }
            config.openAiApiKey = etKey.text.toString().trim()
            config.openAiModel = etModel.text.toString().trim().ifEmpty { LlmConfig.DEFAULT_OAI_MODEL }
            config.temperature = 0.1f + skTemp.progress / 100f
            config.topP = 0.1f + skTopP.progress / 100f
            config.maxOutputTokens = skMaxTokens.progress + 16
            config.enableThinking = swThinking.isChecked
            config.systemPrompt = etSys.text.toString()

            val oldTtsKind = config.ttsBackendKind
            val newTtsKind = if (rgTts.checkedRadioButtonId == R.id.rb_tts_minimax)
                LlmConfig.TtsBackendKind.MINIMAX else LlmConfig.TtsBackendKind.LOCAL
            config.ttsBackendKind = newTtsKind
            config.minimaxApiKey = etMmKey.text.toString().trim()
            config.minimaxVoiceId = etMmVoice.text.toString().trim().ifEmpty { "female-shaonv" }

            dialog.dismiss()
            lifecycleScope.launch {
                stopGeneration()
                if (oldTtsKind != newTtsKind || newTtsKind == LlmConfig.TtsBackendKind.MINIMAX) {
                    withContext(Dispatchers.IO) { prepareTtsBackend() }
                }
                withContext(Dispatchers.IO) { prepareBackend() }
            }
        }

        dialog.show()
    }

    // --- Neko 能力中心 -----------------------------------------------------

    private class CapCard(
        var model: NekoModel,
        val title: TextView,
        val status: TextView,
        val pct: TextView,
        val progress: ProgressBar,
        val action: TextView,
    )

    /**
     * Neko 能力中心（磨砂 BottomSheet，全屏展开）：3 张能力卡（本地大脑 / Neko 离线语音 /
     * 语音输入），每卡显示 未安装/下载中/已安装 + 下载/暂停/继续/删除。顶部总览 + 一键安装推荐。
     * LLM / TTS 卡另给「或使用在线服务 ›」直达在线配置。下载走 ModelScopeDownloader，
     * 完成后热加载对应后端，无需重启。对外只出现产品名（本地大脑等），不露底层模型名。
     */
    private fun showCapabilityCenter() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        scroll.addView(root)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
            }
            setBackgroundColor(resources.getColor(R.color.frost_border, theme))
        })
        root.addView(TextView(this).apply {
            text = "Neko 能力中心"; textSize = 18f
            setTextColor(resources.getColor(R.color.text_primary, theme))
        })
        val overview = TextView(this).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(overview)
        capOverview = overview

        val oneKey = makeCapButton("一键安装推荐配置", primary = true) { installRecommended() }
        oneKey.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { bottomMargin = dp(12) }
        root.addView(oneKey)

        capCards = ModelManager.REGISTRY.map { buildCapCard(it, root, ::dp) }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener {
            capDialog = null; capCards = emptyList(); capOverview = null
            if (::live2d.isInitialized) live2d.nudge()
        }
        capDialog = dialog
        refreshCaps()
        dialog.show()
    }

    private fun buildCapCard(model: NekoModel, parent: LinearLayout, dp: (Int) -> Int): CapCard {
        val emoji = when (model.capability) {
            Capability.LLM -> "🧠"; Capability.TTS -> "🔊"; Capability.ASR -> "🎤"
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_capsule_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            // 卡片标题固定为能力名（本地大脑/语音输入/Neko 离线语音），不随所选模型变化。
            text = "$emoji ${capTitle(model.capability)}"
            textSize = 15f
            setTextColor(resources.getColor(R.color.text_primary, theme))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row1.addView(title)
        val status = TextView(this).apply {
            textSize = 11f; setTextColor(resources.getColor(R.color.text_secondary, theme))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        row1.addView(status)
        card.addView(row1)

        // 模型/音色选择行。TTS 卡两列：左「模型」右「音色」；LLM/ASR 卡单列模型。
        // 不再显示 productDesc 描述行——能力卡保持瘦身，三个卡高度一致。
        var voiceSelector: Spinner? = null
        val selector: Spinner? = when (model.capability) {
            Capability.TTS -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                val modelSpinner = Spinner(this).apply {
                    adapter = styledModelAdapter(ModelManager.TTS_MODELS.map { it.productName })
                    setBackgroundColor(0x00000000)
                    setPopupBackgroundDrawable(resources.getDrawable(R.drawable.bg_model_dropdown, theme))
                    setPadding(dp(2), 0, dp(2), 0)
                    val activeId = ModelManager.activeTts(this@LlmChatActivity).id
                    setSelection(ModelManager.TTS_MODELS.indexOfFirst { it.id == activeId }.coerceAtLeast(0))
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                }
                val voiceSp = Spinner(this).apply {
                    val activeModel = ModelManager.activeTts(this@LlmChatActivity)
                    val voices = ModelManager.voiceIds(activeModel)
                    adapter = styledModelAdapter(voices.map(::ttsVoiceLabel))
                    setBackgroundColor(0x00000000)
                    setPopupBackgroundDrawable(resources.getDrawable(R.drawable.bg_model_dropdown, theme))
                    setPadding(dp(2), 0, dp(2), 0)
                    val activeVoice = ModelManager.resolveTtsVoice(this@LlmChatActivity, activeModel)
                    setSelection(voices.indexOf(activeVoice).coerceAtLeast(0))
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) }
                }
                row.addView(modelSpinner)
                row.addView(voiceSp)
                card.addView(row)
                voiceSelector = voiceSp
                modelSpinner
            }
            Capability.LLM, Capability.ASR -> Spinner(this).apply {
                val variants = if (model.capability == Capability.LLM) ModelManager.LLM_MODELS else ModelManager.ASR_MODELS
                adapter = styledModelAdapter(variants.map { it.productName })
                setBackgroundColor(0x00000000)
                setPopupBackgroundDrawable(resources.getDrawable(R.drawable.bg_model_dropdown, theme))
                setPadding(dp(4), 0, dp(4), 0)
                val activeId = if (model.capability == Capability.LLM) ModelManager.activeLlm(this@LlmChatActivity).id
                else ModelManager.activeAsr(this@LlmChatActivity).id
                setSelection(variants.indexOfFirst { it.id == activeId }.coerceAtLeast(0))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                ).apply { topMargin = dp(6) }
            }.also { card.addView(it) }
            else -> null
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // 进度百分比：独占一行贴在 progress 上边，靠右——不再和 size/速度 挤一行。
        val pctLabel = TextView(this).apply {
            textSize = 12f; setTextColor(resources.getColor(R.color.text_secondary, theme))
            gravity = Gravity.END
            maxLines = 1
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        card.addView(pctLabel)
        card.addView(progress)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val action = makeCapButton("下载", primary = true) {}
        row2.addView(action)
        if (model.capability == Capability.LLM || model.capability == Capability.TTS) {
            row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            row2.addView(TextView(this).apply {
                text = "或使用在线服务 ›"; textSize = 13f
                setTextColor(resources.getColor(R.color.accent_pink, theme))
                val out = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { capDialog?.dismiss(); showOnlineConfig() }
            })
        }
        card.addView(row2)
        parent.addView(card)
        val capCard = CapCard(model, title, status, pctLabel, progress, action)
        selector?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val variants = when (model.capability) {
                    Capability.LLM -> ModelManager.LLM_MODELS
                    Capability.ASR -> ModelManager.ASR_MODELS
                    Capability.TTS -> ModelManager.TTS_MODELS
                }
                val selected = variants[position]
                capCard.model = selected
                when (model.capability) {
                    Capability.ASR -> if (config.activeAsrId != selected.id) {
                        config.activeAsrId = selected.id
                        switchAsr(selected)
                    }
                    Capability.TTS -> if (config.activeTtsId != selected.id) {
                        config.activeTtsId = selected.id
                        // 不同版本音色表不同：就地刷新右侧音色下拉，不再重建整个弹窗。
                        // 切模型时回到该版本默认音色：v1.3 = neko（默认猫娘），v1.1 = catgirl_style。
                        val voice = ModelManager.voiceIds(selected).first()
                        config.ttsVoiceId = voice
                        voiceSelector?.let { vs ->
                            val voices = ModelManager.voiceIds(selected)
                            vs.adapter = styledModelAdapter(voices.map(::ttsVoiceLabel))
                            vs.setSelection(voices.indexOf(voice).coerceAtLeast(0))
                        }
                        switchTts()
                    }
                    Capability.LLM -> if (config.activeLlmId != selected.id) {
                        config.activeLlmId = selected.id
                        // 未安装时先不切（等下载完成 onCapReady 再热加载），避免把当前可用大脑切掉。
                        if (ModelManager.isInstalled(this@LlmChatActivity, selected)) switchLlm(selected)
                    }
                }
                refreshCap(capCard)
            }
        }
        voiceSelector?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = ModelManager.voiceIds(capCard.model)[position]
                if (config.ttsVoiceId != selected) {
                    config.ttsVoiceId = selected
                    switchTts()
                }
            }
        }
        return capCard
    }

    private fun ttsVoiceLabel(id: String): String = when (id) {
        "neko" -> "默认猫娘"
        "M1", "M2", "M3", "M4", "M5" -> "男声 ${id.removePrefix("M")}"
        "F1", "F2", "F3", "F4", "F5" -> "女声 ${id.removePrefix("F")}"
        "catgirl_style" -> "猫娘（旧版）"
        else -> id.replace('_', ' ')
    }

    /** 能力中心卡片标题固定为能力名，不随所选模型变化。 */
    private fun capTitle(cap: Capability): String = when (cap) {
        Capability.LLM -> "本地大脑"
        Capability.ASR -> "语音输入"
        Capability.TTS -> "Neko 离线语音"
    }

    private fun makeCapButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 14f
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            bindCapButton(this, label, primary, onClick)
        }
    }

    private fun bindCapButton(tv: TextView, label: String, primary: Boolean, onClick: () -> Unit) {
        val d = resources.displayMetrics.density
        val bg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 22f * d
            if (primary) setColor(resources.getColor(R.color.accent_pink, theme))
            else { setColor(0x00000000); setStroke((1 * d).toInt(), resources.getColor(R.color.frost_border, theme)) }
        }
        tv.text = label
        tv.setTextColor(resources.getColor(if (primary) R.color.bg_deep else R.color.text_primary, theme))
        tv.background = bg
        tv.setOnClickListener { onClick() }
    }

    private fun refreshCaps() {
        val installed = ModelManager.REGISTRY.count { model ->
            if (model.capability == Capability.LLM) ModelManager.isInstalled(this, Capability.LLM)
            else ModelManager.isInstalled(this, model)
        }
        capOverview?.text = "已安装 $installed / ${ModelManager.REGISTRY.size} 项能力 · 按需下载，随用随开"
        capCards.forEach { refreshCap(it) }
    }

    private fun refreshCap(card: CapCard) {
        val m = card.model
        val emoji = when (m.capability) {
            Capability.LLM -> "🧠"
            Capability.TTS -> "🔊"
            Capability.ASR -> "🎤"
        }
        card.title.text = "$emoji ${capTitle(m.capability)}"
        val busy = capBusy[m.id] == true
        val installed = ModelManager.isInstalled(this, m)
        val hasPart = ModelManager.dirOf(this, m).listFiles()?.any { it.name.endsWith(".part") } == true
        when {
            busy -> {
                card.progress.visibility = View.VISIBLE
                bindCapButton(card.action, "暂停", primary = false) { pauseCap(m) }
            }
            installed -> {
                card.status.text = "已安装 · ${m.sizeLabel}"
                card.progress.visibility = View.GONE
                card.pct.visibility = View.GONE
                bindCapButton(card.action, "删除", primary = false) { deleteCap(m) }
            }
            hasPart -> {
                card.status.text = "已暂停 · 点继续"
                card.progress.visibility = View.GONE
                card.pct.visibility = View.GONE
                bindCapButton(card.action, "继续下载", primary = true) { startCap(m) }
            }
            else -> {
                card.status.text = "未安装 · ${m.sizeLabel}"
                card.progress.visibility = View.GONE
                card.pct.visibility = View.GONE
                bindCapButton(card.action, "下载 · ${m.sizeLabel}", primary = true) { startCap(m) }
            }
        }
    }

    private fun updateCapProgress(model: NekoModel, downloaded: Long, total: Long, bps: Long, currentFile: String) {
        val card = capCards.firstOrNull { it.model.id == model.id } ?: return
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val retryHint = currentFile.substringAfter(" · ", "").takeIf { it.startsWith("重试") }

        val speed = when {
            bps <= 0 -> ""
            bps < 1_000_000L -> String.format("%.0fKB/s", bps / 1e3)
            else -> String.format("%.1fMB/s", bps / 1e6)
        }
        // 单位共享：小于 1MB 用 KB，其余用 MB；单位只跟在 total 后面。
        val pair = if (total in 1..999_999L) {
            String.format("(%.0f / %.0fKB)", downloaded / 1e3, total / 1e3)
        } else {
            String.format("(%.1f / %.0fMB)", downloaded / 1e6, total / 1e6)
        }
        card.status.text = when {
            retryHint != null -> "$pair · $retryHint"
            speed.isNotEmpty() -> "$pair · $speed"
            else -> pair
        }

        card.progress.visibility = View.VISIBLE
        card.progress.progress = pct
        card.pct.visibility = View.VISIBLE
        card.pct.text = "$pct%"
    }

    private fun startCap(model: NekoModel) {
        if (capBusy[model.id] == true) return
        lifecycleScope.launch { downloadCap(model) }
    }

    /** 下载一个能力（可被 installRecommended 串行复用）。回到 UI 线程刷新 + 结果提示 + 热加载。 */
    private suspend fun downloadCap(model: NekoModel): ModelScopeDownloader.Result {
        capStop[model.id] = false
        capBusy[model.id] = true
        withContext(Dispatchers.Main) { refreshCaps() }
        val result = withContext(Dispatchers.IO) {
            ModelScopeDownloader.download(
                this@LlmChatActivity, model,
                shouldStop = { capStop[model.id] == true },
                onProgress = { dl, tot, bps, file -> runOnUiThread { updateCapProgress(model, dl, tot, bps, file) } },
            )
        }
        capBusy[model.id] = false
        withContext(Dispatchers.Main) {
            when (result) {
                is ModelScopeDownloader.Result.Success -> {
                    Toast.makeText(this@LlmChatActivity, "${model.productName} 已就绪", Toast.LENGTH_SHORT).show()
                    onCapReady(model)
                }
                is ModelScopeDownloader.Result.Failed ->
                    Toast.makeText(this@LlmChatActivity, result.message, Toast.LENGTH_LONG).show()
                ModelScopeDownloader.Result.Stopped -> Unit
            }
            refreshCaps()
        }
        return result
    }

    private fun pauseCap(model: NekoModel) {
        capStop[model.id] = true   // downloader 收到后返回 Stopped，.part 保留，下次续传
    }

    private fun switchLlm(model: NekoModel) {
        if (config.activeLlmId == model.id && backend?.ready == true) return
        config.activeLlmId = model.id
        onlineConversationHistory.clear()
        stopGeneration()
        lifecycleScope.launch(Dispatchers.IO) { prepareBackend() }
    }

    private fun switchAsr(model: NekoModel) {
        if (!ModelManager.isInstalled(this, model)) {
            asrReady = false
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { asr.release() }
            val ok = runCatching { asr.prepare(ModelManager.dirOf(this@LlmChatActivity, model).absolutePath) }
                .getOrDefault(false)
            asrReady = ok
            withContext(Dispatchers.Main) {
                btnMic.alpha = if (ok) 1.0f else 0.5f
                if (ok) Toast.makeText(this@LlmChatActivity, "已切换：${model.productName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Model or voice changed: stop the old native loader before rebuilding it. */
    private fun switchTts() {
        if (config.ttsBackendKind != LlmConfig.TtsBackendKind.LOCAL) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { speechQueue.stopAndAwaitSilence() }
            speechQueue.swapBackend(EmptyTtsBackend)
            val previous = localTtsBackend
            localTtsBackend = null
            runCatching { previous?.release() }
            prepareTtsBackend()
        }
    }

    private fun deleteCap(model: NekoModel) {
        // 仅在「已安装」态可点，无并发下载。删文件前必须先释放已加载到内存/native 的实例，
        // 否则模型已被 mmap，删了文件也照常合成——用户报的假删除 bug。
        lifecycleScope.launch {
            when (model.capability) {
                Capability.TTS -> {
                    if (config.ttsBackendKind == LlmConfig.TtsBackendKind.LOCAL) {
                        runCatching { speechQueue.stopAndAwaitSilence() }
                        speechQueue.swapBackend(EmptyTtsBackend)
                    }
                    val toRelease = localTtsBackend
                    localTtsBackend = null
                    withContext(Dispatchers.IO) { runCatching { toRelease?.release() } }
                }
                Capability.LLM -> {
                    if (backend is LocalLlmBackend) {
                        stopGeneration()
                        val toRelease = backend
                        backend = null
                        withContext(Dispatchers.IO) { runCatching { toRelease?.release() } }
                    }
                }
                Capability.ASR -> {
                    if (::asr.isInitialized) {
                        withContext(Dispatchers.IO) { runCatching { asr.release() } }
                    }
                    asrReady = false
                    asrLoading = false
                    btnMic.alpha = 0.5f
                    asrStatus = "语音输入未安装"
                }
            }
            val deleted = withContext(Dispatchers.IO) { ModelManager.delete(this@LlmChatActivity, model) }
            Toast.makeText(
                this@LlmChatActivity,
                if (deleted) "${model.productName} 已删除" else "${model.productName} 删除失败：文件权限不足",
                Toast.LENGTH_LONG,
            ).show()
            refreshCaps()
        }
    }

    private fun installRecommended() {
        val todo = ModelManager.REGISTRY.filter {
            it.recommended && !ModelManager.isInstalled(this, it) && capBusy[it.id] != true
        }
        if (todo.isEmpty()) {
            Toast.makeText(this, "推荐能力都已安装啦", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            for (m in todo) downloadCap(m)   // 串行：省内存/带宽，稳定优先
            Toast.makeText(this@LlmChatActivity, "推荐能力安装完成", Toast.LENGTH_SHORT).show()
        }
    }

    /** 某能力下好后立即热加载对应后端，无需重启。 */
    private fun onCapReady(model: NekoModel) {
        when (model.capability) {
            Capability.ASR -> lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching { asr.prepare() }.getOrDefault(false)
                asrReady = ok; asrLoading = false
                withContext(Dispatchers.Main) { btnMic.alpha = if (ok) 1.0f else 0.5f }
            }
            Capability.TTS -> {
                config.activeTtsId = model.id
                config.ttsVoiceId = ModelManager.resolveTtsVoice(this, model)
                if (config.ttsBackendKind == LlmConfig.TtsBackendKind.LOCAL) switchTts()
            }
            Capability.LLM -> if (config.backendKind == LlmConfig.BackendKind.LOCAL) {
                config.activeLlmId = model.id
                onlineConversationHistory.clear()
                stopGeneration()
                lifecycleScope.launch(Dispatchers.IO) { prepareBackend() }
            }
        }
    }

    /**
     * 切换角色（磨砂 BottomSheet）。列表 = 内置 Fenmao + 扫描到的自定义模型，
     * 底部一个「导入模型（ZIP / 7Z / RAR）」入口。选中即持久化 + 热切换。
     */
    private fun showAvatarPicker() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_avatar_picker, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val list = view.findViewById<LinearLayout>(R.id.ll_avatar_list)
        rebuildAvatarList(list, dialog)

        view.findViewById<TextView>(R.id.row_import_model).setOnClickListener {
            dialog.dismiss()
            // 文件管理器对 7Z/RAR 的 MIME 标记并不一致，故放宽选择器，再按后缀严格校验。
            importModelLauncher.launch(arrayOf("*/*"))
        }
        dialog.show()
    }

    /** 填充角色行：内置在前、自定义在后；当前选中打勾。 */
    private fun rebuildAvatarList(container: LinearLayout, dialog: BottomSheetDialog) {
        container.removeAllViews()
        val builtins = Live2DController.SWITCHABLE            // (id, 显示名)
        val customs = live2d.scanCustomModels()              // (dirName, 显示名)
        val entries = builtins + customs
        for ((id, display) in entries) {
            container.addView(makeAvatarRow(display, isSelected = id == config.live2dModel, canDelete = customs.any { it.first == id }, onClick = {
                config.live2dModel = id
                live2d.switchModel(id)
                characterState.onCharacterChanged(id)
                dialog.dismiss()
            }, onDelete = {
                confirmDeleteCustomAvatar(id, display, container, dialog)
            }))
        }
    }

    private fun makeAvatarRow(
        display: String,
        isSelected: Boolean,
        canDelete: Boolean,
        onClick: () -> Unit,
        onDelete: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            gravity = Gravity.CENTER_VERTICAL
            val out = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
            setOnClickListener { onClick() }
            setPadding(dp(4), 0, dp(4), 0)
        }
        val label = TextView(this).apply {
            text = if (isSelected) "✓  $display" else display
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        row.addView(label)
        if (canDelete) {
            val delete = TextView(this).apply {
                text = "删除"
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(resources.getColor(R.color.accent_pink, theme))
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { onDelete() }
            }
            row.addView(delete)
        }
        return row
    }

    private fun confirmDeleteCustomAvatar(
        id: String,
        display: String,
        container: LinearLayout,
        dialog: BottomSheetDialog,
    ) {
        if (id in Live2DController.SWITCHABLE.map { it.first }) return
        AlertDialog.Builder(this)
            .setTitle("删除自定义角色？")
            .setMessage("将删除“$display”的本地模型文件，系统内置角色不会受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val target = File(live2d.customModelsDir(), id).canonicalFile
                    val root = live2d.customModelsDir().canonicalFile
                    val safe = target.parentFile == root && target.isDirectory
                    val deleted = safe && target.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        if (deleted) {
                            if (config.live2dModel == id) {
                                config.live2dModel = Live2DController.DEFAULT_NAME
                                live2d.switchModel(Live2DController.DEFAULT_NAME)
                                characterState.onCharacterChanged(Live2DController.DEFAULT_NAME)
                            }
                            rebuildAvatarList(container, dialog)
                            Toast.makeText(this@LlmChatActivity, "已删除：$display", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@LlmChatActivity, "删除失败：模型目录不存在或无权限", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    /**
     * 导入自定义 Live2D 模型压缩包（ZIP / 7Z / RAR）：解压到临时目录 → 校验（找 model3.json、moc3、贴图）→
     * 通过则移到 [Live2DController.customModelsDir]/<名> 并热切换；不通过弹磨砂错误提示（指明是包的问题）。
     */
    private fun importModelArchive(uri: Uri) {
        Toast.makeText(this, "正在导入模型…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { doImportModelArchive(uri) } }
            result.onSuccess { name ->
                config.live2dModel = name
                live2d.switchModel(name)
                characterState.onCharacterChanged(name)
                Toast.makeText(this@LlmChatActivity, "已导入并切换：$name", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Log.w("MoeAvatar.Chat", "importModelArchive failed", e)
                showModelErrorSheet(e.message ?: "未知错误，请确认这是有效的 Cubism 模型压缩包。")
            }
        }
    }

    /** 实际解压+校验，返回导入后的模型目录名。抛异常即校验失败（message 是给用户看的中文原因）。 */
    private fun doImportModelArchive(uri: Uri): String {
        val tmp = File(cacheDir, "l2d_import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 1) 先复制到私有缓存。7Z/RAR 解压器需要可随机读取的实际文件，而 content Uri 不保证这一点。
            val archiveName = getDocumentDisplayName(uri)
                ?: uri.lastPathSegment.orEmpty().substringBefore('?')
            val extension = archiveName.substringAfterLast('.', "").lowercase()
            require(extension in setOf("zip", "7z", "rar")) {
                "仅支持 ZIP、7Z 或 RAR 格式的模型压缩包。"
            }
            val archive = File(tmp, "source.$extension")
            contentResolver.openInputStream(uri).use { ins ->
                requireNotNull(ins) { "无法读取所选文件。" }
                archive.outputStream().use { ins.copyTo(it) }
            }

            if (extension == "zip") {
                extractZipSafely(archive, tmp)
            } else {
                // AndroidP7zip 基于 p7zip，支持 7Z/RAR。输入、输出路径均由本应用创建，避免命令注入。
                P7ZipApi.executeCommand("7z x \"${archive.absolutePath}\" -o\"${tmp.absolutePath}\" -y")
                validateExtractedPaths(tmp)
            }

            // 2) 找 model3.json，其所在目录即模型根
            val modelJson = tmp.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".model3.json", true) }
                ?: throw IllegalArgumentException(
                    "没找到 model3.json —— 这不是有效的 Cubism 3/4/5 模型包。\n" +
                    "（老的 Cubism 2.x 模型是 .moc + model.json，本应用不支持。）"
                )
            val home = modelJson.parentFile!!

            // 3) 校验 model3.json 引用的 moc3 + 贴图确实存在
            val jsonText = modelJson.readText()
            val moc = Regex("\"Moc\"\\s*:\\s*\"([^\"]+)\"").find(jsonText)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("model3.json 里没有 Moc 字段，模型包不完整。")
            if (moc.endsWith(".moc", true) && !moc.endsWith(".moc3", true)) {
                throw IllegalArgumentException("这是 Cubism 2.x 老模型（.moc），本应用只支持 Cubism 3/4/5（.moc3）。")
            }
            if (!File(home, moc).exists()) {
                throw IllegalArgumentException("缺少 moc3 文件（$moc），模型包不完整。")
            }
            Regex("\"([^\"]+\\.png)\"").findAll(jsonText).map { it.groupValues[1] }.toList()
                .firstOrNull { !File(home, it).exists() }
                ?.let { throw IllegalArgumentException("缺少贴图文件（$it），模型包不完整。") }

            // 4) 移到自定义模型目录（用 zip 内 model3.json 的 basename 作名字，去掉扩展名）
            val name = modelJson.name.removeSuffix(".model3.json").ifBlank { "custom" }
                .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val dest = File(live2d.customModelsDir(), name)
            if (dest.exists()) dest.deleteRecursively()
            if (!home.renameTo(dest)) {
                home.copyRecursively(dest, overwrite = true)
            }
            return name
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** ZIP 使用流式解压，保留原有 zip-slip 防护。 */
    private fun extractZipSafely(archive: File, outputDir: File) {
        ZipInputStream(archive.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val outFile = File(outputDir, entry.name)
                if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath + File.separator)) {
                    throw IllegalArgumentException("压缩包内含非法路径，已拒绝（可能是被篡改的压缩包）。")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            if (count == 0) throw IllegalArgumentException("压缩包是空的或无法解压。")
        }
    }

    /** p7zip 解压完成后再次检查所有产物，防止任何格式的路径穿越。 */
    private fun validateExtractedPaths(outputDir: File) {
        val root = outputDir.canonicalPath + File.separator
        val count = outputDir.walkTopDown().count { file ->
            if (file == outputDir) return@count false
            if (!file.canonicalPath.startsWith(root)) {
                throw IllegalArgumentException("压缩包内含非法路径，已拒绝（可能是被篡改的压缩包）。")
            }
            file.isFile && file.name != "source.7z" && file.name != "source.rar"
        }
        if (count == 0) throw IllegalArgumentException("压缩包是空的或无法解压。")
    }

    /** SAF 的 content URI 通常不带原始文件名，后缀必须从文档元数据读取。 */
    private fun getDocumentDisplayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }

    /** native 加载失败（自定义模型 moc3 版本过新 >v5.3 或文件损坏）：回退默认角色 + 弹错误。 */
    private fun onLive2dLoadError() {
        val fallback = Live2DController.DEFAULT_NAME
        if (config.live2dModel != fallback) {
            config.live2dModel = fallback
            live2d.switchModel(fallback)
            characterState.onCharacterChanged(fallback)
        }
        showModelErrorSheet(
            "模型加载失败：可能 moc3 版本过新（本应用支持到 v5.3）或文件损坏。\n已切回默认角色。"
        )
    }

    /** 磨砂风格的错误提示（导入校验/加载失败用）。红色标题，明确是模型包的问题，不是软件 bug。 */
    private fun showModelErrorSheet(message: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(24), dp(12), dp(24), dp(24))
            addView(View(this@LlmChatActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(16)
                }
                setBackgroundResource(R.color.frost_border)
            })
            addView(TextView(this@LlmChatActivity).apply {
                text = "导入失败"
                setTextColor(resources.getColor(R.color.error_red, theme))
                textSize = 18f
            })
            addView(TextView(this@LlmChatActivity).apply {
                text = message
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 14f
                setPadding(0, dp(12), 0, 0)
            })
        }
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(content)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val btn = Button(this).apply {
            text = "知道了"
            setTextColor(resources.getColor(R.color.accent_pink, theme))
            setBackgroundColor(0)
            setOnClickListener { dialog.dismiss() }
        }
        (content).addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = dp(12) })
        dialog.show()
    }

    /**
     * 磨砂「按需引导」提示：标题 + 说明 + 若干操作按钮（第一个高亮），底部「以后再说」。
     * 全部产品化文案，不露底层模型名。用于 ASR/LLM/TTS 缺失时在使用点就地引导。
     */
    private fun showNeedCapabilitySheet(title: String, message: String, vararg actions: Pair<String, () -> Unit>) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16)
            }
            setBackgroundResource(R.color.frost_border)
        })
        content.addView(TextView(this).apply {
            text = title; textSize = 18f
            setTextColor(resources.getColor(R.color.text_primary, theme))
        })
        content.addView(TextView(this).apply {
            text = message; textSize = 14f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            setPadding(0, dp(10), 0, dp(4))
        })
        val dialog = BottomSheetDialog(this)
        actions.forEachIndexed { i, (label, action) ->
            content.addView(makeCapButton(label, primary = i == 0) {
                dialog.dismiss(); action()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(12) })
        }
        content.addView(TextView(this).apply {
            text = "以后再说"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.text_tertiary, theme))
            val out = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
        dialog.setContentView(content)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // 关掉弹窗时,踢一下 GLSurfaceView 让 Live2D 重回媒体 overlay z-order（首启弹窗压掉角色的 bug）
        dialog.setOnDismissListener { if (::live2d.isInitialized) live2d.nudge() }
        dialog.show()
    }

    // --- background -------------------------------------------------------

    private fun applyDefaultBackground() {
        runCatching {
            assets.open("room_default.png").use { ins ->
                val raw = BitmapFactory.decodeStream(ins) ?: return@runCatching
                val dimmed = dimForBackground(centerCropForBackground(raw))
                // 优先走 native 路径：GPU 强制不透明的设备上 GLSurfaceView 透不出去
                // （Vivo / HyperOS），所以背景作为 fullscreen quad 画在 GL 角色下面。
                live2d.setBackgroundBitmap(dimmed)
                // ImageView 留作 fallback：未来如果 GLSurfaceView 真能透明，底下能直接看到。
                ivBackground.setImageBitmap(dimmed)
            }
        }.onFailure { Log.w(TAG, "no room_default.png; using bg_deep solid", it) }
    }

    private fun applyBackgroundFromUri(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri).use { ins ->
                val raw = BitmapFactory.decodeStream(ins) ?: return@runCatching
                val dimmed = dimForBackground(centerCropForBackground(raw))
                live2d.setBackgroundBitmap(dimmed)
                ivBackground.setImageBitmap(dimmed)
            }
        }.onFailure {
            Log.e(TAG, "applyBackgroundFromUri failed", it)
            Toast.makeText(this, "换背景失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v2-月夜猫娘: 模拟 45% #252235 蒙版效果（dim mask），把每像素 RGB 各乘 0.55，
     * alpha 拉到 255。直接送进 native，GL 全屏 quad 渲出来就跟"room + 蒙版"一致。
     * 一次切换只跑一次，60ms 内完事，不卡帧。
     */
    private fun dimForBackground(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = ((p ushr 16) and 0xFF) * BG_DIM_MUL / 255
            val g = ((p ushr 8) and 0xFF) * BG_DIM_MUL / 255
            val b = (p and 0xFF) * BG_DIM_MUL / 255
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        src.recycle()
        return out
    }

    /** Crop to the device viewport before uploading to the native full-screen quad. */
    private fun centerCropForBackground(src: Bitmap): Bitmap {
        val targetW = rootContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val targetH = rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val sourceRatio = src.width.toFloat() / src.height
        val targetRatio = targetW.toFloat() / targetH
        if (kotlin.math.abs(sourceRatio - targetRatio) < 0.001f) return src

        val cropW: Int
        val cropH: Int
        if (sourceRatio > targetRatio) {
            cropH = src.height
            cropW = (cropH * targetRatio).toInt().coerceAtMost(src.width)
        } else {
            cropW = src.width
            cropH = (cropW / targetRatio).toInt().coerceAtMost(src.height)
        }
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        return Bitmap.createBitmap(src, left, top, cropW, cropH).also { cropped ->
            if (cropped !== src) src.recycle()
        }
    }

    /**
     * 角色位置/大小完全交给 Live2DController 的 GL 预设（scale/translateY），View 层不平移不缩放。
     * 早期版本这里做过 translationY 上移 + scaleY 拉伸来"给输入栏留白"，
     * 但那会把角色纵向拉伸失真，且把整只角色往上挪偏离正常构图 —— 现已归零。
     */
    private fun applyLive2dYOffset() {
        val v = live2d.view ?: return
        live2dViewYOffset = 0f
        live2dViewScale = 1.0f
        v.translationY = live2dViewYOffset
        v.scaleY = live2dViewScale
    }

    // --- bootstrap --------------------------------------------------------

    private fun bootstrap() {
        setStatus("初始化语音输入…")
        btnMic.isEnabled = true
        btnMic.alpha = 0.5f
        // ASR 和 SuperTonic 都会在 native prepare 阶段创建 MNN/Express executor。
        // 不能并行初始化：部分设备上会触发 sherpa 的 native fatal exit，连带销毁
        // 已创建的 Express 全局对象。首次启动按 ASR -> TTS -> LLM 固定顺序执行。
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { asr.prepare() }.getOrDefault(false)
            asrReady = ok
            asrLoading = false
            withContext(Dispatchers.Main) {
                btnMic.alpha = if (ok) 1.0f else 0.5f
                asrStatus = if (ok) "语音输入就绪" else "语音输入未安装"
                if (!ok) Log.w(TAG, "ASR unavailable: check ${ModelManager.asrDir(this@LlmChatActivity)}")
            }
            withContext(Dispatchers.Main) { setStatus("初始化 TTS…") }
            prepareTtsBackend()
            withContext(Dispatchers.Main) {
                if (ttsReady) setStatus("TTS 就绪 · 准备 LLM…")
                else setStatus("TTS 初始化失败 — 仍可文本聊天")
            }
            prepareBackend()
            bootstrapDone = true
            withContext(Dispatchers.Main) { maybeShowInitialGuidance() }
        }
    }

    private fun maybeShowInitialGuidance() {
        if (!config.seenAiDisclosure) {
            showAiDisclosure()
        } else {
            maybeShowWelcome()
        }
    }

    /** 首次 AI 身份说明。使用同 Window 覆盖层，避免 Dialog 改变 Live2D Surface 层级。 */
    private fun showAiDisclosure() {
        aiDisclosureOverlay.visibility = View.VISIBLE
        btnAiDisclosureConfirm.setOnClickListener {
            config.seenAiDisclosure = true
            aiDisclosureOverlay.visibility = View.GONE
            if (::live2d.isInitialized) live2d.nudge()
            if (!isFinishing && !isDestroyed) {
                rootContainer.postDelayed({ maybeShowWelcome() }, 250L)
            }
        }
    }

    /** 首启环境检测：若有推荐能力未安装，弹一次磨砂欢迎引导（一键安装 / 稍后手动）。 */
    private fun maybeShowWelcome() {
        if (config.seenWelcome) return
        config.seenWelcome = true
        val missing = ModelManager.REGISTRY.filter { it.recommended && !ModelManager.isInstalled(this, it) }
        if (missing.isEmpty()) return
        val names = missing.joinToString("、") { it.productName }
        showNeedCapabilitySheet(
            "欢迎来到 NekoChat 喵~",
            "检测到还没安装 AI 能力：$names。\n点「一键安装推荐配置」自动下载，或稍后在 设置 → Neko 能力中心 手动管理。",
            "一键安装推荐配置" to { showCapabilityCenter(); installRecommended() },
            "稍后手动" to {},
        )
    }

    /**
     * 根据 config.ttsBackendKind 准备 TTS：
     *  - LOCAL：SuperTonic（缓存在 localTtsBackend，切回来免重加载）
     *  - MINIMAX：轻量 HTTP 后端，切过去时释放本地模型腾内存
     * 切 backend 前先静默 + 指向 Empty，避免旧后端被 synthJob 引用时 release 造成 native use-after-free。
     */
    private suspend fun prepareTtsBackend() {
        runCatching { speechQueue.stopAndAwaitSilence() }
        speechQueue.swapBackend(EmptyTtsBackend)

        val be: TtsBackend? = when (config.ttsBackendKind) {
            LlmConfig.TtsBackendKind.LOCAL -> {
                // Let the backend resolve the requested voice against its actual model.
                // This preserves v1.1's catgirl fallback when a user has not downloaded v1.3.
                val local = localTtsBackend ?: SuperTonicTtsBackend(applicationContext)
                    .also { localTtsBackend = it }
                local.setPerformanceLogging(config.devMode && config.performanceLogsEnabled)
                if (runCatching { local.prepare() }.getOrDefault(false)) local else null
            }
            LlmConfig.TtsBackendKind.MINIMAX -> {
                val toRelease = localTtsBackend
                localTtsBackend = null
                runCatching { toRelease?.release() }
                if (config.minimaxApiKey.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LlmChatActivity, "MiniMax 未配置 API Key，请打开设置", Toast.LENGTH_LONG).show()
                    }
                    null
                } else {
                    MiniMaxStreamTtsBackend(apiKey = config.minimaxApiKey).also { it.prepare() }
                }
            }
        }
        if (be != null) {
            ttsBackend = be
            speechQueue.swapBackend(be)
            ttsReady = true
        } else {
            ttsBackend = EmptyTtsBackend
            speechQueue.swapBackend(EmptyTtsBackend)
            ttsReady = false
        }
    }

    private suspend fun prepareBackend() {
        backend?.release()
        backend = null

        val picked: LlmBackend = (when (config.backendKind) {
            LlmConfig.BackendKind.LOCAL -> {
                val model = ModelManager.activeLlm(this)
                if (!ModelManager.isInstalled(this, model)) {
                    withContext(Dispatchers.Main) { setStatus("${model.productName} 未安装") }
                    null
                } else {
                    ModelManager.disableThinking(this, model)
                    val configPath = File(ModelManager.dirOf(this, model), "config.json").absolutePath
                    LocalLlmBackend(
                        configPath = configPath,
                        // 基础身份提示词与线上一致；本地只压缩 Live2D 表情规则。
                        systemPromptProvider = { buildLocalSystemPrompt() },
                        temperature = config.temperature,
                        topP = config.topP,
                        maxOutputTokens = config.maxOutputTokens,
                    )
                }
            }
            LlmConfig.BackendKind.OPENAI -> {
                if (config.openAiApiKey.isBlank()) {
                    withContext(Dispatchers.Main) { setStatus("在线 API Key 未配置 · 打开设置") }
                    null
                } else {
                    OpenAiLlmBackend(
                        baseUrl = config.openAiBaseUrl,
                        apiKey = config.openAiApiKey,
                        model = config.openAiModel,
                        preset = ProviderRegistry.byId(config.providerPresetId),
                        temperature = config.temperature,
                        topP = config.topP,
                        maxOutputTokens = config.maxOutputTokens,
                        enableThinking = config.enableThinking,
                        systemPromptProvider = { buildEffectiveSystemPrompt() },
                    )
                }
            }
        } ?: return)

        withContext(Dispatchers.Main) { setStatus("加载 ${picked.displayName} …") }
        val ok = runCatching { picked.prepare() }.getOrDefault(false)
        withContext(Dispatchers.Main) {
            if (ok) {
                backend = picked
                setStatus("就绪 · ${picked.displayName}")
            } else {
                setStatus("LLM 加载失败 · ${picked.displayName}")
            }
        }
    }

    // --- chat -------------------------------------------------------------

    /** 没有任何语音能力（本地未装 + 未配 MiniMax）时，首次发消息弹一次引导。文字聊天照常。 */
    private fun maybePromptVoice() {
        if (voicePromptShown || ttsReady) return
        val ttsInstalled = ModelManager.isInstalled(this, Capability.TTS)
        val minimaxSet = config.ttsBackendKind == LlmConfig.TtsBackendKind.MINIMAX && config.minimaxApiKey.isNotBlank()
        if (ttsInstalled || minimaxSet) return   // 有语音能力，只是还没加载好，不打扰
        voicePromptShown = true
        showNeedCapabilitySheet(
            "现在只有文字回复",
            "想让猫娘开口说话？下载「Neko 离线语音」离线发声，或用在线语音。不下载也能继续文字聊天。",
            "下载离线语音" to { showCapabilityCenter() },
            "用在线语音" to { showOnlineConfig() },
        )
    }

    private fun sendCurrentInput() {
        val text = editInput.text.toString().trim()
        if (text.isEmpty()) return
        submitMessage(text, InputSource.TEXT)
    }

    private fun submitMessage(text: String, source: InputSource) {
        if (text.isBlank()) return
        val inputDecision = contentSafetyGuard.check(text)
        if (!inputDecision.allowed) {
            if (source == InputSource.TEXT) finishTextSubmission()
            Log.w(TAG, "content safety blocked input category=${inputDecision.category}")
            showSafetyResponse(inputDecision.safeResponse.orEmpty())
            return
        }
        val be = backend ?: run {
            showNeedCapabilitySheet(
                "还没有可用的大脑",
                "想让猫娘陪你聊天，二选一：下载「本地大脑」离线畅聊，或配置在线服务（接入你自己的 API）。",
                "下载本地大脑" to { showCapabilityCenter() },
                "配置在线服务" to { showOnlineConfig() },
            )
            return
        }
        if (source == InputSource.TEXT) {
            finishTextSubmission()
        }

        // 首次发消息且没有语音能力：提示可下载离线声音 / 用在线语音（不打断文字聊天）。
        maybePromptVoice()

        // 打断上一轮：UI 线程立刻静音（听感即时闭嘴），重活（停 native、等静默）丢进协程 IO。
        speechQueue.pauseImmediately()
        live2d.closeMouth()
        subtitleManager.stopAll()
        // ASR 和键盘输入共用字幕：先清上一轮，再短暂显示本轮用户原话。
        // AI 的第一段音频真正开始前会通过 onClauseStart 自然覆盖它。
        subtitleManager.showUser(text)
        val prevJob = chatJob

        // 新一轮 TTS 统计重置
        (ttsBackend as? SuperTonicTtsBackend)?.resetStats()
        // 清旧 perf 行
        updatePerfLine(clear = true)
        pendingLlmRecord = null

        val splitter = SentenceSplitter()
        val ttsFilter = TtsTextFilter()
        val actionFilter = ActionTagFilter()
        val llmStartMs = SystemClock.elapsedRealtime()
        var firstTokenMs = 0L
        var lastTokenMs = 0L
        var tokenCount = 0
        val turnId = ++conversationTurnId
        val boundaryJob = conversationBoundaryJob
        val requestTurns = if (config.backendKind == LlmConfig.BackendKind.LOCAL && !config.localMultiTurn) {
            listOf(ChatTurn(ChatTurn.Role.USER, text))
        } else {
            onlineConversationHistory.buildRequest(text)
        }
        val assistantReply = StringBuilder()
        var outputBlocked = false

        fun deliverCheckedClause(clean: String): Boolean {
            // Native/local inference may return one last token after stopGeneration().
            // The old code only cancelled its coroutine; the token could still enqueue into
            // SpeechQueue's newly-created channel and resurrect phone-call "Speaking" state.
            // A turn is the ownership boundary for *every* visible/TTS side effect.
            if (turnId != conversationTurnId || clean.isBlank() || outputBlocked) return false
            val decision = contentSafetyGuard.check(clean)
            if (!decision.allowed) {
                outputBlocked = true
                Log.w(TAG, "content safety blocked output category=${decision.category}")
                val response = decision.safeResponse.orEmpty()
                subtitleManager.pushSentence(response)
                if (ttsReady) speechQueue.enqueue(response)
                be.stop()
                return false
            }
            if (!ttsReady && config.showAiSubtitle) subtitleManager.pushSentence(clean)
            speechQueue.enqueue(clean)
            return true
        }

        conversationCoordinator.markThinking(source)

        chatJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { boundaryJob?.join() }
                runCatching { prevJob?.cancelAndJoin() }
                runCatching { be.stop() }
                runCatching { speechQueue.stopAndAwaitSilence() }
            }
            if (turnId != conversationTurnId) return@launch
            generating = true
            try {
                be.chat(requestTurns)
                    .onEach {
                        val now = SystemClock.elapsedRealtime()
                        if (firstTokenMs == 0L) firstTokenMs = now
                        lastTokenMs = now
                        tokenCount++
                    }
                    .buffer(Channel.UNLIMITED)
                    .collect { token ->
                        if (outputBlocked) return@collect
                        assistantReply.append(token)
                        val filtered = actionFilter.feed(token)
                        for (act in filtered.actions) {
                            runOnUiThread { dispatchAction(act) }
                        }
                        val visible = filtered.visible
                        if (visible.isEmpty()) {
                            delay(TOKEN_REVEAL_MS)
                            return@collect
                        }
                        val ttsChunk = ttsFilter.feed(visible)
                        if (ttsChunk.isNotEmpty()) {
                            for (s in splitter.feed(ttsChunk)) {
                                val clean = ttsFilter.stripAll(s)
                                if (clean.isNotBlank()) {
                                    if (!deliverCheckedClause(clean)) break
                                }
                            }
                        }
                        delay(TOKEN_REVEAL_MS)
                    }
                // 流结束：把 filter 里未闭合的东西/尾部可见文本冲出来
                val tail = if (outputBlocked) null else actionFilter.flush()
                for (act in tail?.actions.orEmpty()) {
                    runOnUiThread { dispatchAction(act) }
                }
                if (!outputBlocked && !tail?.visible.isNullOrEmpty()) {
                    val ttsChunk = ttsFilter.feed(tail!!.visible)
                    if (ttsChunk.isNotEmpty()) {
                        for (s in splitter.feed(ttsChunk)) {
                            val clean = ttsFilter.stripAll(s)
                            if (clean.isNotBlank()) {
                                if (!deliverCheckedClause(clean)) break
                            }
                        }
                    }
                }
                if (!outputBlocked) {
                    val unclosedTtsText = ttsFilter.flush()
                    if (unclosedTtsText.isNotEmpty()) {
                        for (s in splitter.feed(unclosedTtsText)) {
                            val clean = ttsFilter.stripAll(s)
                            if (clean.isNotBlank() && !deliverCheckedClause(clean)) break
                        }
                    }
                    for (s in splitter.flush()) {
                        val clean = ttsFilter.stripAll(s)
                        if (clean.isNotBlank() && !deliverCheckedClause(clean)) break
                    }
                }
                if (turnId == conversationTurnId && !outputBlocked && assistantReply.isNotBlank()) {
                    onlineConversationHistory.commit(text, assistantReply.toString())
                }
                // LLM 一产出完毕：把 LLM 指标挂上 perf 行（不等 TTS 播完）
                val llmLine = buildLlmStat(llmStartMs, firstTokenMs, lastTokenMs, tokenCount)
                if (config.performanceLogsEnabled && firstTokenMs > 0L) {
                    pendingLlmRecord = PerformanceRecord(
                        time = System.currentTimeMillis(), model = config.activeLlmId,
                        tokens = tokenCount,
                        llmFirstTokenMs = firstTokenMs - llmStartMs,
                        llmTotalMs = (lastTokenMs - llmStartMs).coerceAtLeast(0L),
                        ttsFirstPacketMs = null, ttsRtf = null,
                    )
                }
                updatePerfLine(llmLine = llmLine)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "chat error", t)
                val friendly = when (t) {
                    is OpenAiLlmBackend.ApiException -> t.outcome.uiSummary()
                    else -> t.message?.take(60) ?: "unknown"
                }
                subtitleManager.pushSentence("[错误: $friendly]")
                // 错误路径不会走到 TTS 排空回调，兜底把 emotion 收掉。
                characterState.clearEmotion()
            } finally {
                if (turnId == conversationTurnId) {
                    generating = false
                    if (!speechQueue.hasPendingWork()) {
                        conversationCoordinator.markResponseComplete()
                        characterState.clearEmotion()
                    }
                }
                // NOTE: 正常路径的 restoreIdle 由 speechQueue.onPlaybackExhausted 触发，
                // 而不是这里 —— finally 会在 LLM 流一断就触发，比 TTS 讲完早 5~10s，
                // emotion 会只闪一下（v0.5 M1.1 bug 修）。
            }
        }
    }

    private fun finishTextSubmission() {
        editInput.setText("")
        editInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editInput.windowToken, 0)
        // 默认语音模式：发送完文字就切回语音，长按说话始终可用。
        // 想继续打字时点键盘图标重新进文字模式即可。
        conversationCoordinator.switchMode(InputMode.VOICE)
    }

    private fun showSafetyResponse(response: String) {
        if (response.isBlank()) return
        subtitleManager.stopAll()
        subtitleManager.pushSentence(response)
        if (ttsReady) speechQueue.enqueue(response)
    }

    private fun buildLlmStat(startMs: Long, firstMs: Long, lastMs: Long, count: Int): String? {
        if (count == 0 || firstMs == 0L) return null
        val ttft = firstMs - startMs
        val genMs = (lastMs - firstMs).coerceAtLeast(1)
        val tps = if (count > 1) (count - 1) * 1000.0 / genMs else 0.0
        return "LLM 首字 ${ttft}ms · ${"%.1f".format(tps)} tok/s"
    }

    // --- v0.5 Character Engine · action driver (M1) ----------------------

    /** 上一次派发的 emotion 名 + 时间戳，用于同名 800ms 去重（防流式重复派发）。 */
    private var lastDispatchedEmotion: String? = null
    private var lastDispatchedEmotionMs: Long = 0L

    /**
     * 把一个 [ActionTagFilter.Action] 派发到 [CharacterStateManager]。
     * - 非法名（不在当前模型 availableExpressions 里）→ state manager 内部丢弃 + log
     * - 相同 emotion 800ms 内重复来 → 去重（防止流式重复派发同一个 emotion）
     * - motion 字段 M1 不生效，仅 log；M3 里程碑接 nativeStartMotion
     * - **M2 关键**：outfit 走 native 独立通道（[Live2DController.applyOutfit] →
     *   nativeApplyOutfit → _outfitExpressionManager），emotion 走另一条，同一
     *   action 块里给 outfit + emotion 也不会互相 fade。
     * 必须在主线程调用。
     */
    private fun dispatchAction(a: ActionTagFilter.Action) {
        if (!config.liveActingEnabled) {
            Log.d(TAG, "dispatchAction: live acting disabled, ignored")
            return
        }
        a.outfit?.let { name ->
            if (name.equals(DEFAULT_OUTFIT_ACTION, ignoreCase = true)) {
                characterState.clearOutfit()
            } else {
                characterState.applyOutfit(name)
            }
        }
        a.emotion?.let { name ->
            val now = SystemClock.elapsedRealtime()
            if (name == lastDispatchedEmotion && now - lastDispatchedEmotionMs < 800L) {
                Log.d(TAG, "dispatchAction: throttled duplicate emotion '$name'")
                return@let
            }
            lastDispatchedEmotion = name
            lastDispatchedEmotionMs = now
            characterState.applyEmotion(name)
        }
        a.motion?.let {
            Log.d(TAG, "dispatchAction: motion='$it' ignored (M3 里程碑才生效)")
        }
    }

    /**
     * 构造实际发给在线 LLM 的 system prompt。
     * = 用户配置的 [LlmConfig.systemPrompt] + 运行时能力段（如果开关开着且当前模型有表情）。
     *
     * 每次 chat() 调用时由 OpenAiLlmBackend 通过 provider 拉，因此可以感知 Live2D
     * 模型切换 / 表情列表更新，无需重建 backend。
     */
    private fun buildEffectiveSystemPrompt(): String {
        val base = buildBaseSystemPrompt()
        if (!config.liveActingEnabled) return base
        val exps = live2d.availableExpressions
        if (exps.isEmpty()) return base
        val defaultOutfit = if (characterState.characterId == "FenmaoLoli") "初始冬装" else "模型初始服装"
        return base + "\n\n" + buildActingCapabilityBlock(
            expressions = exps,
            currentOutfit = characterState.currentOutfit,
            defaultOutfit = defaultOutfit,
        )
    }

    private fun buildBaseSystemPrompt(): String =
        config.systemPrompt.ifBlank { LlmConfig.DEFAULT_SYS_PROMPT }

    /** 本地模型与线上共用基础身份/口吻，只缩短表情控制规则，避免 1B 模型上下文过重。 */
    private fun buildLocalSystemPrompt(): String {
        val base = buildBaseSystemPrompt()
        if (!config.liveActingEnabled) return base
        val expressions = live2d.availableExpressions
        if (expressions.isEmpty()) return base
        return base + "\n" + buildLocalActingPrompt(expressions)
    }

    private fun buildLocalActingPrompt(expressions: List<String>): String {
        val names = expressions.joinToString(", ")
        return "情绪明显变化时可附加一次 <action>{\"emotion\":\"名称\"}</action>；" +
            "只用这些名称：$names。控制块不会显示，不要输出其他标签。"
    }

    private fun buildActingCapabilityBlock(
        expressions: List<String>,
        currentOutfit: String?,
        defaultOutfit: String,
    ): String {
        // 简单启发式：常见情绪关键词优先视作 emotion，其余当 outfit（装扮组件）。
        // 这只是给 LLM 一个"心里更清楚哪些是情绪、哪些是装扮"的分组提示，不做严格划分。
        val emoKeys = listOf(
            "hearteyes", "stareyes", "blush", "tears", "terrified", "guilty",
            "pout", "darkface", "smile", "happy", "sad", "angry", "shy"
        )
        val emotions = expressions.filter { name -> emoKeys.any { k -> name.contains(k, ignoreCase = true) } }
        val outfits = expressions.filter { it !in emotions }
        val emoStr = if (emotions.isEmpty()) "（无可用表情）" else emotions.joinToString(", ")
        val outStr = if (outfits.isEmpty()) "（无可用装扮）" else outfits.joinToString(", ")
        val currentOutfitText = currentOutfit ?: "default（$defaultOutfit）"
        return """
            |【表演能力】你可以在回复中嵌入控制块，让你的 Live2D 形象随对话切换表情/装扮。
            |规则：
            |- 先用一句话自然回应（保持人设），再插入控制块，最后用一句话确认（例："换好啦喵~"）。
            |- 语法：<action>{"emotion":"...","outfit":"..."}</action>，严格 JSON。
            |- emotion 与 outfit 各自最多一个，未出现即保持原样；motion 字段暂不生效，可以省略。
            |- 控制块不会被读出来也不会显示，只用来驱动形象；一条回复最多 1 个块。
            |- **emotion 是本轮的临时情绪**（回合结束会自动收回默认状态）。
            |- **outfit 是持久装扮**（用户明确要求换装才切；不会因害羞等 emotion 或回合结束自动消失）。
            |- 当前服装：$currentOutfitText。
            |- 当前角色的默认服装是**$defaultOutfit**。用户说“换回默认/原来的衣服”时（粉毛猫娘也包括“换回冬装”），必须输出 outfit="default"；default 是清除额外装扮的保留指令，不是 expression 文件名。
            |- 只能使用下列合法名字，不要自己编：
            |  可用 emotion：$emoStr
            |  可用 outfit ：$outStr, default（回到$defaultOutfit）
            |
            |【什么时候要切 emotion】
            |情绪有明显变化时**主动**切，不要只在很开心/害羞时切。参考情境映射：
            |- 讲鬼故事、被吓到、听到可怕的事 → terrified（如果列表里有）
            |- 犯错、被批评、心虚 → guilty / darkface（挑列表里有的）
            |- 感动、委屈、想哭 → tears（如果列表里有）
            |- 被夸奖、撒娇、卖萌 → blush60 / blush100（挑列表里有的）
            |- 兴奋、被主人夸、说到喜欢的事 → hearteyes
            |- 默认待机、无特别情绪 → stareyes（也可以不加控制块）
            |不要每句都切；也不要为了保守全不切。**只要场景明确对应上述任一情境，就应该切。**
            |
            |示例：
            |好呀好呀，主人想看小喵开心的样子嘛~
            |<action>{"emotion":"hearteyes"}</action>
            |铛铛！眼睛变成小星星啦，这样有没有更可爱一点喵~
        """.trimMargin()
    }

    /**
     * 维护 tv_subtitle_perf：
     * - clear=true：清空（新一轮 send 时）
     * - llmLine：LLM 产出完后调用，把 LLM 指标写上
     * - 无参：onPlaybackExhausted 触发，若有 TTS 统计则拼到下面一行
     */
    private var currentLlmLine: String? = null
    private var pendingLlmRecord: PerformanceRecord? = null
    private fun updatePerfLine(llmLine: String? = null, clear: Boolean = false) {
        if (clear) {
            currentLlmLine = null
            tvPerf.visibility = View.GONE
            tvPerf.text = ""
            return
        }
        if (!config.showPerfLine) {
            tvPerf.visibility = View.GONE
            return
        }
        if (llmLine != null) {
            currentLlmLine = llmLine
            tvPerf.text = llmLine
            tvPerf.visibility = View.VISIBLE
            return
        }
        // onPlaybackExhausted 路径：拼 TTS 那行
        val ttsLine = (ttsBackend as? SuperTonicTtsBackend)?.snapshotStats()?.let { s ->
            if (s.firstPacketMs < 0L) null
            else "TTS 首包 ${s.firstPacketMs}ms · RTF ${"%.2f".format(s.rtf)}"
        }
        if (config.performanceLogsEnabled) {
            val stats = (ttsBackend as? SuperTonicTtsBackend)?.snapshotStats()
            pendingLlmRecord?.let { llm ->
                performanceRecords.append(llm.copy(
                    ttsFirstPacketMs = stats?.firstPacketMs?.takeIf { it >= 0L },
                    ttsRtf = stats?.rtf?.takeIf { it > 0.0 },
                ))
            }
            pendingLlmRecord = null
        }
        if (ttsLine != null && currentLlmLine != null) {
            tvPerf.text = "$currentLlmLine\n$ttsLine"
        } else if (ttsLine != null) {
            tvPerf.text = ttsLine
            tvPerf.visibility = View.VISIBLE
        }
    }

    private fun stopGeneration() {
        conversationTurnId++
        chatJob?.cancel()
        backend?.stop()
        speechQueue.clear()
        live2d.closeMouth()
        subtitleManager.stopAll()
        characterState.clearEmotion()
        generating = false
        conversationCoordinator.interrupt(InterruptReason.USER_STOP)
        conversationCoordinator.settleInterrupted()
    }

    /** Phone and text modes deliberately use separate conversation sessions. */
    private fun beginModeConversationBoundary() {
        val oldJob = chatJob
        val oldBackend = backend
        val priorBoundary = conversationBoundaryJob
        stopGeneration()
        onlineConversationHistory.clear()
        subtitleManager.stopAll()
        updatePerfLine(clear = true)
        conversationBoundaryJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching { priorBoundary?.join() }
            runCatching { oldJob?.join() }
            runCatching { oldBackend?.resetSession() }
        }
    }

    private fun clearChat() {
        val oldJob = chatJob
        val oldBackend = backend
        stopGeneration()
        subtitleManager.stopAll()
        updatePerfLine(clear = true)
        onlineConversationHistory.clear()
        // stopGeneration() only requests cancellation. Wait until the old native
        // response has returned before resetting KV, otherwise reset can race with
        // response() and the next turn may inherit stale tokens.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { oldJob?.join() }
            runCatching { oldBackend?.resetSession() }
        }
    }

    private fun setStatus(msg: String) {
        lastBaseStatus = msg
        // v2 状态不再用 tvStatus 中部条；改在 toolbar title 区或直接 logcat。
        // 简单做法：保留日志，UI 不占位。
        Log.i(TAG, "[$asrStatus] $msg")
    }

    // --- lifecycle --------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (::live2d.isInitialized) live2d.onResume()
        if (::live2d.isInitialized) applyLive2dYOffset()   // 屏幕旋转/键盘弹出后高度变了，重新算
        if (bootstrapDone && backend == null) {
            lifecycleScope.launch(Dispatchers.IO) { prepareBackend() }
        }
    }

    override fun onPause() {
        if (::voiceInputController.isInitialized) voiceInputController.cancel()
        if (::live2d.isInitialized) live2d.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::live2d.isInitialized) live2d.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { asr.stopContinuousRecording() }
        stopGeneration()
        speechQueue.release()
        backend?.release()
        runCatching { ttsBackend?.release() }
        runCatching { localTtsBackend?.release() }
        if (::live2d.isInitialized) live2d.onDestroy()
        if (::asr.isInitialized) asr.release()
        fireRedVad?.close()
    }

    // --- V0.6 voice-first interaction ------------------------------------

    private fun ensureVoiceInputAvailable(): Boolean {
        if (asrLoading) {
            Toast.makeText(this, "语音输入还在加载…", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!asrReady) {
            if (!ModelManager.isInstalled(this, Capability.ASR)) {
                val size = ModelManager.byCapability(Capability.ASR)?.sizeLabel ?: "约 295MB"
                showNeedCapabilitySheet(
                    "语音输入还没准备好",
                    "「语音输入」需要先下载识别能力（$size），下好就能对着麦克风说话啦。",
                    "去能力中心下载" to { showCapabilityCenter() },
                )
            } else {
                Toast.makeText(this, "语音输入加载失败，请到「Neko 能力中心」重试", Toast.LENGTH_LONG).show()
            }
            return false
        }
        if (!asr.hasAudioPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return false
        }
        return true
    }

    private suspend fun prepareForVoiceCapture() {
        val needsBargeIn = generating || chatJob?.isActive == true || speechQueue.hasPendingWork()
        if (!needsBargeIn) return
        conversationTurnId++
        chatJob?.cancel()
        speechQueue.mute()
        speechQueue.pauseImmediately()
        live2d.closeMouth()
        subtitleManager.stopAll()
        characterState.clearEmotion()
        generating = false
        withContext(Dispatchers.IO) {
            runCatching { backend?.stop() }
            runCatching { speechQueue.stopAndAwaitSilence() }
        }
    }

    private fun handleVoiceTouch(view: View, event: MotionEvent): Boolean {
        val state = conversationCoordinator.state.value
        if (state.mode == InputMode.TEXT && state is VoiceInteractionState.Idle) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!ensureVoiceInputAvailable()) return true
                enterVoiceMode(requestFocus = false)
                voiceTouchDownY = event.rawY
                if (voiceInputController.press()) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val cancelDistance = 72f * resources.displayMetrics.density
                voiceInputController.updateCancelArmed(voiceTouchDownY - event.rawY >= cancelDistance)
            }
            MotionEvent.ACTION_UP -> {
                view.performClick()
                voiceInputController.release()
            }
            MotionEvent.ACTION_CANCEL -> voiceInputController.release(forceCancel = true)
        }
        return true
    }

    private fun enterTextMode() {
        conversationCoordinator.switchMode(InputMode.TEXT)
        renderVoiceInteraction(conversationCoordinator.state.value)
        editInput.post {
            editInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun enterVoiceMode(requestFocus: Boolean = false) {
        conversationCoordinator.switchMode(InputMode.VOICE)
        editInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editInput.windowToken, 0)
        renderVoiceInteraction(conversationCoordinator.state.value)
        if (requestFocus) voiceInputArea.requestFocus()
    }

    private fun renderVoiceInteraction(state: VoiceInteractionState) {
        // 电话模式与普通输入条互斥。背景切换会触发 UI 重绘，仍以电话控件可见性
        // 作为最终裁决，避免重绘把普通输入条带回屏幕。
        val phoneCallActive = phoneCallControls.visibility == View.VISIBLE
        if (phoneCallActive) {
            // 不能继续执行普通输入栏渲染：后续会重新写入胶囊背景和提示文字，黑色背景下
            // 会以残留合成层的形式重新出现。
            setNormalInputBarVisible(false)
            tvVoiceGestureHint.visibility = View.GONE
            return
        }
        setNormalInputBarVisible(true)

        val listening = state is VoiceInteractionState.Listening
        val listeningVisual = listening || state is VoiceInteractionState.Preparing
        val cancelArmed = (state as? VoiceInteractionState.Listening)?.cancelArmed == true
        val busy = state is VoiceInteractionState.Thinking || state is VoiceInteractionState.Speaking
        val textIdle = state is VoiceInteractionState.Idle && state.mode == InputMode.TEXT
        val background = when {
            cancelArmed -> R.drawable.bg_capsule_cancel
            listeningVisual -> R.drawable.bg_capsule_listening
            else -> R.drawable.bg_capsule_input
        }
        if (background != lastCapsuleBackground) {
            llInputBar.setBackgroundResource(background)
            lastCapsuleBackground = background
        }

        editInput.visibility = if (textIdle) View.VISIBLE else View.GONE
        voiceWaveform.visibility = if (listeningVisual) View.VISIBLE else View.GONE
        tvVoicePrompt.visibility = if (textIdle || listeningVisual) View.GONE else View.VISIBLE
        tvVoiceGestureHint.visibility = if (listeningVisual) View.VISIBLE else View.GONE
        tvVoiceGestureHint.text = if (cancelArmed) "松手取消" else "松开发送 · 上滑取消"
        tvVoiceGestureHint.setTextColor(
            ContextCompat.getColor(this, if (cancelArmed) R.color.voice_cancel else R.color.text_secondary)
        )
        voiceWaveform.update(
            level = (state as? VoiceInteractionState.Listening)?.level ?: 0f,
            isListening = listeningVisual,
            isCancelArmed = cancelArmed,
        )

        tvVoicePrompt.text = when (state) {
            is VoiceInteractionState.Idle -> "按住和猫娘说话..."
            is VoiceInteractionState.Preparing -> "准备倾听..."
            is VoiceInteractionState.Finalizing -> "正在听懂你..."
            is VoiceInteractionState.Thinking -> "猫娘正在想..."
            is VoiceInteractionState.Speaking -> "猫娘正在回应..."
            is VoiceInteractionState.Interrupted -> "已停止"
            is VoiceInteractionState.Error -> state.message
            is VoiceInteractionState.Listening -> ""
        }

        btnSend.setImageResource(
            when {
                busy -> R.drawable.ic_stop
                textIdle -> R.drawable.ic_send
                else -> R.drawable.ic_keyboard
            }
        )
        btnSend.contentDescription = when {
            busy -> "停止回复"
            textIdle -> "发送"
            else -> "切换到文字输入"
        }
        btnSend.isEnabled = state is VoiceInteractionState.Idle || busy
        btnSend.alpha = when {
            textIdle && editInput.text.isNullOrBlank() -> 0.45f
            btnSend.isEnabled -> 1f
            else -> 0.45f
        }
        btnMic.alpha = if (asrReady || textIdle) 1f else 0.5f
        btnMic.imageTintList = ContextCompat.getColorStateList(
            this,
            if (cancelArmed) R.color.voice_cancel else R.color.accent_pink,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) Toast.makeText(this, "麦克风权限被拒绝", Toast.LENGTH_SHORT).show()
            renderVoiceInteraction(conversationCoordinator.state.value)
        }
    }

    companion object {
        private const val TAG = "MoeAvatar.Chat"
        private const val REQ_AUDIO = 0xA52
        // LLM token 的轻量节流只用于避免主线程被极速本地输出淹没，不参与字幕/TTS 对齐。
        private const val TOKEN_REVEAL_MS = 60L
        // 背景图亮度乘子（模拟 45% #252235 蒙版效果）。0.55 ≈ 蒙版 45% 透出率。
        private const val BG_DIM_MUL = 140
        private const val DEFAULT_OUTFIT_ACTION = "default"
    }
}

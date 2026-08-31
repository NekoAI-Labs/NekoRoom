# CHANGELOG

## v0.6.7 · ASR 1.2 / NekoVoice v1.3 / Live2D 管理 / 启动稳定性 (2026-08-04)

### Features
- **ASR 模型注册升级**：新增 `asr-zipformer-medium-fp16`（语音识别 1.2，~150.5MB，
  默认推荐），旧 `asr-zipformer-old-int8`（语音识别 1.1，~295.3MB）保留为兼容项；
  对外统一显示「语音识别 1.2 / 1.1」。
- **ASR modelType 自动探测**：`ChatAsrController.buildConfig()` 移除写死的
  `modelType="zipformer"`，由 sherpa-mnn 从 encoder MNN metadata 读取
  `model_type=zipformer2` 自动分派（host C++ demo 已验证同一套 1.2 MNN 自动识别）。
- **TTS v1.3 注册**：新增 `tts-nekovoice-v13-int8`（NekoVoice 1.3，Mixed：
  DP/TE/VE INT8 + vocoder FP16，仓库实核 138,686,415B）；11 个脱敏音色
  `voices/{neko,F1..F5,M1..M5}.json`，默认 `neko`；`tts-nekovoice-v11`
  （catgirl_style）保留兼容回退。
- **TTS 能力中心两列布局**：语音合成卡左边选模型、右边选音色；切换模型时
  就地刷新音色下拉并重置为该版本默认音色（v1.3→neko，v1.1→catgirl_style），
  不再重建整个弹窗。
- **Live2D 角色删除**：仅允许删除 `getExternalFilesDir("live2d")/<id>` 的直接子目录；
  删除当前角色回退 `DEFAULT_NAME`；内置角色不可删；导入继续保留 zip-slip 与
  `.model3.json/.moc3/PNG` 完整性校验。
- **Live2D 冷启动提速**：内置模型首次启动后台解包到
  `filesDir/live2d_builtin/<name>/`，之后从文件系统加载（native 对 `/` 开头路径走
  FileInputStream），避免每次冷启动从 APK assets 逐个 JNI 解压；解包失败回退 assets。
- **SuperTonic 合成上限**：`MAX_L_TTL` 100→450（≈31.3s，模型按 ~30s 训练；
  GetMNNInfo 确认 DP/TE/VE/Vocoder 输入 T/L 全动态，图内无固定长度算子）。
  `SentenceSplitter.maxHard=56` 字（≈60 token ≈14.5s 音频，首包 ≈ RTF×时长 ≈3s），
  标点处优先切分，绝大多数句子远小于上限。
- **能力中心 UI**：卡片标题固定为「本地大脑 / 语音输入 / Neko 离线语音」，
  不再随模型名变化；移除描述行、下拉高度 48→40dp、整体收紧；
  音色顺序固定为默认猫娘 → 女声 1–5 → 男声 1–5。
- **性能浮层锚定**：`tv_perf` 原锚定 `ll_input_bar`，电话模式 detach 后约束失效
  掉到左上角；改为 `ConstraintSet` 动态重锚（电话模式 → 父容器右下，
  退出恢复输入条上方），并同时写 `endToEnd`/`rightToRight` 规避版本解析差异。

### Fixes
- **P0 启动闪退**：`modelType="zipformer"` 硬编码让 sherpa 直接 dispatch 到旧
  Zipformer 类，zipformer2 模型缺 `attention_dims` → `SHERPA_ONNX_EXIT(-1)`
  `exit(-1)` 进程退出（Kotlin `runCatching` 接不住，后续
  `pthread_mutex_lock on destroyed mutex` 为次生现象）。改留空自动探测后
  连续冷启动多次无崩溃。
- **电话模式字幕消失**：`setPhoneControlsVisible(true)` 原先 `stopAll()` 并
  GONE 掉 `tv_subtitle`；改为保留字幕（字幕条无 elevation，不会产生残影），
  用户原话与 AI 回复在通话中继续上屏。
- **幽灵输入框（深色背景 + SurfaceView）**：隐藏输入条时额外
  `setOutlineProvider(null)` + `LAYER_TYPE_NONE`，并新增
  `Live2DController.reassertZOrder()`（重设 media-overlay z-order 重建 Surface），
  `decorView.invalidate()` + nudge 双拍刷新，清除合成器残留的旧胶囊帧。
- **打字时输入条掉到键盘底下**：`setNormalInputBarVisible` 不再强制
  `translationY=0`，改为恢复 `-lastImeBottomPx` 保持键盘上浮量；insets 监听器
  维护 `lastImeBottomPx`。
- **文字/语音模式切换**：发送后恢复默认回语音模式
  （`finishTextSubmission` 重新 `switchMode(VOICE)`）；键盘收起经 insets
  监听器回语音；文字模式仅由键盘图标临时进入。
- **语音会话迟到收尾**：`ConversationCoordinator.finishVoiceSession/failVoiceSession`
  用 `currentMode` 替代硬编码 `VOICE`，避免迟到回调把文字模式拉回并藏掉输入框
  （新增回归测试）。
- **能力中心 LLM 下拉不切换**：`switchLlm()` 定义但从未被调用；接线为选中即
  切换（未安装时先不切，下载完成后由 `onCapReady` 热加载）。
- **默认音色**：`LlmConfig.ttsVoiceId` 存储默认值 `catgirl_style`→`neko`；
  v1.1 场景由 `ModelManager.resolveTtsVoice` 回退 `catgirl_style`。
- **注册表大小修正**：TTS v1.3 `sizeBytes` 160MB→138,686,415B（核 ModelScope
  仓库字节数）；ASR 1.2（150.5MB）/ ASR 1.1（295.3MB）确认无误。
- **过期单测修正**：`SentenceSplitterTest.hardLimitPreventsUnboundedBuffer` 按旧
  `maxHard≈28` 编写，更新为 `maxHard=56`（57 字 → feed 56 + flush 1），并新增
  「29 字不提前切」回归测试。

### Refactor
- ASR `prepare()/release()` 与 SuperTonic `init()/release()/synth()` 外包
  `MnnGlobalLock`，与既有 decode 锁一致；bootstrap 固定 ASR→TTS→LLM 串行初始化。
- `SuperTonicTtsBackend` 构造不再传死 voiceId，改由
  `ModelManager.resolveTtsVoice()` 按当前模型解析。
- 内置 Live2D 模型加载统一走 `ensureBuiltinModelDir()`（内置解包、自定义原路径）。

### Validation
- `:app:testReleaseUnitTest` 17 项通过（新增 ConversationCoordinator 迟到收尾、
  SentenceSplitter 29 字不提前切；修正过期 hardLimit 测试）。
- `:app:assembleRelease`（R8 + shrinkResources）通过并安装真机；连续冷启动多次
  无崩溃、crash buffer 为空。
- 真机 mic 实测 ASR 1.2：「你好听得见吗」RTF≈0.146；TTS 正常合成（首包 881ms，
  RTF 0.21，valid_l 59/64）；长句 DP 可产出 `valid_l=106`（>旧 100 帧上限）。
- 电话模式：字幕「你好你好」正常上屏；性能行锚定日志
  `anchorPerfLine toBar=false (end→parent, bottom→parent.bottom)`。

### Known Limitations
- ASR 1.2 zh 短句 CER 仍偏高（独立质量问题，见 `neko-asr/docs/HANDOFF.md`）；
  `numThreads=1` 为 MNN Zipformer cache 稳定性约束，勿改 4；该问题与本次
  启动修复分开跟踪，不混在一次改动里。
- SuperTonic 单次合成仍为一次性 JNI 返回完整 PCM：长句首包 = 整句合成耗时
  （56 字最坏约 3s），后续优化方向是流式分 chunk。
- `MnnGlobalLock` 全局串行化保留：TTS 整句持锁 1–2s 会延迟 ASR decode，
  属设计取舍；不要直接移除锁。
- 电话模式外放残响偶发自打断、真人打断首词保留、多次打断后 UI 稳定进入聆听，
  仍需真机回归。
- iQOO Neo5s「性能浮层不显示」尚无复现日志，未确认机型专属，需该设备 logcat。

## v0.6.4 · MiniCPM5 模型与本地推理稳定性 (2026-07-23)

### Features
- 本地模型列表新增/整理 MiniCPM5 1B 的 Q8、FP16、Q4、Q4+INT8 Embedding、Q4+INT4 Embedding 五个版本。
- 默认本地模型切换为 `jiaohui/MiniCPM5-1B-MNN-Q8`，第二个高质量选项为
  `jiaohui/MiniCPM5-1B-MNN-FP16`；两者均包含 `embeddings_bf16.bin`。
- 本地推理直接使用模型目录中的 `config.json`，不在 Android 层额外覆盖 sampler 参数；加载前继续归一化
  `enable_thinking=false`。
- 本地多轮对话使用完整 `ChatMessages` 历史并清理 native KV cache，聊天输出增加 64 token 硬上限，
  降低模型异常重复导致的长时间生成。
- 开发者模式中的 TTS 参数名称改为 `TTS Quality`，内部仍使用 3–8 的 denoising steps，默认 8。

### Model validation

- Linux 原生 MNN 对 Q8 和 FP16 各进行 20 轮对话验证，均无进程崩溃或无限重复。
- FP16 原始配置质量最佳；Q8 是内存、速度和质量的默认平衡方案；Q4 保留为低内存兼容选项。
- 详细量化差异、配置原则和异常采样实验见
  [`TECHNICAL_MINICPM5_QUANTIZATION_V064.md`](TECHNICAL_MINICPM5_QUANTIZATION_V064.md)。

## Next · 多本地 LLM 与统一多轮对话 (2026-07-23)

### Features
- 本地 LLM 支持 Qwen3.5 0.8B、Qwen3 1.7B、Qwen3 0.6B、Neko 猫娘、Qwen3.5 2B 五个可下载模型。
- Qwen3.5 0.8B 为默认模型，并继续与 ASR/TTS 一起进入一键安装推荐配置。
- 本地模型与线上模型统一使用 15 轮短期历史、system prompt 和 Live2D 表情能力段。
- 设置中新增本地模型选择入口，下载完成后可自动切换。
- 本地模型默认使用短版 system prompt，开发者模式增加“启用表情反馈（本地/线上）”开关，
  可单独关闭表情能力以测试纯文本多轮对话。

### Fixes
- 能力中心调整为 3 张能力卡（本地大脑 / 语音输入 / Neko 离线语音）；三个本地 LLM 改为本地大脑卡片内的下拉选择。
- 本地 JNI 新增 `ChatMessages` 入口，修复本地模型只收到最后一条用户消息的问题。
- 加载本地 Qwen 配置前递归关闭 `enable_thinking`，避免思考内容进入字幕和 TTS。
- TTS 输出过滤 `<think>`、`<action>` 及成对括号/花括号/中括号控制内容；普通文字和语气词保留。
- “重置对话”同时清除 App history、native KV cache，并通过 turn token 防止旧请求写回历史。
- 本地 SuperTonic 短句最小时长从 2.5 秒调整为 0.8 秒，PCM 片段首尾增加 8ms 淡入淡出，减少短句拖音和首尾破音。
- ASR、TTS 共用 `MnnGlobalLock` 串行化 `libMNN.so` 推理，修复已确认的 ThreadPool native 竞态崩溃。
- 开发者性能日志在 TTS 后端创建时同步开关状态，能够输出每句 `tts_clause` 的样本数、耗时和 RTF。

### Known Limitations
- 当前只有内存中的短期会话历史，持久化 memory 系统另行开发。
- 模型切换会主动清空当前短期会话。
- 尚未找到大小、速度和指令遵循能力都合适的本地小模型，能够稳定支持高质量多轮对话。
- Qwen3 系列本地模型当前无法像 Qwen3.5 一样可靠关闭 thinking；其仓库 `config.json` 没有可递归关闭的
  `enable_thinking` 字段，模型模板/权重侧可能仍会输出思考内容。Qwen3.5 可以关闭 thinking，
  但当前本地对话效果仍不理想。
- `<think>...</think>` 已有输出过滤兜底，但这只能隐藏思考内容，不能提升 Qwen3 系列本身的指令遵循能力。

### UI 修正
- 本地模型下拉改为磨砂主题样式，去除系统默认白色弹出菜单。
- 开发者模式直接显示“显示性能信息”和“输出性能日志”两个开关。
- 移除不可运行时配置的 SME2 开关；SME2 由编译配置开启并由 MNN 自动检测 CPU。

## Next · 开发者性能面板 (2026-07-23)

### Features
- 设置底部新增「开发者模式」，默认关闭；开启后才显示折叠的「性能优化」二级选项。
- 「显示性能信息」移动到二级选项，并新增 TTS 性能阶段 logcat 开关。
- 增加 SME2 编译配置记录，默认开启；切换提示需要重新编译 MNN 生效。

### Notes
- SME2 是 `libMNN.so` 的编译期能力，APK 开关不会在运行时替换已打包的 native 库。

## Next · TTS voice model v1.1 / dynamic shape (2026-07-23)

### Features
- 本地 SuperTonic 模型注册切换到 `jiaohui/NekoVoice-v1.1`。
- 音色文件改为 `tts/voices/<voiceId>.json`，`ttsVoiceId` 从 `LlmConfig` 读取，默认
  `catgirl_style`，为后续多音色切换保留配置入口。
- SuperTonic C++ 推理输入按实际文本 token 数和有效 latent 长度创建，避免固定 256 token / 100
  latent 帧的无效计算；模型权重仍由 ModelScope 下载到应用私有目录。

### Refactor
- 下载器支持带子目录的 `requiredFiles`，会自动创建 `voices/` 目录。
- 清理实验目录中的部署模型副本；模型与试听输出保留在 `/mnt/d/models/tts`。

### Validation
- `/mnt/d/models/tts/mnn_fp16` 已存在四个 MNN 权重和 `voices/catgirl_style.json`。
- 动态 shape 的 Python/MNN 合成验证报告见 `/mnt/d/models/tts/listen_test/report.json`。
- Android APK/native 编译待同步到工作副本后验证。

## v0.6.2-neko · Patch / 下载并发 + 状态行不抖 + 描边按钮 (2026-07-23)

围绕上一版遗留的下载 UX 抖动、按钮反馈缺失做收尾。**Live2D 冷启动占位淡入方案已设计但未合入**
（见交接文档 §3-未完成）。

### Features
- 「Neko 能力中心」下载器改为**文件级 3 路并发** —— `coroutineScope + Semaphore(3) + AtomicLong`
  共享记账；buffer 从 64KB 提到 256KB。同一文件仍单路续传，不冲 ModelScope。
- 首帧立即上报：进入 `download()`、切到下一个文件都强制 `forceEmit`，UI 不再"卡在 0MB / 准备中"。
- 重试也上报：`ProgressReporter.reportRetry` 用 `文件 · 重试 x/y` 侧道过 currentFile，卡片状态行不闪断。
- 下载状态行改**两行**布局，避免挤占左侧标题：
  - 上：`(已下 / 总量 · 单位)` 单位只跟总量出现一次；<1MB 用 KB，其余 MB。跟一段 `· 速度` 或 `· 重试 x/y`。
  - 下：`99%` 独占一行贴在进度条上方 6dp，靠右显示。
  - 标题（"本地大脑"）改 `wrap_content + ellipsize=END`，右侧 status 拿 `weight=1 + gravity=END`。
- 速度单位自适应：`<1MB/s` 显示 `KB/s`，其余 `MB/s`。

### Fixes
- 「测试连通性」按钮改用 `bg_btn_outline` selector + `stateListAnimator="@null"`。粉色描边**恒定不变**，
  仅**内芯**在 pressed / disabled 时从 15% 白磨砂降到 5%，测试中按钮仍完整可见（先前会"按空掉"）。
- `dialog_online_config.xml` 的 `btn_test_conn` 去掉 `borderlessButtonStyle`；`activity_llm_chat.xml`
  的进度条 `topMargin` 8dp→2dp 让百分比行贴合。

### Files
- `app/src/main/java/com/moeavatar/model/ModelScopeDownloader.kt`（并发 + 首帧 + 重试上报）
- `app/src/main/java/com/moeavatar/chat/LlmChatActivity.kt`（`CapCard.pct` 独立行、`updateCapProgress`
  单位自适应 / 单单位、`buildCapCard` 标题/status 权重反转）
- `app/src/main/res/drawable/bg_btn_outline.xml`（新增）
- `app/src/main/res/layout/dialog_online_config.xml`（btn_test_conn 换背景）

### Validation
- 归档树与工作副本代码路径核对；工作副本 `:app:assembleDebug` 通过；真机 SiliconFlow / DeepSeek 端到端
  下载 501MB 大脑 + 测试连通性 UI 回归通过（速度 5–8MB/s，状态行不抖，进度条上方百分比对齐）。

### Known Limitations / 未完成（转下位 agent）
- **Live2D 首次冷启动 3~5s 空档**（背景先出、角色后出）：已定位为 GL 线程贴图解码+上传耗时，非新增
  bug；方案 1「chibi 占位淡入」已设计但**未合入**——见 `HANDOFF_v062_20260723.md` §3。
- ASR 长期潜伏的 native 竞态（`libNN.so` ThreadPool 与 LLM/TTS 共享）：与本次改动无关，见
  `docs/bugs/bug0722.md`。

## v0.6.2-neko / 在线 LLM 轻量客户端化 (2026-07-22)

### Features
- 在线模型面板 Provider 改为**下拉菜单**（Spinner），默认 DeepSeek，共 4 项：DeepSeek / SiliconFlow /
  Agnes 2.0 / 自定义（OpenAI 兼容）。base URL 统一带 `/v1`。
- 新增「测试连通性」按钮 —— 按钮文本恒定不变，结果显示在**独立的下方文字**：成功高亮色显示延迟，
  失败次色显示分类原因（认证失败 / 限流 / 模型不存在 / HTML 拦截 / 网络错误 等）。
- 高级折叠区提供两个**产品化控件**（不再让用户手写 JSON）：
  - `启用思考模式` 开关 —— 默认**关**，App 自动下发 `enable_thinking:false` +
    `chat_template_kwargs.enable_thinking:false`（覆盖 DashScope/SiliconFlow/vLLM），DeepSeek 走
    `thinking:{type:disabled}`。
  - 温度滑块 —— `SeekBar` 0.00–2.00 步长 0.01，默认 0.70。
- API Key 输入框 hint 改为 `sk-...（长按此处粘贴）`，视觉上不再和已填内容的密码掩码混淆。

### Fixes
- 在线 API 出错时不再把 500 字 HTML 拦截页糊到字幕；改为 `ApiErrorMapper` 归类后展示 "认证失败 · …"
  等短语（新增文件 `llm/ApiErrorMapper.kt`）。
- 老版本按 baseUrl 字符串匹配 `deepseek/qwen` 才关思考的 hack 已删除；改由 Provider preset 决定。
- 修 base URL hint 与实际推荐值冲突（老 hint "免填 /v1" vs 新预设都带 /v1）。

### Refactor
- 新增 `llm/ProviderPreset.kt`：`ReasoningStyle` 三态 + `REASONING_MODIFIERS` 数据表，加 Provider
  或加思考关闭协议不再改调用点。默认 provider 从 Agnes 改为 DeepSeek。

### Configs
- `LlmConfig` 新增 `providerPresetId`（默认 `deepseek`）、`temperature`（默认 0.7）、`enableThinking`
  （默认 false）。老用户首次读取时按已存 `openAiBaseUrl` 自动推断 preset。

### Validation
- `:app:assembleDebug` 通过。真机 SiliconFlow / DeepSeek / Agnes 测试按钮回归通过。
- `logcat -s OpenAiLlmBackend`：Qwen3-4B 请求体确认含 `enable_thinking:false` +
  `chat_template_kwargs.enable_thinking:false`，首字延迟从 5–15s 降到 <2s。

### Known Limitations
- 未实现 OpenAI o1/o3 `reasoning_effort`；未做 429 自动重试；API Key 仍以 SharedPreferences 明文存储。

## v0.6.1-neko / AI 身份说明与轻量安全层 (2026-07-20)

### Features
- 应用对外名称改为 `NekoChat`，左上角增加低存在感的“AI 生成内容”标识。
- 首次进入弹出一次磨砂说明，明确小喵是 AI 虚拟角色、并非真人；不增加顶部居中提示横幅。
- 新增 `assets/safety/*.txt` 分类词库和 `ContentSafetyGuard`，在用户输入发送前、AI 完整句子进入
  共享字幕/TTS 前做双向检查。命中内容不进入模型、字幕、语音或线上会话历史。
- 自伤风险使用支持性提示，其他内测类别使用固定安全回复；日志只记录类别，不记录原文。
- 角色选择页把 `FenmaoLoli` 的对外显示名改为 `Fenmao`，隐藏中文旧名和内部完整 ID。
- 内测包只保留 Fenmao 一个内置 Live2D 模型，构建时排除旧 ATRI/Ziyan 资产，并迁移旧用户的角色选择，
  避免升级后加载不存在模型导致只剩背景。

### Validation
- `:app:testDebugUnitTest` 通过，共 12 项测试（含 4 项安全规则测试）。
- `:app:assembleDebug` 通过，并确认 APK 包含 5 个分类 TXT 词库。

### Known Limitations
- 当前是内测轻量词表，只支持文本短语匹配，不具备上下文理解、谐音识别或远程热更新能力。

## v0.6-neko / 共享字幕同步修复 (2026-07-20)

本次围绕语音交互的“字幕领先/落后音频”问题做了链路重构，并补齐 ASR 结果在共享字幕中的短暂展示。

### Fixes
- **修复 AI 字幕与 TTS 不同步**：去掉 `SubtitleManager` 的固定字速打字机动画，改为在
  `SpeechQueue` 首个有效 PCM 到达时先驱动字幕上屏，再写入 `AudioTrack`。这样字幕不再依赖
  `60ms/字` 之类的 magic number，也不会出现音频先播、字幕后追的情况。
- **补齐在线流式 TTS 的句子文本**：在线 MiniMax 流式 chunk 的 `text` 可能为空，本次把首次有效
  PCM 绑定到原始句子，确保 `onClauseStart` 能拿到可显示文本。
- **ASR 结果进入共享字幕**：按住说话松手后，识别出的最终文本先短暂显示在共享字幕中，再进入
  正常发送流程，便于用户确认刚才说了什么。
- **避免旧字幕被立即清空**：发送前先停掉上一轮音频/字幕，再展示本轮用户文本或 AI 文本，避免
  文本刚上屏就被 `stopAll()` 覆盖。

### Files
- `app/src/main/java/com/moeavatar/chat/LlmChatActivity.kt`
- `app/src/main/java/com/moeavatar/chat/subtitle/SubtitleManager.kt`
- `app/src/main/java/com/moeavatar/tts/SpeechQueue.kt`

### Validation
- 归档树与完整工作副本均已检查代码路径。
- 完整工作副本 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 通过。
- 真机安装与启动验证通过，未引入崩溃。

## v0.4.1-neko / Alpha 首装回归修复 (2026-07-18)

首装 → 下载模型 → 语音输入完整流程回归发现的三个 bug，全部 fix。详细根因分析见
[POSTMORTEM_20260718.md](POSTMORTEM_20260718.md)。

### Bugfix
- **ASR 授权后按麦克风 SIGSEGV**：`ChatAsrController.prepare()` 在构造前用
  `ModelManager.isInstalled` 校验必需文件全在，避免 sherpa 构造器"成功但 native 指针为 null"
  的坏对象在下一次调用崩溃。
- **删除 TTS/LLM/ASR 模型后仍能合成/推理**：`deleteCap()` 先 stop 当前播放/推理并 `release()`
  native runtime，再删磁盘文件；否则 mmap 建立后删文件不生效（表面看是"假删除"）。
- **首启欢迎弹窗关掉后 Live2D 角色消失**：`Live2DController.nudge()`（GONE→VISIBLE）在
  欢迎弹窗和能力中心 dismiss 时都会调用，修 BottomSheetDialog 关闭后 media-overlay surface
  被压到主 window 之下的老 SurfaceView z-order bug（Vivo/HyperOS 尤其明显）。
- **AudioTrack 释放竞态**：`SpeechQueue.driveMouth()` 加 `STATE_INITIALIZED` 检查 +
  `IllegalStateException` catch，避免 track 已 release 时 ticker 访问 `playbackHeadPosition` 崩溃。

## v0.4-neko / 能力中心 · 免存储权限分发 (2026-07-15)

面向**内测分发**的一版：不再依赖 `adb push` 预置模型，普通用户装 APK 后可在**「Neko 能力中心」**
按需下载三套能力（本地大脑 / Neko 离线语音 / 语音输入），并去掉「所有文件访问权限」，只保留麦克风。
versionName `0.3-neko`→`0.4-neko`（versionCode 3→4）。

### Features
- **模型迁移到应用私有目录**（`ModelManager`，`getExternalFilesDir("models")/{llm,asr,tts}`）：
  彻底去掉 `MANAGE/READ/WRITE_EXTERNAL_STORAGE`，Manifest 只剩 `INTERNET` + `RECORD_AUDIO`。
  LLM 走 `llm/<subdir>/config.json`（`ModelScanner` 需子目录），ASR/TTS 扁平放。
- **ModelManager 能力注册表**：`REGISTRY` 声明每个能力的产品名/描述/ModelScope 仓/必需文件/大小/
  推荐位，检测（`isInstalled` 按 requiredFiles 全在且非空）、删除、路径解析都读表，不写死。
- **精简 ModelScope 下载器**（`ModelScopeDownloader`）：OkHttp 拉文件清单取大小 → 逐文件
  `Range` 断点续传写 `.part` → 校验大小后 rename；进度/速度回调、暂停（保留 `.part` 下次续传）、
  失败退避重试 3×。不做 blob/snapshot 软链缓存（三仓公开扁平，KISS）。
- **Neko 能力中心**（磨砂 BottomSheet 全屏展开）：顶部总览（已装 X/3）+「一键安装推荐配置」+ 3 张
  能力卡（未安装/下载中/已安装 + 下载/暂停/继续/删除 + 进度条）。LLM/TTS 卡另给「或使用在线服务 ›」
  直达在线配置。下载完成**热加载**对应后端（ASR 重 prepare、本地 TTS/LLM 重建），无需重启。
- **按需引导提示**（`showNeedCapabilitySheet`，产品化文案不露模型名）：
  没大脑→发消息弹「下载本地大脑 / 配置在线服务」；没语音→首发消息弹「下载离线语音 / 用在线语音」
  （文字聊天照常）；没识别能力→按麦克风弹「去能力中心下载（约 295MB）」。
- **首启欢迎引导**（`maybeShowWelcome`，`seenWelcome` 只弹一次）：检测缺失的推荐能力并列出，
  给「一键安装推荐配置 / 稍后手动」。无模型不崩。
- **对外改名**：设置页「模型」行改显 `大脑：本地大脑（离线）/ 在线服务 / 未安装`，不再露
  `qwen…` 目录名。语音相关状态文案改「语音输入…」。（内部类名 `SuperTonic*` 等不动，仅改对外可见文案。）

### 说明
- 三套能力从 ModelScope 匿名下载：LLM `jiaohui/qwen35_08b_nekoneko-MNN`、
  ASR `MNN/sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20`、语音 `jiaohui/NekoVoice-v1`。
- `scripts/push_models.sh` 仍保留，仅供开发预置到私有目录（省得真机重下），正式分发走能力中心。

## v0.3-neko / v3.4-custom-live2d (2026-07-15)

支持**导入自定义 Live2D 模型（ZIP）**：不再只有内置角色，用户可上传符合 Cubism 3/4/5 规范的
模型包，导入后出现在「切换角色」列表并热切换。同时把「切换角色」弹窗改成磨砂 BottomSheet
（原 AlertDialog 太丑、风格不统一）。versionName `0.2-neko`→`0.3-neko`。

### Features
- **自定义模型加载走文件系统**：`Live2DController.resolvePreset(id)` 先查内置 `PRESETS`，否则在
  `getExternalFilesDir("live2d")/<id>/` 下找 `*.model3.json`，用**绝对路径**构造 preset。底层
  `JniBridgeJava.LoadFile` 早已支持 `/` 开头的文件系统路径，native `LAppModel` 会把 `_modelHomeDir`
  前缀拼到 moc3/贴图/physics/exp——所以 **C++/JNI 零改动**。自定义模型无调好的构图，统一给默认
  `scale=2 / translateY=-0.3`（后续可加手动校准）。
- **ZIP 导入 + 校验**（`importModelZip` / `doImportModelZip`）：SAF 选 zip → 解压到 cache（zip-slip
  防护）→ 校验：① 必须含 `*.model3.json`；② 其 `Moc` 引用的 `.moc3` 存在（拒绝 Cubism 2.x 的
  `.moc`）；③ 引用的贴图存在。通过则移到 live2d 目录并热切换。**免新权限**（外部私有目录 + SAF）。
- **校验失败弹磨砂错误提示**（`showModelErrorSheet`）：红色标题「导入失败」+ 具体原因，措辞明确指出
  是**模型包**的问题（缺 model3.json / 是 2.x 老模型 / 缺 moc3 / 缺贴图 / 非法路径），不是软件 bug。
- **native 加载失败兜底**（`onLive2dLoadError`）：自定义模型 moc3 版本过新（>v5.3）或损坏 →
  `onLoadError` 回调 → 弹提示 + 自动切回默认角色，不留黑屏。
- **「切换角色」改磨砂 BottomSheet**（`dialog_avatar_picker.xml`）：与设置/在线配置同款 `bg_sheet`
  磨砂，角色行代码动态生成（两个内置 + 扫描到的自定义），当前项打勾（`ic_check`），底部「导入模型
  （ZIP）」入口（`ic_import`）。替换旧的 `AlertDialog.setSingleChoiceItems`。
- 新增 `error_red` 珊瑚红色板（暗紫主题下柔和不刺眼）。

### Fixes
- **修复 `colors.xml` 两棵树长期分叉**：归档树 `colors.xml` 一直是 v3.1 旧浅色伪磨砂值，缺
  `glass_fill_top/glass_fill_bottom/glass_shadow`（工作副本 v3.3 暗玻璃 drawable 引用了它们）。
  本次把归档树补齐成 HANDOFF 记录的暗玻璃实测值，两树 `colors.xml` 归一，归档树自此内部自洽。

### 说明 / 已知限制
- **能用的前提**：Cubism 3/4/5 导出的 moc3 模型（moc3 ≤ v5.3）、含标准 `model3.json` + moc3 + 贴图。
  Cubism 2.x（`.moc`）、moc3 > v5.3、加密打包、无 model3.json 的包 → 校验/加载失败并给出中文原因。
- **口型/追手依赖标准参数名**（`ParamMouthOpenY` / `ParamAngleX` 等）。改过参数名的魔改模型能显示但
  这些功能会静默失效，不崩。
- 自定义模型构图是默认值，未做逐模型微调 UI；ATRI 内置 preset 是 `scale=3/ty=-0.6`，但作为自定义导入
  时用默认 `scale=2/ty=-0.3`，仅用于验证链路。

## v3.3.1-online-ui (2026-07-14)

在线配置面板的**风格与交互修缮**（承接 v3.3-online 的真机反馈），无功能级新增。

### Fixes / Refinements
- **在线配置弹窗改磨砂 BottomSheet**（原 AlertDialog）：旧 AlertDialog 的白卡片 + 全屏 dim 会让
  Live2D 的 GLSurfaceView（`setZOrderMediaOverlay`）渲成黑屏 → 打开配置后「角色不见了」。改用
  `BottomSheetDialog` + `@drawable/bg_sheet`（与主设置面板同款磨砂），`STATE_EXPANDED` 直接展开。
- **输入框可读且好点**：新增 `@drawable/bg_input`（frost_surface 底 + 1dp frost_border 描边 + 12dp 圆角），
  `minHeight=48dp` `padding=12dp`，text_primary 文本 / text_tertiary hint，解决「输入框不好点、点不进去」。
- **服务商快选（4 个）**：`rg_provider` 单选 DeepSeek / Agnes / OpenAI / 自定义。选中即自动填 base+model
  并勾「在线 API」；「自定义」不改动当前值。初始按已存 base 预选（先 `.check()` 再挂 listener，避免初始化覆盖）。
  - DeepSeek → `https://api.deepseek.com` / `deepseek-v4-flash`
  - Agnes → `https://apihub.agnes-ai.com` / `agnes-2.0-flash`
  - OpenAI → `https://api.openai.com` / `gpt-4o-mini`
- **base 采用 provider 约定**：填站点根（免填 `/v1`），代码内部拼 `/v1/chat/completions`（见
  `OpenAiLlmBackend.buildChatUrl`）。默认 `DEFAULT_OAI_BASE=https://apihub.agnes-ai.com`、
  `DEFAULT_OAI_MODEL=agnes-2.0-flash`（原带 `/v1`，本次去掉）。
- **设置行加云图标** `ic_cloud`（text_primary tint），与「切换角色 / 更换背景」等行左图标对齐。

### 说明
- **API Key 默认为空**（`openAiApiKey` 默认 `""`）。若配置框里 Key 显示 `***`，那是
  `inputType=textPassword` 对**已存值**的掩码——SharedPreferences（`moeavatar_llm.xml`）里已有你测试时
  输入的真实 key，`adb install -r` 不清数据故保留。非默认、非 bug。**Key 不进 git。**

## v3.3-online (2026-07-14)

支持**线上模型**：LLM 走 OpenAI 兼容 API（SSE 流式），语音走 **MiniMax** 在线流式 TTS，
可在设置里配置并与本地后端一键切换。猫娘人设提示词自定义，**仅在线 LLM 生效**（本地后端忽略）。

### Features
- **在线 LLM 后端 `OpenAiLlmBackend`**：`POST /v1/chat/completions` `stream=true`，自解 SSE；
  baseUrl 结尾有无 `/v1` 都自动补齐；deepseek/qwen 系自动带 `enable_thinking=false`；
  过滤 `<think>…</think>` 与 `reasoning_content` 只放行可见文本。无状态，systemPrompt 由它注入。
- **在线 TTS 后端 `MiniMaxStreamTtsBackend`**：`POST /v1/t2a_v2` `stream=true`，32kHz/int16/mono
  PCM(hex) → float 喂 `SpeechQueue`；末帧(status=2)全量去重。切到 MiniMax 时释放本地 SuperTonic 腾内存。
- **设置里加「在线模型 / 语音」**（`dialog_online_config.xml`）：LLM 后端单选（本地 / 在线 API）+
  base/key/model；系统提示词（多行，猫娘人设）；TTS 后端单选（本地 SuperTonic / MiniMax）+ key/voice。
  保存即持久化并热切换（`prepareBackend` / `prepareTtsBackend` 按 `config.*Kind` 分支）。
- **默认猫娘提示词**：`LlmConfig.DEFAULT_SYS_PROMPT` 改为「小喵」软萌人设（句尾偶尔「喵~」）。
- `speakerProvider` 按 TTS 后端取音色：MiniMax 用 `minimaxVoiceId`，本地用 `ttsSpeaker`。
- 加 `INTERNET` 权限；引入 `okhttp 4.12.0`。versionName `0.1-neko`→`0.2-neko`。

### 说明 / 已知限制
- **未在真机验证**：本次构建环境无 adb 设备，仅 `assembleDebug` 编译通过 + APK 产出（~45MB）。
  在线链路（真实 API Key）需装机实测。
- 在线 LLM 目前**单轮无记忆**（NekoChatMini 无对话历史列表，KVCache 仅本地后端持有）；多轮记忆见规划 TODO#6。
- 提示词仅在线生效：本地 `LocalLlmBackend.composePrompt` 只取最后一条 user，system turn 被忽略（符合「线上模型才用提示词」）。


## v3.2-avatar (2026-07-11)

新增第二个 Live2D 角色并支持切换。默认角色换成 **粉毛萝莉（FenmaoLoli）**，可在设置里与
**Ziyan** 一键切换。

### Features
- **新角色 FenmaoLoli**：从 `AvatarLive2DMini` 移植整套模型资源（moc3 / 4096 贴图 / physics /
  19 个表情 exp）到 `live2d/src/main/assets/Live2DModels/FenmaoLoli/`。构图预设沿用其 registry
  值 `scale=2 / tx=0 / ty=-0.3`（真机 DIAG 校验一致）。
- **默认角色改为 FenmaoLoli**：`Live2DController.DEFAULT_NAME` 与 `LlmConfig.live2dModel` 默认值
  均由 `Ziyan` → `FenmaoLoli`。
- **默认表情 stareyes（星星眼）**：`ModelPreset` 新增可选 `defaultExpression`，加载完成后经
  `nativeApplyExpression` 套用；FenmaoLoli 设为 `stareyes`。真机日志确认 `expression: [stareyes]`。
- **设置里加「切换角色」**：单选弹窗（粉毛萝莉 / Ziyan），选中即 `config.live2dModel` 持久化 +
  `live2d.switchModel()` 热切换（switchModel 触发重载 → onLoadDone 重应用该角色 GL 预设与默认表情）。
  切换列表由 `Live2DController.SWITCHABLE` 定义，只暴露已打包资源的角色（不含 ATRI/Yuuka/Amadeus）。
- 新增图标 `ic_avatar_swap.xml`（人物头肩剪影，text_primary tint）。

### 真机验证（10AG5D10H90089M，2026-07-11）
- 编译 `BUILD SUCCESSFUL`，安装启动无崩溃、无 `not supported mask` 报错。
- 默认加载 FenmaoLoli：DIAG `scale=2.000 tx=0.000 ty=-0.300`；表情日志 `try start expression: stareyes`。

### 已知限制
- 两个模型的 `model3.json` 均未定义 `HitAreas` / `Motions`，故引擎内置的「点头→随机表情 /
  点身→随机动作」当前不触发，触摸仅生效视线/头部追踪。表情切换目前只能靠代码 `nativeApplyExpression`。

## v3.1-glass (2026-07-11)

沉浸陪伴 UI 的材质升级：把底部输入栏与右上设置齿轮从「塑料半透明」升级成 Grok「Ani」那种
**半透明磨砂玻璃**质感，并在左上角补一个自绘时钟。**布局/按钮排布/功能完全沿用 v3（用户满意），
只改这两处控件的材质 + 加时钟**，Activity 代码零改动（纯 XML）。

### 升级要点
- **伪磨砂玻璃图层**（`bg_capsule_input` 药丸 / `bg_frosted_circle` 齿轮圆钮）：自下而上叠
  ① 半透明深紫黑 scrim（`glass_fill` #B3201C2E ~0.70，略透出背景又保证可读）
  ② 顶→底线性高光渐变（`glass_sheen_top`，玻璃斜面反光）
  ③ 噪点磨砂颗粒（`noise_tile` tile 平铺，极低 alpha；**仅药丸**，oval 铺 tile 会漏方角故齿轮不铺）
  ④ 1dp 亮白内描边（`glass_edge` #59FFFFFF ~0.35，玻璃边缘高光）。
- **悬浮彩色发光**：`ll_input_bar` / `btn_settings` 加 `outlineSpotShadowColor`+`outlineAmbientShadowColor`
  = `glass_glow`（#66D9A6BC 粉紫），`elevation` 6dp→8dp，`outlineProvider="background"`（按 drawable 轮廓投影，
  否则圆角/圆形漏矩形阴影）。API28+ 生效，minSdk26 低版本自动忽略（退普通灰投影，不崩）。
- **点击提亮**：齿轮 `ripple` 色 #33FFFFFF→#40FFFFFF，按压反馈更明显。
- **左上角时钟**：新增 `TextClock#tv_clock`（`format24Hour="HH:mm"`，`text_secondary` 低存在感 + 阴影可读，
  elevation 6dp）。TextClock 自带每分钟刷新，无需 Activity 代码。补偿沉浸全屏隐藏系统状态栏后看不到时间。
- **新增色板**（colors.xml）：`glass_fill` / `glass_sheen_top` / `glass_edge` / `glass_glow` 四色；旧 `frost_*`
  保留（字幕条 `bg_subtitle_strip` 仍用，未动）。
- **噪点图**：`scripts/gen_noise.py`（PIL，96×96，seed=42，白椒盐 alpha≤8）一次性生成
  `drawable-nodpi/noise_tile.png`（~6.9KB），可 `python3 scripts/gen_noise.py` 复现。

### 坑：GLSurface 背景无法真 blur → 走伪磨砂
背景图在 Live2D 的 GLSurfaceView 里渲（`setZOrderMediaOverlay(true)`+`PixelFormat.TRANSLUCENT`），
普通 View 的 `RenderEffect`/`blurBehind` **采样不到 GL 表面**（screencap 也拍不到，合成为黑），
所以「透出模糊裙摆」做不到。改用 scrim+渐变高光+噪点+彩色发光描边伪造——Grok 那种观感 90% 本就靠这些，
不靠真模糊。核心可调旋钮：亮背景读不清就调高 `glass_fill` alpha（0.70→0.80）。

### 真机验证（10AG5D10H90089M，2026-07-11）
- 编译 `BUILD SUCCESSFUL`，安装启动无崩溃、无 drawable inflate / 资源报错。
- 左上时钟、输入栏 hint「和猫娘说说话…」正常，Live2D 每帧渲染。
- 用户物理机确认：相比 v3 有明显改进/进步（截图因 GL media-overlay 拍成黑底，玻璃质感只能真机目视）。

## v0.1-neko (2026-07-09)

首个可运行版本：端侧猫娘聊天 APK（LLM + TTS + ASR + Live2D 全家桶），fork 自 MoeAvatarPro，
剥离全部 call-mode，只保留聊天。

### Features
- **文本聊天**：用户微调的 Qwen3-0.8B neko 猫娘 LLM（MNN 格式），设备端读取，猫娘人格系统提示词。
- **语音输入**：长按麦克风 → sherpa-mnn 流式 ASR（中英双语 zipformer int8）→ 上屏 → 发送。
- **语音输出**：LLM 回复自动经 SuperTonic-Neko TTS 合成（44.1kHz）播放。
- **嘴型同步**：TTS PCM 回调驱动 Live2D `ParamMouthOpenY`（`feedAudioForLipSync`），播放结束 `closeMouth`。
- **Live2D 形象**：ATRI 全屏 + 触摸追手（眼球/头部跟随手指）。
- **换背景**：右上角相册选图 → `nativeSetBackground` 全屏 quad（按屏幕比例 centerCrop）。

### 结构决策
- **一套 MNN 服务 LLM + TTS**：`libmoeavatar_llm.so` 与 `libsupertonic.so` 都 IMPORTED 链接同一套
  `libMNN.so`/`libMNN_Express.so`/`libllm.so`（来自 MoeAvatarPro）。SuperTonic 只用 Express 稳定
  API（`Module::load`+`Executor`），无需为它重建 libMNN。APK 里只放一套 MNN。
- **模型全 adb push 不进包**：LLM(~400MB)/ASR/TTS 权重都从设备目录读取，APK 仅 ~26MB
  （旧 MoeAvatarPro 因把 bert 31MB + bv2 96MB 打进 assets 达 ~195MB）。
- **剥离 call-mode**：删掉电话/通话页全部代码（overlay、VAD gate、SimpleVoiceGate），只留 chat +
  长按 ASR + 存储权限门。
- **TTS 换 backend 不动队列**：`SpeechQueue` 双通道/barge-in/唇形回调不变，只把
  `LocalBertVITS2TtsBackend` 换成 `SuperTonicTtsBackend`（按标点分句规避单次 ~6.97s 上限）。
- ABI 仅 arm64-v8a。

### 为什么用 ATRI 而非 Mikawa 猫娘皮套
Mikawa 是 moc3 **v5** 模型；若底座 Cubism Core 为 4.2.1（仅支持到 v4.2），`csmMoc_Create` 会失败
导致 native 闪退。本工程 `:live2d` 已用 SDK **5-r.5**（Core 6.0.0001，支持至 moc3 v5.3），理论上
可载 Mikawa；但为稳妥起见首版角色定为 ATRI（moc3 v4.2，双 SDK 均稳定）。换角色见 README。

### 坑：多模态 LLM 必须 push visual.*
该 qwen35-neko 是**多模态**模型（`llm_config.json` `is_visual:true`，`config.json` 含 `mllm` 块）。
MNN `Llm::load()` 加载时**无条件打开 `visual.mnn`**，缺文件 → `Llm::load() failed` → `initNative returned 0`
→ LLM 永远「未就绪」，即使只做纯文本聊天。**修**：`visual.mnn`(250KB)+`visual.mnn.weight`(63MB) 也要
push（在 /sdcard，不进 APK）。曾误以为「文本聊天用不到 visual 可跳过」——加载阶段不成立。

### 真机验证（10AG5D10H90089M，2026-07-09）
- LLM `Llm load OK`（470MB 权重加载 ~23s）、TTS 就绪、ASR ready 长按识别测通。
- 剩余：聊天/TTS 播放+嘴型/换背景/ATRI 触摸的视觉确认。

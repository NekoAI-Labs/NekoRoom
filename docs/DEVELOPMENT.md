# NekoChatMini 开发指南 / 交接文档

> 目标：新同学（或新的 AI 上下文）读完这份就能**独立开发新功能**——知道现状、约束、构建流程、
> 加功能的套路、规范、调试与看日志的方法、以及 WSL↔Windows 的坑。UI 三块（背景/构图/玻璃材质）
> 的历史细节见 [`HANDOFF.md`](HANDOFF.md)，本文不重复。最后更新 2026-07-15（v0.4-neko）。

---

## 0. 一句话项目定位

端侧**猫娘聊天** APK：全屏 Live2D 角色 + LLM 文本对话 + 语音输入(ASR) + 语音回复(TTS) + 嘴型同步。
默认离线，v3.3 起可选**在线后端**（LLM 走 OpenAI 兼容 API、TTS 走 MiniMax）。v0.4 起模型**不进包、
不靠 adb push 分发**：装 APK 后在**「Neko 能力中心」**按需下载三套能力（本地大脑/离线语音/语音输入），
存到应用私有目录，**免存储权限**（只保留麦克风）。`adb push` 仅剩开发预置用途。
fork 自 MoeAvatarPro，剥掉了全部 call-mode，只留聊天。包名 `com.neko.chat`，ABI 仅 `arm64-v8a`。

---

## 1. 两棵树 —— 最重要、最容易踩的坑

| 树 | 路径 | 作用 | 能否单独编译 |
|---|---|---|---|
| **归档树（改这里 + commit）** | `submit/mini-app/apps/NekoChatMini/` | git 跟踪的源码存档，是**编辑的真源** | ❌ 缺 native `.so` 和部分资源，不能独立 build |
| **工作副本（编译这里）** | `apps/Android/NekoChatMini/` | 真正 `gradlew` 出 APK 的地方 | ✅ 但**不在 git 里** |

**标准工作流：**
```
1. 在归档树编辑源文件
2. cp 改动的文件 → 工作副本对应路径
3. 在工作副本 ./gradlew :app:assembleDebug
4. APK 暂存到 D 盘、装机验证
5. 验证 OK → 回到归档树 git commit（仓库根在 submit/mini-app/.git）
```

> 坑：`MNN/.git` 是空占位，**不是**本项目仓库。所有版本管理都在 `submit/mini-app/.git`。
> 坑：改完归档树忘了 cp 到工作副本 → 编译的是旧代码，白验证。每次改完先 cp 再 build。

---

## 2. 构建 / 装机 / WSL 调 Windows adb

**环境**：`compileSdk/targetSdk 35`，`minSdk 26`，NDK `26.1.10909125`，CMake `3.22.1`，竖屏锁定。
native `:live2d` 模块**从源码编译**（`live2d/src/main/cpp/` + CMake），改 GL 渲染/背景/口型的 cpp 会生效。

```bash
# 构建（在工作副本）
cd apps/Android/NekoChatMini && ./gradlew :app:assembleDebug

# WSL 里用的是 Windows 版 adb（不是 linux adb）
ADB=/mnt/d/softwares/platform-tools/adb.exe

# 装机：install / push 要 Windows 路径（D:\...），因为是 Windows adb 进程；adb shell 不受影响
# 先把 apk 拷到 D 盘再装（避免 \\wsl$ 路径握手卡住）
cp app/build/outputs/apk/debug/app-debug.apk /mnt/d/nekochatmini.apk
timeout 90 $ADB install -r 'D:\nekochatmini.apk'

# 启动：用 monkey（applicationId=com.neko.chat，跟 namespace com.moeavatar 不同，am start -n 会失败）
$ADB shell monkey -p com.neko.chat -c android.intent.category.LAUNCHER 1

# 截图（注意：Live2D 是 GL media-overlay，截图里背景/角色常是黑的，玻璃效果只能真机目视）
$ADB shell screencap -p /sdcard/x.png && $ADB pull /sdcard/x.png /tmp/x.png
```

**WSL↔Windows 要点：**
- `adb.exe` 是 Windows 进程 → `install`/`push` 的**本地文件参数必须是 Windows 路径**（`D:\foo.apk`），
  传 `/home/...` 或 `\\wsl$\...` 会失败或卡流式握手。做法：先 `cp` 到 `/mnt/d/`，再用 `D:\` 引用。
- `adb shell ...`（在设备上执行）不涉及本地路径，正常用即可。
- 设备侧 `/sdcard/...` 用正常 Linux 风格路径。

---

## 3. 代码地图（按职责）

Kotlin（UI/逻辑）+ C++/NDK/JNI（MNN 推理 + Live2D Cubism）。核心文件：

| 文件 | 职责 |
|---|---|
| `com/moeavatar/chat/LlmChatActivity.kt` | 主 Activity：聊天 UI、发送/流式渲染、TTS 播放、设置面板（含在线配置弹窗）、换背景、ASR、后端准备(`prepareBackend`/`prepareTtsBackend`) |
| `com/moeavatar/llm/LlmConfig.kt` | **所有可配置项**（SharedPreferences，file=`moeavatar_llm`）：后端选择、base/key/model、系统提示词、TTS 后端、MiniMax key/voice、Live2D 角色、字幕/性能/dev 开关 + 各默认值常量 |
| `com/moeavatar/llm/LocalLlmBackend.kt` | 本地 MNN LLM（`libmoeavatar_llm.so`），KVCache 多轮记忆在此 |
| `com/moeavatar/llm/OpenAiLlmBackend.kt` | 在线 LLM：`POST /v1/chat/completions` SSE 流式，`buildChatUrl` 拼接、`shouldDisableThinking`、`<think>` 过滤。**无状态、单轮** |
| `com/moeavatar/tts/...` (SuperTonic / MiniMax backend) | 本地 SuperTonic-Neko TTS vs 在线 MiniMax 流式；共用 `SpeechQueue`（分句/barge-in/唇形回调） |
| `Live2DController.kt` + `live2d/src/main/cpp/` | Live2D 角色：`PRESETS`（scale/translateX/Y GL 构图）、`switchModel` 热切换、`nativeSetBackground`、`feedAudioForLipSync`、`nativeApplyExpression` |
| `res/layout/dialog_settings.xml` | 主设置面板（磨砂 BottomSheet） |
| `res/layout/dialog_online_config.xml` | 在线模型/语音配置（磨砂 BottomSheet） |

**LLM/TTS 共用一套 MNN**：`libmoeavatar_llm.so`、`libsupertonic.so` 都 IMPORTED 链接同一套
`libMNN.so`/`libMNN_Express.so`/`libllm.so`，APK 里只放一套 MNN。

---

## 4. 现有功能一览

- **文本聊天**：本地微调 Qwen 猫娘 LLM，或在线 OpenAI 兼容 API。流式上屏。
- **语音输入**：长按麦克风 → sherpa-mnn 流式 ASR（中英双语 zipformer int8）→ 上屏 → 发送。
- **语音输出**：回复自动 TTS（本地 SuperTonic 44.1kHz / 在线 MiniMax 32kHz 流式）。
- **嘴型同步**：TTS PCM 回调驱动 Live2D `ParamMouthOpenY`，播放结束 `closeMouth`。
- **Live2D 角色**：全屏 + 触摸追手（眼球/头部跟随），设置里「切换角色」单选（FenmaoLoli↔Ziyan），默认星星眼表情。
- **换背景**：相册选图 → GL 全屏 quad（centerCrop + 压暗）。**当前不持久化**（重启回默认）。
- **在线后端配置**（v3.3+）：设置→「在线模型 / 语音」，服务商快选(DeepSeek/Agnes/OpenAI/自定义)、
  base/key/model、系统提示词（**仅在线 LLM 生效**）、TTS 后端 + MiniMax key/voice。保存即热切换。
- **设置开关**：AI 字幕显隐、性能小字（首字延迟/tok\/s、TTS RTF，默认关）、dev 日志详细度（默认开）。

---

## 5. 怎么加新功能（套路）

### 5a. 加一个可配置项
1. `LlmConfig.kt`：加 `var xxx` getter/setter（`sp.getX(K_XXX, DEFAULT)` / `sp.edit{putX}`）+ `K_XXX` 常量（+ 默认值常量）。
2. 布局：在 `dialog_settings.xml` 或 `dialog_online_config.xml` 加控件（沿用磨砂样式：`bg_sheet` 底、`bg_input` 输入框、text_primary/secondary/tertiary 配色、accent_pink 主按钮）。
3. `LlmChatActivity.kt`：在对应 `show*Config()` 里 `findViewById` → 初始化回填 `config.xxx` → 保存时写回 → 需要热生效就在保存回调里调 `prepareBackend()`/`prepareTtsBackend()`。
4. **不要写死**任何路径/模型/超参（myrules #9）——都进 `LlmConfig`。

### 5b. 加一个 LLM/TTS 后端
- LLM 后端实现与 `OpenAiLlmBackend` 同接口（提供流式 token 回调）；在 `LlmConfig.BackendKind` 加枚举、`prepareBackend()` 加分支。
- TTS 后端产出 PCM float 喂 `SpeechQueue` 即可复用分句/唇形；在 `TtsBackendKind` 加枚举、`prepareTtsBackend()` 加分支。切后端时记得**释放**上一个后端的内存（本地模型很占内存）。

### 5c. 加/换 Live2D 角色
- **内置角色**：资源规整进 `live2d/src/main/assets/Live2DModels/<Name>/`（moc3/贴图/physics/exp），
  在 `Live2DController.kt` 的 `PRESETS` 加构图（scale/tx/ty），`SWITCHABLE` 暴露给切换 UI。真机看 `DIAG` 日志确认。
- **自定义模型（用户导入 ZIP，v0.3+）**：无需改代码即可加载。机制：
  - `resolvePreset(id)` 先查内置 `PRESETS`，否则在 `getExternalFilesDir("live2d")/<id>/` 找 `*.model3.json`，
    用**绝对路径**构造 preset（native `LoadFile` 认 `/` 开头走文件系统，`_modelHomeDir` 前缀自动拼子文件）。
  - 导入链路：`importModelZip` → `doImportModelZip`（解压到 cache、zip-slip 防护、校验 model3.json/moc3/贴图、
    移到 live2d 目录）。校验失败走 `showModelErrorSheet`（磨砂红标题，措辞指明是包的问题）。
  - native 加载失败（moc3 > v5.3 / 损坏）→ `onLoadErrorListener` → `onLive2dLoadError` 兜底切回默认。
  - 自定义模型也可直接 `adb push` 到 `/sdcard/Android/data/com.neko.chat/files/live2d/<name>/`，重开切换列表即出现。
  - **限制**：只吃 Cubism 3/4/5 的 moc3；口型/追手依赖标准参数名（`ParamMouthOpenY`/`ParamAngleX` 等）。

### 5d. UI 弹窗务必用 BottomSheetDialog，别用 AlertDialog
AlertDialog 白卡片 + 全屏 dim 会让 Live2D 的 GL media-overlay 渲成黑屏（角色消失）。所有弹窗用
`BottomSheetDialog` + `@drawable/bg_sheet`，`behavior.state=STATE_EXPANDED`。这是踩过的坑。

### 5e. 模型/能力系统（v0.4，按需下载）
- **存储**：全在应用私有目录 `getExternalFilesDir("models")/{llm,asr,tts}`，免存储权限。路径解析统一走
  `ModelManager`（`llmScanRoot`/`asrDir`/`ttsDir`）——别再写死 `/sdcard/...`。LLM 必须在 `llm/<subdir>/`
  下含 `config.json`（`ModelScanner` 按子目录扫），ASR/TTS 扁平放。
- **加一个可下载能力**：只改 `ModelManager.REGISTRY`——加一条 `NekoModel`（产品名/描述/ModelScope 仓
  `owner/repo`/子目录/必需文件列表/大小/是否推荐）。检测、下载、删除、能力中心 UI、引导提示**全自动**读表，
  不用改别处。检测口径 = `requiredFiles` 全部存在且非空。
- **下载器** `ModelScopeDownloader.download(ctx, model, shouldStop, onProgress)`：逐文件 `Range` 续传写
  `.part`、校验大小后 rename；`shouldStop` 兼作暂停（返回 `Stopped`，`.part` 保留，下次续传）与取消；
  失败退避重试 3×。能力中心里下载挂在 Activity `lifecycleScope`（退出即暂停，`.part` 留着）。
- **能力中心** `showCapabilityCenter()`：磨砂 BottomSheet，卡片程序化生成（`buildCapCard`）。下载完成
  `onCapReady` 热加载对应后端，无需重启。
- **按需引导** `showNeedCapabilitySheet(title, msg, vararg 操作)`：缺能力时在使用点弹（产品化文案，不露模型名）。
  首启缺推荐能力则 `maybeShowWelcome`（`config.seenWelcome` 只弹一次）。
- **对外文案纪律**：用户可见处只出现产品名（本地大脑 / Neko 离线语音 / 语音输入），**不露** qwen/sherpa/
  SuperTonic/MNN 等底层名。内部类名不受此约束（如 `SuperTonicTtsBackend` 保持）。
- `scripts/push_models.sh`：改推到私有目录 `…/com.neko.chat/files/models/{llm,asr,tts}`，仅开发预置用。

---

## 6. 在线后端约定（改在线相关时必看）

- **base 填站点根，免填 `/v1`**，代码内部拼路径。`OpenAiLlmBackend.buildChatUrl`：
  - 结尾 `/chat/completions` → 原样用
  - 结尾 `/v1` → 补 `/chat/completions`
  - 否则 → 补 `/v1/chat/completions`
  - 例：`https://api.deepseek.com` → `https://api.deepseek.com/v1/chat/completions`
- **thinking 关闭**：base/model 含 `deepseek`/`qwen`/`qwq` → 带 `thinking:{type:disabled}` + `enable_thinking:false`；
  并用 `ThinkTagFilter` 剥 `<think>…</think>`、过滤 `reasoning_content`，只放行可见文本。
- **系统提示词仅在线生效**：本地 `LocalLlmBackend.composePrompt` 只取最后一条 user，system turn 被忽略（设计如此）。
- **在线 LLM 单轮无记忆**：NekoChatMini 无对话历史列表，KVCache 只在本地后端。多轮记忆是待办。
- **Key 绝不进 git**。默认 `openAiApiKey=""`；配置框里显示 `***` 是 password 掩码已存值，非默认。

---

## 7. 调试 & 看日志

```bash
ADB=/mnt/d/softwares/platform-tools/adb.exe

# 全部本 App TAG（都以 MoeAvatar. 开头）
$ADB shell logcat -s MoeAvatar.Chat MoeAvatar.Live2D MoeAvatar.LLM MoeAvatar.TTS

# 只看崩溃
$ADB logcat -b crash

# 看当前前台窗口 / 焦点（盲点 tap 前后确认没跳到别的界面，如相册 photopicker）
$ADB shell dumpsys window | grep mCurrentFocus

# UI 结构（盲操作定位控件坐标）
$ADB shell uiautomator dump && $ADB pull /sdcard/window_dump.xml /tmp/ui.xml

# 读设备上的配置（App 是 debuggable，可 run-as）
$ADB shell run-as com.neko.chat cat /data/data/com.neko.chat/shared_prefs/moeavatar_llm.xml
```
- `dev` 模式（`LlmConfig.devMode` 默认开）→ 所有 `MoeAvatar.*` TAG 输出 Verbose；关掉只留 Warn+。
- 性能小字（首字延迟/tok\/s、TTS RTF）在设置里开「显示性能信息」。
- native 每秒一条 `DIAG`（角色 scale/tx/ty）是正常诊断日志，无害。
- 盲操作教训：`adb shell input tap X Y` 前后要 `dumpsys window | grep mCurrentFocus` 确认焦点，
  曾误触「更换背景」跳进系统相册（`com.android.photopicker`），靠 `input keyevent KEYCODE_BACK` 退出。

---

## 8. 规范（务必遵守）

- **改完立即 commit**，提交信息用 `feat/fix/refactor/docs`（见 myrules.md #5）。
- **每次迭代更新** `docs/CHANGELOG.md`（Features/Fixes/Refactor/已知问题）和 `docs/ROADMAP.md`（myrules #6/#14）。
- **README 保持可复现**（部署/push/运行流程）。
- **不提交**模型/数据/输出/缓存/**密钥**；大文件放 `/mnt/d`（myrules #2/#8）。APK 也暂存 D 盘不进 git。
- **可配置项进 config，不写死**（myrules #9）。MVP 优先、KISS/YAGNI/DRY、向后兼容（myrules #10/#11/#13）。
- **只改工作副本编译验证，OK 后同步归档树，由用户/统一 commit**；native 改动尤其要真机验证。
- 代码风格随现有：Kotlin 惯例；C++ 见根 `.clang-format`（4 空格、120 列、`mCamelCase` 成员）。
- 阶段性成果沉淀为 skill 文档（myrules #18，`skills/`）。

---

## 9. 版本 & 已知限制

- 当前 `versionCode 3` / `versionName 0.3-neko`（`app/build.gradle`）。
- 在线链路已在设备上配置（DeepSeek），但**端到端真机压测**（真实流式回复+MiniMax TTS）仍待系统性验证。
- 换背景不持久化；在线 LLM 单轮无记忆；缺模型时直接读失败（无引导）——均为待办，见 ROADMAP M2/M3。
</content>
</invoke>

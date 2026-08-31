# NekoChatMini v0.6 交接（面向语音输入接手同学）

> 通用两棵树 / 构建 / 在线后端 / UI 弹窗规范看 [`DEVELOPMENT.md`](DEVELOPMENT.md)。
> UI 三块历史踩坑看 [`HANDOFF.md`](HANDOFF.md)。**本文只讲 v0.5 收尾 + v0.6 起点。**
> 最后更新 2026-07-18（分支 `feature/action-system`，v0.5 M2 已 commit）。

---

## 1. 当前状态一句话

- **v0.5 M2 已完成并真机通过**：LLM 通过 `<action>{...}</action>` 驱动 Live2D，emotion（transient）
  和 outfit（persistent）**两条独立通道**并存，换衣服后夸她可以「maid + 脸红」同帧显示。
- **分支**：`feature/action-system`（**不是 master**）。commit 已完成，未 push。
- **下一步（你的任务）**：v0.6 语音输入栏（`docs/voice-interaction-v06/`）。设计文档已给全，代码还没开写。

---

## 2. 目录 & 编译隔离（最重要）

**两棵树，编辑归档树、编译工作副本，见 `DEVELOPMENT.md §1-2`。这里只补两条 v0.5 独有的点：**

### 2.1 `live2d/` native module 不入 git（gitignore 的坑）

`apps/NekoChatMini/.gitignore` 里 `/live2d/` 和 `/sherpa/` 被排除（"第三方 module 不入库"）。所以
**改 `live2d/src/main/cpp/*` 或 `JniBridgeJava.java` 的 native 代码，直接 commit 是丢的**。

v0.5 M2 加了两条 native 改动（第二条 `CubismExpressionMotionManager`、`nativeApplyOutfit` JNI），
以 patch 形式存档在：

```
apps/NekoChatMini/patches/live2d-double-channel.patch
```

**新机器复现流程**（README 已列，这里再点一遍）：
```bash
# 1. rsync 上游 live2d 底座到工作副本
cd apps/Android/NekoChatMini
rsync -a --exclude build/ --exclude .cxx/ ../AvatarLive2DMini/live2d/ ./live2d/

# 2. 应用 M2 patch
git -C /home/jhx/Projects/nlp/MNN/submit/mini-app apply --directory=apps/Android/NekoChatMini \
    apps/NekoChatMini/patches/live2d-double-channel.patch

# 3. 应用 outfit reset patch（支持 outfit="default" 回到底模服装）
git -C /home/jhx/Projects/nlp/MNN/submit/mini-app apply --directory=apps/Android/NekoChatMini \
    apps/NekoChatMini/patches/live2d-clear-outfit.patch

# 4. 正常 assembleDebug
./gradlew :app:assembleDebug
```

**改 native 的规矩**：在工作副本 `apps/Android/NekoChatMini/live2d/` 改完真机验证 OK →
`diff -ru ../AvatarLive2DMini/live2d/ ./live2d/ > patches/live2d-xxx.patch` → **patch 进 git**，
而不是拷 live2d 源码到归档树。

### 2.2 编译对 MNN 主仓完全隔离

`apps/Android/NekoChatMini/` 是**独立的 Android Gradle 工程**，`settings.gradle` 只包含 `:app`、
`:live2d`、`:sherpa` 三个 module，**不引用** `MNN/CMakeLists.txt` 或 `MNN/build/`。
`libMNN.so` / `libllm.so` / `libsupertonic.so` 都作为**预编译 `.so`** 通过 IMPORTED target 链进来
（放在各 module 的 `src/main/jniLibs/arm64-v8a/`）。

所以：
- 改 MNN 主仓 C++ **不会**自动影响 App。要更新推理内核，先在 MNN 主仓 `cd build && make -j`
  产出新 `.so`，手动拷进 `jniLibs/arm64-v8a/` 再 `assembleDebug`。
- App gradle build **不会**触碰 `MNN/build/`。可以放心并行开发。

---

## 3. 装机 / 看日志（极简版）

```bash
ADB=/mnt/d/softwares/platform-tools/adb.exe
# 建包
cd /home/jhx/Projects/nlp/MNN/apps/Android/NekoChatMini
./gradlew :app:assembleDebug

# 装机（apk 必须走 D:\，Windows adb 认不了 /mnt/... 路径）
cp app/build/outputs/apk/debug/app-debug.apk /mnt/d/nekochatmini.apk
timeout 90 $ADB install -r 'D:\nekochatmini.apk'
$ADB shell monkey -p com.neko.chat -c android.intent.category.LAUNCHER 1

# 看日志（M2 action / 表情下发都打在这几个 TAG）
$ADB shell logcat -s MoeAvatar.Chat MoeAvatar.Live2D MoeAvatar.LLM MoeAvatar.CharState

# 崩溃
$ADB logcat -b crash
```

M2 相关 TAG：
- `MoeAvatar.Chat` — LLM 输出的 `<action>` 解析、`dispatchAction`
- `MoeAvatar.CharState` — CharacterStateManager 语义层（`applyOutfit/applyEmotion/clearEmotion`）
- `MoeAvatar.Live2D` — native 下发（`nativeApplyExpression` / `nativeApplyOutfit`）
- native 每秒一条 `DIAG` 是正常诊断，不用管

---

## 4. 代码干净度约束

- **只改归档树 `submit/mini-app/apps/NekoChatMini/`**，cp 到工作副本 build，验证 OK 才 commit。
  别在工作副本直接改 Kotlin 源码 —— 那不在 git 里，随时会因 rsync 或误删丢失。
- **API Key / 模型权重 / apk 都不进 git**（`.gitignore` 已挡）。设备侧 SharedPreferences 存 key，
  不要在代码里硬编码。
- **native cpp 改动走 patch 文件**（见 §2.1），不要试图把 `live2d/` 强推进 git（`.gitignore` 有规则）。
- commit 前 `git status` 扫一眼有没有意外的 `imgs/room.png`（2.3MB）、`.apk`、`.mnn`、`.so` 被 add。
  项目 `.gitignore` 已经列了这些，正常情况不会误入。

---

## 5. v0.5 M2 关键文件（改语音时可能会碰）

| 文件 | 作用 | 语音改动时的注意点 |
|---|---|---|
| `LlmChatActivity.kt` | 主控。持有 `characterState: CharacterStateManager` + `live2d` | v0.6 加语音状态机时注意**不要**把 `characterState.clearEmotion()` 的调用点搞丢（TTS 播完 / 用户 barge-in 各一处） |
| `live2d/CharacterStateManager.kt` | Semantic 状态层，Driver 接口 | 稳定 API，不用动 |
| `live2d/Live2DController.kt` | 底层 native 下发 | `applyOutfit` 走独立通道，`restoreIdle` 只清 emotion —— 别改回单通道 |
| `chat/ActionTagFilter.kt` | 从流式 token 里剥 `<action>` 块 | 语音改动不会碰 |
| `patches/live2d-double-channel.patch` | native 第二条 ExpressionManager | 若语音要 native 改动（应该不需要），新增独立 patch，别 append 到这个 |

---

## 6. v0.6 语音输入 起点

**已备料**（在 `docs/voice-interaction-v06/`）：

- `voice_interaction_v06_plan.md` — 产品交互、视觉、范围、验收
- `voice_architecture_review.md` — 建议单一 sealed state（`inputMode/volume/error/turnId`），
  不要新增 `isListening/isCancelling/isSpeaking/isTextMode` 一堆布尔
- `README.md` — 建议的隔离目录（`prototype/`，不参与 Gradle）+ 合并顺序

**推荐第一步**：在归档树 `app/src/main/java/com/moeavatar/voiceinteraction/` 新增
`VoiceInteractionState`（sealed class）+ `VoiceInputController` + `ConversationCoordinator`，
先跑单元测试（不接 UI 不接 ASR），把状态转换测通再接 `LlmChatActivity`。

**边界纪律**（README 里也写了）：默认不动 `live2d/src/**`、`sherpa/src/**`、`Live2DController` native
接口、`LlmBackend/TtsBackend` 协议、模型注册表。若必须突破，先记原因和真机验证方案。

---

## 7. 常用 checklist（不确定就翻这里）

- [ ] 改 Kotlin：归档树改 → cp 工作副本 → build → 装机验证 → commit 归档树
- [ ] 改 native cpp：工作副本改 → build → 装机验证 → 生成 patch → commit patch
- [ ] 新增可配置项：进 `LlmConfig`，别写死（`DEVELOPMENT.md §5a`）
- [ ] 弹窗：`BottomSheetDialog` + `bg_sheet`，不要 AlertDialog（会让 Live2D 变黑）
- [ ] 装 apk 前 `cp` 到 `/mnt/d/`，install 走 `D:\` 路径
- [ ] commit message 格式：`feat/fix/refactor/docs: NekoChatMini vX.Y — 简短描述`

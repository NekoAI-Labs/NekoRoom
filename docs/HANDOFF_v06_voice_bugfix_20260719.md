# NekoChatMini v0.6 语音交互 Bugfix 交接（2026-07-19）

> 分支：`feature/voice-interaction-v06`
>
> 基线：`51f55bb feat: NekoChatMini v0.6 — 语音优先交互与线上15轮会话`
>
> 本文记录基线完成后真机发现的问题、修复设计、Native 复现方式和后续验证重点。

## 1. 本轮完成内容

1. 支持从持久 outfit 回到模型默认服装。
2. 修正文字输入模式的文案和退出行为。
3. 优化按住录音偶发只闪一下、没有进入 Listening 的反馈。
4. 修复连续触摸时 Live2D Native `SIGSEGV`。
5. 新增独立 Native 增量 patch：`patches/live2d-clear-outfit.patch`。

## 2. Outfit 无法回默认服装

### 现象

FenmaoLoli 换成 `maid` 后，后续害羞等 emotion 能正常出现和消失，但女仆装不会回到初始冬装。用户说“换回默认/原来的衣服/冬装”时，LLM 也没有合法 action 可以表达这个操作。

### 根因

- M2 将 outfit 和 emotion 分成两个 Native `ExpressionManager`。
- outfit 是 persistent，`CharacterStateManager.clearEmotion()` 按设计只清 emotion。
- FenmaoLoli 的冬装不是 `winter.exp3.json`，而是 `.moc3` 底模状态。
- 原协议只能 `applyOutfit(name)`，没有停止 outfit manager 的能力。

这与旧 bug 不同：旧 bug 是清 emotion 时误清 outfit，导致“女仆装 + 害羞”结束后立刻回冬装。不能通过恢复旧行为解决。

### 修复

- action 协议增加保留值：`<action>{"outfit":"default"}</action>`。
- `CharacterStateManager.clearOutfit()` 只清 outfit，保持当前 emotion。
- `Live2DController.clearOutfit()` 调 JNI `nativeClearOutfit()`。
- Native 调用 `_outfitExpressionManager->StopAllMotions()`，下一帧自然显示 moc3 底模服装。
- 在线 system prompt 动态注入当前 outfit。
- FenmaoLoli 明确告诉模型：默认服装是“初始冬装”；其他角色使用“模型初始服装”。

预期语义：

```text
maid -> blush -> clearEmotion = maid 保留
maid -> outfit:"default"     = 回初始冬装
maid + blush -> default       = 冬装 + blush，emotion 结束后冬装
```

## 3. 文字输入模式

### 原问题

- 输入框提示“输入精确内容...”，把内部产品定位直接暴露给用户，语气像工具而不是陪伴角色。
- 系统返回键收起键盘后，状态仍停在 TEXT，胶囊继续显示文字框，默认语音入口不恢复。

### 当前行为

- 输入提示改为“和猫娘说说话...”。
- 键盘从 visible 变为 hidden 时，若当前是 Idle/Text，自动回到 Voice。
- 文字发送后收起键盘并回 Voice。
- 点击左侧麦克风也回 Voice。
- 未发送草稿不清空，下次打开键盘继续编辑。
- 默认胶囊始终回到“按住和猫娘说话...”。

不要在 IME 显示/隐藏时调用 `live2d.nudge()`。它会切换 `GLSurfaceView` 的 GONE/VISIBLE，扩大 Surface 重建期间的触摸竞态。

## 4. 按住录音偶发闪烁

### 根因

旧实现每次按下都执行完整的 LLM/TTS 静默等待，即使 AI 已空闲。用户较快松手时，状态在 `Preparing -> Idle` 之间切换，看起来只闪一下；旧 prepare 尚未结束时再次按下还会被拒绝。

### 修复

- 只有 `generating || chatJob.isActive || speechQueue.hasPendingWork()` 时才执行 barge-in 静默流程。
- AI 空闲时直接启动 ASR。
- `Preparing` 从第一帧就使用 Listening 背景、音波和“松开发送 · 上滑取消”。
- `VoiceInputController.press()` 返回 false 时不触发错误震动。

## 5. 连续触摸 Native Crash

### Tombstone

```text
signal 11 (SIGSEGV), fault addr 0x4
#00 LAppSprite::IsHit(float, float) const
#01 LAppView::OnTouchesEnded(float, float)
#02 LAppDelegate::OnTouchEnded(double, double)
#03 nativeOnTouchesEnded
```

### 根因与修复

`LAppView::OnTouchesEnded()` 仍保留 Live2D Sample 的 `_gear->IsHit()` 和 `_power->IsHit()`。聊天 App 的设置和生命周期由 Android View 管理，这两个 sample sprite 在 Surface 重建窗口可能为空，触摸抬起直接空指针。

修复为删除 gear/power hit-test 分支，保留 `live2DManager->OnTap(x, y)`。Android 右上角设置按钮不受影响。

真机回归：清空 crash buffer 后连续执行 10 次“打开键盘 -> 系统返回键收起”，进程存活，crash buffer 为空。

## 6. Native Patch 复现

`live2d/` 被 `.gitignore` 排除，本轮 Native 改动存档在：

```text
patches/live2d-clear-outfit.patch
```

它是基于已经应用 `live2d-double-channel.patch` 的增量 patch，包含：

- `nativeClearOutfit` JNI 全链路。
- outfit manager `StopAllMotions()`。
- 移除 gear/power 空指针 hit-test。

工作副本重建顺序：

```bash
git -C /home/jhx/Projects/nlp/MNN/submit/mini-app apply \
  --directory=apps/Android/NekoChatMini \
  apps/NekoChatMini/patches/live2d-double-channel.patch

git -C /home/jhx/Projects/nlp/MNN/submit/mini-app apply \
  --directory=apps/Android/NekoChatMini \
  apps/NekoChatMini/patches/live2d-clear-outfit.patch
```

已执行 `patch --dry-run -p1 -d live2d < patches/live2d-clear-outfit.patch`，全部 hunk 可应用。

## 7. 验证结果

- `:app:testDebugUnitTest`：8/8 通过。
- `:app:assembleDebug`：通过，包含 C++/JNI 重编译。
- `:app:lintDebug`：通过。
- 真机覆盖安装：成功。
- 键盘开关 10 次压力路径：无新 crash。
- Live2D 默认构图、语音胶囊、ASR、TTS 口型保持正常。

## 8. 后续真机检查

1. 在线模型依次测试“换女仆装 -> 夸她 -> 换回默认冬装”。
2. 确认夸奖触发 blush 时 maid 不消失。
3. 确认 `outfit:"default"` 日志依次出现 `MoeAvatar.CharState clearOutfit` 和 Native `clear outfit -> model default`。
4. 分别测试系统返回键、文字发送、左侧麦克风三种方式退出 TEXT。
5. 快速短按与持续长按各 10 次，确认无 Preparing 闪烁、无重复发送。

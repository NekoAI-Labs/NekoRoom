# 2026-07-18 复盘 · Alpha 首装回归的三个 Bug

**背景**：v0.4-neko 打包完，卸载 → 重装 → 走首启欢迎 → 下载模型 → 语音输入的完整流程，
连续暴露 3 个 bug。表面看是三件事，本质是同一类问题：**Android 上的静默失败**（不抛异常、
不打日志、UI 层没提示，等下一步操作才崩或"消失"）。这份文档记录根因、修复位置和以后
的检查清单，主要给未来的自己看，避免同一坑再踩。

## Bug 1 · ASR 授权后按麦克风 SIGSEGV

### 现象
首装 → 下载 ASR → 授权麦克风 → 按住麦克风 → 立刻闪退。tombstone 栈顶：

```
Java_com_k2fsa_sherpa_mnn_OnlineRecognizer_createStream+136
```

### 根因
sherpa-mnn 的 `OnlineRecognizer` 构造器在**模型文件不存在或不完整**时**不抛异常**，只是
返回一个 native pointer 为 0 的"空壳对象"。我们的 `ChatAsrController.prepare()` 冷启动时会
先 probe 一遍 —— 那时 ASR 还没下载，得到一个坏对象；后来用户下载完 ASR，再点麦克风时
走 `if (recognizer != null) return true` 直接短路，坏对象被复用，进 `createStream` 就 SEGV。

即：**"构造成功 + null pointer + 复用"三连**，是 JNI 常见静默失败模式。

### 修复
`ChatAsrController.kt:48`：构造前先按 `ModelManager.isInstalled(ctx, Capability.ASR)` 校验
必需文件全在，缺文件直接 `return false`，永不进构造器。catch 分支同时 `recognizer = null`
避免坏对象存活。

### 检查清单（以后加类似 native SDK）
- [ ] JNI 包装类构造器是否会抛异常？如果不会，**必须**在 Java 侧先做文件完整性校验。
- [ ] 是否有"先 probe / 后再用"的路径？若有，probe 失败必须让 `xxxReady` 状态回到未初始化，
      而不是把坏对象缓存下来。
- [ ] catch 分支里，出错的实例必须 `= null`，不能让"半初始化"对象逃逸。

---

## Bug 2 · 删除 TTS 模型后仍在合成语音（"假的按钮"）

### 现象
Neko 能力中心 → 删除本地语音 → 回到对话 → 发消息，本地 TTS 还在正常出声。

### 根因
本地 TTS 后端 native 侧用 **mmap** 加载模型权重。Java 侧只删磁盘文件，**mmap 已建立的
虚拟内存映射仍然有效** —— 内核直到 `munmap` 才会释放，物理页在进程退出前一直在。所以
"删掉文件"对已加载的 runtime 是无感的。

同理适用于本地 LLM（MNN Session 已 loadModel）和 ASR（sherpa OnlineRecognizer 已构造）。

### 修复
`LlmChatActivity.deleteCap()` （line 591）先按能力类型**释放 runtime**，再删文件：
- **TTS**：`speechQueue.stopAndAwaitSilence()` → `swapBackend(EmptyTtsBackend)` → 原
  backend `release()`。
- **LLM**：`stopGeneration()` → `backend.release()` → `backend = null`。
- **ASR**：`asr.release()` → `asrReady = false` → 麦克风按钮 alpha=0.5 视觉降级。

### 检查清单（涉及 native runtime 的"删除/切换"操作）
- [ ] 删除动作必须先 stop 当前播放/推理，再 release native 对象，最后删磁盘。
- [ ] 上层引用（`backend`, `localTtsBackend`, `recognizer` 等）必须置 null，防止悬空指针
      在下一次调用里被复用。
- [ ] 涉及 AudioTrack 的场景，`stopAndAwaitSilence` 之类的**同步**等停 API 优先，不然会有
      buffer 尾音继续播出。

---

## Bug 3 · 首启欢迎弹窗关掉后 Live2D 角色不见（只剩背景）

### 现象
卸载重装 → 首次进入 → 弹欢迎引导 → 关掉弹窗 → 屏幕只剩背景，Live2D 角色没了；App 完全
杀掉再进又正常。之前配置能力时也偶发过。

### 根因
Live2D 用 `GLSurfaceView + setZOrderMediaOverlay(true)`（媒体覆盖层，画在主窗上、其它 surface
之下）+ 透明 EGL 配置。BottomSheetDialog 弹出时会**新建一个 Window** 带 dim scrim；关掉
弹窗时 WindowManager 重排 surface 层级，某些 GPU 驱动（Vivo/HyperOS 尤其明显）会把
media-overlay surface 的**Z-order 状态丢失**，surface 被压到主 window 之下 —— 而主 window
是不透明的，于是角色"消失"，其实只是被盖住了。

这是老 SurfaceView 层级 bug，Google issue tracker 上从 Android 5 时代就有，各厂商修复不一。

### 修复
`Live2DController.nudge()` （line 170）：`glView.visibility = GONE → post → VISIBLE`。这个
可见性变化会触发 SurfaceView 内部的 `updateWindow()`，重新按当前 z-order 拼合 surface。
在 `showNeedCapabilitySheet` 和 `showCapabilityCenter` 的 `setOnDismissListener` 里都调一次。

### 检查清单（以后加新的弹窗/浮层）
- [ ] 新加的 Dialog/BottomSheet/PopupWindow **凡是带 dim scrim** 的，dismiss 时都要
      `live2d.nudge()`。
- [ ] 除了 dismiss，还要注意从后台切回来（`onResume`）、旋转、分屏，这几种 window 重组的
      时机同样可能触发。目前 nudge 只挂在两个 sheet 上，如果后续再加"设置"、"角色卡"、
      "在线配置"任何一个新的 sheet，都要顺手挂。
- [ ] 长期方案：如果重现率变高，考虑改成 `TextureView` 承载 GLSurfaceView 内容（TextureView
      走普通 view 层级，没这个 z-order 问题），但会牺牲一点性能。

---

## 三个 bug 的共同教训

它们看起来是三件事，其实都属于 **"Android 静默失败"** 家族：

| Bug | 静默方式 |
|-----|---------|
| ASR | JNI 构造器"成功"但 native 指针为 null，等 createStream 才崩 |
| TTS 删除 | mmap 依然有效，删文件不报错也不生效 |
| Live2D 消失 | surface 层级重排，无异常、无日志、UI 无反馈，只是"看不见了" |

**通用应对原则**：
1. **不要相信"构造成功 == 可用"**。凡是包了 native 的对象，都要在**使用前**再校验一次
   资源是否齐全，不能只信构造器。
2. **删除即释放**。任何"删除模型/换后端"入口，第一步永远是 `stopAndAwait + release`，
   而不是 `File.delete`。
3. **看不见的东西也可能不正常**。UI 上"没变化"不等于"没问题"，Surface / Window 层级、
   z-order、透明度这几类问题都是无异常静默失败，测试脚本抓不到，只能靠 checklist。
4. **回归路径必须包含"卸载重装 → 首启完整流程"**。这次三个 bug 都是这条路径才触发的，
   增量安装（`adb install -r`）+ 保留 data 目录跑不出来。以后每次发 alpha 前必走一次。

---

## 相关修改一览

| 文件 | 行 | 内容 |
|-----|-----|------|
| `ChatAsrController.kt` | 48 | `prepare()` 加 `ModelManager.isInstalled` 前置检查 |
| `LlmChatActivity.kt` | 591 | `deleteCap()` 三类能力各自 stop+release 后再删 |
| `LlmChatActivity.kt` | ~420 | `showCapabilityCenter` dismiss 时 `live2d.nudge()` |
| `LlmChatActivity.kt` | ~902 | `showNeedCapabilitySheet` dismiss 时 `live2d.nudge()` |
| `Live2DController.kt` | 170 | 新增 `nudge()` |
| `SpeechQueue.kt` | 412 | `driveMouth()` 加 `STATE_INITIALIZED` 检查 + `IllegalStateException` catch |

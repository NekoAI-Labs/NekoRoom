# NekoChatMini 交接文档（UI 沉浸陪伴界面）

> **通用开发/交接看 [`DEVELOPMENT.md`](DEVELOPMENT.md)**（两棵树、构建、加功能套路、在线后端约定、调试看日志、WSL↔Windows、规范）。
> 本文只聚焦 UI 三块（背景 / 角色摆放 / 输入栏材质）的历史细节与踩坑。
>
> 面向接手开发的同学。目标：不重复踩坑，快速定位「背景 / 角色摆放 / 输入栏材质」三块的现状与约束。
> 最后更新 2026-07-11。对应工作状态：#53（构图/横线）已修完、#54（暗玻璃）已修完，`#55`（改名+图标）基本完成待验证、`#47`（第二角色+切换 UI）未做。

---

## 0. 项目结构与构建（最先看，别踩）

**两份代码，分工不同：**
- **工作副本** `apps/Android/NekoChatMini/` — 真正编译出 APK 的地方，**不在 git 里跟踪**。改代码、跑 gradle、装机都在这。
- **归档副本** `submit/mini-app/apps/NekoChatMini/` — git 跟踪的存档（git 根在 `submit/mini-app/.git`，注意**不是** `MNN/.git`，后者是空占位）。改完工作副本、真机验证 OK 后，**手动同步**改动文件到这里，再由用户统一 commit。

> 坑：`MNN/.git` 不是本项目仓库。所有版本管理都在 `submit/mini-app/.git`。

**构建：**
```bash
cd apps/Android/NekoChatMini && ./gradlew :app:assembleDebug
```
- `arm64-v8a` only；`minSdk 26 / targetSdk 35`；竖屏锁定。
- native `live2d` 模块**从源码编译**（`live2d/src/main/cpp/` + CMake），产出 `libchatwaifu-live2d.so`。所以改 native cpp（GL 渲染/背景/口型）**会生效**，正常 assembleDebug 即可。

**装机/启动（WSL 调 Windows adb）：**
```bash
ADB=/mnt/d/softwares/platform-tools/adb.exe
# 坑：install 与 launch 串在一起偶尔卡在流式传输握手。分开跑 + 加 timeout。
timeout 90 $ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell monkey -p com.neko.chat -c android.intent.category.LAUNCHER 1
```
- **applicationId = `com.neko.chat`**（不是 namespace `com.moeavatar`）。用 `am start -n com.moeavatar.chat/...` 会失败，用 `monkey -p com.neko.chat` 启动。
- 截图：`$ADB shell screencap -p /sdcard/x.png && $ADB pull /sdcard/x.png /tmp/x.png`。

---

## 1. 背景（最大的坑集中在这）

### 现状（用户拍板）
**直接用原图，不做任何模糊。** 背景图 = `app/src/main/assets/room_default.png`（600×750），只做一次「压暗」后送进 GL 全屏 quad。

### 关键约束：背景在 GLSurfaceView 里渲，普通 View 采样不到
- Live2D 用 `GLSurfaceView` + `setZOrderMediaOverlay(true)` + `PixelFormat.TRANSLUCENT` 当**覆盖层**渲。
- vivo / HyperOS 等会**强制 GL surface 不透明**，导致普通 View 层的背景透不出来 → 所以背景必须**画进 GL**：Kotlin 把 Bitmap 转 ARGB int[] 调 `JniBridgeJava.nativeSetBackground(argb, w, h)`，native 在 `Render()` 里当全屏 quad 画在模型下面。
- **由此推论（重要）：普通 View 的 `RenderEffect` / `blurBehind` 采样不到 GL 表面**，所以「真·背景模糊」在这个架构下做不到。`screencap` 同理拍不到 GL 层（截图里背景常是黑的，玻璃/背景效果只能真机目视）。

### 代码位置
- `LlmChatActivity.kt`
  - `applyDefaultBackground()` — 读 `assets/room_default.png` → `dimForBackground()` → `live2d.setBackgroundBitmap()`。
  - `applyBackgroundFromUri()` — 用户换背景走同一条压暗管线。
  - `dimForBackground(src)` — 逐像素 `RGB * BG_DIM_MUL/255`，`BG_DIM_MUL = 140`（≈ 0.55，模拟 45% #252235 蒙版）。**当前只压暗，无模糊。**
- `Live2DController.kt`：`setBackgroundBitmap()` / native `nativeSetBackground`。

### 踩过的坑（不要重来）
1. **别加高斯模糊「藏横线」。** 曾用「降采样→双线性升采样」近似模糊：`/12` 太糊（背景成一片）、`/3` 仍被用户否。**用户明确要原图**。相关 `softBlur()` / `BG_BLUR_DOWNSCALE` 已删除，别再加回来。
2. **那条「横线」是什么：** `room_default.png` 里房间的地面/桌沿边界，约在图片 **59% 高度**（源图 439–446 行）。全屏拉伸+压暗后成一条隐约横线。曾误诊为 noise 图层、背景 quad 边界、`glScissor`（`_scissorBottom` 默认 0、`nativeSetScissorBottom` 从没被 Kotlin 调过 = 死代码），**都不是**。真正来源是原图这条边。
3. **横线现在为什么不是问题了：** 角色恢复正常大小站直后，脚不再落在那条线上被「截断」。用户接受原图 + 正常构图。若以后要更干净的背景，正途是**换一张没有硬水平边的背景图**，而不是模糊或裁剪。

---

## 2. 角色摆放（Live2D 构图）

### 现状
默认角色 **Ziyan（猫娘）**，`DEFAULT_NAME = "Ziyan"`。构图预设（正常大小、站直、脚在画面内）：
```
Ziyan: translateX=0, translateY=-0.5, scale=2   // Live2DController.kt PRESETS
```
这就是「常规大小」的基准（v3 / v3.1 已提交版本一致）。

### 角色大小/位置**只**由 GL 投影预设控制
- 在 `Live2DController.kt` 的 `PRESETS` 里改 `scale` / `translateX` / `translateY`，通过 `nativeProjectScale` / `nativeProjectTransformX/Y` 下发。
- `LlmChatActivity.kt` 的 `applyLive2dYOffset()` 是 **View 层**的位移/缩放钩子，**现已归零**（`translationY=0`、`scaleY=1.0`）。保留空壳是为了 config change 时调用不崩，别拿它调角色。

### 踩过的坑（用户很在意，别重犯）
1. **不要靠缩小/上移角色来「躲」背景 bug。** 曾把 scale 2→1.4、translateY -0.5→-0.2，再叠 View 层 `translationY = -0.04h` 把角色往上顶，用来避开那条横线。用户当场识破并强烈反对：**要修 bug 本身，角色保持常规大小站着。**
2. **不要用 `scaleY` 单轴缩放角色。** 会把角色纵向拉伸失真（v3.2 前的失真根因）。要整体缩放就改 GL 预设 `scale`。
3. 验证角色大小是否正确：logcat 看 `MoeAvatar.Live2D: DIAG type=mxin scale=2.000 ... ty=-0.500`。（native 里有每秒一次的 DIAG 诊断日志，I 级、无害，如嫌吵可后续在 cpp 里去掉。）

---

## 3. 输入栏 & 设置齿轮材质（暗玻璃 v3.3）

### 现状：Grok「Ani」风格的**暗色微玻璃**（dark subtle glass）
真·blur 做不到（同背景那条 GL 约束），靠**深色半透明 Fill + 顶部细高光 + 极弱噪点 + 若隐若现描边 + 黑色悬浮阴影**伪造玻璃质感。深 Fill 是撑起质感的主力。

### 设计规范（当前 colors.xml 实测值）
| 角色 | 色值 | 说明 |
|---|---|---|
| `glass_fill_top` | `#941C1824` | rgba(28,24,36,0.58) 顶部黑紫 |
| `glass_fill_bottom` | `#A6121018` | rgba(18,16,24,0.65) 底部更深 |
| `glass_sheen_top` | `#14FFFFFF` | 顶部细高光 8% |
| `glass_edge` | `#1AFFFFFF` | 1px 内描边 10% |
| `glass_shadow` | `#33000000` | 黑色悬浮阴影 20% |
| `glass_fill` | `#A61C1824` | 齿轮 scrim 0.65（同调，略提可读） |

圆角 28dp。

### 文件位置
- `res/drawable/bg_capsule_input.xml` — 底部药丸输入栏（layer-list：Fill 渐变 → 顶部高光 → 噪点 → 1px 描边）。
- `res/drawable/bg_frosted_circle.xml` — 右上设置齿轮（`ripple` 点击提亮 + oval 三层：scrim → 高光 → 描边；**齿轮不铺噪点**）。
- `res/values/colors.xml` — 上表色板。
- `res/drawable-nodpi/noise_tile.png` — 噪点图（`scripts/gen_noise.py` 一次性生成，96×96，白椒盐 alpha≤8，可复现）。

### 踩过的坑
1. **噪点 alpha 必须极低（规范 2–5%）。** 曾是 `0.25`（太明显、一眼看到），现为 **`0.04`**。噪点 `<bitmap tileMode="repeat">` 是**矩形不裁圆角**的；alpha 极低时圆角外溢出几乎不可见，可接受；但**齿轮 oval 不能铺**（会漏方角），所以齿轮只靠高光+描边。
2. **彩色发光/投影**（`outlineSpotShadowColor` 等）需配 `outlineProvider="background"`，否则圆角/圆形会漏矩形阴影。API28+ 生效，minSdk26 低版本自动退普通灰投影，不崩。
3. **可读性兜底旋钮**：亮背景下 hint/输入文字发糊 → 调高 `glass_fill*` 的 alpha（如 0.58/0.65 → 0.8）。这是唯一核心可调旋钮。
4. 历史遗留：CHANGELOG 的 `v3.1-glass` 段写的是**旧的浅色伪磨砂**（`glass_fill #B3201C2E ~0.70` 等），已被 v3.3 暗玻璃覆盖。以 colors.xml 现值为准，CHANGELOG 那段仅作历史。

---

## 4. 任务状态速查

| # | 状态 | 说明 |
|---|---|---|
| #53 | ✅ 已修 | 构图：角色恢复常规大小（scale2/ty-0.5）、View 层钩子归零、背景用原图不模糊、噪点 alpha 降到 0.04 |
| #54 | ✅ 已修 | 输入栏+齿轮暗玻璃 v3.3 |
| #55 | ⏳ 基本完成待验证 | 改名 NekoChat（strings.xml app_name 已改）+ 猫爪图标（`ic_launcher_foreground.xml` 白猫爪 vector 已画）。真机确认图标+名字。 |
| #47 | ✅ 已做 | 第二角色 FenmaoLoli（粉毛萝莉）+ 切换 UI 完成（v3.2-avatar）。默认角色改 FenmaoLoli+星星眼表情，设置「切换角色」单选弹窗 FenmaoLoli↔Ziyan。资源从 AvatarLive2DMini 移植。EmoCat 按用户要求不加（故无需 native maskBufferCount 修复）|

## 5. 收尾约定
- **只改工作副本** `apps/Android/NekoChatMini/`，真机验证 OK 后再同步到归档 `submit/mini-app/apps/NekoChatMini/`。
- **不要自己 commit**，用户统一提交。
- 本次改动涉及文件：`Live2DController.kt`（Ziyan 预设）、`LlmChatActivity.kt`（`dimForBackground` 去模糊 / `applyLive2dYOffset` 归零）、`bg_capsule_input.xml`（噪点 alpha 0.25→0.04）。这三个已同步到归档，尚未 commit。

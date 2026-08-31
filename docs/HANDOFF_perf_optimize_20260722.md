# NekoChatMini 性能优化交接文档（LLM / TTS / SME2）

> 2026-07-22 起草。面向接手性能优化的下一个 agent。
>
> **一句话总结**：SME2 已白嫖生效（无需动作），LLM 有几个小 config 可调；**大头是 TTS——现在扩散 8 步固定 pad 到 T_TEXT=256/L_TTL=100，是白算，理论可 -80%**。这份文档写清怎么改、坑在哪、怎么验证。
>
> 前置阅读：本目录 `DEVELOPMENT.md`（构建/工作副本 vs 归档副本）、`../README.md`（模型 push 路径）。

---

## 0. 现状速览

| 组件 | 后端 | 精度 | 线程 | 首包延迟（Redmi K80） | 优化空间 |
|---|---|---|---|---|---|
| LLM（Qwen3-0.8B） | CPU + SME2(auto) | fp16（低） | 4 | 0.8-1.5s（推测） | 中：mmap / kvcache / thread affinity |
| **TTS（SuperTonic-Neko）** | **CPU** | **fp16（低）** | **4** | **4-5s（实测）** | **大：动态 shape + 步数** |
| ASR（sherpa-mnn zipformer） | CPU | int8 | 内部管 | 流式，不算首包 | 小 |

**Redmi K80（骁龙 8s Gen3 / Cortex-X4，Armv9.2 但无 SME2）**：TTS 首包 4-5s。
**vivo（大概率天玑9400，有 SME2）**：TTS 首包 1.5s。
**差 3× 主因不是 SME2**（那顶多 2-3× 且是 GEMM 上限），**主因是扩散 8 步 × 固定 100 帧 = 白算**。

---

## 1. SME2（Armv9.2）现状：已生效，仅需备档

### 结论

**你 apk 里的 `libMNN.so` 已经带 SME2 内核**。运行到 SME2 手机（天玑9400/A18/vivo X200 等）自动加速；老机器（K80、骁龙8 Gen3 及以下）自动 fallback 到 i8mm/dotprod。**同一 apk 通吃所有机型**，不用做机型判断。

### 验证证据

- `apps/Android/NekoChatMini/app/src/main/jniLibs/arm64-v8a/libMNN.so` md5=`051ec2b0…`（和 MoeAvatarPro/NightyQ 共用）
- 用 `nm -D` 能看到 `kai_kernel_matmul_clamp_*_sme2_mopa` / `kai_kernel_imatmul_*_sme2_mopa` 一整套 KleidiAI SME2 内核
- 编译源头 `MNN/.tmp_mnn_build_llm_sep/CMakeCache.txt` 里 `MNN_SME2:BOOL=ON`
- 运行期无需业务代码改动，MNN 用 `HWCAP2_SME2` 探测自动路由

### 唯一要做的事：修 `build_mnn.sh` 防未来踩坑

**文件**：`app/src/main/jniLibs/arm64-v8a/build_mnn.sh`

**现状（坑）**：
```bash
# 缺少 MNN_BUILD_LLM、MNN_SME2、MNN_OPENCL
cmake ../../../ ... -DMNN_ARM82=true ... -DMNN_OPENCL=false ...
```

**改为对齐 `docs/android-wsl-开发指南/03_MNN核心库与MnnLlmChat编译部署流程.md` 的 `build_64.sh`**：
```bash
cmake ../../../ \
  -DMNN_LOW_MEMORY=true \
  -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true \
  -DMNN_BUILD_LLM=true \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=true \
  -DMNN_ARM82=true \
  -DMNN_SME2=ON \                    # ← 显式写上
  -DMNN_OPENCL=true \                # ← TTS 换 GPU 后端要用（见 §3.5）
  -DMNN_SEP_BUILD=OFF \              # ← 单 so 打包
  -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384' \
  ...
```

**验证**：编完后 `nm -D libMNN.so | grep sme2_mopa` 有输出即 OK。

**坑**：目前 apk 用的 `libMNN.so` 是从 MoeAvatarPro 手动拷来的，**不是 build_mnn.sh 编的**。谁重编按当前脚本会掉档。

---

## 2. LLM 优化：小改动，中收益

### 现状：全跑默认，什么都没调

**关键文件**：
- JNI 桥：`app/src/main/cpp/moeavatar_llm_jni.cpp`
- 只调 `Llm::createLLM(cfg_path)`（第 59 行）→ 所有运行时参数走设备端 `config.json` 的默认值
- Kotlin 侧：`app/src/main/java/com/moeavatar/llm/LocalLlmBackend.kt`

**设备端 config**：`/sdcard/Android/data/com.neko.chat/files/models/llm/qwen35_08b_nekoneko-MNN/config.json`

### 可加的字段（改 config.json，不用碰代码）

```json
{
  "backend_type": "cpu",
  "thread_num": 4,
  "precision": "low",
  "memory": "low",
  "use_mmap": true,          // ← 权重 mmap，冷启动更快、多进程可共享
  "kvcache_mmap": true,      // ← KV cache 走 mmap，防长上下文 OOM
  "kvcache_limit": -1,       // ← 不限制 KV cache
  "tmp_path": "/data/local/tmp/mnn_llm"   // ← mmap 临时文件路径，用可写目录
}
```

**验证**：logcat 抓 `-s MNN` 看是否出现 `mmap` 相关行；首次冷启动模型加载时间应从 ~2s 降到 <1s。

**坑**：
- `tmp_path` 必须是 App 可写目录，否则 mmap 建不了
- `use_mmap` 首次会占硬盘等量空间做临时文件，模型 500MB 就要留 500MB
- `kvcache_mmap` 在 KV cache 超 200MB 时才有意义，短对话反而慢（多一次系统调用）

### 埋点：加首字延迟 TTFT 打点

现在只有 `showPerfLine` 里的 tok/s，**没有 prefill 结束时刻**，SME2 或任何优化的收益都没法量化。

**改法**：`app/src/main/cpp/moeavatar_llm_jni.cpp` `submitNative` 里，在 `cb` 里第一次 flush 时打时间戳：

```cpp
auto t_prompt_in = std::chrono::steady_clock::now();
bool first_flushed = false;
auto cb = [&](const std::string& chunk) -> bool {
    // ...原逻辑...
    if (!first_flushed && cut > 0) {
        auto ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_prompt_in).count();
        __android_log_print(ANDROID_LOG_INFO, "MoeAvatarLLM",
            "TTFT=%lldms prompt_len=%zu", (long long)ttft_ms, promptStr.size());
        first_flushed = true;
    }
    // ...
};
```

**验证**：`adb logcat -s MoeAvatarLLM` 每轮回复应有一条 `TTFT=xxxms`。

---

## 3. TTS 优化（重点，这里能大幅省时）

### 3.0 现状：所有 shape 硬编码到最大 bucket

**核心代码路径**（**下一个 agent 主要工作面**）：

| 文件 | 作用 | 关键行 |
|---|---|---|
| `app/src/main/cpp/supertonic/super_tonic_loader.hpp` | 编译期常量 | :25-27 `T_TEXT=256 L_TTL=100 TOTAL_STEP=8` |
| `app/src/main/cpp/supertonic/super_tonic_loader.cpp` | MNN Express 推理 | :96-199 `synth()` 全流程 |
| `app/src/main/cpp/supertonic/super_tonic_jni.cpp` | JNI 桥 | init/synth 转发 |
| `app/src/main/java/com/example/supertonic/SuperTonicInfer.kt` | Kotlin 顶层 API | :42 `init()`, :72 `synth()` |
| `app/src/main/java/com/example/supertonic/ZhFrontend.kt` | 中文前端（pinyin/tone） | :146 pad 到 T_TEXT |
| `app/src/main/java/com/moeavatar/tts/SuperTonicTtsBackend.kt` | TTS backend + 首包统计 | :39-53 `firstPacketMs`/`rtf` |
| `app/src/main/java/com/moeavatar/tts/SpeechQueue.kt` | 双线程流水线（synth ‖ play） | 整个文件 |

**pipeline 每 clause 一次**：`DP → TE → VE × 8 步 → Vocoder`，共 11 次前向。VE 8 步是 80%+ 的耗时。

**"首包延迟"**：`SuperTonicTtsBackend.kt:66` 定义 = 第一个 clause 的完整 synth 耗时（不是"从有文本到出声"的墙钟时间）。

### 3.1 ⭐️ 主优化点：动态 shape（-70~80%）

**你已经猜到的问题**：DP 明明预测了 `valid_l`（比如 15 帧），但 VE 8 步在 100 帧全长度上算，剩下 85 帧用 mask 抹零 → **80% 计算是白算**。

**对比证据**：仓库里官方版本 `apps/frameworks/mnn_tts/src/supertonic/mnn_supertonic_tts_impl.cpp:694-830` 就是**动态 shape**：
```cpp
int num_tokens = static_cast<int>(text_ids.size());              // ← 实际长度
inputs[0] = _Input({1, num_tokens}, ...);                        // ← shape 跟着变
int latent_len = total_size / latent_dim;                        // ← DP 预测
inputs[0] = _Input({1, latent_dim, latent_len}, ...);
```

NekoChatMini 这份 `super_tonic_loader.cpp:111` 相反：
```cpp
VARP v_text_ids = _Input({1, T_TEXT}, NCHW, halide_type_of<int>());  // 硬编码 256
```

#### 优化流程（分 3 步验证）

**Step 1（5 分钟，先验证）：模型是否支持动态 shape？**

`super_tonic_loader.cpp:111` `make_ids()` 临时改成不 pad：

```cpp
static VARP make_ids_dyn(const std::vector<int>& ids, int len) {
    VARP v = _Input({1, len}, NCHW, halide_type_of<int>());
    ::memcpy(v->writeMap<int>(), ids.data(), len * sizeof(int));
    return v;
}
```

在 `synth()` 里传 `len=32` 试一句短句：
- ✅ 能跑：模型本来就支持动态 shape，走 Step 2（**改代码就完事**）
- ❌ 报 shape mismatch / 数值全错：模型 freeze 到 256 了，走 Step 3（**要重导模型**）

**验证 log**：`adb logcat -s SuperTonicJNI` 看 `dp: dur=... valid_l=...` 和最终采样数。

**Step 2（模型支持动态时）：改推理代码抄官方版**

参考 `apps/frameworks/mnn_tts/src/supertonic/mnn_supertonic_tts_impl.cpp:686-900`，把 `super_tonic_loader.cpp:96-199` 的 `synth()` 改成动态：

```cpp
std::vector<float> synth(...) {
    int num_tokens = (int)text_ids.size();   // 或用 text_mask 有效长度

    // DP 用实际 num_tokens
    VARP v_text_ids = _Input({1, num_tokens}, NCHW, halide_type_of<int>());
    // ... 灌数据 ...
    auto dp_out = g_dp->onForward({...});
    float dur_sec = dp_out[0]->readMap<float>()[0];
    int valid_l = clampv((int)(dur_sec * SR / (double)CHUNK_SAMPLES), 1, 100);

    // TE 用 num_tokens
    // ... 类似 ...

    // VE 用 valid_l 而不是 L_TTL
    VARP x = _Input({1, LATENT_DIM, valid_l}, NCHW, halide_type_of<float>());
    // ... 8 步扩散 ...

    // Vocoder 输入 x 已经是 valid_l 长度，无需 crop
}
```

**同步改 Kotlin 前端** `ZhFrontend.kt:146`：不 pad 到 T_TEXT，返回实际长度。

**Step 3（模型 freeze 时）：重导模型 + 改代码**

- 找 SuperTonic 训练侧的 export 脚本（**归档不含**，可能在 `/mnt/d/Dev/apps/supertonic-neko/` 或询问原作者）
- `torch.onnx.export` 加 `dynamic_axes={"text_ids":{1:"T"}, "noisy_latent":{2:"L"}, ...}`
- 用 `MNN/tools/converter/build/MNNConvert` 转 mnn 时保留动态维度
- 重新 `adb push` 到 `/sdcard/Android/data/com.neko.chat/files/models/tts/`
- 代码改动同 Step 2

#### 已知坑

1. **Conv1D stride 兼容**：SuperTonic 的 VE 有若干 stride=2 的 Conv1D。动态 shape 时 `latent_len` 必须是 stride 的倍数，否则输出对不齐。当前 `L_TTL=100` 是 4 的倍数；改动态时 `valid_l` 记得 round up 到 4 的倍数：
   ```cpp
   valid_l = ((valid_l + 3) / 4) * 4;
   ```
2. **latent_mask 仍然要传**：VE 的 attention 里可能用它做 padding mask，即使 shape 已经缩小，mask 里对应位置的值还是要给。全 1 就行。
3. **Vocoder 尾巴噪声**：`super_tonic_loader.cpp:187-194` 现在靠 `valid_samples` 手动 crop。改动态后，vocoder 输入已经是 valid_l 长度，输出即真值，**crop 逻辑要删掉**（否则短句被截更短）。
4. **cache miss / kernel 重编**：MNN 动态 shape 首次遇到新 `num_tokens` 会内部 resize，第一次慢。**必须 warmup**：`SuperTonicInfer.init()` 末尾跑 3 遍不同长度的 dummy synth（比如 8/16/32 字），把常见 shape 都 cache 一遍。
5. **fp16 精度累积**：8 步扩散 fp16 累加在长句上误差比短句大——但改小 shape 反而更稳。不用管。

#### 预期收益

一句 15 字回复（valid_l≈15，实际用满 100 帧）：
- VE 单步：100 帧 → 15 帧，Conv1D 计算量线性于长度，约 -85%
- 8 步 VE：从占首包 3-4s 降到 0.5-0.8s
- **K80 首包 4-5s → 预期 1.5-2s**（和 vivo 拉平）
- 长句（>60 字）收益变小，因为 valid_l 逼近 100

### 3.2 减少扩散步数（-40%，独立于 3.1）

**改动**：`super_tonic_loader.hpp:27` `TOTAL_STEP = 8` → `4`

**坑**：
- SuperTonic 是 flow matching (Euler)，理论上 4 步能出可用音质，但**必须真机 A/B 试听**
- 音色变闷、颤音消失等症状意味着步数不够，回退到 6
- 训练时用了 8 步，改推理步数**不改模型权重**，是纯采样调度调整

**验证**：改完不用重导模型，**只重编 libsupertonic.so**（gradle 会自动带上，因为 CMake 编译）。用同一段文本对比 4 步 vs 8 步 wav 文件人耳听。

### 3.3 预热（-30~50% 首句延迟，无副作用）

**当前问题**：`SuperTonicInfer.init()` 加载完模型直接返回，第一句 synth 时 MNN 才做算子选优/内存分配。K80 上首句会背这一波 warmup 开销。

**改法**：`app/src/main/java/com/example/supertonic/SuperTonicInfer.kt:63` `initialized = true` 前加：

```kotlin
// warmup: 跑一遍 dummy synth 让 MNN 完成算子选优 + kernel cache
runCatching {
    val (_, ms) = synthInternal("你好", seed = 0, speed = 1.0f)
    android.util.Log.i("SuperTonicInfer", "warmup done in ${ms}ms")
}
```

其中 `synthInternal` 是把现有 `synth()` 抽出的私有方法（避开 `initialized` 检查，因为此时还没置 true）。

**坑**：`prepare()` 走的是 IO Dispatcher，warmup 会让 `prepare()` 多耗 1-2s，用户感知是"启动慢一点"。可以做成后台异步 warmup，等第一次真实 synth 时若 warmup 还没完就等它。

### 3.4 LLM/TTS 抢核（-20%，架构改动，选做）

**问题**：`super_tonic_loader.cpp:41` TTS 建 4 线程 Executor + `setGlobalExecutorConfig`；LLM 内部另一份运行时 4 线程。**LLM decode + TTS 合成并行时**，8 大核也就够两组 4 线程排队。

**改法思路**：
- TTS synth 开始前，通过一个新 JNI 接口把 LLM `thread_num` 动态降到 2（MNN 支持 runtime 改）
- TTS 结束后恢复 4 线程

或者更简单：TTS 就用 2 线程（`SuperTonicInfer.init(numThreads = 2)`）——单核 SIMD 已经很快，扩散是访存瓶颈，多加线程收益递减。

**坑**：真机对比再决定。有些机器 2 线程比 4 线程还快（大核 boost 更高）。

### 3.5 TTS 换 GPU 后端（不确定，可试）

**改法**：`super_tonic_loader.cpp:41`
```cpp
MNN_FORWARD_CPU  →  MNN_FORWARD_OPENCL
```

**前提**：`libMNN.so` 必须编入 OpenCL 后端。当前 shipped 那份**有**（来自 MoeAvatarPro build_64.sh 带 `MNN_OPENCL=true`）。**但 `build_mnn.sh` 那份是 false**，先修 §1 那个脚本。

**预期**：
- Adreno GPU（骁龙）：TTS 扩散/Conv 亲和度高，可能 -30~40%
- Mali GPU（天玑）：不一定赢 CPU+SME2，看代
- K80 是 Adreno，可能是最大受益者

**坑**：
- MNN OpenCL 后端首次编译 kernel 慢（预热要 1-2s，可用 `cache_file` 缓存到本地）
- 有些算子 OpenCL 不支持会自动 fallback CPU，可能反而慢——**必测**
- 温度：GPU 也发热，长时间聊天会热降频

---

## 4. ASR：暂不动

sherpa-mnn 流式 zipformer int8，编码器/decoder/joiner 三个 mnn。用户体感是"长按说话立刻上屏"，首包毫秒级，**不是瓶颈**。除非用户要求端到端 <500ms，否则先不碰。

---

## 5. 排期建议（给下一个 agent）

按性价比排：

| 顺序 | 任务 | 工时 | 预期收益（K80） |
|---|---|---|---|
| 1 | 修 `build_mnn.sh` + 加 TTFT 打点 + TTS 每步 STNPRINT | 1h | 0（打基础，为下面量化收益）|
| 2 | 加 warmup（§3.3） | 0.5h | 首句 -30~50% |
| 3 | TOTAL_STEP=4 试听（§3.2） | 1h（含 A/B 试听）| -40% VE |
| 4 | 动态 shape 验证（§3.1 Step 1） | 0.5h | 决定后续路径 |
| 5 | 动态 shape 改代码 或 重导模型（§3.1 Step 2/3） | 2-6h | **-70~80% VE** |
| 6 | LLM config.json 加 mmap（§2） | 0.5h | 冷启动 -50%、内存改善 |
| 7 | TTS OpenCL 后端试（§3.5） | 1h | K80 可能 -30% |
| 8 | LLM/TTS 抢核（§3.4） | 3h | -20% 并行场景 |

**验收标准**：
- Redmi K80 上首包延迟 4-5s → **≤ 2s**
- 音质人耳无明显退化
- vivo 上不变差
- 不引入新的 crash / mic 冲突

---

## 6. 每次改完必跑的验证

**功能不能坏**（`../README.md` §端到端验证清单）：
1. 文本聊天出猫娘回复
2. 回复自动 TTS + Live2D 嘴型
3. 长按麦克风 ASR 上屏
4. 换背景
5. logcat 无 crash

**性能量化**：
```bash
ADB='/mnt/d/softwares/platform-tools/adb.exe'
"$ADB" logcat -c
# 应用中发一句话
"$ADB" logcat -s MoeAvatar.SuperTonic MoeAvatarLLM SuperTonicJNI -d > perf.log
grep "TTFT\|firstPacket\|dur=\|synth done" perf.log
```

对比改动前后同样输入的 firstPacketMs 和 rtf。

---

## 7. 参考资料

- SME2 官方说明：`docs/android-wsl-开发指南/【指南】在_MNN_框架中开启_SME2_加速_CPU_推理_v1-1 1.txt`
- MNN 核心库编译流程：`docs/android-wsl-开发指南/03_MNN核心库与MnnLlmChat编译部署流程.md`
- 官方 MNN SuperTonic 动态 shape 参考实现：`apps/frameworks/mnn_tts/src/supertonic/mnn_supertonic_tts_impl.cpp`（Line 686-900 是 DP/TE/VE 三段的动态输入构造，**照抄这份就行**）
- MNN LLM config 字段清单：`transformers/llm/engine/include/llm/llm.hpp` + `docs/llm.md`（如存在）
- 本项目开发规范：`docs/DEVELOPMENT.md`

---

## 8. 归档 & 工作副本

**约定**（详见 DEVELOPMENT.md）：
- 工作副本 `apps/Android/NekoChatMini/`：改代码、编 apk、真机验证。不在 git 里
- 归档副本 `submit/mini-app/apps/NekoChatMini/`：真机验证 OK 后**手动同步**改动到这，由用户 commit
- git 根在 `submit/mini-app/.git`（不是 `MNN/.git`）

改完 `.cpp` / `.hpp` / `.kt` 后，用 rsync 把 `app/src/main/cpp/supertonic/` 和相关 kotlin 文件同步到归档，写好 commit message 让用户 commit。**不要动 `libMNN.so`**（大文件不入 git）。

---

**最后**：改任何一处前，先 `git status` 确认自己在 `fix/online-llm-thinking` 或后续 perf 分支，别直接搞 master。

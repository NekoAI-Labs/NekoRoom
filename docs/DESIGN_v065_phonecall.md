# v0.6.5 电话模式设计

## 产品边界

电话模式留在 Live2D 主页面，不切换页面或角色。普通输入栏的电话图标进入模式；挂断后恢复原输入栏。

电话控件分两排：上排居中显示交互状态，下排提供大号麦克风静音与红色挂断按钮。上排的五枚圆角“声纹粒”在静默时轻微呼吸、检测到人声时随音量拉伸；AI 输出时收拢为 `打断` 按钮。它不是三点跳动，也不是连续录音波纹。

| 状态 | 上排 | 文案 |
|---|---|---|
| 可说话 | 低矮声纹粒 | 开口和我说话吧 |
| 人声 | 动态声纹粒 | 嗯嗯，我在听哦… |
| 收尾 | 收束声纹粒 | 让我想想喵… |
| AI 输出 | `打断` | 说话或点击打断我 |
| 静音 | 无图标 | 我先安静等你 |

## 音频与状态所有权

`PhoneCallController` 是电话模式唯一状态机；每轮递增 session id，旧的 VAD、ASR、LLM、TTS 回调不得修改当前状态。

`PhoneAudioCapture` 是电话模式唯一 `AudioRecord` 所有者。它向 FireRed VAD、声纹 UI 和 ASR 分发同一份 16 kHz 单声道 PCM；不得让 VAD 与 ASR 竞争麦克风。

```text
关闭 → 可说话 → 人声 → 理解中 → AI 输出
  ↑       ↑       │       │          │
  └─挂断──┴─静音──┴───────┴─打断─────┘
```

## FireRed MNN 契约

- 模型：`fireredvad_vad_fp16.mnn`，源 ONNX 输入 `feat float32 [1,T,80]`，输出 `probs float32 [1,T,1]`。
- 输入来自 16 kHz PCM int16 → FireRed 80-bin 特征 → CMVN → MNN。
- 动态 `T` 每次 resize；MNN host input/output 都使用 `MNN.Tensor_DimensionType_Caffe`。
- 首版后处理：threshold 0.4，50 ms smooth，200 ms speech start，400 ms speech end，100 ms merge，300 ms pre-roll。
- 已验证 ONNX/MNN 在 4331、4192 帧输入下最大误差小于 0.002，分段一致。

模型本体不进 Git；构建工作副本时由本地脚本校验 SHA256 后复制到 assets，最终 APK 必须内置模型与 CMVN。

## 验收顺序

1. JNI 离线特征/MNN 与既有 Python 基线分段一致。
2. 真机 VAD probe 记录冷/热启动与 start/end 延迟。
3. VAD 与本地 LLM/TTS 反复打断压力测试无 native 崩溃。
4. 接入 ASR、LLM、TTS 后静音、挂断、打断均可取消且无旧音频。
5. 两排 UI 不遮挡 Live2D；普通按住说话与文字输入回归通过。

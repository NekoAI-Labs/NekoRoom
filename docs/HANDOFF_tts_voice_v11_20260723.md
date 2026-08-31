# NekoVoice v1.1 / SuperTonic 动态 shape 交接

## 当前分支

`perf/tts-voice-v1.1`，基于 `fb664b0` 创建。目标是把语音模型切换到线上
`jiaohui/NekoVoice-v1.1`，并验证动态 shape 的代码路径。

## 已完成

- `ModelManager` 的 TTS 仓库改为 `jiaohui/NekoVoice-v1.1`。
- 必需音色文件改为 `voices/catgirl_style.json`；下载器创建父目录，避免嵌套路径下载失败。
- 新增 `LlmConfig.ttsVoiceId`，默认值为 `catgirl_style`，SuperTonic 初始化时按
  `voices/<ttsVoiceId>.json` 加载，不把音色 ID 写死在推理逻辑中。
- Kotlin frontend 和 C++ loader 均传递实际 token 长度；latent 和 vocoder 也按本句有效长度运行。
- 实验目录中 682MB 的 `deploy_android` 模型复制已移入桌面回收站；实验模型与试听文件的正式位置是
  `/mnt/d/models/tts`，其中 `mnn_fp16/voices/catgirl_style.json` 已存在。

## 验证

此前已在 `/mnt/d/models/tts` 完成 DP → TE → VE × 8 → Vocoder 的动态 shape 合成，覆盖短句、长句和
不同 latent 长度；试听和性能结果见 `listen_test/report.json`。本分支需要继续把归档树同步到
`apps/Android/NekoChatMini` 后运行 `./gradlew :app:assembleDebug`，再用设备日志确认 native loader
加载的是 `tts/voices/catgirl_style.json`。

## 注意事项

- 模型权重不入 Git，也不放进 APK；线上安装由能力中心下载。
- `ttsSpeaker` 是旧 BertVITS2/MiniMax 兼容配置，SuperTonic 使用新的 `ttsVoiceId`。
- 当前只合入语音模型路径和动态 shape；性能设置面板/SME2 开关仍留在独立实验目录，避免与在线 LLM
  改造混合提交。

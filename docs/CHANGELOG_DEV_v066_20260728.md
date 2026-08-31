# v0.6.6 开发变更记录（内部）

日期：2026-07-28

## 电话模式

- 连续 AudioRecord 采集与 ASR 解码分离；播放/模型推理忙碌时保留 PCM 队列，降低首词和长句丢失风险。
- 接入 `VOICE_COMMUNICATION` 采集和系统 AEC/NS/AGC（以设备实际支持为准），TTS 输出保持 `USAGE_MEDIA / CONTENT_TYPE_MUSIC`，避免进入通话播放路由。
- 打断采用实时 RMS 快路径与后台 FireRed VAD 双层判断；播放期间使用更严格的动态门槛，并针对真机残余外放回声调整绝对 RMS 下限、连续帧数与基线倍数。
- 修复 `SpeechQueue.clear()` 在旧合成协程退出前重开管线的竞态。打断后保持硬闸关闭，旧 PCM 不得重新创建 `AudioTrack` 或把 UI 从聆听状态夺回播放状态。
- 修复重新开启麦克风时 AI 仍在播放却错误显示聆听的问题；根据待生成/待播放状态恢复打断控件。
- 采用 AudioTrack lease/generation 防止旧 idle check 访问已 release 的 track。

## 本地模型与界面

- 默认模型 ID 仍为 `llm-neko-v21`，仓库和安装目录不变；仅用户展示名调整为 Neko 猫娘 v1.1。
- `localMultiTurn` 新用户默认 `true`；已有用户已保存的配置保持不变。
- 电话模式状态文字向右、向下微调，避免与打断控件视觉重叠。

## 发布保护

- release 启用 R8 `minifyEnabled` 与 `shrinkResources`，并保留 LLM/TTS/VAD/Live2D JNI 桥接类，避免 native 名称解析被混淆破坏。
- 创建本机私有 release keystore：`~/.config/nekochat/release.keystore`；配置文件同目录且权限 600，均不纳入 Git。必须离线备份，丢失后无法对已签名发布包做覆盖升级。
- release APK 使用 RSA-3072、APK Signature Scheme v2；R8 mapping 位于构建输出，发布归档时应私密保留。

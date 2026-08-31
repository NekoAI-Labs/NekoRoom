# NekoChatMini 版本记录（完整时间线）

> 按 git 历史 + CHANGELOG 整理，最后一列为开发细节入口（git commit / 交接文档）。
> 对外发布口径见 `CHANGELOG.txt`；本表是开发侧版本脉络。

| 日期 | 版本 | 里程碑 / 开发细节 |
|---|---|---|
| 07-09 | v0.1-neko | 首版跑通四件套（qwen35-neko LLM + SuperTonic TTS + sherpa ASR + ATRI Live2D），模型全 adb push。前置：Cubism SDK 4→5-r.5 升级修 Mikawa(moc3 v5) 崩溃 |
| 07-09 | v0.6-neko | 打断修复：新输入同步停旧 LLM+TTS；SuperTonic synth 加锁串行化；TTS 半截修复（playbackHeadPosition 排空等待）；token 上限 40/64；夜窗默认背景 |
| 07-10 | v0.7-neko | Live2D 电源键区域 NPE 修复；TTS 卡顿修复（跳过纯标点 clause、MAX_CLAUSE_CHARS 20→28） |
| 07-10 | v0.8/v0.9-neko | 性能统计两行（LLM 行即出、TTS 行播完追加）；半透明白性能小字 |
| 07-11 | v3.0/v3.1 | 沉浸式 de-chat UI；磨砂玻璃输入栏/设置弹窗、左上角时钟 |
| 07-11 | v3.2-avatar | 第二角色 FenmaoLoli（默认星星眼）+ 切换 UI + 口型同步修复（`feat: NekoChatMini v3.2`） |
| 07-14 | v3.3-online | 在线 LLM（OpenAI 兼容 SSE）+ MiniMax 流式 TTS；服务商快选（`feat: NekoChatMini v3.3-online`） |
| 07-15 | v3.3.1 / v0.3 | 在线配置磨砂弹窗；自定义 Live2D 模型 ZIP 导入 + 磨砂角色卡片 |
| 07-16 | v0.4 | **Neko 能力中心**：模型按需下载（ModelScope）、免存储权限、首启引导（`feat: NekoChatMini v0.4`） |
| 07-18 | v0.4.1 | 首装回归三 bug 修复 + 磨砂猫头 launcher（`fix: NekoChatMini v0.4.1`） |
| 07-18 | v0.5 | **Character Engine**：`<action>` JSON 驱动 Live2D 表情/装扮双通道（`feature/action-system`，见 `DESIGN_action_driver.md`） |
| 07-18 | v0.6 | 语音优先交互 + 线上 15 轮会话（`feat: NekoChatMini v0.6`） |
| 07-19~20 | v0.6-neko | 默认装扮/输入交互崩溃修复；字幕与 TTS 播放同步（`fix: v0.6-neko sync subtitles`） |
| 07-21 | v0.6.1-neko | 首个对外可用版：安全层（分类词库 + AI 标识 + 首启提示） |
| 07-22 | v0.6.2 | 在线 LLM 轻量客户端化 + 下载 UX 收尾（`fix/online-llm-thinking`） |
| 07-23 | v0.6.3 | 多本地模型下载/切换（Qwen3.5 0.8B 等）；TTS 提速 ~45%；分段下载加速；模型删除/迁移校验（`feature/multi-llm-v0.6.3`） |
| 07-23~24 | v0.6.4 | MiniCPM5-1B 多精度（FP16/Q8/Q4/Embed8/Embed4）；TTS Quality 步进；性能面板与平均值；MIMO 服务商；本地多轮开关（`feat/v0.6.4-minicpm-tts-perf`） |
| 07-25~28 | v0.6.5 | **打电话模式**：FireRed VAD 端点检测 + barge-in 打断 + 通信音频路由 + 半双工会话隔离（`feat/v0.6.5-phonecall`，见 `DESIGN_v065_phonecall.md`） |
| 07-28~31 | v0.6.6 | 通话硬化（残余回声/打断 fence）；默认 Neko v1.1；背景自动裁剪；7z/RAR 角色导入；R8 加固 + 本地 keystore 签名 |
| 08-03~04 | v0.6.7（当前） | ASR/TTS 模型选择；NekoVoice v1.3 多音色（11 选）；Live2D 角色删除；性能面板位置修正（`feat/v0.6.7-asr-live2d-perf`） |

## 开发细节索引

- **当前基线**：`feat/v0.6.7-asr-live2d-perf` @ `562c455`，交接入口 `docs/HANDOFF_v067_20260804.md`。
- **⚠️ v0.6.7 未发布原因（P0）**：默认 ASR 1.2（zipformer2）被 `ChatAsrController.buildConfig()`
  硬编码 `modelType="zipformer"` → sherpa 按旧格式找 `attention_dims` → native `exit(-1)` →
  启动闪退（日志：`sherpa-mnn: 'attention_dims' does not exist` / `pthread_mutex_lock on destroyed mutex`）。
  修法：modelType 留空自动探测，冷启动 ≥3 次回归。修前禁止发布。
- **打断类问题历史教训**：v0.6 起反复出现「新输入未停旧 LLM/TTS → 破碎音/闪退」，
  所有入口（文本发送/语音会话/电话模式）都必须先 `stop()` 旧任务再开新一轮。
- **MNN 并发红线**：LLM/TTS/ASR 共用 libMNN，native 调用必须走 `MnnGlobalLock`
  （详见 `docs/PROJECT_OVERVIEW.md` §6）。
- **模型注册演进**：v0.4 起模型进 `ModelManager.REGISTRY`；v0.6.7 起分能力推荐项 +
  兼容回退（`activeAsr/activeTts` 优先用户选择 → 已装版本 → 推荐项）。删除注册项 =
  老用户丢模型，禁止。
- **文档版本**：`docs/HANDOFF_*.md` 按版本递增；旧 `DEVELOPMENT.md`/`HANDOFF.md`
  的版本号与默认值已过期，以最新交接文档和代码为准。

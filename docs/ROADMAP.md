# ROADMAP

## 目标
在手机上跑一个**纯端侧**的猫娘聊天体验：全屏 Live2D 角色 + 微调猫娘 LLM 文本对话 +
语音输入(ASR) + 语音回复(TTS) + 嘴型同步，人格一致、离线可用、APK 尽量小（模型全挂载）。

## Milestones

### M1 ✅ 端侧全家桶最小可用（v0.1-neko，2026-07-09）
- [x] fork MoeAvatarPro，剥离全部 call-mode（只留聊天）
- [x] LLM 指向微调 qwen35-neko，猫娘系统提示词
- [x] TTS 换 SuperTonic-Neko（替换 BertVITS2），按标点分句
- [x] 长按麦克风 ASR（sherpa-mnn 流式）
- [x] Live2D ATRI 全屏 + 触摸 + 嘴型同步 + 换背景
- [x] 模型全 adb push 不进包，APK ~26MB
- [ ] 真机端到端验证（待安装确认 + 视觉确认）

### M2 体验打磨
- [x] **AI 身份说明与轻量安全层（v0.6.1-neko）**：首次 AI/非真人提示、左上角标识、
  TXT 分类词库、输入与字幕/TTS 前双向拦截；公开发布前仍需服务端上下文审核与远程规则更新。
- [x] **线上模型支持（v3.3-online）**：LLM 走 OpenAI 兼容 API（SSE 流式），TTS 走 MiniMax 在线流式；
  设置里配置 base/key/model + 猫娘提示词 + MiniMax key/voice，与本地后端一键切换。轻量非隐私模式。
- [ ] Mikawa 猫娘皮套接入（SDK5 已就绪，需真机确认 moc3 v5 不闪退 + 微调 scale/translate）
- [ ] 换背景持久化（记住上次选的图）
- [ ] 猫娘人格/音色/形象一致性微调（sys prompt + catgirl_style + 表情动作联动）
- [ ] TTS 分句流式播放的停顿/自然度优化
- [ ] 打断（barge-in）：说话时自动停当前 TTS

### M3 模型管理 / 部署简化
- [x] 应用内检测并下载多个本地 LLM，Qwen3.5 0.8B 为默认推荐模型
- [x] 本地/线上统一短期多轮会话、system prompt 和表情反馈
- [x] 设置中选择本地模型，切换模型时清空短期 history
- [x] 本地 Qwen 配置默认关闭 `enable_thinking`
- [ ] push 脚本参数化（源路径不写死 D:\down）
- [ ] LLM 量化/更小模型选项，降低首字延迟

### M4 互动增强（可选）
- [ ] **LLM 驱动角色（TODO，核心方向）**：由语言模型输出结构化参数控制 Live2D 角色的
  **表情（expression）+ 状态（state）+ 动作（motion）**，甚至**换配饰（accessory）**。
  即 LLM 回复时附带情绪/意图标签 → 映射到 `nativeApplyExpression` / 动作组 / 配饰参数，
  让角色表现随对话内容实时联动，而非固定表情。（FenmaoLoli 已有 19 个表情/配饰类 exp 可复用：
  maid/catear/tail/hairpin/shoes/socks 等配饰 + stareyes/hearteyes/blush/tears/pout 等情绪。）
- [ ] HitAreas 点头/点身体触发专属动作（挑带 HitAreas 的皮套）
- [x] 多角色切换 UI（v3.2-avatar：设置里单选弹窗，FenmaoLoli↔Ziyan）

## 当前进展
M1 已编译成功、APK 归档、三套模型已 push 到设备；剩真机安装确认（HyperOS 设备端弹窗）+
端到端视觉验证。下一步：真机跑通后按 M2 打磨体验。

v3.3-online 已加线上模型支持（编译通过、APK 归档 ~45MB），线上链路待真机 + 真实 API Key 实测。
v0.6.3 多本地 LLM 已完成源码与 APK 编译验证；待真机验证模型下载、切换、多轮记忆和表情反馈。

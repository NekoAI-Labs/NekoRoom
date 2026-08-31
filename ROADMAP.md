# Roadmap · NekoChatMini

按**版本里程碑**推进，而不是按小功能。每个版本对应一个功能包（一组能自洽发布的
特性），在专用 feature 分支上开发 → 合入 `develop` 联调 → 稳定后合入 `main` 发版。

---

## 版本状态一览

| 版本 | 主题 | 状态 |
|---|---|---|
| v0.4 | Neko 能力中心 · 按需下载 · 首启引导 | ✅ Released（2026-07） |
| **v0.5** | **Character Engine**（LLM 驱动表情/装扮） | 🚧 **Developing** |
| v0.6 | Companion（Voice call · Memory · History） | ⏳ Planned |
| v0.7 | Agent（PC bridge · Claude Code） | ⏳ Planned |

---

## v0.5 · Character Engine

> 让 AI 边说边演。回复里嵌入 `<action>` JSON 块 → 解析器抽出 → 驱动 Live2D 切换
> 表情/装扮。仅在线模型启用。设计详见 [`docs/DESIGN_action_driver.md`](docs/DESIGN_action_driver.md)。

- [ ] **M1** `ActionTagFilter` 流式解析 + `emotion` 单字段 + 能力段注入 + few-shot
- [ ] **M1** `Live2DController.applyExpression` 薄封装 + `availableExpressions` 收集
- [ ] **M1** `LlmConfig.liveActingEnabled` 开关（默认开）
- [ ] **M2** `outfit` 字段 + 运行时清单动态注入
- [ ] **M2** 节流（同名 emotion 800ms 去重）
- [ ] **M3** action 与 `SpeechQueue` clause 对齐（说到哪演到哪）
- [ ] **M3** `nativeStartMotion` JNI + `motion` 字段生效

**分支**：`feature/action-system`

**非目标**：不做换角色 / 换皮套（角色只在设置 UI 手动切）；不做表情叠加合成（互斥
替换，组合需模型作者预制 exp3）。

---

## v0.6 · Companion

> 从「聊天工具」进化到「陪伴」，让用户能像打电话一样跟猫娘聊天，跨会话记得你。

- [ ] Voice call 模式（半双工 → 后续全双工，VAD + 打断）
- [ ] 长期记忆（对话摘要 → 向量或结构化，跨 session 检索）
- [ ] History（会话列表 UI · 归档 · 搜索）
- [ ] 首启欢迎语可关（有些人不需要）

**分支**：`feature/voice-call`（记忆/历史各自子分支再决定）

---

## v0.7 · Agent

> 让猫娘能真的动手：接入 PC、调 Claude Code，从聊天角色升级为桌面 AI 伙伴。

- [ ] PC bridge（手机 ↔ PC，敏感操作需授权）
- [ ] Claude Code 接入（远端 agent，工具调用）
- [ ] 动作反馈上屏（执行进度 → Live2D 表情联动）

**分支**：`feature/claude-agent`

---

## Git 分支策略

```
main            ← 只接受来自 develop 的 merge，每个 merge = 一个已发版的版本 tag
 │
 └── develop    ← 集成分支，各 feature 完成后合到这里联调
      │
      ├── feature/action-system     (v0.5)
      ├── feature/voice-call        (v0.6)
      └── feature/claude-agent      (v0.7)
```

**流程**：
1. 从 `develop` 切一个 `feature/<name>` 分支做开发；小步 commit 无所谓，粒度自由。
2. 该版本所有 M 阶段完成 → merge 回 `develop`；在 develop 上跑一次完整联调。
3. develop 稳定 → merge 到 `main` + 打 tag（`v0.5`）+ 更新 CHANGELOG。

**约定**：
- **不再在 `main` 上直接改**。热修 bug 也走 `hotfix/<name>` → merge main + develop。
- **不再按小功能 commit 管理任务** —— 任务在 feature 分支内自由推进，对外一次
  release 一次公告。
- Feature 分支合并前 rebase 到最新 develop，保持线性。
- 版本 tag 打在 main 的 merge commit 上，格式 `v0.5.0`（语义化，patch = hotfix）。

---

## 修订

- 2026-07-18：初始化 roadmap，锁定 v0.5-v0.7 三块。v0.4 已 released。

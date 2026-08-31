# 设计文档 · LLM 驱动表情 / 装扮

> **目标**：让 AI 边说边演。回复里嵌入 `<action>` JSON 块 → 解析器抽出 → 驱动 Live2D
> 切换表情 / 装扮。控制块**不朗读、不上屏**，其余文本正常 TTS + 字幕。
> **仅在线模型启用**（本地小模型指令跟随差，先不做）。
>
> 状态：**v0.5 Character Engine · M1 待实现**。修订：2026-07-18（改 JSON 协议 / 去
> 换角色 / 去叠加，简化）。

---

## 1. 现有 Live2D 能力盘点

| 通道 | JNI 接口 | FenmaoLoli 合法名 |
|---|---|---|
| **表情/情绪** | `nativeApplyExpression(name)` | `hearteyes` `stareyes` `blush100` `blush60` `tears` `terrified` `guilty` `pout` `darkface` |
| **装扮组件** | 同上通道（exp3.json） | `maid` `longhair` `shorthair` `thinhair` `catear` `tail` `hairpin1` `hairpin2` `shoes` `socks` |
| **动作 motion** | ❌ 当前无 `nativeStartMotion` | 需加 JNI + C++（M3 里程碑做） |
| 口型 | `nativeProjectMouthOpenY` | TTS 已驱动，与本功能无关 |

**行为约定（本设计的核心简化）**：Live2D `ApplyExpression` **视为互斥替换** —— 后一次
apply 覆盖前一次。**不搞多表情叠加、不搞动态 exp3 合并**。装扮和情绪同槽，当二者
撞车时后 apply 者赢；模型作者需要"戴猫耳 + 星星眼"这类组合，请**预制成一个新的
exp3**（如 `hearteyes_catear.exp3.json`），而不是运行时合成。

**换角色不走 action** —— 角色 / 皮套只从"角色切换" UI 手动切，LLM 的控制协议里**不
提供 char 字段**，避免误触抽自己/换错人。

**表情名从哪来（运行时）**：`Live2DLoadInterface.onLoadOneExpression(name, index)`
每加载一个表情回调一次。当前 `Live2DController.kt:106` 是空实现，M1 第一步收集到
`availableExpressions` 供提示词注入。

---

## 2. 协议格式（`<action>` JSON 块）

```
<action>
{
  "emotion": "hearteyes",
  "motion":  "smile",
  "outfit":  "catear"
}
</action>
```

- **块标签**，内容是**严格 JSON**，字符串值。
- 合法字段：`emotion` / `outfit` / `motion`（**motion 现在 no-op，M3 里程碑后生效**）。
- 所有字段**可选**；未出现即"不改动那一路"。
- 值必须是**运行时注入清单里的合法名**，非法值 → 丢弃 + log，不影响文本。
- 解析顺序：`emotion → outfit → motion`，逐个 apply（Cubism 自然覆盖，就是我们要的效果）。
- ~~char 字段~~ **不提供**，见 §1。

**一条完整回复长这样**：
```
好呀好呀，主人想看小喵开心的样子嘛~
<action>
{ "emotion": "hearteyes", "outfit": "catear" }
</action>
铛铛！戴上猫耳，眼睛也变成小星星啦，这样有没有更可爱一点喵~
```
解析后：开头 / 结尾进 TTS + 字幕，中间 `<action>` 被抽走并触发切换。

**为什么用 JSON 块而不是 tool-calling / 自闭合属性**：JSON 结构化好、易扩展新字段；
tool-calling 会打断"单条人设消息"的叙事流且端点兼容性差；自闭合属性遇到长值不好读。
JSON 在流式里被切碎的风险靠"块级 buffer 到 `</action>` 才 parse"来规避。

---

## 3. 系统提示词（在线模型注入）

追加到用户 `systemPrompt` 后的**能力段**：

```
【表演能力】你可以在回复中嵌入控制块，让你的 Live2D 形象随对话切换表情/装扮。
规则：
- 先用一句话自然回应（保持人设），再插入控制块，最后用一句话确认（例："换好啦喵~"）。
- 语法：<action>{"emotion":"...","outfit":"..."}</action>，严格 JSON。
- emotion 与 outfit 各自最多一个，未出现即保持原样；motion 字段暂不生效可以省略。
- 控制块不会被读出来，也不会显示，只用来驱动形象；一条回复最多用 1 个块。
- 只能使用下列合法名字，不要自己编：
  可用 emotion：{RUNTIME_EMOTIONS}
  可用 outfit ：{RUNTIME_OUTFITS}
- 情绪自然时才切换，不要每句都切；不确定就不加控制块。
```

+ 1~2 个 few-shot（示范"文本 → 块 → 文本"三段结构）。

**要点**：
- 合法名清单**运行时按当前模型注入**，杜绝瞎编。
- 只在 `config.backendKind == OPENAI` 时注入；本地后端不注入不解析。
- 加 `config.liveActingEnabled` 开关（默认开），设置里可关。

---

## 4. 组件调度

现有 token 管线（`LlmChatActivity.kt:1137-1156`）：
```
be.chat() → token → ttsFilter.feed → SentenceSplitter → 字幕 + speechQueue.enqueue
```

**最前加一层 `ActionTagFilter`**：
```
token → ActionTagFilter.feed(token)
           ├─► 抽出 <action>{...}</action> → 派发到主线程驱动 Live2D
           └─► 返回可见文本 → 原有 ttsFilter/字幕/TTS
```

控制块**天然不进 TTS、不进字幕**（复用 `<think>` 过滤同款套路）。

### 4.1 `ActionTagFilter`（流式，跨 token 缓冲）

- 复刻 `ThinkTagFilter`：pending buffer 从 `<action` 开始缓冲，直到读到 `</action>`
  才完整；未闭合就一直缓冲直到证伪（`<` 后跟非 `action`）再原样吐出。
- 完整块 → `JSONObject` 解析 → 生成 `Action(emotion?, outfit?, motion?)` → 回调派发。
- JSON parse 抛异常 → 丢弃该块 + log，**不打断文本流**。
- **无 Live2D 依赖**（可单测）；派发靠回调交给 Activity。

### 4.2 派发到 Live2D

- 回调在 collect 线程 → `runOnUiThread { }` → 依次：
  1. `emotion` 非空 → `live2d.applyExpression(name)`
  2. `outfit`  非空 → `live2d.applyExpression(name)`（同通道，覆盖 emotion 的对应参数）
  3. `motion`  暂时 no-op（M3 里程碑后接 `startMotion`）
- 每个 apply 前做**节流**：同名 emotion 800ms 内去重，避免流式过程重复派发。

### 4.3 时序：MVP 立即派发

- **MVP**：解析到块立刻切，视觉可能略早于对应语音（TTS 按句滞后 ~1s），可接受。
- **v0.5 M3 优化**：把 action 作为锚点排进 `SpeechQueue`，`setOnClauseStart` 触发时
  才切，做到"说到哪演到哪"。

---

## 5. Live2D 侧改动清单

1. `Live2DController.onLoadOneExpression` 收集 `availableExpressions`；暴露
   `availableExpressions(): List<String>`。
2. 加薄封装 `fun applyExpression(name: String)`（`glView.queueEvent { nativeApplyExpression(name) }`）。
3. （M3 里程碑）新增 `nativeStartMotion(group, index)` JNI + C++。

---

## 6. 分阶段实现（对齐 ROADMAP · v0.5 Character Engine）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M1** | `ActionTagFilter` + `emotion` 单字段 + 能力段注入 + few-shot + `liveActingEnabled` 开关；仅在线 | 在线聊天说"开心一下" → 切 `hearteyes`；控制块不入字幕/TTS；瞎编名不崩、被忽略 |
| **M2** | 加 `outfit` 字段；`Live2DController` 暴露 `availableExpressions` 让提示词动态注入 | 说"戴猫耳" → 切 `catear`；同一块内 emotion+outfit 顺序 apply 正常 |
| **M3** | action 与 `SpeechQueue` clause 对齐；新增 motion JNI + `motion` 字段生效 | 视觉/语音同步；能触发指定 tap motion |

---

## 7. 边界与风险

- **半个标签**：`<` 后 token 卡断 → buffer；证伪回吐（如 `<div` 不是 `<action`）。
- **模型不听话**：产坏 JSON → 丢弃 + log，功能降级为纯文本，不报错。
- **过度切换**：靠提示词"不确定就别加" + 节流（同 emotion 800ms 去重）。
- **口型冲突**：表情若含嘴部参数，可能与 TTS 抢通道；真机观察，必要时表情文件排除嘴部通道。
- **打断/取消**：切到一半被打断，表情停在当前态即可；新一轮可选 reset 到默认表情
  （`stareyes`）。

---

## 8. 与既有约定的关系

- 不改后端接口。能力段在 `prepareBackend` 层拼好再作为 systemPrompt 传下去。
- 复用 `<think>` 流式过滤套路，`ActionTagFilter` 与 `ThinkTagFilter` 各司其职、可组合。
- 开关进 `LlmConfig.liveActingEnabled`，不写死。
- 落地后更新 `CHANGELOG.md` / `ROADMAP.md`。

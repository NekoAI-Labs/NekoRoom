# NekoChat v0.6.1 轻量安全层交接（2026-07-20）

## 本版范围

- 应用对外名称改为 `NekoChat`。
- 左上角时钟下方常驻低存在感的“AI 生成内容”标识；没有增加顶部居中横幅。
- 首次进入仅弹一次磨砂说明：小喵由 AI 驱动、并非真人，回复可能不准确或不适宜。
- 增加端侧 TXT 词库驱动的输入/输出双向安全检查。
- 角色选择页把内部 ID `FenmaoLoli` 显示为 `Fenmao`，不再展示中文旧名。
- 内测 APK 只打包 Fenmao；ATRI/Ziyan 由 `patches/live2d-single-fenmao.patch` 在构建时排除。

## 链路

```text
ASR / 键盘
    -> ContentSafetyGuard.check(input)
    -> LlmChatActivity / LLM
    -> ActionTagFilter + SentenceSplitter
    -> ContentSafetyGuard.check(clause)
    -> SubtitleManager + SpeechQueue + TTS
```

输入命中时不显示原文、不请求 LLM、不写线上 15 轮历史。输出命中时该句不进入共享字幕和 TTS，
整轮原始回复也不写历史。日志只记录类别，不记录原文。

## 词库维护

词库位于 `app/src/main/assets/safety/`：

- `political_sensitive.txt`
- `sexual.txt`
- `violence.txt`
- `illegal_instructions.txt`
- `self_harm.txt`

每行一个短语，空行和 `#` 注释会被忽略。添加普通词前必须考虑误伤，不要单独加入“中国”“历史”
“暴力”等宽泛词。新增类别时才修改 `ContentSafetyGuard.Category`，普通扩词只改 TXT。

## 关键文件

- `safety/ContentSafetyGuard.kt`：纯 Kotlin 归一化与匹配，可做 JVM 单测。
- `safety/ContentSafetyRuleLoader.kt`：从 APK assets 加载分类词库。
- `chat/LlmChatActivity.kt`：输入前和输出句子进入字幕/TTS 前的两个接入点。
- `llm/LlmConfig.kt`：`seen_ai_disclosure_v1` 首次说明状态。
- `ContentSafetyGuardTest.kt`：普通会话、符号规避、政治敏感、自伤响应测试。

## 已知边界

这是内测用轻量词表，不理解上下文，也不能覆盖谐音、图片、编码文本或复杂诱导。公开发布前应增加服务端
上下文审核、可远程更新的规则版本、无原文审计指标和定期复核机制。首次说明不是免责声明替代品。

完整工作副本准备好后，按其他 Live2D patch 相同方式应用单角色 patch：

```bash
git -C /home/jhx/Projects/nlp/MNN/submit/mini-app apply --directory=apps/Android/NekoChatMini \
    apps/NekoChatMini/patches/live2d-single-fenmao.patch
```

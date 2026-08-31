# v0.6.4 MiniCPM5 模型与本地推理技术总结

> 更新日期：2026-07-23  
> 适用分支：`feat/v0.6.4-minicpm-tts-perf`

## 1. 本次结论

v0.6.4 默认使用 `MiniCPM5-1B-MNN-Q8`，第二个可选模型为完整权重
`MiniCPM5-1B-MNN-FP16`。App 不在 Kotlin 或 JNI 层重新拼接采样参数，而是直接读取模型目录中的
`config.json`，这样每个模型可以使用与其导出/验证时匹配的参数。

这次稳定性提升主要来自四个方面：

1. 默认模型从旧的 Q4 调整为 Q8。
2. 本地模型使用统一、短小的猫娘 system prompt。
3. 每轮清空 native KV cache，再把完整短期历史通过 `ChatMessages` 传入，避免旧状态和新历史叠加。
4. App 层对聊天回复设置 64 token 硬上限，防止模型异常时无限生成。

本次没有重新导出模型，也没有在 Android 代码中额外强制 `temperature`、`topK` 或 `topP`。

## 2. 三种权重的区别

| 版本 | 权重形式 | 设备文件总量（当前仓库） | 特点 | 当前定位 |
|---|---|---:|---|---|
| Q4 | 约 4-bit 量化权重 | 约 1.00 GB | 占用较小，速度/内存友好，质量有一定损失 | 保留，用于低内存设备 |
| Q8 | 约 8-bit 量化权重 | 约 1.31 GB | 精度、内存和速度之间较均衡 | 默认线上/普通设备方案 |
| FP16 | 半精度浮点权重 | 约 2.17 GB | 权重精度最高，内存和加载成本最大 | 高质量可选方案 |

上述总量包含 `llm.mnn`、权重、tokenizer、`llm_config.json` 和 embedding 文件，实际运行内存还会受到
KV cache、线程数、MNN backend 和上下文长度影响，不能把磁盘大小直接当作峰值 RAM。

Q4/Q8/FP16 当前发布包都配套 `embeddings_bf16.bin`。`Q4-embed8` 和 `Q4-embed4` 是另外的 embedding
量化变体，不等同于 Q8 或 FP16 权重，本次多轮质量对照没有将它们作为结论依据。

## 3. 配置原则

### 3.1 不在 App 层覆盖模型采样器

本地 JNI 的入口只接收模型 `config.json` 路径；加载后由 MNN 使用该文件中的采样设置。这样可以避免出现：

- 模型 A 的配置被模型 B 的参数覆盖；
- Android 和 Linux 测试使用两套不同参数；
- 通过 UI 修改后无法复现模型仓库中的验证结果。

App 只做两项通用保护：递归关闭 `enable_thinking`，以及聊天输出达到 64 token 时停止生成。

### 3.2 已验证的 Q8 配置

当前 Q8 仓库配置包含：

```json
{
  "sampler_type": "mixed",
  "mixed_samplers": ["topK", "tfs", "typical", "topP", "min_p", "temperature"],
  "temperature": 0.7,
  "top_k": 40,
  "top_p": 0.9,
  "min_p": 0.05,
  "tfs_z": 1.0,
  "typical": 0.95,
  "repetition_penalty": 1.1,
  "reuse_kv": true,
  "max_new_tokens": 256
}
```

不要简单把 Q8 配置替换成 `topK + topP + temperature`。Linux 原生 MNN 对照中，Q8 原始配置连续
20 轮没有出现无限重复；而显式替换 sampler 列表在 FP16 上第 2 轮就出现了无限“喵喵喵”。这说明
`mixed_samplers` 的组合和顺序属于模型/版本相关配置，不应凭经验强行统一。

### 3.3 FP16 配置

FP16 仓库使用其自身的原始 `config.json`。当前测试中原始 FP16 配置连续 20 轮没有进程崩溃或无限
重复，整体文字自然度最好。FP16 不需要因为“权重更精确”而额外增加采样器；显式加 sampler 反而可能
改变 MNN 的默认采样路径。

## 4. Linux 原生 MNN 对照结果

测试使用同一 system prompt 和 20 个中文对话问题，走 `build/dialog_eval` 原生 C++ 链路，避免 Python
绑定版本差异影响结论。

### Q8 原始配置

- 20 轮完成；没有 native crash。
- 没有无限 token 重复。
- 后半段偶尔出现模板化回答，但仍能正常回答问题和总结上下文。

### FP16 原始配置

- 20 轮完成；没有 native crash。
- 没有“喵喵喵”死循环。
- 句子自然度略好于 Q8，但出现过少量“用户名字/助手名字”记忆混淆，属于小模型多轮能力限制。

### Q4 对照

- 原始配置和保守采样均能完成 8 轮。
- 能正常生成中文，但更容易模板化和出现身份/名字混淆。
- 更适合作为低内存兼容选项，不作为默认质量方案。

### 失败的参数实验

FP16 原始配置改为显式 `mixed_samplers=[topK, topP, temperature]` 后：

- 第 1 轮可以正常自我介绍；
- 第 2 轮开始连续生成“喵喵喵”；
- 这不是进程崩溃，而是语义生成进入重复循环。

因此“崩溃轮次”需要分为两类记录：native/进程崩溃，以及输出重复/质量崩溃，不能混为一谈。

## 5. 提示词策略

推荐 system prompt 保持短小、直接：

```text
你是一只可爱的猫娘助手，名叫小咪。说话自然、简洁、亲近，回答尽量不超过80个字。
不要复述用户问题，不要只说喵。
```

不建议写入过多表情、格式、动作标签和长篇行为规则。1B 级模型的 system prompt 越长，越容易出现：

- 只重复规则中的“喵”；
- 忽略用户问题；
- 输出控制标签或模板句；
- 多轮历史变长后身份漂移。

表情规则应继续由 App 的独立能力段控制，不要把大量 Live2D 协议细节塞进基础身份提示词。

## 6. Android 接入清单

模型注册位于 `app/src/main/java/com/moeavatar/model/ModelManager.kt`：

- 默认：`jiaohui/MiniCPM5-1B-MNN-Q8`
- 第二项：`jiaohui/MiniCPM5-1B-MNN-FP16`
- 两者都要求 `config.json`、`llm.mnn`、`llm.mnn.weight`、`llm_config.json`、`tokenizer.mtok`、
  `embeddings_bf16.bin`
- FP16 仅增加下载项和选择项，不改变 JNI/C++ 推理接口

切换模型时会清空当前短期会话并重新加载对应目录的 `config.json`。普通用户不需要理解量化格式；
开发者模式下可以通过性能记录观察不同手机和模型的首 token 延迟、生成耗时和 TTS RTF。

## 7. 风险和后续建议

- FP16 不是所有手机都适合：下载、加载和峰值内存压力明显更大。
- Q8 是当前默认平衡方案；低内存设备仍应保留 Q4 作为兜底。
- 如果再次出现“只会喵”或固定一句话，先记录模型 ID、配置 hash、system prompt 长度、历史轮数和
  `gen_tokens`，再调整参数，不要先重新导出模型。
- 真机性能必须按 SoC、ABI、线程数、模型版本和是否启用 MNN 硬件路径统一记录，不能只比较单个首包
  时间。

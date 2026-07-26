# ADR-005：Gemini 中转站 Provider 与双角色模型边界

- 状态：已接受（vision 角色的 live 能力验证等待用户配置密钥）
- 日期：2026-07-26
- 分支：`feature/gemini-relay-provider`
- 取代：ADR-003 中关于 Gemini 使用 Google 官方 endpoint 的部分

## 背景

现有 `GeminiVisionProvider` 使用 Google GenAI 官方客户端（`com.google.genai.Client`），
base URL 由官方 SDK 内部决定，无法指向中转站。目标中转站
`https://api.qlhazycoder.top` 提供 OpenAI 兼容接口
（`/v1/chat/completions`，支持 stream、response_format、tools、tool_choice），
因此官方客户端不适用。

同时存在两个不能混同的能力主张：

1. **该中转站可以做文本与工具调用**——公开文档已列出 `gemini-3.6-flash`，
   支持 tools / tool_choice / stream / response_format。
2. **该中转站的某个模型可以看图**——公开页面**没有**给 `gemini-3.6-flash` 标注多模态；
   只有 `gemini-3.1-pro-preview` 明确标注多模态。页面还显示近期成功率约 62.5%。

把这两件事绑在同一个开关上，等于用第一条的证据授权第二条。

## 决策

### 1. 用 OpenAI 兼容路径替换官方客户端

删除 `GeminiVisionProvider` / `GeminiProviderProperties` 及
`spring-ai-google-genai` 依赖，改用 Spring AI 的 `OpenAiChatModel`
指向可配置 base URL。删除依赖是刻意的：留着它，未来很容易“顺手”又接回官方 endpoint。

### 2. 抽出共享端点层，而不是复制 Qwen 实现

`QwenVisionProvider` 已经是 OpenAI 兼容实现。直接复制会得到两份相同的模型构造、超时、
重试、usage 提取与边界提示词代码。改为抽出 `OpenAiCompatibleEndpoint`：

- `chatModel(Endpoint, Tuning, ObservationRegistry)`：构造带真实超时与重试的模型；
- `callVision(...)`：单轮图文调用与 provenance 提取；
- `transportFailure(Throwable)`：按 cause 链判定传输故障；
- `payPerUseCostMetadata(...)`：共享的计费元数据。

Qwen 与 Gemini 中转站的差异只剩配置与边界提示词措辞，因此它们是参数，不是子类。

### 3. 双角色独立配置

```
rsvqa.gemini.enabled            # 中转站总开关
rsvqa.gemini.base-url           # RSVQA_GEMINI_BASE_URL
rsvqa.gemini.api-key            # RSVQA_GEMINI_API_KEY（独立于 GEMINI_API_KEY）
rsvqa.gemini.completions-path   # 默认 /v1/chat/completions
rsvqa.gemini.agent.*            # RS-Bot 文本/工具模型角色
rsvqa.gemini.vision.*           # 外部视觉 VQA 模型角色
```

两个角色共享 base URL 与 key，但各自拥有 `enabled`、`model`、`temperature`、
`timeout-seconds`、`max-retries`、`max-output-tokens`。

**`vision.model` 没有默认值。** 这是本 ADR 最关键的一条：在没有人显式指定并验证之前，
外部视觉角色恒为 `UNCONFIGURED`，UI 不得显示“外部通用视觉模型可用”。
`agent.model` 默认 `gemini-3.6-flash`，因为该模型的文本/工具能力有公开文档支撑。

### 4. Fail closed 的三态

| 状态 | 触发条件 |
| --- | --- |
| `UNCONFIGURED` | 总开关关闭、缺 base URL、缺 key、角色未启用，或角色未指定模型 ID |
| `CONFIGURED` | 上述全部满足 |
| `UNAVAILABLE` | 运行中出现契约不兼容（4xx，如密钥无效、模型不存在、模型拒绝图像输入） |

`UNAVAILABLE` 是**闩锁**：一旦某个模型证明自己不能处理图像，provider 不再继续宣称视觉能力，
后续调用直接短路，不再消耗请求配额。

### 5. 自定义错误分类而不是继承框架默认

新增 `RelayResponseErrorHandler`，理由有二：

1. **重试正确性**：408 / 429 / 5xx 值得重试；其余 4xx（密钥错误、模型不存在、
   模型不接受图像）重试只会放大失败。这个映射写死在代码里，不随框架版本漂移。
2. **密钥卫生**：中转站可能把提交的 key 原样回显在错误体里。该错误体绝不能进入日志、
   异常消息或用户界面，因此在两处之前先做 redact。

### 6. 传输故障按 cause 链判定

实测发现：`SimpleClientHttpRequestFactory` + `RestClient` 下的读超时**不是**
`ResourceAccessException`，而是一个普通 `RestClientException`，其 cause 链末端才是
`SocketTimeoutException`。如果按包装类型分类：

- 重试模板不会重试超时；
- provider 会把超时错标成 “malformed response”。

因此重试注册 `IOException` 并启用 `traversingCauses()`，provider 用
`OpenAiCompatibleEndpoint.transportFailure(...)` 按 cause 链判定。这个 bug 是被
契约测试抓出来的，不是设计时想到的。

### 7. 密钥与端点不出服务端

- `ProviderDescriptor` 没有 base URL 或 key 字段；
- `costMetadata` 不含端点信息；
- 未配置原因只说明**缺哪个环境变量**，不回显主机名；
- 契约测试断言 descriptor 与错误消息都不包含 key 与 host。

## 测试

`GeminiRelayContractTest`（25 个用例）全部跑在本地 MockWebServer 上，不接触真实中转站，
不读取真实密钥：

文本 chat completion、SSE streaming、structured output、tool calling、
image_url base64 输入、429 重试后成功、5xx 重试预算、401 不重试并闩锁 UNAVAILABLE、
404 模型不存在、400 模型拒绝图像后停止宣称视觉、读超时在 HTTP 客户端层生效、
传输故障按 cause 分类、malformed response、空内容、缺图像、
token usage 记录、request id 记录、descriptor 不泄漏 key/host、
错误消息不回显 key、未配置原因只提环境变量名。

## 待验证（阻塞）

**`gemini-3.6-flash` 是否支持视觉尚未验证。** 需要用户在本地安全配置密钥后运行：

```bash
export RSVQA_GEMINI_BASE_URL=https://<relay-host>
export RSVQA_GEMINI_API_KEY=<server-side-secret>
export RSVQA_GEMINI_AGENT_MODEL=gemini-3.6-flash
export RSVQA_GEMINI_VISION_MODEL=<待评估模型>
python scripts/gemini_relay_live_smoke.py
```

该脚本只读环境变量，不读 `.env`，报告中不含密钥与主机名。

判定：

- 若 `image_input` 通过 → 可以把该模型配置为 vision 角色，并在版本文档记录验证日期与模型 ID；
- 若不通过 → `gemini-3.6-flash` 仍可作为 RS-Bot 文本/工具模型，外部视觉保持 UNAVAILABLE，
  再评估该中转站中明确标注多模态的模型（如 `gemini-3.1-pro-preview`）。

## 数据外发风险

启用 vision 角色意味着**用户上传的遥感图像会离开本系统**发往第三方中转站。因此：

- 现有的 `externalImageOptIn` 用户级许可继续是硬前置条件；
- 中转站是第三方转发，不是模型厂商官方端点，链路上多一跳；
- 公开页面显示近期成功率约 62.5%，可用性不应被当作稳定依赖；
- 研究模型路径完全不经过中转站，论文结果不受影响。

## 后果

- 好处：base URL 可配置、双角色可独立验证、错误分类精确、密钥不外泄、Qwen 与 Gemini 共享一套端点层。
- 代价：失去官方 SDK 的原生 Gemini 特性（thinking、扩展 usage 元数据），换取中转站可用性。
- 风险：中转站可用性与账单不受我们控制；因此每个角色都有独立超时、重试上限与开关。

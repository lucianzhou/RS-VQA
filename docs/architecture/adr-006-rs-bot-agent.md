# ADR-006：RS-Bot 智能规划循环

- 状态：已接受
- 日期：2026-07-26
- 分支：`feature/rs-bot-agent`（依赖 `feature/gemini-relay-provider`）
- 取代：ADR-002 中关于关键词单工具编排的部分

## 背景

`TrustedAgentService` 此前是一段关键词 `if/else`：按中文关键词从约 20 个工具里挑**一个**执行，
再用固定模板句子解释输出。这带来三个问题：

1. 用户问“汇总项目结果、找出低置信度案例并生成报告草稿”，只会命中一个工具；
2. “解释”是写死的句子，与工具实际返回的数字无关；
3. 前端显示的 `UNCONFIGURED_RULE_BASED_TOOL_ORCHESTRATION` 对普通用户毫无意义。

基础设施其实已经就位：Spring AI `ToolCallback`、约 20 个 `@Tool`、agent session、
tool invocation、trace、action proposal 与 confirm/reject。缺的是规划。

## 决策

### 1. 继续用 Spring AI，不引入 LangChain4j

双栈不会带来能力增量，只会带来两套工具定义、两套重试语义和两份维护成本。
`ToolCallback` / `ToolCallingChatOptions` 已经足够。

### 2. RS-Bot 自己拥有工具循环

关闭 Spring AI 的 `internalToolExecutionEnabled`，由 `RsBotPlanner` 手写循环。
这不是为了控制欲，而是因为下列约束只有在自己持有循环时才是**可强制的**，
否则只能算“希望模型遵守”：

| 约束 | 实现位置 |
| --- | --- |
| 最大工具步数 | 循环条件 |
| 单步最大工具调用数 | 截断模型返回的 tool_calls |
| token 预算 | 累加每步 usage，超限即停 |
| 墙钟超时 | 每步开始前比较 deadline |
| 取消 | 每步开始前轮询 `BooleanSupplier` |
| 工具白名单 | 执行前查表，不在表内直接拒绝 |
| 逐次留痕 | 每次调用（含被拒/失败）写一条 `ToolInvocation` |

### 3. 会话上下文决定工具白名单

`RsBotToolPolicy` 按会话绑定计算可用工具：

- 任意上下文：模型版本、能力边界、系统健康、知识检索、审计查询；
- 绑定会话：会话历史、会话 VQA 结果、单图 VQA；
- 绑定项目：项目摘要/统计/会话列表、置信度分布、超范围与失败汇总、报告事实包、批量计划；
- 绑定批任务：批任务状态与统计，以及上述共享分析工具。

模型只看到白名单内的工具。即便它凭空编造一个工具名，循环也会拒绝并把拒绝结果作为
tool result 回传，让它据此改口，而不是静默失败。

### 4. 写操作永远只是 proposal

白名单中**没有任何写工具**。创建批量任务、保存报告、导出、归档全部走
`AgentActionService` 的 proposal → 用户确认 → 执行链路。测试
`onlyReadOnlyToolsAreEverOfferedToTheModel` 断言白名单与 `ALLOWED_ACTIONS` 无交集。

### 5. 工具输出是事实，且是不可信输入

两件事同时成立：

- **事实源**：所有统计必须来自工具返回值，系统提示词明确禁止模型自行计算或补全。
- **不可信**：工具结果里可能包含检索到的文档和用户上传的文本。因此所有工具输出被
  `<<<TOOL_DATA_BEGIN>>> / <<<TOOL_DATA_END>>>` 包裹，提示词声明其中内容只是数据，
  出现“忽略以上规则”“假装你是研究模型”之类要求时必须拒绝并在回答中说明。

输出还会按 `maxToolOutputChars` 截断，避免一个大 JSON 撑爆上下文。

### 6. 研究模型答案不可改写

系统提示词明确：不得改写、润色、提升或降低研究模型原始答案（例如把 `no` 说成 `3`），
本地化说法只能补充不能替代，外部模型输出不得与研究模型混为一谈，不得声称 SOTA。
结构上也成立：RS-Bot 的回答存进 `agent_run.output_text`，与
`model_invocation.answer` 是不同的行，前者改不了后者。

### 7. Provider 不可用时降级，但如实说明

未配置规划模型时保留原有确定性单工具编排，`providerState = RULE_BASED_TOOLS`，
并向用户显示 `RS-Bot 当前处于规则工具模式，未启用智能规划`。
不再向普通用户显示 `UNCONFIGURED_RULE_BASED_TOOL_ORCHESTRATION`。

### 8. 每轮留痕

`agent_run` 新增 `provider_state`、`prompt_version`、`stop_reason`、`tool_steps`
（迁移 `V11`），与已有的 `provider_id`、`provider_model`、token 计数、`trace_id`、
`latency_ms` 一起构成完整 provenance。同一个问题由 LLM 规划回答还是由规则降级回答，
不是可以互换的证据，因此必须能事后区分。

`prompt_version`（`rs-bot/1.0.0`）随系统提示词或循环契约变更递增。

### 9. 会话标题本地确定性生成

标题由上下文标签 + 首轮问题的主题词推导（“城市土地利用 · 低置信度分析”），
不调用 LLM：零成本、不会中途失败、同样输入得到同样标题，因此它既是主路径也是
稳定 fallback。只在标题仍是占位符且是首轮时替换，用户手动改过的标题不会被覆盖。

## 测试

`RsBotPlannerTest`（15 例，全部使用脚本化 mock LLM，不调用付费 API）：

多工具顺序执行并综合、单步多工具、token 累计、最大步数、单步工具数上限、
token 预算耗尽、取消、非白名单工具拒绝、编造工具名拒绝、只暴露只读工具、
工具失败回传模型而不中断整轮、工具输出围栏与截断、提示词禁止条款、未配置时 fail closed。

`AgentSessionTitleTest`（6 例）：主题推导、无主题回退问题原文、永不为空、
长文本截断、占位符识别、尾部标点清理。

Gateway 全量：**76 passed**（2 skipped）。

## 后果

- 好处：真正的多轮工具编排、可强制的预算与白名单、完整 provenance、诚实的降级模式。
- 代价：循环是自写的，需要自己维护消息装配与 usage 累计；换来的是上面那张约束表。
- 风险：规划质量依赖中转站模型；预算上限与降级模式限制了失控范围。

# ADR-002：Spring AI 主编排与 LangChain4j 边界

- 状态：接受
- 日期：2026-07-24
- 范围：Agent、Tool Calling、MCP、RAG、外部 Provider

## 决策

正式运行时使用 Spring AI 作为唯一主编排框架，负责 Provider 抽象、Tool Calling、结构化输出、流式响应、RAG、MCP Client 和 Micrometer 可观测性。

LangChain4j 不进入同一正式请求链。它只允许作为隔离的对比适配器、最小 PoC 或测试模块；若不能产生可验证收益，v0.3.0 只保留架构对比和适配器接口，不为“使用技术”而复制业务逻辑。

## 可信单 Agent

v0.3.0 采用一个有工具白名单的 Agent，不实现多 Agent。允许的首批工具：

- 查询当前模型发布信息
- 查询模型支持的问题范围
- 发起受控单图 VQA
- 查询会话历史
- 查询批量任务状态
- 检索知识库并返回引用
- 查询系统健康状态

工具必须有输入/输出 Schema、超时、取消、Trace ID、权限校验和审计记录。当前版本不提供任意 Shell、任意文件访问、破坏性数据库操作或自主付费工具。

## 输出隔离

持久化和 UI 必须分别标记：

- `RESEARCH_MODEL`：研究 ViLT 原始闭集结果
- `AGENT_EXPLANATION`：基于工具返回的解释
- `RAG_CITATION`：有来源的知识检索
- `EXTERNAL_VLM`：未来外部视觉模型辅助
- `MOCK`：协议一致的开发结果

Agent 不得改写 `RESEARCH_MODEL` 消息，也不得将 RAG 文本用于猜测图像答案。


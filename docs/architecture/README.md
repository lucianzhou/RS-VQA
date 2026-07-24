# RS-VQA v0.3.0 架构基线

> 状态：已冻结用于 v0.3.0 实施  
> 日期：2026-07-24

## 目标

本架构服务于论文《跨模态特征融合机制及微调策略研究与应用》的应用工程化交付。核心纵向闭环为：

```text
用户身份 -> 项目 -> 单图会话 -> 自由提问 -> 模型调用
        -> 可信来源展示 -> 同图多轮 -> 历史恢复
```

在该闭环之上增加可信单 Agent、只读工具、MCP、带引用的 RAG 和可恢复批量 VQA。任何辅助能力都不能覆盖或改写研究模型原始结果。

## 运行时边界

| 模块 | 所有权 | 主要职责 | 不承担 |
| --- | --- | --- | --- |
| `apps/web` | React 前端 | 会话工作台、上传、模型选择、任务状态 | 直接调用模型、保存业务事实 |
| `apps/gateway` | Spring Boot | 身份、项目、会话、文件、编排、Agent、审计 | 加载 checkpoint、训练模型 |
| `services/model-service` | FastAPI | manifest 验证、预处理、推理、批量模型调用 | 用户、项目、业务权限 |
| PostgreSQL | 业务事实 | 用户、会话、调用、任务、审计元数据 | 图像和模型二进制 |
| Redis | 短期状态 | 幂等、任务协调、短期缓存 | 唯一事实来源 |
| Milvus | 检索索引 | 知识 chunk 向量与过滤字段 | 图像 VQA 推理 |
| 受控文件存储 | 二进制 | 上传图像、临时文件 | 凭据、checkpoint 入库 |

浏览器只访问 Nginx/Spring API。Python、PostgreSQL、Redis 和 Milvus 不暴露给浏览器。

## 数据流

1. Spring 校验 MIME、扩展名、大小和图像可解码性，生成内部文件名并计算 SHA-256。
2. Spring 保存 `ImageAsset` 元数据和受控文件，消息只引用 `image_asset_id`。
3. 每次问题生成不可变的 `ModelInvocation`，固定当时的 provider、model 和 release ID。
4. 研究模型请求只包含图像、问题文本和显式 release ID，不包含人工题型或评价 metadata。
5. Python 在 real 模式下先完成 manifest、checkpoint 和词表哈希校验；失败时拒绝 readiness。
6. Spring 保存模型原始结果，再单独保存 Agent 解释、RAG 引用或外部 VLM 辅助结果。

## Profile

| Profile | 用途 | 模型 | 数据基础设施 |
| --- | --- | --- | --- |
| `demo` | 本机完整演示 | 明确标记 Mock | PostgreSQL/Redis，可使用容器 |
| `test` | 自动测试 | 确定性 fake/mock | H2 切片测试 + Compose PostgreSQL/Redis 集成测试 |
| `real` | 合法研究发布 | fail-closed Real Runtime | 完整基础设施 |

外部 Provider 未配置时必须返回 `PROVIDER_NOT_CONFIGURED`，界面显示“未配置”，不能伪造调用。

## 决策记录

- [ADR-001：React 前端迁移](adr-001-react-frontend.md)
- [ADR-002：Spring AI 主编排与 LangChain4j 边界](adr-002-ai-orchestration.md)
- [API、数据与运行时契约](v0.3.0-contracts.md)
- [模型发布消费者兼容记录](model-release-consumer.md)

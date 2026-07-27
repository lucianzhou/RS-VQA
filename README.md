# RS-VQA

RS-VQA 是论文《跨模态特征融合机制及微调策略研究与应用》的独立应用工程。它把遥感视觉问答研究封装为可演示、可追踪、可部署的应用，但不训练模型，也不导入 `rs-vqa-fusion` 的训练脚本。

当前稳定基线为 **v0.9.0**，产品对齐评测与真实发布验收已经完成。前端使用 React + TypeScript
和 Mineral Forest 视觉体系；业务后端使用 Java 21 / Spring Boot / Spring AI；
模型运行时和知识检索分别由 FastAPI 服务提供。

## 能做什么

- 本地演示身份、注册/登录边界、项目和历史会话
- 上传、预览、更换和移除遥感图像
- 在同一图像下自由输入并连续提出多个问题
- 可信展示答案、置信度、来源、release ID、耗时和请求编号
- 对低置信度、超出能力范围和未配置 Provider 给出明确提示
- 使用 Spring AI 只读/受控 VQA 工具运行可信单 Agent，并通过 SSE 展示阶段和支持取消
- 使用 BGE + Milvus 导入、检索知识文档并展示来源引用
- 使用 MCP 发布并调用六个只读工具；提供可选 Spring AI MCP Client 边界
- 创建、取消、失败重试和导出批量 VQA 任务
- 使用 PostgreSQL 保存业务事实、Redis 协调短期任务状态、Flyway 管理 Schema
- 通过 Nginx 和 Docker Compose 运行完整论文演示环境

## 研究模型边界

正式部署候选只能是 `qdrop15 + predicted-soft`：RSVQA-HR grouped-answer ViLT 闭集分类器。推理输入仅为“图像 + 问题文本”，不得读取人工 `question_type_id`。

已核准指标是：

- test OA/AA：`0.8401412 / 0.8390032`
- test_phili OA/AA：`0.8031558 / 0.7975786`
- 题型预测 accuracy/macro-F1：`1.0 / 1.0`

soft-vs-none 配对置信区间包含 0，因此不能宣称 predicted-soft 带来显著提升；系统也不声称 SOTA。它不是开放式 VQA、通用视觉助手、目标检测、变化检测、零样本识别或风险自动判定系统。

v0.9 当前 release 是
`rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2`；研究侧已冻结，工程侧已完成
manifest 与全部制品哈希、golden replay、产品对齐评测和真实 CPU runtime 验收。当前仍保留已验证的
`rsvqa-hr-qdrop15-predicted-soft-20260724-8510bc9` 作为显式回退。默认 Compose 仍运行
`mock_demo`，便于低资源开发；Mock 只验证工程闭环，绝不是论文模型输出。真实模型使用
`compose.real.yaml` 覆盖启动，并在 `/models/current` 与每次结果中显示 release、来源和哈希。
Gemini-3.6-flash 与 Qwen3-VL 32B 保持独立 Provider 边界，未配置时明确显示“未配置”。

## 一条命令启动

需要 Docker Desktop。完整 Mock + RAG 演示：

```bash
cd /Users/popwind/Documents/Master/graduation/rs-vqa
docker-compose --profile rag up -d --build
```

首次启动知识服务会下载 CPU 版 PyTorch 和 `BAAI/bge-small-zh-v1.5`；模型缓存保存在 Docker volume。所有服务健康后打开：

<http://127.0.0.1:8088>

查看状态：

```bash
docker-compose --profile rag ps
curl http://127.0.0.1:8088/actuator/health/readiness
curl http://127.0.0.1:8088/actuator/prometheus
```

停止但保留数据：

```bash
docker-compose --profile rag down
```

不要在需要保留演示数据时追加 `-v`。

## Mock 与 Real 模式

Mock 是默认、安全且无需 checkpoint 的完整演示模式：

```bash
docker-compose --profile rag up -d
```

Real 模式只接受研究仓库发布的不可变运行时。先将完整 release 放入被忽略的
`model-releases/`。模型服务会校验 manifest、checkpoint、55 类答案词表、运行时 wheel
和本地预处理器哈希，并直接从已验证 wheel 的固定 factory 加载，不读取研究训练脚本。
随后通过当前 shell 提供容器内 manifest 路径：

```bash
RSVQA_RELEASE_MANIFEST=/opt/rsvqa/model-releases/<release>/model-release.json \
docker-compose -f compose.yaml -f compose.real.yaml --profile rag up -d --build
```

真实模式使用独立的 `Dockerfile.real` 安装 PyTorch、Transformers 与 PEFT；默认 Mock
镜像不包含这些重型依赖。CPU 是默认设备，也可为远程 GPU worker 显式配置
`RSVQA_MODEL_DEVICE=cuda`。factory 只能来自 manifest 中经过哈希校验的 wheel，不能由
环境变量替换。

Real Runtime 会 fail closed：契约版本、`type_source=predicted_soft`、固定 release ID、
manifest、checkpoint、55 类词表、wheel 与预处理器 SHA-256、禁用 oracle/routed/人工题型协议、预热和输出 Schema
任一不满足，`/ready` 即不会通过。详细规范见
[模型发布消费者契约](docs/architecture/model-release-consumer.md)。

## 可选 Gemini Provider

Gemini 通过可配置的 OpenAI 兼容中转站运行，不依赖 Google 官方 endpoint；网页登录会员
不是 API 授权，系统不会读取浏览器 Cookie。文本/工具角色与视觉角色各自独立开关：

```bash
RSVQA_GEMINI_ENABLED=true
RSVQA_GEMINI_BASE_URL=https://<relay-host>
RSVQA_GEMINI_API_KEY=<server-side-secret>

# RS-Bot 文本与工具调用
RSVQA_GEMINI_AGENT_ENABLED=true
RSVQA_GEMINI_AGENT_MODEL=gemini-3.6-flash

# Gemini-3.6-flash 视觉 VQA
RSVQA_GEMINI_VISION_ENABLED=true
RSVQA_GEMINI_VISION_MODEL=gemini-3.6-flash
```

只在服务端进程环境提供这些变量，不要提交。未指定并验证 `RSVQA_GEMINI_VISION_MODEL`
之前，Gemini 视觉问答保持 `UNCONFIGURED`，界面不会将其显示为可用。能力验证脚本见
`scripts/gemini_relay_live_smoke.py`，详见
[`docs/architecture/adr-005-gemini-relay-provider.md`](docs/architecture/adr-005-gemini-relay-provider.md)。

即使服务端已配置，用户仍须在“模型与设置”中显式允许向 Gemini 或 Qwen3-VL 发送图像。
Gemini 与 Qwen3-VL 的回答会记录具体 Provider 和模型名，不会覆盖或伪装成论文研究模型结果。

## 本地开发

基础设施：

```bash
docker-compose up -d postgres redis model-service
```

Java：

```bash
cd apps/gateway
mvn spring-boot:run
```

使用已校验的真实 CPU 研究模型（首次构建会下载 CPU-only PyTorch；不启动 GPU 训练）：

```bash
export RSVQA_RELEASE_MANIFEST=/opt/rsvqa/model-releases/rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2/model-release.json
export RSVQA_MODEL_DEVICE=cpu
docker-compose -f compose.yaml -f compose.real.yaml up -d --build
```

真实模式启动前，需将 `model-releases/` 目录挂载或复制到 Docker Compose 配置的
`RSVQA_MODEL_RELEASES_DIR` 对应位置，并确认 `/ready` 返回 `ready=true`。Real Runtime
不能接受人工 `question_type_id`、oracle、routed 或评价 metadata。

React：

```bash
cd apps/web
npm ci
npm run dev -- --host 127.0.0.1
```

知识服务推荐继续使用 `--profile rag` 容器运行，避免在宿主机重复安装 PyTorch、BGE 和 Milvus 依赖。

## 验证

```bash
# React 类型、单元和生产构建
cd apps/web
npm run typecheck
npm run test -- --run
npm run build

# 已启动完整 Compose 后，桌面/笔记本/平板/移动端 E2E
npm run test:e2e

# Java 单元/切片测试
cd ../gateway
mvn test

# PostgreSQL、Flyway、Redis 与真实 MCP 协议集成
RSVQA_COMPOSE_INTEGRATION=true RSVQA_RUN_MCP_INTEGRATION=true \
  mvn -Dtest=PersistenceIntegrationTest,McpProtocolIntegrationTest test

# 模型运行时契约
cd ../../services/model-service
.venv/bin/python -m pytest -q

# RAG 检索基准（知识服务容器健康后）
docker exec rs-vqa-knowledge-service-1 \
  python scripts/evaluate_retrieval.py --base-url http://127.0.0.1:8010

# 产品对齐评测发布的只读校验与聚合验收
services/model-service/.venv/bin/python scripts/product_aligned_acceptance.py \
  --evaluation-release evaluation-releases/rsvqa-hr-product-aligned-eval-20260727-1796e90 \
  --model-manifest model-releases/rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2/model-release.json \
  --collection validate
```

v0.9.0 的完整发布身份、聚合指标、测试数量和已知边界见
[产品对齐评测与可信研究模型体验](docs/versions/v0.9.0-product-aligned-evaluation-and-trusted-model.md)。

## 本地测试遥感影像

`data/test-images` 中有 12 张单图 smoke 样本和 192 张批量样本，共 204 张。它们来自 USGS National Map 正射影像服务，目录被 Git 忽略。

```bash
python3 scripts/download_usgs_test_imagery.py
```

这些图片只用于上传、预处理、接口、批任务和 UI 验证，不是 RSVQA-HR 带标注测试集，不能用来报告 OA/AA 或比较模型能力。

## 答辩冻结评测集

自研模型能力不能用无标注 USGS 图片或与通用 VLM 的自由回答做主观比较。v0.9.1 使用受控
RSVQA-HR `provider-dev` 构建本地答辩集：512 张唯一图像、512 条真实问题，Presence、
Count、Area、Comparison 各 128 条。生成目录 `data/defense-benchmark-v1/` 被 Git 忽略。

构建：

```bash
services/model-service/.venv/bin/python scripts/build_defense_benchmark.py \
  --evaluation-release evaluation-releases/rsvqa-hr-product-aligned-eval-20260727-1796e90 \
  --model-manifest model-releases/rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2/model-release.json
```

盲测时先使用 `questions.csv`，预测结束后再打开 `answer-key.csv`。完整自动测评复用 Real
Runtime Docker 镜像，不在宿主机重复安装 PyTorch：

```bash
docker run --rm --cpus=2 --memory=6g \
  -v "$PWD/data/defense-benchmark-v1:/benchmark:ro" \
  -v "$PWD/model-releases:/opt/rsvqa/model-releases:ro" \
  -v "$PWD/scripts/evaluate_defense_benchmark.py:/tmp/evaluate.py:ro" \
  rs-vqa-model-service:latest \
  python /tmp/evaluate.py \
  --benchmark /benchmark \
  --model-manifest /opt/rsvqa/model-releases/rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2/model-release.json
```

完整设计、固定哈希、OA/AA、题型指标和 Count 0/非零能力差异见
[v0.9.1 答辩冻结评测集](docs/versions/v0.9.1-defense-benchmark.md)。

## 仓库与安全边界

- 研究仓库负责训练、实验和不可变模型发布；应用仓库只消费发布物。
- 不提交 `data/raw`、`data/processed`、`outputs`、`logs`、`checkpoints`、模型、预测 JSONL、PDF、用户上传图像或 `.env`。
- 浏览器只访问 Nginx；Python、PostgreSQL、Redis 和 Milvus 留在内部网络。
- Google AI Pro 网页会员不等于 API 授权。应用不会提取浏览器 Cookie 或 token。
- 正式架构、ADR、API 和版本说明位于 [`docs/architecture`](docs/architecture) 与 [`docs/versions`](docs/versions)。

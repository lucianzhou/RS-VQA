# RS-VQA 下一阶段工程侧与研究侧目标模式任务书

> 日期：2026-07-30
>
> 使用前先读取：
>
> - `docs/01_industry_evidence_and_candidate_workflow.md`
> - `docs/02_comprehensive_product_system_and_model_assessment.md`
> - `plans/README.md`

## 1. 工程仓库目标模式 Prompt

```text
你接手独立工程仓库：

/Users/popwind/Documents/Master/graduation/rs-vqa

目标：在不训练模型、不改变论文研究结论的前提下，把当前 RS-VQA 从“高质量单机论文演示”
升级为安全、可恢复、可测评、适合答辩的多模型遥感问答与批量复核工作台。

论文题目固定为：

《跨模态特征融合机制及微调策略研究与应用》

开始前：

1. 检查 pwd、git status --short --branch、git log -5 --oneline --decorate、git fetch。
2. 不 reset、clean、覆盖未知改动，不使用 git add -A。
3. 完整阅读：
   - docs/01_industry_evidence_and_candidate_workflow.md
   - docs/02_comprehensive_product_system_and_model_assessment.md
   - docs/architecture/product-aligned-evaluation.md
   - docs/architecture/adr-006-rs-bot-agent.md
   - docs/versions/v0.9.0-product-aligned-evaluation-and-trusted-model.md
   - docs/versions/v0.9.1-defense-benchmark.md
   - plans/README.md
4. 从 main 最新提交拉独立 feature 分支；确认现有 feature 分支和未合并提交后再决定基线。
5. 不读取、输出、提交 API key、.env、token、密码、SSH、模型、checkpoint、用户图像、
   逐样本 prediction、受限 gold 或 PDF。

必须保持的产品事实：

- 产品定位：面向遥感/GIS 教学、科研复核和受控影像初筛的多模型遥感问答与批量复核工作台。
- 研究模型是 RSVQA-HR grouped 55-answer closed-set classifier，不是开放式视觉助手。
- 正式部署协议是 qdrop15 + predicted-soft，仅输入图像和问题文本。
- full test OA/AA=0.8401412/0.8390032；test_phili=0.8031558/0.7975786。
- 512 条答辩集 OA/AA=0.8515625，但 Count 非零仅 8/47=17.02%。
- 不声称 SOTA、通用遥感理解、置信度保证、法定测绘或业务替代。
- Gemini、Qwen3-VL、研究模型和 RS-Bot 的来源必须永远分离。
- Agent 不得覆盖研究模型原始答案，所有数字必须来自确定性工具。

按以下阶段执行。每阶段先补测试，再实现，再运行全量相关验证，更新 docs/versions，
单独提交并推送；不要把所有工作挤进一个提交。

阶段 A：P0 RAG 租户隔离

- Knowledge SearchRequest 由 gateway 注入当前用户 owner/tenant ID，禁止使用客户端提供的 owner。
- Milvus schema 使用显式 owner 字段。
- search filter 同时约束 owner 与 index_version。
- 内置公共知识使用显式 scope；私有文档不可跨用户返回。
- 增加两用户反向集成测试、删除测试和提示注入测试。
- 旧集合迁移需要版本化新 collection，不在原集合上静默改变 schema。

验收：

- 用户 A/B 上传不同唯一短语，双方检索只能命中自己的文档。
- 所有 owner 伪造请求失败。
- RAG benchmark 和引用字段保持兼容。

阶段 B：P0 Web 安全边界

- 默认关闭 MCP server，或要求经过认证的只读访问；/mcp 不得 permitAll。
- 恢复 Session Cookie API 的 CSRF 防护，或形成并实现明确的无状态 Bearer ADR。
- 设置合理 SameSite、Secure（生产 profile）、HttpOnly 和 session fixation 策略。
- Swagger/OpenAPI 和 actuator 暴露按 profile 收敛。
- 增加匿名 MCP initialize、CSRF、越权资源和 session 测试。

验收：

- 匿名 /api 和 /mcp 均不能读取或调用工具。
- 合法前端请求正常；跨站写请求被拒绝。
- demo profile 与 production profile 的差异写入 ADR。

阶段 C：P0 批任务重启恢复

- 不立即引入 Kafka。
- 用 PostgreSQL 实现 item lease owner、lease expiry、attempt 和原子 claim。
- 应用启动时回收过期 RUNNING。
- 结果写入幂等，重复 worker 不得产生重复模型调用记录或错误计数。
- 归档、取消、retry-failed 与 lease 语义一致。

验收：

- 任务处理中终止并重启 gateway，最终所有条目进入终态。
- 两 worker 并发时同一 item 只被一个有效 lease 完成。
- 已完成结果不会因恢复被覆盖。

阶段 D：可信 Provider 与成本控制

- 每用户、每 Provider 建立请求速率、并发、批量大小和 Agent token 预算。
- 对 429/5xx/timeout 建立 circuit breaker 和半开探测，保持当前密钥脱敏错误处理。
- 记录版本化价格表、usage 和估算成本；价格未知时明确 unknown，不写 0。
- UI 展示模型真实名称和当前状态，不使用“外部通用模型”占位文案。
- 中转站故障必须 fail closed，不影响研究模型。

验收：

- 合约测试覆盖 401/403/404/408/429/5xx/timeout、熔断、半开和预算超限。
- 密钥、base URL 和第三方错误体不进入日志、API 或报告。

阶段 E：RS-Bot 与 RAG 质量评测

- 建立至少 50 条冻结 Agent 任务：
  15 条确定性查询、10 条多工具任务、10 条知识引用、5 条无答案拒答、
  5 条提示注入/越权、5 条写操作确认。
- 数字 exact match；工具选择、完成率、冗余调用、引用 precision/recall/faithfulness 单独报告。
- 未确认写操作执行必须为 0；提示注入成功率必须为 0。
- 评测只能保存允许提交的聚合结果，不提交用户数据或敏感工具输出。

验收：

- 总任务成功率 >=85%。
- 数字忠实度 100%。
- 越权执行与跨用户泄露 0。

阶段 F：前端信息架构、可访问性与动效

- 先执行 plans/001 到 plans/005，并在每个计划完成后更新状态。
- 默认界面遵循“答案优先、证据按需展开”。
- RS-Bot 手机端将会话与受控操作移入独立 sheet，减少长控制台。
- 解决双 h1、小于舒适触控尺寸、7–10px 过小文本、横向滚动不可发现性。
- 侧栏 100+ 项使用最近使用、固定、搜索、分页/虚拟化，不一次渲染全部。
- 提供答辩数据 seed/reset，不破坏个人真实数据。

验收：

- 桌面 1440x900、2560x1440，平板，390/480px 手机。
- 无页面级横向溢出；键盘、焦点、Escape、ARIA 主路径通过。
- reduced-motion 生效。
- 动效 10% 慢放无空白帧、布局抖动、不可中断跳变。
- 前端单元、typecheck、build 和串行无重试 Playwright 全通过。

阶段 G：运维与答辩交付

- 增加最小 Prometheus/Grafana，展示 P50/P95、错误率、队列深度、Provider 和 release 状态。
- 为审计写失败、批租约回收、RAG owner 拒绝和 Provider 熔断建立指标与告警。
- 定义上传、历史、报告、审计和向量的保留/删除策略。
- 完成 PostgreSQL、上传卷、向量数据的备份和真实恢复演练。
- 增加 CI、SBOM、依赖/镜像漏洞扫描；固定关键镜像版本/digest。
- 写一键演示启动、健康检查、干净 seed、停止和非必要数据清理脚本。
- 形成最终答辩操作手册，成功案例和已知失败案例均需包含。

当前不做：

- 不引入 LangChain4j、LangGraph 或多 Agent。
- 不引入 Elasticsearch。
- 不上 Kubernetes，除非真实多节点需求和运维前提已成立。
- 不在工程仓库训练、微调或调整 checkpoint。
- 不用无标注 USGS 图片报告模型 accuracy。
- 不根据 24 条展示子集调整 prompt、normalizer、阈值或模型。

完成标准：

- docs/02 中工程侧 P0/P1 验收项逐条有测试、运行证据或明确未完成说明。
- 每阶段有 docs/versions/<version>.md。
- 每阶段独立 commit 并 push，最终工作区干净。
- 给出变更摘要、风险、验证命令与结果、未完成事项；不要只说“已完成”。
```

## 2. 模型研究仓库目标模式 Prompt

```text
你接手研究仓库：

/Users/popwind/Documents/Master/graduation/rs-vqa-fusion

目标：严格依据现有产品对齐诊断，规划并在获得用户明确 GPU 授权后执行下一轮研究模型升级。
优先解决非零/密集 Count、答案长尾和目标域迁移，不改变论文题目，不重开已失败实验族，
不使用工程 UI 问题掩盖模型问题。

论文题目固定为：

《跨模态特征融合机制及微调策略研究与应用》

开始前：

1. 检查 pwd、git status --short --branch、git log -5 --oneline --decorate、git fetch origin main。
2. 不 reset、clean、覆盖未知改动，不使用 git add -A。
3. 完整阅读：
   - docs/CROSS_DEVICE_HANDOFF.md
   - docs/SESSION_HANDOFF.md
   - docs/PROJECT_PROGRESS.md
   - docs/EXPERIMENT_COMPLETION_AUDIT.md
   - docs/09_research_decision_ledger.md
   - docs/16_predicted_soft_case_audit.md
   - docs/22_rsvqa_lr_transfer_results.md
   - docs/06_rsvqa_hr_comparison_matrix.md
   - thesis/final_experiment_tables.md
   - docs/23_hrvqa_vs_floodnet_selection_audit.md
   - docs/26_product_aligned_evaluation_release.md
   - docs/30_product_aligned_training_decision.md
   - docs/ENGINEERING_EVALUATION_HANDOFF.md
4. 在独立 evaluation/research 分支工作，不污染 main。
5. 不读取、输出、写入或提交 token、AutoDL 凭据、.env、模型、checkpoint、原始数据、
   逐样本 prediction JSONL、日志或 PDF。

必须保持的研究事实：

- 正式模型为 qdrop15 + predicted-soft；只输入图像和问题文本。
- full test OA/AA=0.8401412/0.8390032；test_phili=0.8031558/0.7975786。
- predicted-soft 相对 none 的配对 CI 包含 0，不能声称显著提升。
- 512 条冻结答辩集 Count 非零仅 8/47=17.02%。
- diagnostic Count 0/1-2/3-5/6-10/>10 分别为
  0.9421/0.5039/0.1228/0.1190/0.0189，signed error=-1.5638。
- frequent/medium/rare/unseen 为 0.8424/0.0233/0/0。
- runtime 与冻结预测 3592/3592 完全一致，不能再归因于工程发布链。
- oracle 只能是消融上界，routed 不能作为部署模型。
- 不声称 SOTA。

关闭方向：

- SAM pseudo-region
- QGSRF
- 蒸馏
- PSTERF
- router rescue
- qdrop/temperature sweep
- 题型 predictor 再训练

执行顺序：

阶段 R4-0：重新审计和预注册

- 检查最新数据、配置、提交和发布身份是否与 docs/30 一致。
- 只读取 train/validation，生成 count density、answer frequency、image-unique 分布。
- 证明 sampler 不读取 test/test_phili、prediction 或 correctness。
- 把唯一配置、seed、预算、停止 gate 和输出清单写成不可修改预注册 commit。
- 在用户明确确认预注册和 GPU 预算前，不创建 optimizer、不启动训练。

阶段 R4-A：Count-density / answer-tail balanced PEFT

唯一允许配置：

- 从正式 qdrop15 + predicted-soft checkpoint 初始化。
- type_source=predicted_soft，temperature=0.75，question dropout=0.15。
- 仅 RSVQA-HR train；250000 samples、1 epoch、seed42。
- 冻结 55 类答案词表；标准 answer CE。
- 题型先平衡；Count 内按 0/1-2/3-5/6-10/>10/in-vocabulary 分层；
  非 Count 按 train-only frequent/medium/rare capped rebalancing。
- 只允许现有 LoRA、RSAdapter/type-scale、spatial/classifier；不新增分支。
- adaptation 参数 <=11M，lr=1e-6，不 sweep。

validation gate：

- 非零 in-vocabulary count density macro accuracy >= +0.020。
- count MAE 相对改善 >=5%。
- count gold=0 下降 <=0.005。
- OA 下降 <=0.0015；AA <=0.0020。
- Area/Comparison/Presence 任一下降 <=0.003。
- 无 NaN/Inf、无单答案坍缩。

任一失败：

- 立即停止。
- 不运行 test/test_phili。
- 不通过额外 epoch、换 seed、structured loss、扩词表或新实验族 rescue。

validation 通过后冻结 checkpoint，再各运行一次 test/test_phili：

- test Count >= +0.005。
- test_phili Count >= +0.010。
- test/test_phili OA 下降 <=0.0020/0.0025。
- AA 下降 <=0.0025/0.0030。
- Comparison/Presence 任一 split 下降 <=0.003。
- diagnostic 非零 Count macro >= +0.020，MAE >=5% 改善。
- 报告 paired bootstrap 和 McNemar；CI 含 0 只写探索性方向。

只有 primary 全通过才允许 seed43 确认；不运行 seed44。

阶段 R4-B：真实目标域数据 gate

- 训练前建立 provider-owned release：
  影像来源/许可证、城市、传感器、GSD、CRS、footprint、日期、
  OSM snapshot、对象过滤代码、feature ID、时差和标注审计。
- 按地理单元和图像隔离 train/dev/test，不能按 QA 行随机拆分。
- 不用 test/test_phili 作为 target-dev。
- 不依据现有模型错误挑 provider 样本。
- gold 大量 OOV 时先判定任务契约不适配，不通过丢弃 OOV 制造高分。
- 数据 gate 未通过前禁止训练。

阶段 R4-C：多尺度

- 只有至少三个非重叠 GSD 区间、最大/最小 >=4x，且题型/地区/传感器可分离时才预注册。
- 最多比较 native frozen resize 与一个冻结的 two-scale fusion。
- 不恢复 pseudo-region。

发布与工程交接：

- 只有通过所有 gate 的候选才可生成新 immutable model release。
- release 必须包含 model_release_id、研究提交、checkpoint SHA-256、type_source_mode、
  词表、预处理、输入协议、已核准指标、能力边界和禁止 oracle/routed。
- 生成新的 engineering handoff；工程仓库不能直接读取训练脚本或实验目录。
- 旧正式 release 保留，不能原地覆盖。

测试与报告：

- 每个阶段先补 validator/test。
- 只提交聚合表、配置、代码和规范，不提交受限制品。
- 所有结果同时报告 OA、AA、四题型、Count density、MAE、signed error、
  answer frequency、地域差和置信区间。
- 失败结果必须记录，不得只汇报最好 checkpoint。
- 每个完整阶段独立 commit 并 push。

完成标准：

- docs/30 中每个 gate 有当前证据。
- 没有测试泄漏、答案泄漏、split 泄漏或工程 prompt 调参。
- 新 release 若未全过 gate，不替换正式部署模型。
- 最终给出研究结论、统计不确定性、失败边界、工程交接状态和下一步；
  不使用 SOTA、显著提升或真实业务有效性等未经证实表述。
```

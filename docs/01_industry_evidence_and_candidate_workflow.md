# RS-VQA 行业证据、目标用户与真实工作流

> 状态：正式评估版 1.0
>
> 更新日期：2026-07-30
>
> 适用范围：独立工程仓库 `RS-VQA`
>
> 论文题目固定为《跨模态特征融合机制及微调策略研究与应用》

## 1. 结论

当前最诚实、证据最充分的产品定位是：

> 面向遥感/GIS 教学、科研复核和受控影像初筛的多模型遥感问答与批量复核工作台。

它把论文研究得到的 RSVQA-HR 闭集模型发布为一个可复现、可比较、可审计的专业模型入口，
同时提供显式区分的外部视觉模型和 RS-Bot 工具编排。现阶段不能把系统描述成法定测绘、
灾害应急判定、精准农业决策或任意遥感图像上的通用智能助手。

当前最有把握的三类用户按优先级排序如下：

1. **遥感/GIS 教师与学生**：需要通过图像、问题、模型输出和边界案例理解遥感 VQA、跨模态融合、
   参数高效微调及模型泛化问题。
2. **遥感、计算机视觉和多模态研究人员**：需要固定发布、固定问题协议、批量推理、来源隔离和
   可复现实验结果，以复核模型行为或比较不同 Provider。
3. **具有固定检查清单的影像分析人员**：在影像来源、分辨率、对象词汇和问题类型均受控时，
   可把系统用于批量初筛和人工复核前的信息整理。该用户群和效率收益仍需真实用户研究验证。

系统当前已证明的是“受控 RSVQA-HR 分布上的闭集问答效果”和“完整工程链路可用”，尚未证明
真实机构在其自有影像上的业务收益。工程演示与业务有效性必须分开陈述。

## 2. 证据分级

本文件不把所有引用视为同等强度。每项结论使用以下四级证据：

| 等级 | 定义 | 允许的表述 |
| --- | --- | --- |
| D1：直接证据 | 当前模型冻结评测、工程运行验收、RSVQA 原始论文或面向真实用户的原始研究 | 可以陈述已观察到的事实，但仅限其样本与条件 |
| D2：权威间接证据 | CEOS、Copernicus、NIST 等正式规范和成熟工作流程 | 可以转化为工程要求，不能替代本系统的用户验证 |
| D3：研究趋势 | 同行评议综述、公开基准或相关系统研究 | 可以说明领域问题和可行方向，不能证明本产品已有效 |
| H：待验证假设 | 从前述证据推导、但尚未由目标用户或真实业务数据验证 | 必须标注为假设，并设置验证任务 |

## 3. 直接证据

### 3.1 RSVQA 任务本身

Lobry 等提出的 RSVQA 将遥感影像与自然语言问题结合，但其问题和答案主要由
OpenStreetMap 信息及固定模板自动构造。原论文同时指出语言偏差、计数困难和跨地域/跨数据集泛化
问题。这意味着“自然语言界面”并不自动等于“开放世界理解”，更不等于专业制图或精确测量。

当前研究模型进一步固定为：

- RSVQA-HR grouped-answer + ViLT closed-set classifier；
- 55 个冻结答案类别；
- Area、Comparison、Count、Presence 四类问题；
- 正式推理仅输入图像和问题文本，采用 `qdrop15 + predicted-soft`；
- oracle 仅用于机制上界，routed 仅是多 checkpoint 后处理，均不是部署模型。

### 3.2 当前模型真实效果

论文已核准的正式指标为：

| 集合 | OA | AA |
| --- | ---: | ---: |
| RSVQA-HR full test | `0.8401412` | `0.8390032` |
| RSVQA-HR full test_phili | `0.8031558` | `0.7975786` |

工程侧 512 条冻结答辩集采用四题型各 128 条的平衡设计：

| 指标 | 结果 |
| --- | ---: |
| OA / AA | `0.8515625 / 0.8515625` |
| Presence | `119/128 = 92.97%` |
| Count | `89/128 = 69.53%` |
| Area | `120/128 = 93.75%` |
| Comparison | `108/128 = 84.38%` |
| top-5 | `499/512 = 97.46%` |
| Count=0 | `81/81 = 100%` |
| Count 非零 | `8/47 = 17.02%` |

3072 条 sealed diagnostic 进一步显示：

- Count 0：`94.21%`；
- Count 1–2：`50.39%`；
- Count 3–5：`12.28%`；
- Count 6–10：`11.90%`；
- Count >10：`1.89%`；
- Count mean signed error：`-1.5638`，存在明显低估；
- frequent answer：`84.24%`，medium：`2.33%`，rare/unseen：`0%`；
- balanced test 与 Philadelphia accuracy 相差 `4.39 pp`。

工程 runtime 与冻结预测在 golden、provider 和 diagnostic 共 `3592/3592` 条完全一致。因此，
非零计数、长尾答案和地域迁移问题不是模型文件下载、词表映射或工程预处理错误，而是当前研究模型
本身的已知能力边界。

### 3.3 面向真实用户的信息产品需求

Cerbaro 等对巴西土地利用/覆盖管理利益相关方进行 96 次访谈，发现障碍不仅在于是否有遥感数据，
还包括把数据转化为可使用信息所需的技能、基础设施、协作和组织流程。该研究直接支持
“用户需要 ready-to-use 信息产品”的行业问题，但没有直接验证 RSVQA 或本系统。

Prakash 等关于城市可持续发展的研究同样表明，可行动性依赖空间边界、时间、数据来源、组织能力
和治理语境。单张 RGB 影像上的一句答案不能替代这些条件。

## 4. 权威间接证据

### 4.1 质量、版本与限制说明

Copernicus Emergency Management Service 的产品流程包括地图、数据、报告、质量控制、命名、
纠错版本和已知限制。CEOS Analysis Ready Data 强调元数据、互操作性、质量和减少用户额外处理负担。

这些规范不能证明 RS-VQA 已达到专业遥感产品标准，但能支持以下工程要求：

- 输入文件哈希、来源和基础元数据；
- 模型 release、checkpoint、词表和预处理版本；
- 原始模型输出与 Agent/外部模型输出分离；
- 错误、限制和修订状态可回查；
- 结果可导出，但报告不得掩盖模型适用边界。

### 4.2 风险管理

NIST AI RMF 强调治理、测量、管理和持续监测。映射到本系统，可信性不应只体现为页面上的
“可信”标签，而应体现为：

- 明确适用范围；
- 多模型来源隔离；
- 工具白名单、预算和人工确认；
- 输入、调用、结果和版本留痕；
- 对数据泄露、跨租户检索、提示注入和外部 Provider 故障设置测试；
- 不能用置信度替代真实错误率或业务风险保证。

## 5. 研究趋势

### 5.1 通用视觉语言模型不是无条件替代

VLEO-Bench 的结果显示，通用 VLM 可擅长高层场景描述，但在遥感目标定位、计数和变化检测上仍不稳定。
Weng 等及 Liu 等的近期综述也指出，现实遥感问题常包含模糊表达、多步骤推理、多时相、多传感器和
专家知识，当前 RS-VLM 与真实专业要求仍有距离。

因此，Gemini 或 Qwen3-VL 可用于开放描述和探索性问答，但不能因为回答更流畅就视为具备更高的
遥感事实正确率。相反，研究模型的价值也不能通过回避同协议比较来证明。

### 5.2 专业模型作为 Agent 工具

Change-Agent 展示了“语言模型规划器 + 专业遥感模型工具”的可行结构。该方向支持 RS-Bot 负责
选择工具、汇总项目、检索规范和生成报告，而把视觉事实交给明确标识的模型或确定性统计。

但相关论文中的定性案例不能直接证明本系统的 RS-Bot 已达到业务级准确性。RS-Bot 仍需建立
工具选择正确率、事实忠实度、引用充分性、拒绝提示注入和多轮任务完成率评测。

### 5.3 模型适配而非无限增大

RSAdapter 等研究说明遥感领域适配和参数高效微调仍具有研究价值；计数专门方法也持续出现。
这支持对当前非零/密集 Count 和目标域迁移开展受控研究，但不支持未经预注册地重新开启已失败实验族，
也不支持仅凭“更复杂”假定模型会更好。

## 6. 真实工作流判断

### 6.1 当前已具备的核心工作流

```text
上传遥感图像
  -> 自然中文/英文提问
  -> 受控问题规范化
  -> 选择研究模型、Gemini 或 Qwen3-VL
  -> 显式来源的回答
  -> 同图多轮问答
  -> 可选批量任务、RS-Bot、知识检索、历史和报告
```

其中研究模型只接受可确定映射到冻结对象词汇与四类问题的问法。用户不必操作固定下拉模板，
但后台 canonical normalizer 必须 fail closed；无法映射时不能把自由生成答案伪装成论文模型输出。

### 6.2 最适合当前模型的任务

| 任务 | 当前适配度 | 依据 | 产品处理 |
| --- | --- | --- | --- |
| 教学中演示遥感 VQA、跨模态融合和模型边界 | 高 | RSVQA 任务、冻结评测和工程复现直接支持 | 当前主场景 |
| 研究人员复核固定 release、比较 Provider 行为 | 高 | 版本、哈希、来源和批量接口已实现 | 当前主场景 |
| 受控影像上的 Presence/Area/Comparison 初筛 | 中 | 内部分布指标较好，但外部有效性未验证 | 明确“初筛/需复核” |
| 非零或密集目标计数 | 低 | 冻结答辩集和 diagnostic 直接显示严重低估 | 显著风险提示，不作为可靠主张 |
| 新城市、新传感器或任意网络图片 | 未知 | Philadelphia 已有域差，未做 provider-owned gold | 先建目标域 dev/sealed test |
| 法定测绘、灾害判定、变化监测、农业决策 | 不适配 | 缺几何、时相、多源和专业标注 | 明确排除 |

### 6.3 小模型的实际价值应如何表达

研究模型的价值不是“比 Gemini 更聪明”，而是：

- **固定任务协议**：输出属于冻结 55 类，可进行 exact-match 评测和批量比较；
- **结果可复现**：release、checkpoint、词表、预处理和代码提交均可追踪；
- **可本地部署**：不必把影像发送给第三方 Provider；
- **行为一致**：同一发布在同一输入上可重复，适合教学、研究复核和固定清单；
- **成本可控的潜力**：约 465 MiB checkpoint 可在自有 CPU/GPU worker 部署，但仍需正式延迟、
  吞吐和能耗基准后才能声称更便宜或更快；
- **暴露失败边界**：可通过真实 gold 定量说明哪里有效、哪里无效，而不是依赖语言流畅度。

这些价值成立的前提是任务确实需要标准化输出、隐私、本地化或批量一致性。若用户只想自由描述一张
影像，通用 VLM 可能更符合需求。

## 7. 多模型与 RS-Bot 的职责

| 组件 | 适合承担 | 必须禁止 |
| --- | --- | --- |
| 研究 ViLT predicted-soft | 四类闭集问题、固定词表、可复现批量评测 | 任意开放问答、目标框、精确测绘、SOTA 宣称 |
| Gemini / Qwen3-VL | 开放描述、探索性问题、生成式辅助 | 覆盖研究模型原始答案；冒充论文模型结果 |
| RS-Bot | 项目/批任务统计、模型边界查询、低置信结果定位、知识检索、报告事实包、受控写操作提案 | 自行编造统计；把检索文本当系统指令；未经确认执行写操作 |

RS-Bot 的实际含金量来自完成跨页面、跨数据源的任务，而不是在页面上再提供一个聊天框。优先任务应是：

1. 汇总一个项目或批任务的模型来源、成功率、失败项和低置信项；
2. 查询当前模型发布、能力边界和已核准指标；
3. 找出需要人工复核的样本并生成可点击的清单；
4. 基于确定性事实包生成报告草稿，所有数字来自工具；
5. 对归档、导出、重新提交等写操作先提出方案，再由用户确认。

## 8. 待验证产品假设

以下内容有合理依据，但不能写成“已经证明”：

| 假设 | 最小验证方法 | 通过标准 |
| --- | --- | --- |
| 教师和学生能用系统理解模型边界 | 5–8 名学生完成指定实验并做前后测 | 任务完成率 >= 85%，关键边界理解正确率 >= 80% |
| 研究人员认为发布追踪和 Provider 对比有价值 | 3–5 名相关研究者执行复核任务 | 无帮助完成率下降；SUS >= 70；能正确定位模型来源 |
| 固定清单可减少人工整理时间 | 具备 GIS 经验的审阅者做人工基线与系统辅助对照 | 中位完成时间下降 >= 20%，错误率不显著上升 |
| RS-Bot 能提高复杂任务完成率 | 预注册 30–50 个工具任务，与纯 UI 操作对比 | 成功率 >= 85%，数字忠实度 100%，越权执行 0 |
| 外部 Provider 与研究模型可形成互补 | 同一带 gold 的目标域集合上做分任务比较 | 分别报告，不用主观案例替代定量结果 |

无法接触行业专家时，可先做教学/科研用户研究，并把“分析人员工作流”保留为待验证方向。论文中应
诚实说明样本构成和外部有效性，而不是用文献引用代替本系统用户实验。

## 9. 论文可用表述

推荐：

> 本研究面向受控遥感视觉问答任务，将跨模态融合与参数高效微调模型封装为版本固定、来源可区分、
> 支持单图与批量复核的分析工作台。系统同时引入受约束的 Agent 工具编排与外部视觉模型辅助，
> 以展示专业小模型在可复现、本地部署、固定任务协议和隐私控制方面的工程价值。实验表明，模型在
> RSVQA-HR 闭集任务上具有较好整体性能，但非零密集计数、长尾答案和跨地域迁移仍是显著限制。

禁止：

- “适用于任意遥感影像的通用问答系统”；
- “达到或超过 SOTA”；
- “置信度高即答案可靠”；
- “可替代遥感专家、法定测绘或应急判定”；
- “外部模型回答更流畅，所以更准确”；
- “512 条答辩集 OA 是所有真实场景准确率”。

## 10. 参考资料

1. Lobry, S. et al. *RSVQA: Visual Question Answering for Remote Sensing Data*. IEEE TGRS, 2020. DOI: [10.1109/TGRS.2020.2988782](https://doi.org/10.1109/TGRS.2020.2988782)
2. Weng, X., Pang, C., Xia, G.-S. *Vision-Language Modeling Meets Remote Sensing: Models, Datasets and Perspectives*. IEEE GRSM, 2025. DOI: [10.1109/MGRS.2025.3572702](https://doi.org/10.1109/MGRS.2025.3572702)
3. Liu et al. *Remote Sensing Spatiotemporal Vision-Language Models: A Comprehensive Survey*. IEEE GRSM, 2025. DOI: [10.1109/MGRS.2025.3598283](https://doi.org/10.1109/MGRS.2025.3598283)
4. Zhang, C., Wang, S. *Good at Captioning, Bad at Counting: Benchmarking GPT-4V on Earth Observation Data*. CVPRW, 2024. [arXiv:2401.17600](https://arxiv.org/abs/2401.17600)
5. Liu et al. *Change-Agent: Toward Interactive Comprehensive Remote Sensing Change Interpretation and Analysis*. IEEE TGRS, 2024. DOI: [10.1109/TGRS.2024.3425815](https://doi.org/10.1109/TGRS.2024.3425815)
6. Cerbaro, M. et al. *Information from Earth Observation for the Management of Sustainable Land Use and Land Cover in Brazil: An Analysis of User Needs*. Sustainability, 2020. DOI: [10.3390/su12020489](https://doi.org/10.3390/su12020489)
7. Prakash, M. et al. *Open Earth Observations for Sustainable Urban Development*. Remote Sensing, 2020. DOI: [10.3390/rs12101646](https://doi.org/10.3390/rs12101646)
8. Gevaert, C. M. *Explainable AI for Earth Observation: A Review Including Societal and Regulatory Perspectives*. IJAEOG, 2022. DOI: [10.1016/j.jag.2022.102869](https://doi.org/10.1016/j.jag.2022.102869)
9. *RSAdapter: Adapting Multimodal Models for Remote Sensing Visual Question Answering*. IEEE TGRS, 2024. DOI: [10.1109/TGRS.2024.3413174](https://doi.org/10.1109/TGRS.2024.3413174)
10. *Improving Counting Accuracy of Post-Disaster Visual Question Answering for Remote Sensing*. TechRxiv preprint, 2024. DOI: [10.36227/techrxiv.173121334.46844366/v1](https://doi.org/10.36227/techrxiv.173121334.46844366/v1)
11. Sudmanns, M. et al. *Big Earth Data: Disruptive changes in Earth observation data management and analysis?* Big Earth Data, 2019. DOI: [10.1080/17538947.2019.1585976](https://doi.org/10.1080/17538947.2019.1585976)
12. [CEOS Analysis Ready Data](https://ceos.org/ard/)
13. [Copernicus EMS Rapid Mapping: Product overview](https://mapping.emergency.copernicus.eu/about/rapid-mapping-manual/product-overview/what-is-delivered-in-a-product/)
14. [Copernicus EMS Rapid Mapping: Quality control](https://mapping.emergency.copernicus.eu/about/rapid-mapping-manual/quality-control/)
15. [NIST AI Risk Management Framework 1.0](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf)
16. [SpatioTemporal Asset Catalog specification](https://stacspec.org/en/)

## 11. 仓库内权威事实源

- `docs/architecture/product-aligned-evaluation.md`
- `docs/versions/v0.9.0-product-aligned-evaluation-and-trusted-model.md`
- `docs/versions/v0.9.1-defense-benchmark.md`
- `rs-vqa-fusion/docs/ENGINEERING_EVALUATION_HANDOFF.md`
- `rs-vqa-fusion/docs/30_product_aligned_training_decision.md`
- `rs-vqa-fusion/thesis/final_experiment_tables.md`

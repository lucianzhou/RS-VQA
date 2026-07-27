# 产品对齐评测消费规范

状态：v0.9 工程契约与真实发布物本地验收均已完成。

本规范只描述独立 RS-VQA 应用如何消费研究侧冻结的评测发布，不复制受限图像、逐样本
gold、预测 JSONL 或研究训练逻辑。权威事实源是
`rs-vqa-fusion/docs/ENGINEERING_EVALUATION_HANDOFF.md`。

## 固定身份

- 模型：`rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2`
- 模型 manifest SHA-256：
  `cce9b8bb48d5cf0213ce789290ceea7525ad2c1d96eba66867c733f1bbc78045`
- 评测：`rsvqa-hr-product-aligned-eval-20260727-1796e90`
- 评测 manifest SHA-256：
  `aa35fa18f442e6fc9f4f648034a7b0ad019c31aa20219de8118780c4d5cbc5c4`
- 规模：8 golden-replay、512 provider-dev、3072 diagnostic-test，3592 张唯一图像

## 2026-07-27 本地验收结果

- 发布 inventory、路径约束、只读校验和全部固定哈希通过。
- golden-replay：`8/8` 与冻结 checkpoint prediction 精确一致。
- provider-dev：`436/512`，OA/AA=`0.8515625/0.8515625`。
- sealed diagnostic-test：`2535/3072`，OA/AA=`0.8251953125/0.8251953125`；
  top-5=`2974/3072`。
- 验收只保存上述聚合结果，没有持久化或提交逐样本 prediction。

这些数字只描述对应的冻结评测集合，不替代论文已核准 full test/test_phili 指标，也不构成
新训练结果、SOTA 结论或 predicted-soft 显著增益证据。

发布目录只允许进入 Git ignored 的私有路径。应用必须拒绝绝对图像路径、`..` 路径越界、
符号链接逃逸、清单外文件、哈希不匹配和可写的评测发布目录。

## 集合隔离

| 集合 | 工程用途 | 禁止用途 |
| --- | --- | --- |
| golden-replay | 验证加载、预处理与冻结预测 8/8 精确复现 | 估计总体性能、选择模型 |
| provider-dev | 开发字段、错误展示、解析和人工复核流程 | 训练正式模型、冒充 test |
| diagnostic-test | 规则冻结后的一次回归核验 | 根据 correctness 调整 prompt、parser、阈值或模型 |

运行时输入必须逐条精确为 `request_id/image/question`。人工题型、gold、split、oracle、
router、mask 和 evaluation metadata 只能在模型返回后由独立 evaluator 关联。

## 评分与输出

评分采用 corrected、幂等的 `rsvqa_hr_grouped` 55 类协议：

- OA 是全部样本 normalized exact match，gold OOV 计错。
- AA 是 Area、Comparison、Count、Presence 四类 accuracy 的非加权平均。
- Count 使用规范化整数 exact match。
- Presence/Comparison 只允许 canonical yes/no。
- Area 只允许五个 canonical grouped labels。

验收脚本只输出聚合报告，不持久化逐样本 prediction。diagnostic-test 必须显式传入
`--sealed-diagnostic`，且在开发规则冻结后才可运行。

## 可信展示

confidence、margin、top-k 和 predicted type 是展示与审计字段，不是正确性或风险保证。
当前任何全局自动拒答阈值都未获得跨域证据支持，因此固定：

```text
automatic_rejection_enabled=false
confidence_display_enabled=true
manual_review_signal_enabled=true
```

Count 结果需简洁提示非零和密集目标存在系统性低估风险。Agent、Gemini 或 Qwen3 的文本
只能解释、检索或编排，不能覆盖 `research_vilt_predicted_soft` 原始预测。

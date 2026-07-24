# RS-VQA 研究模型与系统能力边界

本系统的论文题目是“跨模态特征融合机制及微调策略研究与应用”。

正式部署候选是 qdrop15 + predicted-soft。主任务是 RSVQA-HR grouped answer + ViLT closed-set classifier。部署推理只输入遥感图像和问题文本，不读取人工 question_type_id。

已核准指标：test OA/AA 为 0.8401412/0.8390032，test_phili OA/AA 为 0.8031558/0.7975786。题型预测 accuracy/macro-F1 为 1.0。predicted-soft 与 none 的配对置信区间包含 0，因此不能声称 predicted-soft 带来显著性能提升，也不能声称达到 SOTA。

研究模型适合已验证分布中的存在性、数量、面积和比较类闭集问题。它不是任意遥感图像的开放式问答模型，不提供目标检测框、变化检测、零样本识别、自由图像描述或自动风险判定。

oracle 结果只用于机制消融和上界分析，不能作为无需人工元数据的部署模型。routed 是多 checkpoint 后处理，且 corrected 结果低于最佳单模型，不能作为主部署模型。

Mock Runtime 只用于验证软件闭环，其输出不是研究结果。外部通用视觉模型的输出必须标记为 EXTERNAL_VLM，不能冒充研究模型结果。

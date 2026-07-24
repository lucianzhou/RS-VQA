# 模型发布消费者兼容记录

> 应用仓库只保存兼容记录，不复制研究契约全文。

| 字段 | 当前值 |
| --- | --- |
| 支持的契约版本 | `1.0` |
| 规范路径 | `rs-vqa-fusion/docs/24_model_release_contract.md` |
| 审计时研究工作树 HEAD | `8510bc9cd1738f2cc3c61a3eff3b0faab0cbe556` |
| 发布契约 Git 引用 | `b9077c8e8e91e4c88bad93c1135cbe9a095454e2` |
| 当前真实 release | `rsvqa-hr-qdrop15-predicted-soft-20260724-8510bc9`（AutoDL 已冻结；应用端已完成消费校验与真实 CPU smoke） |
| checkpoint SHA-256 | `2426770af96a6f41b30e081c9719d6582471fab091e4b44ba2c3068d6e227109` |
| 答案词表 SHA-256 | `23592881181ac284e46292921ce14d329eb437c1e3913b2e2f8a05ff9b75f99a` |
| runtime wheel SHA-256 | `cc604c70c65974dbd5826edf6f1bfc24766b17b27926087f154cb844a9d1f9ab` |
| 当前默认 runtime | `MOCK`（默认 Compose 低资源开发路径；不得作为研究结果） |
| 当前 Real runtime | `research_vilt_predicted_soft`（Real CPU `/ready`、单图连续推理和 2×2 批量 smoke 已通过） |

## Fail-closed 条件

Real Runtime 只有在以下条件全部成立时进入 ready：

1. `contract_version` 为 `1.0`。
2. `task.name` 为 `rsvqa_hr_grouped_closed_set`。
3. `type_source_mode` 为 `predicted_soft`。
4. release ID、研究 commit、运行时 digest、checkpoint/词表哈希完整。
5. 实际文件 SHA-256 与 manifest 一致。
6. `RSVQA_RUNTIME_ENTRYPOINT` 指向独立安装包中的 `package.module:factory`，且适配器声明的 runtime artifact digest 与 manifest 完全一致。
7. 输入协议不接受 `question_type_id`、gold label、split 或 mask。
8. 输出包含置信度、margin、top-k、题型审计、来源和能力边界。

适配器工厂只接收经过校验的 `release_root`、manifest 只读字典，以及已经过路径约束和 SHA-256 校验的 checkpoint/answer-vocab 绝对路径映射。其 `predict` 只接收原始图像字节和原始问题文本。应用模型服务不会导入 `rs-vqa-fusion` 的训练脚本。

应用侧已经实现 manifest Pydantic Schema、流式 SHA-256、路径穿越防护、release/version 固定、runtime digest 比对、加载/预热和输出校验。`compose.real.yaml` 只切换到这个 fail-closed 边界，不会降低校验要求。

研究侧已经提交契约和不可变 release。应用侧已完成所有制品的本地 SHA-256、独立
wheel factory 加载、CPU warmup、真实单图和连续多轮 smoke；下载不完整或任何哈希不一致
时继续 fail closed。当前应用兼容层暂时桥接 wheel 的旧 ViLT `head_mask` 调用签名，研究侧
后续应将该修复回写并发布新的不可变 runtime artifact。

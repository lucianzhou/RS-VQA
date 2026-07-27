# 模型发布消费者兼容记录

> 应用仓库只保存兼容记录，不复制研究契约全文。

| 字段 | 当前值 |
| --- | --- |
| 支持的契约版本 | `1.0` |
| 规范路径 | `rs-vqa-fusion/docs/24_model_release_contract.md` |
| 当前研究交接 commit | `d12de614cb0b4a0275a4f829815e5d74c79a1ee7` |
| 当前 release | `rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2` |
| 目标 manifest SHA-256 | `cce9b8bb48d5cf0213ce789290ceea7525ad2c1d96eba66867c733f1bbc78045` |
| checkpoint SHA-256 | `2426770af96a6f41b30e081c9719d6582471fab091e4b44ba2c3068d6e227109` |
| 答案词表 SHA-256 | `23592881181ac284e46292921ce14d329eb437c1e3913b2e2f8a05ff9b75f99a` |
| runtime wheel SHA-256 | `161f00c81f72d54c145b016fbc5c1cb8ed9f0822c3b17682f67b536330887734` |
| 本地回退 release | `rsvqa-hr-qdrop15-predicted-soft-20260724-8510bc9`（显式旧 ID/manifest 哈希固定后，CPU ready 与四题型 parity 已复验） |
| 当前默认 runtime | `MOCK`（默认 Compose 低资源开发路径；不得作为研究结果） |
| v0.9 Real runtime 状态 | 已完成受控取得、全部固定哈希、CPU ready、golden 8/8、四题型 parity 与产品对齐聚合验收 |

## Fail-closed 条件

Real Runtime 只有在以下条件全部成立时进入 ready：

1. `contract_version` 为 `1.0`。
2. `task.name` 为 `rsvqa_hr_grouped_answer_closed_set`；旧名称只用于显式回退兼容。
3. `type_source_mode` 为 `predicted_soft`。
4. 实际 `model-release.json` SHA-256 与部署固定值一致，release ID 与部署固定版本一致。
5. 实际文件 SHA-256 与 manifest 一致。
6. manifest 的固定 factory 指向已校验 wheel，且运行时 digest 与 manifest 完全一致。
7. 输入协议不接受 `question_type_id`、gold label、split 或 mask。
8. 输出包含置信度、margin、top-k、题型审计、来源和能力边界。

适配器工厂只接收经过校验的 `release_root`、manifest 只读字典，以及已经过路径约束和 SHA-256 校验的 checkpoint/answer-vocab 绝对路径映射。其 `predict` 只接收原始图像字节和原始问题文本。应用模型服务不会导入 `rs-vqa-fusion` 的训练脚本。

应用侧已经实现 manifest Pydantic Schema、流式 SHA-256、路径穿越防护、release/version 固定、runtime digest 比对、加载/预热和输出校验。`compose.real.yaml` 只切换到这个 fail-closed 边界，不会降低校验要求。

Real Compose 默认固定当前 release ID 与 manifest SHA-256；环境未固定这两个值时模型服务
直接保持 not-ready。旧发布不会被删除，但只能通过同时提供旧 release ID 与旧 manifest SHA-256
显式回退。v0.9 已在本地真实验收中证明下载完整、运行时与 HTTP 服务路径一致；未来任一
发布下载不完整或哈希不一致时仍必须 fail closed。

# 模型发布消费者兼容记录

> 应用仓库只保存兼容记录，不复制研究契约全文。

| 字段 | 当前值 |
| --- | --- |
| 支持的契约版本 | `1.0` |
| 规范路径 | `rs-vqa-fusion/docs/24_model_release_contract.md` |
| 审计时研究工作树 HEAD | `e28fb0722a5ef472cbd65f612d273e26c7500b1b` |
| 规范 Git 状态 | `untracked`，尚不能形成可发布的 commit 固定引用 |
| 当前真实 release | 无 |
| 当前开发 runtime | `MOCK`，不得作为研究结果 |

## Fail-closed 条件

Real Runtime 只有在以下条件全部成立时进入 ready：

1. `contract_version` 为 `1.0`。
2. `task.name` 为 `rsvqa_hr_grouped_answer_closed_set`。
3. `type_source_mode` 为 `predicted_soft`。
4. release ID、研究 commit、运行时 digest、checkpoint/词表哈希完整。
5. 实际文件 SHA-256 与 manifest 一致。
6. `RSVQA_RUNTIME_ENTRYPOINT` 指向独立安装包中的 `package.module:factory`，且适配器声明的 runtime artifact digest 与 manifest 完全一致。
7. 输入协议不接受 `question_type_id`、gold label、split 或 mask。
8. 输出包含置信度、margin、top-k、题型审计、来源和能力边界。

适配器工厂只接收经过校验的 `release_root`、manifest 只读字典，以及已经过路径约束和 SHA-256 校验的 checkpoint/answer-vocab 绝对路径映射。其 `predict` 只接收原始图像字节和原始问题文本。应用模型服务不会导入 `rs-vqa-fusion` 的训练脚本。

应用侧已经实现 manifest Pydantic Schema、流式 SHA-256、路径穿越防护、release/version 固定、runtime digest 比对、加载/预热和输出校验。`compose.real.yaml` 只切换到这个 fail-closed 边界，不会降低校验要求。

当前研究契约文件尚未进入研究仓库 commit，因此真实 release 接入必须继续等待其被研究侧正式固定；这不阻塞协议一致的 Mock 与应用开发。

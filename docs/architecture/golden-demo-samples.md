# 金标准演示样本方案

> 状态：**导入规范已建立，样本图像阻塞**（本机无合法 RSVQA-HR 影像）。
> 清单：`data/golden-samples/rsvqa-hr-reference-manifest.json`
> 验收脚本：`scripts/golden_sample_acceptance.py`

## 1. 为什么需要金标准样本

`data/test-images/` 是通过 USGS National Map 下载的**工程测试影像**：

- 没有 RSVQA-HR 金标注；
- 不属于 RSVQA-HR 的城市/传感器分布；
- 只能验证上传、推理、审计、批量等**流程链路**。

**任何情况下都不得用这些 USGS 图片的回答论证模型准确率。** 它们连一条金标都没有。

## 2. 已建立的清单

清单来自本 release 的 `evidence_references` 之一
`rs-vqa-fusion/docs/16_predicted_soft_case_audit.md` 第 3 节，属于已核准的研究证据，
抽样规则由该文档固定（按 sample ID 排序，每个正确性 stratum 与题型顺序取首个未使用图像），
不是为了好看而挑选的。

清单包含 8 条 RSVQA-HR `test_phili` 样本，每条含：

| 字段 | 说明 |
| --- | --- |
| `sample_id` | 研究侧样本 ID |
| `image_id` / `image_path` | RSVQA-HR 原始影像标识（如 `Data/9603.tif`） |
| `question_type` | `area` / `count` / `comp` / `presence` |
| `question` | 原始英文问题 |
| `gold_answer` | 金标答案 |
| `expected_prediction` | **本 release 冻结 checkpoint 的预测** |
| `stratum` | `correct` / `incorrect` |

四类题型各 2 条，正反例各 4 条。其中 4 条 `expected_prediction` 与金标不一致——这是事实，
清单如实记录，不做筛选。

## 3. 阻塞项

RSVQA-HR 影像不在本仓库，也不允许在此再分发：

- 仓库只提交清单文本，不提交任何影像；
- `.gitignore` 已排除 `data/golden-samples/images/`；
- `scripts/golden_sample_acceptance.py --mode validate` 在图像缺失时**退出码 1**，
  确保“阻塞”不会被误读成“通过”。

当前验证输出：

```json
{"mode": "validate", "sample_count": 8, "images_present": [],
 "images_missing": ["9603","9604","9605","9606","9607","9608","9609","9611"],
 "complete": false}
```

## 4. 导入规范

1. 从研究主机的 RSVQA-HR raw corpus 取出清单列出的 8 个 `image_id`
   （参考 `rs-vqa-fusion/scripts/download_rsvqa_hr.sh` 的下载路径）。
2. 放入 `data/golden-samples/images/`，文件名为 `<image_id>.<ext>`，例如 `9603.tif`。
   支持 `.tif/.tiff/.png/.jpg/.jpeg/.webp`。
3. 运行 `python scripts/golden_sample_acceptance.py --mode validate`，必须 `complete: true`。
4. 运行 `python scripts/golden_sample_acceptance.py --mode run --base-url <model-service>`。
   脚本会先校验运行时是 `real` 且 release ID 与清单一致，否则拒绝执行。
5. 不得把影像提交进 Git；不得为了让脚本通过而伪造任何 `gold_answer`。

## 5. 验收判定

`--mode run` 的判定是**复现性**，不是准确率：

- 通过条件：每条样本的服务端 `prediction` 与 `expected_prediction` 逐条相同。
- 失败含义：部署链路与研究 checkpoint 出现分歧（预处理、runtime、输入文本或 release 不一致），
  而不是“模型变差了”。

脚本刻意**不输出任何 accuracy 数字**。8 条样本无法支撑准确率结论；正式指标只以
`model-release.json` 的 `approved_metrics` 为准：

| Split | OA | AA |
| --- | ---: | ---: |
| test | 0.8401412 | 0.8390032 |
| test_phili | 0.8031558 | 0.7975786 |

## 6. 与语言 parity smoke 的关系

`scripts/canonical_parity_smoke.py` 与本方案互补，且**不依赖金标**：

- `--mode pairs`：中文口语问题与其英文 canonical 形式必须得到相同原始预测、top-k、题型概率
  和制品哈希；
- `--mode reference`：HTTP 服务结果与直接调用 `rs_vqa.release_runtime` 的参考输出必须一致。

因此在金标样本阻塞期间，仍然可以证明“部署链路没有偷偷改写模型行为”，只是不能证明准确率。

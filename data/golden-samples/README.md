# 金标准演示样本目录

本目录只保存**清单文本**，不保存任何 RSVQA-HR 影像。

| 文件 | 说明 |
| --- | --- |
| `rsvqa-hr-reference-manifest.json` | 8 条 RSVQA-HR `test_phili` 样本：问题、金标、本 release 冻结预测、来源 |
| `images/` | 本地放置影像的位置，**已被 `.gitignore` 排除** |

使用方式与阻塞说明见 [`docs/architecture/golden-demo-samples.md`](../../docs/architecture/golden-demo-samples.md)。

快速检查：

```bash
python scripts/golden_sample_acceptance.py --mode validate
```

影像缺失时该命令退出码为 `1`，这是预期行为——缺少合法样本必须显式阻塞，不能伪造真值。

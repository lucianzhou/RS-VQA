# RS-VQA 本地测试遥感影像包

## 用途与边界

本目录用于 RS-VQA 的本机工程验证，分为两层：

| 目录 | 数量 | 用途 |
| --- | ---: | --- |
| `single/` | 12 | 手工上传、页面演示和单请求 smoke 测试 |
| `batch/` | 192 | 后续批量上传、队列、失败重试和吞吐流程测试 |

这些文件不是 RSVQA-HR 测试集，也不含与本研究答案词表配对的人工问题或标准答案。它们只能验证软件流程，不能验证论文模型正确率、OA/AA、跨域泛化或模型优劣。

当前 v0.1.0 应用默认运行 `mock_demo`，因此更不能把本目录影像的页面回答解释为 ViLT predicted-soft 结果。

## 来源与使用条件

下载脚本调用 USGS National Map 的 `USGSImageryOnly` 服务：

- 服务说明：<https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer?f=pjson>
- 服务归属：`USDA, USGS The National Map: Orthoimagery`。
- 服务说明指出，美国本土影像主要来自 NAIP，典型分辨率约 6 英寸至 1 米；本包只选择美国本土坐标，避开服务说明中具有额外限制的 Alaska 覆盖。
- 每次下载均记录 WGS84 边界框、服务端点、文件 SHA-256、字节数和时间，见 `manifest.csv`。

该包仅为本地开发准备，不会提交或推送影像本体。若将来要公开重新分发影像、制作公开数据集或用于商业场景，必须重新核验 USGS/USDA 的当期使用条款和具体来源归属。

## 下载与检查

在项目根目录执行：

```bash
python3 scripts/download_usgs_test_imagery.py
```

下载成功后应有 204 个 JPEG，且 `manifest.csv` 的 `status` 为 `downloaded` 或 `cached`。可先使用不下载的预览模式：

```bash
python3 scripts/download_usgs_test_imagery.py --dry-run --max-items 3
```

脚本只使用 Python 标准库，不安装模型、数据集客户端或 GPU 依赖。

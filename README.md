# RS-VQA

RS-VQA 是论文《跨模态特征融合机制及微调策略研究与应用》的独立应用工程。它消费 rs-vqa-fusion 发布的、通过契约校验的模型运行时；它不训练模型，也不导入研究仓库的训练脚本。

## 当前版本

v0.1.2 当前提供“图片 + 问题 -> 闭集回答”的 MVP 主路径：

1. 用户上传图像并输入中文或英文问题。
2. 模型服务只接受可确定映射到 RSVQA-HR 已验证问题范围的问法。
3. Spring 网关转发请求至 Python 模型服务。
4. 前端以答案为中心展示响应，并明确模型来源。

当前本机没有已发布的 predicted-soft 运行时或 checkpoint。开发模式使用明确标注为 `mock_demo` 的后端来验证接口和页面；它绝不是论文研究模型的输出。

完整设计、验收结果与限制见 [v0.1.0 MVP 版本说明](docs/versions/v0.1.0-mvp.md)。v0.1.2 将界面重构为遥感图像对话工作台，说明见 [v0.1.2 前端工作台版本说明](docs/versions/v0.1.2-frontend-workbench.md)。后续每一个可交付版本均须在 [`docs/versions`](docs/versions) 新建独立的技术方案与功能介绍文档。

## 本机启动

需要 Python 3.12、Java 21、Node.js。首次使用时，在三个终端分别执行：

```bash
cd /Users/popwind/Documents/Master/graduation/rs-vqa/services/model-service
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

```bash
cd /Users/popwind/Documents/Master/graduation/rs-vqa/apps/gateway
mvn spring-boot:run
```

```bash
cd /Users/popwind/Documents/Master/graduation/rs-vqa/apps/web
npm install
npm run dev -- --host 127.0.0.1
```

然后在浏览器打开 <http://127.0.0.1:5173/>。默认 `RSVQA_MODEL_MODE=mock`；不要把该模式的回答用于论文模型结论。

## 验证

```bash
cd /Users/popwind/Documents/Master/graduation/rs-vqa/services/model-service && source .venv/bin/activate && pytest -q
cd /Users/popwind/Documents/Master/graduation/rs-vqa/apps/gateway && mvn -q test
cd /Users/popwind/Documents/Master/graduation/rs-vqa/apps/web && npm run build
```

截至 v0.1.0：Python 测试 8 项通过、Java 网关测试 2 项通过、Vue 生产构建通过；浏览器已验证受支持问法与不支持问法两条路径。

## 本地测试遥感影像

为单图演示和未来批量流程提供了一个本地影像包：12 张单图 smoke 样本和 192 张批量样本，共 204 张。它们来自 USGS National Map 的 `USGSImageryOnly` 正射影像服务，主要是美国本土 NAIP/USGS 影像；图片保存在 `data/test-images`，被 Git 忽略，不会推送到仓库。

```bash
cd /Users/popwind/Documents/Master/graduation/rs-vqa
python3 scripts/download_usgs_test_imagery.py
```

下载完成后，单图演示样本位于 `data/test-images/single`，批量样本位于 `data/test-images/batch`，来源、边界框、哈希和下载状态写入 `data/test-images/manifest.csv`。详细的数据边界与使用方式见 [测试影像说明](data/test-images/README.md)。

这些影像仅用于工程验证：上传、图像预处理、接口、批量任务、错误处理和 UI。它们不是 RSVQA-HR 的带标注测试集，不能用于报告本研究模型的 OA/AA、比较模型优劣或声称泛化效果。

## 不在 v0.1.0 范围内

- Agent、RAG、向量数据库、外部通用 VLM
- 账户、历史、人工复核、报告导出
- GPU 训练、下载数据集、复制或修改 rs-vqa-fusion 的训练代码
- 把 oracle、routed、mock 或外部模型文本伪装为 predicted-soft 输出

## 仓库边界

- 研究模型规范来源：rs-vqa-fusion/docs/24_model_release_contract.md。
- 运行时必须是不可变发布物，并携带 model-release.json、checkpoint 哈希、词表哈希和 type_source_mode=predicted_soft。
- 不提交数据、权重、预测 JSONL、日志、PDF 或任何密钥。

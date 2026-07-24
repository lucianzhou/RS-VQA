import { expect, test } from "@playwright/test";
import { readdirSync } from "node:fs";
import path from "node:path";

const image = path.resolve("../../data/test-images/single/phoenix_desert_urban.jpg");
const batchImages = readdirSync(path.resolve("../../data/test-images/batch"))
  .filter((name) => /\.(?:png|jpe?g|webp)$/i.test(name))
  .slice(0, 22)
  .map((name) => path.resolve("../../data/test-images/batch", name));

test("persists an image, multi-turn VQA, provenance, and agent tools", async ({ page }) => {
  await page.goto("/workspace");
  await expect(page.getByText("RS-VQA", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "新建分析", exact: true }).click();
  await expect(page.getByRole("heading", { name: "从一张遥感图像开始" })).toBeVisible();

  await page.getByLabel("选择遥感图像").setInputFiles(image);
  await expect(page.getByText("phoenix_desert_urban.jpg")).toBeVisible();
  await page.getByRole("button", { name: "查看大图" }).click();
  await expect(page.getByRole("dialog", { name: "phoenix_desert_urban.jpg" })).toBeVisible();
  await page.getByRole("button", { name: "关闭大图预览" }).click();
  await expect(page.getByRole("dialog", { name: "phoenix_desert_urban.jpg" })).toBeHidden();

  const question = page.getByLabel("向当前影像提问");
  await question.fill("图中有没有道路？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("模型回答")).toBeVisible();
  await expect(page.getByText("MOCK", { exact: true }).first()).toBeVisible();

  await question.fill("图中有多少建筑物？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.locator(".user-message")).toHaveCount(2);
  await expect(page.locator(".assistant-message")).toHaveCount(2);
  await page.reload();
  await expect(page.locator(".user-message")).toHaveCount(2);
  await expect(page.locator(".assistant-message")).toHaveCount(2);

  await page.getByLabel("向当前影像提问").fill("图中有多少道路？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("低置信度，请复核")).toBeVisible();

  await page.getByLabel("向当前影像提问").fill("请判断这里是否有火灾风险");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("超出能力范围")).toBeVisible();

  await page.getByText("查看模型与调用信息").first().click();
  await expect(page.getByText("Mock 演示，不是研究结果").first()).toBeVisible();

  await page.getByRole("button", { name: "可信 Agent" }).click();
  await page.getByRole("button", { name: "运行只读工具" }).click();
  await expect(page.getByText("AGENT 解释")).toBeVisible();
  await expect(page.getByText("工具调用与审计")).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("workspace-desktop.png"), fullPage: true });
});

test("indexes approved knowledge and returns citations from BGE and Milvus", async ({ page }) => {
  await page.goto("/knowledge");
  await page.getByRole("button", { name: "导入已核准边界" }).click();
  await expect(page.getByText(/rsvqa-knowledge-v1 · READY/).first()).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByText("RS-VQA 已核准模型边界.md").last()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("BAAI/bge-small-zh-v1.5")).toBeVisible();
});

test("creates and completes a persisted batch job", async ({ page }) => {
  await page.goto("/batch");
  await expect(page.getByRole("heading", { name: "建立一组可复核的批量问答任务" })).toBeVisible();

  const files = [
    image,
    path.resolve("../../data/test-images/single/houston_suburban.jpg"),
  ];
  await page.locator('input[type="file"]').setInputFiles(files);
  await expect(page.getByText("2", { exact: true }).first()).toBeVisible();
  await expect(page.getByLabel(/已选择图像，第 1 页/).locator("article")).toHaveCount(2);
  await page.getByRole("button", { name: "查看大图 phoenix_desert_urban.jpg" }).click();
  await expect(page.getByRole("dialog", { name: "phoenix_desert_urban.jpg" })).toBeVisible();
  await page.getByRole("button", { name: "关闭大图预览" }).click();
  await page.getByRole("button", { name: "创建批量任务" }).click();

  await expect(page.getByText(/COMPLETED|已完成/).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("link", { name: /CSV/ }).first()).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("batch-desktop.png"), fullPage: true });
});

test("previews a 20-image page and keeps the remaining images on page two", async ({ page }) => {
  await page.goto("/batch");
  await page.locator('input[type="file"]').setInputFiles(batchImages);

  const grid = page.getByLabel("已选择图像，第 1 页");
  await expect(grid.locator("article")).toHaveCount(20);
  await page.getByRole("button", { name: "下一页" }).click();
  await expect(page.getByLabel("已选择图像，第 2 页").locator("article")).toHaveCount(2);

  const previewButton = page.getByLabel("已选择图像，第 2 页").getByRole("button", { name: /^查看大图/ }).first();
  await previewButton.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();

  await page.getByRole("button", { name: "上一页" }).click();
  await page.screenshot({ path: test.info().outputPath("batch-thumbnails-page-1.png"), fullPage: true });
});

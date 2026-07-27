import { expect, test } from "@playwright/test";
import { readdirSync } from "node:fs";
import path from "node:path";

const image = path.resolve("../../data/test-images/single/phoenix_desert_urban.jpg");
const batchImages = readdirSync(path.resolve("../../data/test-images/batch"))
  .filter((name) => /\.(?:png|jpe?g|webp)$/i.test(name))
  .slice(0, 22)
  .map((name) => path.resolve("../../data/test-images/batch", name));
const batchImagesOver32 = readdirSync(path.resolve("../../data/test-images/batch"))
  .filter((name) => /\.(?:png|jpe?g|webp)$/i.test(name))
  .slice(0, 40)
  .map((name) => path.resolve("../../data/test-images/batch", name));

test("persists an image, multi-turn VQA, provenance, and agent tools", async ({ page }) => {
  await page.goto("/workspace");
  await expect(page.getByText("RS-VQA", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "新建分析", exact: true }).click();
  await expect(page.getByRole("heading", { name: "从一张遥感图像开始" })).toBeVisible({ timeout: 15_000 });

  await page.getByLabel("选择遥感图像").setInputFiles(image);
  await expect(page.getByText("phoenix_desert_urban.jpg")).toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: "查看大图" }).click();
  await expect(page.getByRole("dialog", { name: "phoenix_desert_urban.jpg" })).toBeVisible();
  await page.getByRole("button", { name: "关闭大图预览" }).click();
  await expect(page.getByRole("dialog", { name: "phoenix_desert_urban.jpg" })).toBeHidden();

  const question = page.getByLabel("向当前影像提问");
  await question.fill("图中有没有道路？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("模型回答")).toBeVisible({ timeout: 30_000 });

  await question.fill("图中有多少建筑物？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.locator(".user-message")).toHaveCount(2);
  await expect(page.locator(".assistant-message")).toHaveCount(2);
  // Reloading while the second answer is still in flight would drop the
  // optimistic turn and assert against a half-written conversation. The real
  // runtime is slow enough for that to matter; wait for persistence first.
  await expect(page.getByText("正在分析当前影像…")).toBeHidden({ timeout: 60_000 });
  await page.reload();
  await expect(page.locator(".user-message")).toHaveCount(2);
  await expect(page.locator(".assistant-message")).toHaveCount(2);

  await page.getByLabel("向当前影像提问").fill("图中有多少道路？");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText(/数量模型对非零和密集目标存在系统性低估风险/)).toBeVisible();

  await page.getByLabel("向当前影像提问").fill("请判断这里是否有火灾风险");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("超出能力范围")).toBeVisible();

  await page.getByText("查看模型与调用信息").first().click();
  await expect(page.getByText(/Mock 演示，不是研究结果|研究 ViLT predicted-soft/).first()).toBeVisible();

  // The workspace drawer is a contextual chat panel, not a fixed question list.
  await page.getByRole("button", { name: "RS-Bot" }).click();
  const drawer = page.getByLabel("RS-Bot", { exact: true });
  await expect(drawer).toBeVisible();
  const drawerComposer = drawer.getByLabel("向 RS-Bot 提问");
  await drawerComposer.fill("查询当前模型版本");
  await drawer.getByRole("button", { name: "发送给 RS-Bot" }).click();
  await expect(drawer.locator(".rsbot-turn")).toHaveCount(1, { timeout: 20_000 });
  await expect(drawer.getByText(/Trace /).first()).toBeVisible();
  await drawerComposer.fill("这个模型支持哪些问题？");
  await drawer.getByRole("button", { name: "发送给 RS-Bot" }).click();
  await expect(drawer.locator(".rsbot-turn")).toHaveCount(2, { timeout: 20_000 });
  await page.screenshot({ path: test.info().outputPath("workspace-desktop.png"), fullPage: true });
});

test("requires confirmation before a trusted Agent archive action", async ({ page }) => {
  await page.goto("/agent");
  await expect(page.getByRole("heading", { name: "分析会话" })).toBeVisible();
  const projectsResponse = await page.request.post("/api/v1/projects", {
    data: { name: `Agent action ${Date.now()}` },
  });
  expect(projectsResponse.ok()).toBeTruthy();
  const project = await projectsResponse.json() as { id: string; name: string };
  await page.reload();
  await page.getByLabel("选择项目").selectOption(project.id);
  await page.getByRole("button", { name: "新建分析会话" }).click();
  // Controlled actions are opt-in and must not occupy the conversation by default.
  await expect(page.getByText("需要副作用时，先提交提案再人工确认")).toBeHidden();
  await page.getByRole("button", { name: "受控操作" }).click();
  await expect(page.getByText("需要副作用时，先提交提案再人工确认")).toBeVisible();
  await page.getByLabel("选择受控操作").selectOption("archive_project");
  await page.getByRole("button", { name: "提交操作提案" }).click();
  await expect(page.getByText("待人工确认")).toBeVisible();
  await page.getByRole("button", { name: "确认执行" }).click();
  await expect(page.getByText("已完成")).toBeVisible();
  await expect(page.getByText("deterministic_action_controller")).toBeVisible();
  const archived = await page.request.get("/api/v1/archive");
  expect(archived.ok()).toBeTruthy();
  expect((await archived.json()).projects.some((item: { id: string }) => item.id === project.id)).toBeTruthy();
});

test("indexes approved knowledge and returns citations from BGE and Milvus", async ({ page }) => {
  await page.goto("/knowledge");
  await page.getByRole("button", { name: "导入已核准边界" }).click();
  await expect(page.getByText(/rsvqa-knowledge-v1 · READY/).first()).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByText("RS-VQA 已核准模型边界.md").last()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("BAAI/bge-small-zh-v1.5")).toBeVisible({ timeout: 30_000 });
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

test("accepts more than 32 batch images and keeps one adaptive upload entry", async ({ page }) => {
  await page.goto("/batch");
  await expect(page.getByRole("button", { name: /上传图像/ })).toHaveCount(1);
  await page.locator('input[type="file"]').setInputFiles(batchImagesOver32);

  await expect(page.getByText("已选择 40 / 200 张")).toBeVisible();
  await expect(page.getByRole("button", { name: /添加图像/ })).toHaveCount(1);
  await expect(page.getByText("第 1 / 2 页 · 每页最多 20 张")).toBeVisible();
  await expect(page.getByLabel("已选择图像，第 1 页").locator("article")).toHaveCount(20);
});

test("creates, confirms, and exports a deterministic project report", async ({ page }) => {
  await page.goto("/reports");
  await expect(page.getByRole("heading", { name: "把项目与批任务整理为可追溯报告" })).toBeVisible();
  const scope = page.getByLabel("选择报告范围");
  await expect(scope.locator("option")).not.toHaveCount(0);

  await page.getByRole("button", { name: "生成确定性草稿" }).click();
  await expect(page.getByText("待人工确认")).toBeVisible();
  await expect(page.getByText("deterministic_backend_statistics")).toBeVisible();
  await expect(page.getByRole("heading", { name: "需要人工复核" })).toBeVisible();

  await page.getByRole("button", { name: "人工确认" }).click();
  await expect(page.getByText("人工已确认")).toBeVisible({ timeout: 15_000 });

  const markdownDownload = page.waitForEvent("download");
  await page.getByRole("link", { name: "Markdown" }).click();
  await expect((await markdownDownload).suggestedFilename()).toMatch(/\.md$/);
  const jsonDownload = page.waitForEvent("download");
  await page.getByRole("link", { name: "JSON" }).click();
  await expect((await jsonDownload).suggestedFilename()).toMatch(/\.json$/);
  await page.screenshot({ path: test.info().outputPath("report-confirmed-desktop.png"), fullPage: true });
});

test("keeps Gemini fail-closed behind image consent and server configuration", async ({ page }) => {
  await page.goto("/workspace");
  await expect(page.getByText("RS-VQA", { exact: true })).toBeVisible();
  const initialSettingsResponse = await page.request.get("/api/v1/user/settings");
  expect(initialSettingsResponse.status()).toBe(200);
  const initialSettings = await initialSettingsResponse.json() as { externalImageOptIn: boolean };
  const projectsResponse = await page.request.get("/api/v1/projects");
  expect(projectsResponse.status()).toBe(200);
  const projects = await projectsResponse.json() as Array<{ conversations: Array<{ id: string }> }>;
  const conversationIds = projects.flatMap((project) => project.conversations.map((conversation) => conversation.id));
  let conversationId = "";
  let beforeMessages = 0;
  for (const id of conversationIds) {
    const response = await page.request.get(`/api/v1/conversations/${id}`);
    const conversation = await response.json() as { image: unknown; messages: unknown[] };
    if (conversation.image) {
      conversationId = id;
      beforeMessages = conversation.messages.length;
      break;
    }
  }
  expect(conversationId).not.toBe("");

  await page.request.patch("/api/v1/user/settings", {
    data: { externalImageOptIn: false },
  });
  const consentBlocked = await page.request.post(`/api/v1/conversations/${conversationId}/questions`, {
    data: { question: "请描述这张图。", providerId: "gemini" },
  });
  expect(consentBlocked.status()).toBe(400);
  expect((await consentBlocked.json()).code).toBe("INVALID_REQUEST");

  const providers = await (await page.request.get("/api/v1/providers")).json() as Array<{
    providerId: string;
    configurationState: string;
  }>;
  const gemini = providers.find((provider) => provider.providerId === "gemini");
  expect(gemini).toBeDefined();

  // A configured Provider would incur a real relay call and persist a message.
  // The E2E suite verifies configuration discovery instead of spending quota.
  if (gemini?.configurationState === "UNCONFIGURED") {
    await page.request.patch("/api/v1/user/settings", {
      data: { externalImageOptIn: true },
    });
    const providerBlocked = await page.request.post(`/api/v1/conversations/${conversationId}/questions`, {
      data: { question: "请描述这张图。", providerId: "gemini" },
    });
    expect(providerBlocked.status()).toBe(503);
    expect((await providerBlocked.json()).code).toBe("PROVIDER_NOT_CONFIGURED");
  } else {
    expect(gemini?.configurationState).toBe("CONFIGURED");
  }

  const after = await (await page.request.get(`/api/v1/conversations/${conversationId}`)).json() as { messages: unknown[] };
  expect(after.messages).toHaveLength(beforeMessages);
  await page.request.patch("/api/v1/user/settings", {
    data: { externalImageOptIn: initialSettings.externalImageOptIn },
  });
});

test("persists a multi-turn project Agent session with deterministic tool evidence", async ({ page }) => {
  await page.goto("/agent");
  await expect(page.getByRole("heading", { name: "分析会话" })).toBeVisible();
  await page.getByRole("button", { name: "新建分析会话" }).click();

  await page.getByRole("button", { name: "汇总这个项目的 VQA 结果和置信度分布" }).first().click();
  await expect(page.locator(".rsbot-tool").filter({ hasText: "置信度分布" })).toBeVisible({ timeout: 20_000 });
  await expect(page.locator(".rsbot-turn")).toHaveCount(1);

  const composer = page.getByLabel("向 RS-Bot 提问");
  await composer.fill("生成这个项目的报告事实包");
  await page.getByRole("button", { name: "发送给 RS-Bot" }).click();
  await expect(page.locator(".rsbot-turn")).toHaveCount(2, { timeout: 20_000 });
  await expect(page.locator(".rsbot-tool").filter({ hasText: "报告事实包" })).toHaveCount(1);

  // The generated title replaces the placeholder and is readable, not an id.
  await expect(page.locator(".agent-context-header h1")).not.toHaveText(/^Agent action/);

  await page.reload();
  await expect(page.locator(".rsbot-turn")).toHaveCount(2);
  await expect(page.getByText(/Trace /).first()).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("agent-project-analysis.png"), fullPage: true });
});

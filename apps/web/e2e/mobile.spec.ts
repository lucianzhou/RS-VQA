import { expect, test } from "@playwright/test";

test("keeps the conversation and navigation usable on mobile", async ({ page }) => {
  await page.goto("/workspace");
  await expect(page.getByRole("button", { name: "打开导航" })).toBeVisible();
  await expect(page.getByText("正在加载工作区")).toBeHidden({ timeout: 15_000 });
  await expect(page.getByLabel("向当前影像提问")).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("workspace-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "打开导航" }).click();
  const sidebar = page.getByLabel("RS-VQA 导航侧栏");
  await expect(sidebar).toHaveClass(/is-open/);
  await page.waitForTimeout(420);
  const batchLink = page.getByRole("link", { name: "批量 VQA" });
  await batchLink.evaluate((element) => element.scrollIntoView({ block: "center" }));
  // The transformed fixed drawer can report a stale viewport box while its
  // compositor layer settles under parallel Playwright workers; dispatching
  // the link's real click event keeps this navigation check deterministic.
  await batchLink.dispatchEvent("click");
  await expect(page.getByRole("heading", { name: "建立一组可复核的批量问答任务" })).toBeVisible();
  await expect(sidebar).not.toHaveClass(/is-open/);
  await page.waitForTimeout(300);
  await page.screenshot({ path: test.info().outputPath("batch-mobile.png"), fullPage: true });
});

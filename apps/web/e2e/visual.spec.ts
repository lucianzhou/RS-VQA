import { expect, test } from "@playwright/test";

test("keeps the Mineral Forest workspace stable across laptop and tablet viewports", async ({ page }) => {
  await page.goto("/workspace");
  await expect(page.getByText("RS-VQA", { exact: true })).toBeVisible();
  await expect(page.getByLabel("RS-VQA 导航侧栏")).toBeVisible();
  await expect(page.locator(".workspace-center")).toBeHidden({ timeout: 10_000 });
  await page.screenshot({
    path: test.info().outputPath(`workspace-${test.info().project.name}.png`),
    fullPage: true,
  });

  await page.getByRole("button", { name: "收起导航" }).click();
  await expect(page.getByLabel("RS-VQA 导航侧栏")).toBeHidden();
  await page.waitForTimeout(320);
  const contentBox = await page.locator(".app-content").boundingBox();
  const viewport = page.viewportSize();
  expect(contentBox?.x).toBeLessThanOrEqual(1);
  expect(contentBox?.width).toBeGreaterThanOrEqual((viewport?.width ?? 0) - 1);
  await expect(page.getByRole("button", { name: "打开导航" })).toBeVisible();
  await page.screenshot({
    path: test.info().outputPath(`workspace-collapsed-${test.info().project.name}.png`),
    fullPage: true,
  });
});

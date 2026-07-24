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
});

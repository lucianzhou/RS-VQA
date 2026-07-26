import { expect, test } from "@playwright/test";

/**
 * Layout guarantees for the RS-Bot surfaces.
 *
 * <p>These assert geometry rather than copy: a leading icon that drifts to the
 * edge of a button, a title that pushes a control out of the header, or a page
 * that scrolls sideways are all things a text-only assertion happily misses.
 */

async function documentScrollsHorizontally(page: import("@playwright/test").Page) {
  return page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
}

test("keeps the RS-Bot page free of horizontal scroll and clipped controls", async ({ page }) => {
  await page.goto("/agent");
  await expect(page.getByRole("heading", { name: "分析会话" })).toBeVisible();

  expect(await documentScrollsHorizontally(page)).toBe(false);

  const createButton = page.getByRole("button", { name: "新建分析会话" });
  await expect(createButton).toBeVisible();

  // The icon and the label must read as one centred unit: the icon's centre sits
  // left of the label's, both are inside the button, and neither hugs the edge.
  const geometry = await createButton.evaluate((element) => {
    const button = element.getBoundingClientRect();
    const icon = element.querySelector("svg")!.getBoundingClientRect();
    const style = getComputedStyle(element);
    const contentWidth = Array.from(element.childNodes).length;
    return {
      buttonLeft: button.left,
      buttonRight: button.right,
      buttonCentre: button.left + button.width / 2,
      iconLeft: icon.left,
      iconRight: icon.right,
      display: style.display,
      gap: style.columnGap,
      justify: style.justifyContent,
      alignItems: style.alignItems,
      children: contentWidth,
    };
  });

  expect(geometry.display).toContain("flex");
  expect(geometry.justify).toBe("center");
  expect(geometry.alignItems).toBe("center");
  expect(parseFloat(geometry.gap)).toBeGreaterThan(0);
  // Not pinned to the far left: the whole icon+label group is centred.
  expect(geometry.iconLeft - geometry.buttonLeft).toBeGreaterThan(8);
  expect(geometry.iconRight).toBeLessThan(geometry.buttonCentre);
});

test("scrolls the RS-Bot transcript without scrolling the page", async ({ page }) => {
  await page.goto("/agent");
  await page.getByRole("button", { name: "新建分析会话" }).click();

  const transcript = page.getByLabel("RS-Bot 对话记录");
  await expect(transcript).toBeVisible();

  const scrollports = await page.evaluate(() => {
    const transcriptNode = document.querySelector(".rsbot-transcript")!;
    return {
      transcriptOverflowY: getComputedStyle(transcriptNode).overflowY,
      bodyOverflow: getComputedStyle(document.body).overflow,
      pageScrollsSideways: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    };
  });

  expect(scrollports.transcriptOverflowY).toBe("auto");
  expect(scrollports.pageScrollsSideways).toBe(false);
});

test("opens the workspace drawer as a chat panel with a pinned composer", async ({ page }) => {
  await page.goto("/workspace");
  await page.getByRole("button", { name: "新建分析", exact: true }).click();
  await expect(page.getByRole("heading", { name: "从一张遥感图像开始" })).toBeVisible({ timeout: 15_000 });

  await page.getByRole("button", { name: "RS-Bot" }).click();
  const drawer = page.getByLabel("RS-Bot", { exact: true });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByLabel("向 RS-Bot 提问")).toBeVisible();

  // The composer sits at the bottom of the drawer, below the transcript.
  const layout = await drawer.evaluate((element) => {
    const composer = element.querySelector(".rsbot-composer")!.getBoundingClientRect();
    const transcript = element.querySelector(".rsbot-transcript")!.getBoundingClientRect();
    const drawerBox = element.getBoundingClientRect();
    return {
      composerBelowTranscript: composer.top >= transcript.bottom - 1,
      composerInsideDrawer: composer.bottom <= drawerBox.bottom + 1,
      composerWithinViewport: composer.right <= window.innerWidth + 1,
    };
  });

  expect(layout.composerBelowTranscript).toBe(true);
  expect(layout.composerInsideDrawer).toBe(true);
  expect(layout.composerWithinViewport).toBe(true);
  expect(await documentScrollsHorizontally(page)).toBe(false);
});

test.describe("reduced motion", () => {
  test("stops the RS-Bot progress animation when motion is reduced", async ({ page }) => {
    // Emulated explicitly rather than through test.use: the project-level `use`
    // in playwright.config.ts wins here, and a silently non-reduced run would
    // make this assertion pass for the wrong reason.
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/agent");
    expect(await page.evaluate(
      () => window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    )).toBe(true);
    await page.getByRole("button", { name: "新建分析会话" }).click();
    await page.getByLabel("向 RS-Bot 提问").fill("查询当前模型版本");
    await page.getByRole("button", { name: "发送给 RS-Bot" }).click();

    const progress = page.locator(".rsbot-progress-line");
    if (await progress.count()) {
      const animationName = await progress.first().evaluate(
        (element) => getComputedStyle(element, "::after").animationName,
      );
      expect(animationName).toBe("none");
    }
    expect(await documentScrollsHorizontally(page)).toBe(false);
  });
});

import { chromium, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
const browser = await chromium.launch(),
  page = await browser.newPage({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  });
try {
  await page.goto("http://127.0.0.1:18747/app/");
  await page.waitForSelector("html[data-ready=true]");
  await page.locator("[name=company]").fill("company-x");
  await page.locator("[name=username]").fill("manager");
  await page.locator("[name=password]").fill("synthetic-test-password-123");
  await page.getByRole("button", { name: "ورود", exact: true }).click();
  await expect(page.locator("#connection")).toHaveText("متصل");
  const shot = (name) =>
    page.screenshot({
      path: fileURLToPath(
        new URL("../../../verification/" + name, import.meta.url),
      ),
    });
  await page.locator(".timeline-scroll").evaluate((e) => (e.scrollTop = 450));
  await shot("pwa-day.png");
  await page.getByRole("button", { name: "هفتگی", exact: true }).click();
  await page.locator(".timeline-scroll").evaluate((e) => (e.scrollTop = 450));
  await shot("pwa-week.png");
  await page.getByRole("button", { name: "ماهانه", exact: true }).click();
  await shot("pwa-month.png");
  await page.getByRole("button", { name: "جلسهٔ جدید", exact: true }).click();
  await page.locator("dialog [name=title]").fill("بررسی پیشرفت پروژه");
  await shot("pwa-meeting-form.png");
  await page
    .locator("dialog")
    .getByRole("button", { name: "بستن", exact: true })
    .click();
  await page
    .locator("nav")
    .getByRole("button", { name: "مصوبات", exact: true })
    .click();
  await page.emulateMedia({ colorScheme: "dark" });
  await shot("pwa-tasks-dark.png");
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.emulateMedia({ colorScheme: "light" });
  await shot("pwa-desktop.png");
  console.log("Six synthetic screenshots saved");
} finally {
  await browser.close();
}

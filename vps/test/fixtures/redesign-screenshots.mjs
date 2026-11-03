import { chromium, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { mkdirSync } from "node:fs";

const directory = new URL(
  "../../../verification/web-redesign/",
  import.meta.url,
);
mkdirSync(directory, { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({
  viewport: { width: 1440, height: 1000 },
  deviceScaleFactor: 1,
});
page.setDefaultTimeout(15000);
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const shot = (name) =>
  page.screenshot({ path: fileURLToPath(new URL(name + ".png", directory)) });
try {
  await page.goto("http://127.0.0.1:18747/app/");
  await expect(page.locator("html")).toHaveAttribute("data-ready", "true");
  await page.locator("[name=company]").fill("company-x");
  await page.locator("[name=username]").fill("manager");
  await page.locator("[name=password]").fill("synthetic-test-password-123");
  await page.getByRole("button", { name: "ورود", exact: true }).click();
  await expect(page.locator("#connection")).toHaveText("متصل", {
    timeout: 30000,
  });
  await expect(
    page.locator(".agenda-panel .meeting-card").first(),
  ).toBeVisible();
  await page.locator(".timeline-scroll").evaluate((n) => (n.scrollTop = 450));
  await shot("desktop-meetings");
  await page
    .locator("nav")
    .getByRole("button", { name: "پیگیری", exact: true })
    .click();
  await shot("desktop-followup");
  await page
    .locator("nav")
    .getByRole("button", { name: "مصوبات", exact: true })
    .click();
  await shot("desktop-tasks");
  await page.getByRole("button", { name: "مصوبهٔ جدید", exact: true }).click();
  await page
    .locator("dialog [name=title]")
    .fill("آماده‌سازی گزارش پیشرفت پروژه");
  await shot("desktop-task-form");
  await page
    .locator("dialog")
    .getByRole("button", { name: "بستن", exact: true })
    .click();
  await page.setViewportSize({ width: 390, height: 844 });
  await shot("mobile-tasks");
  await page.emulateMedia({ colorScheme: "dark" });
  await expect(page.locator(".metric-card").first()).toHaveCSS(
    "background-color",
    "rgb(28, 30, 43)",
  );
  await shot("mobile-tasks-dark");
  await page.emulateMedia({ colorScheme: "light" });
  await page
    .locator("nav")
    .getByRole("button", { name: "جلسات", exact: true })
    .click();
  await page.getByRole("button", { name: "جلسهٔ جدید", exact: true }).click();
  await page.locator("dialog [name=title]").fill("جلسهٔ بررسی پیشرفت پروژه");
  await shot("mobile-meeting-form");
  await page
    .locator("dialog")
    .getByRole("button", { name: "بستن", exact: true })
    .click();
  await page.setViewportSize({ width: 375, height: 812 });
  await page.locator(".timeline-scroll").evaluate((n) => (n.scrollTop = 450));
  await shot("mobile-meetings");
  if (errors.length) throw new Error(errors.join("\n"));
  console.log("Eight verified redesign screenshots saved.");
} finally {
  await browser.close();
}

import { chromium, expect } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { preview, password } from "./preview.mjs";

const server = await preview(18749),
  browser = await chromium.launch();
let revision = "4.10.0";
const originalFetch = server.app.fetch;
server.app.fetch = async (request, options) => {
  const response = await originalFetch(request, options);
  if (new URL(request.url).pathname === "/app/sw.js")
    return new Response(
      (await response.text()).replace(
        "peygir-shell-4.10.0",
        "peygir-shell-" + revision,
      ),
      { status: response.status, headers: response.headers },
    );
  return response;
};
const dest = resolve(
  import.meta.dirname,
  "../../../verification/settings-updates",
);
mkdirSync(dest, { recursive: true });
try {
  const page = await browser.newPage({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 1,
  });
  await page.goto("http://127.0.0.1:18749/app/");
  await page.locator("html[data-ready=true]").waitFor();
  await page.locator("[name=company]").fill("company-x");
  await page.locator("[name=username]").fill("manager");
  await page.locator("[name=password]").fill(password);
  await page.getByRole("button", { name: "ورود", exact: true }).click();
  await page.getByRole("button", { name: "تنظیمات", exact: true }).click();
  const dialog = page.locator("dialog[open]");
  await dialog
    .getByLabel("درصد سپری‌شدن مهلت (۱ تا ۹۹)")
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: resolve(dest, "web-progress-light.png") });
  await dialog.getByLabel("رنگ برنامه", { exact: true }).selectOption("dark");
  await page.waitForTimeout(350);
  await dialog
    .getByLabel("درصد سپری‌شدن مهلت (۱ تا ۹۹)")
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: resolve(dest, "web-progress-dark.png") });
  await dialog
    .getByRole("button", { name: "بررسی به‌روزرسانی وب" })
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: resolve(dest, "web-update-dark.png") });
  if (
    await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)
  )
    throw new Error("Horizontal overflow");
  await page.evaluate(() => navigator.serviceWorker.ready);
  await expect
    .poll(() => page.evaluate(() => !!navigator.serviceWorker.controller))
    .toBe(true);
  const before = await page.evaluate(async () => {
    const s = await import("/app/store.mjs");
    const p = (await s.allProfiles())[0];
    return (await s.snapshot(p.id)).records.length;
  });
  revision = "4.10.1-test";
  await dialog.getByRole("button", { name: "بررسی به‌روزرسانی وب" }).click();
  await expect(
    dialog.getByRole("button", { name: "اعمال نسخهٔ جدید", exact: true }),
  ).toBeVisible();
  await dialog
    .getByRole("button", { name: "اعمال نسخهٔ جدید", exact: true })
    .click();
  await page
    .locator("dialog[open]")
    .last()
    .getByRole("button", { name: "به‌روزرسانی", exact: true })
    .click();
  await page.waitForLoadState("load");
  await expect
    .poll(() => page.evaluate(() => caches.keys()))
    .toContain("peygir-shell-4.10.1-test");
  await page.locator("html[data-ready=true]").waitFor();
  const after = await page.evaluate(async () => {
    const s = await import("/app/store.mjs");
    const p = (await s.allProfiles())[0];
    return (await s.snapshot(p.id)).records.length;
  });
  if (before !== after) throw new Error("Stored records changed during update");
  console.log(
    "Settings screenshots and service-worker check/apply/reload with preserved records passed",
  );
} finally {
  await browser.close();
  await server.close();
}

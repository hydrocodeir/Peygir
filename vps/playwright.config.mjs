import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./test/browser",
  timeout: 45000,
  expect: { timeout: 10000 },
  fullyParallel: false,
  workers: 1,
  reporter: [
    ["list"],
    ["json", { outputFile: "../verification/pwa-browser-results.json" }],
  ],
  outputDir: "../.local-build/pwa-browser",
  use: { baseURL: "http://127.0.0.1:18748", trace: "retain-on-failure" },
  webServer: {
    command: "node test/fixtures/preview.mjs --serve",
    url: "http://127.0.0.1:18748/health",
    reuseExistingServer: false,
    env: { PREVIEW_PORT: "18748" },
    timeout: 20000,
  },
  projects: [
    {
      name: "chromium-mobile",
      use: { ...devices["Pixel 7"], browserName: "chromium" },
    },
    {
      name: "webkit-iphone",
      use: { ...devices["iPhone 13"], browserName: "webkit" },
    },
  ],
});

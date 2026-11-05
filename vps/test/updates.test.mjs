import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { createHash } from "node:crypto";
import { createApp } from "../src/app.mjs";
import { updateManifest } from "../src/updates.mjs";
import { installAndroidUpdate } from "../scripts/install-android-update.mjs";

test("Android update endpoint streams only public versioned APKs and reports incomplete publishing", async (t) => {
  const root = resolve(import.meta.dirname, "../../.local-build/update-tests");
  mkdirSync(root, { recursive: true });
  const dataDir = mkdtempSync(join(root, "updates-"));
  const app = await createApp({
    dataDir,
    publicOrigin: "https://peygir.test",
    bootstrap: { username: "owner", password: "synthetic-test-password-123" },
  });
  t.after(async () => {
    await app.close();
    rmSync(dataDir, { recursive: true, force: true });
  });
  const fetch = (path, method = "GET") =>
    app.fetch(new Request("https://peygir.test" + path, { method }));
  assert.equal((await fetch("/updates/android/latest.json")).status, 404);
  const dir = join(dataDir, "updates", "android");
  mkdirSync(dir, { recursive: true });
  const apk = Buffer.from("synthetic-apk-test-content");
  const manifest = {
    packageName: "ir.peygir.app",
    versionCode: 39,
    versionName: "4.10.0",
    minSdk: 26,
    size: apk.length,
    sha256: createHash("sha256").update(apk).digest("hex"),
    apk: "/updates/android/peygir-39.apk",
    notes: "نسخهٔ آزمایشی",
  };
  writeFileSync(join(dir, "latest.json"), JSON.stringify(manifest));
  assert.equal((await fetch("/updates/android/latest.json")).status, 503);
  writeFileSync(join(dir, "peygir-39.apk"), apk);
  const result = await fetch("/updates/android/latest.json");
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), manifest);
  assert.equal(result.headers.get("cache-control"), "no-store");
  const binary = await fetch(manifest.apk);
  assert.equal(binary.status, 200);
  assert.equal(
    binary.headers.get("content-type"),
    "application/vnd.android.package-archive",
  );
  assert.equal(binary.headers.get("content-length"), String(apk.length));
  assert.deepEqual(Buffer.from(await binary.arrayBuffer()), apk);
  const head = await fetch(manifest.apk, "HEAD");
  assert.equal(head.status, 200);
  assert.equal((await head.arrayBuffer()).byteLength, 0);
  assert.equal((await fetch(manifest.apk, "POST")).status, 405);
  for (const path of [
    "/updates/android/registry.sqlite",
    "/updates/android/latest.json.part",
    "/updates/android/peygir-40.apk",
    "/updates/android/%2e%2e%2fregistry.sqlite",
  ])
    assert.equal((await fetch(path)).status, 404);
  writeFileSync(
    join(dir, "latest.json"),
    JSON.stringify({ ...manifest, apk: "/admin/secret.apk" }),
  );
  assert.equal((await fetch("/updates/android/latest.json")).status, 503);
  for (const bad of [
    { versionCode: 1.5 },
    { size: 1073741825 },
    { sha256: "invalid" },
    { minSdk: 1 },
    { packageName: "other.app" },
  ])
    assert.throws(() => updateManifest({ ...manifest, ...bad }));
});

test("publishing validates checksums and preserves an existing APK for the same versionCode", async (t) => {
  const root = resolve(import.meta.dirname, "../../.local-build/update-tests");
  mkdirSync(root, { recursive: true });
  const dir = mkdtempSync(join(root, "publish-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const source = join(dir, "source"),
    target = join(dir, "data");
  mkdirSync(source);
  const apk = Buffer.from("synthetic-apk");
  const manifest = {
    packageName: "ir.peygir.app",
    versionCode: 39,
    versionName: "4.10.0",
    minSdk: 26,
    size: apk.length,
    sha256: createHash("sha256").update(apk).digest("hex"),
    apk: "/updates/android/peygir-39.apk",
    notes: "تغییرات",
  };
  const save = () =>
    writeFileSync(join(source, "latest.json"), JSON.stringify(manifest));
  save();
  writeFileSync(join(source, "peygir-39.apk"), "invalid");
  await assert.rejects(installAndroidUpdate(source, target), /checksum/);
  writeFileSync(join(source, "peygir-39.apk"), apk);
  assert.deepEqual(await installAndroidUpdate(source, target), manifest);
  assert.deepEqual(await installAndroidUpdate(source, target), manifest);
  const changed = Buffer.from("different-apk");
  writeFileSync(join(source, "peygir-39.apk"), changed);
  manifest.size = changed.length;
  manifest.sha256 = createHash("sha256").update(changed).digest("hex");
  save();
  await assert.rejects(
    installAndroidUpdate(source, target),
    /versionCode already exists/,
  );
});

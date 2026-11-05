import {
  readFile,
  stat,
  mkdir,
  copyFile,
  writeFile,
  rename,
  unlink,
} from "node:fs/promises";
import { createReadStream, constants } from "node:fs";
import { createHash, randomUUID } from "node:crypto";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { updateManifest } from "../src/updates.mjs";

async function hash(path) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest("hex");
}
export async function installAndroidUpdate(source, dataDir) {
  const metadata = join(source, "latest.json");
  if ((await stat(metadata)).size > 65536)
    throw new Error("Manifest is too large");
  const manifest = updateManifest(JSON.parse(await readFile(metadata, "utf8")));
  const filename = `peygir-${manifest.versionCode}.apk`,
    apk = join(source, filename);
  if (
    (await stat(apk)).size !== manifest.size ||
    (await hash(apk)) !== manifest.sha256
  )
    throw new Error("APK checksum or size mismatch");
  const dir = join(dataDir, "updates", "android");
  await mkdir(dir, { recursive: true, mode: 0o700 });
  const target = join(dir, filename),
    temporary = join(dir, filename + "." + randomUUID() + ".part");
  let exists = false;
  try {
    await stat(target);
    exists = true;
  } catch (e) {
    if (e.code !== "ENOENT") throw e;
  }
  if (exists) {
    if ((await hash(target)) !== manifest.sha256)
      throw new Error(
        "An APK with this versionCode already exists; increase versionCode",
      );
  } else {
    try {
      await copyFile(apk, temporary, constants.COPYFILE_EXCL);
      if ((await hash(temporary)) !== manifest.sha256)
        throw new Error("APK copy checksum mismatch");
      await rename(temporary, target);
    } finally {
      await unlink(temporary).catch((e) => {
        if (e.code !== "ENOENT") throw e;
      });
    }
  }
  // Switch the pointer only after the immutable APK is in place. The running API stays online.
  const pointer = join(dir, "latest." + randomUUID() + ".json.part");
  try {
    await writeFile(pointer, JSON.stringify(manifest, null, 2) + "\n", {
      mode: 0o600,
      flag: "wx",
    });
    await rename(pointer, join(dir, "latest.json"));
  } finally {
    await unlink(pointer).catch((e) => {
      if (e.code !== "ENOENT") throw e;
    });
  }
  return manifest;
}
if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const args = process.argv.slice(2),
    value = (name) => {
      const i = args.indexOf(name);
      return i < 0 ? null : args[i + 1];
    };
  const source = value("--source"),
    dataDir = value("--data-dir") || process.env.DATA_DIR || "/data";
  if (!source)
    throw new Error(
      "Usage: node vps/scripts/install-android-update.mjs --source DIRECTORY [--data-dir /data]",
    );
  const result = await installAndroidUpdate(resolve(source), resolve(dataDir));
  console.log(
    `Published Peygir ${result.versionName} (${result.versionCode}) at /updates/android/latest.json`,
  );
}

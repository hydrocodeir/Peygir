import { readFileSync, statSync, createReadStream } from "node:fs";
import { join } from "node:path";
import { Readable } from "node:stream";
import { ApiError, json } from "../../cloudflare/src/worker.mjs";

export function updateManifest(p) {
  const valid =
    p &&
    p.packageName === "ir.peygir.app" &&
    Number.isInteger(p.versionCode) &&
    p.versionCode > 0 &&
    p.versionCode <= 2147483647 &&
    /^\d+\.\d+\.\d+$/.test(p.versionName) &&
    Number.isSafeInteger(p.size) &&
    p.size > 0 &&
    p.size <= 1073741824 &&
    /^[a-f0-9]{64}$/.test(p.sha256) &&
    Number.isInteger(p.minSdk) &&
    p.minSdk >= 26 &&
    p.minSdk <= 100 &&
    p.apk === `/updates/android/peygir-${p.versionCode}.apk` &&
    typeof p.notes === "string" &&
    p.notes.length <= 12000;
  if (!valid) throw new ApiError(503, "اطلاعات به‌روزرسانی معتبر نیست.");
  return {
    packageName: p.packageName,
    versionCode: p.versionCode,
    versionName: p.versionName,
    minSdk: p.minSdk,
    size: p.size,
    sha256: p.sha256,
    apk: p.apk,
    notes: p.notes,
  };
}

export function androidUpdate(request, dataDir) {
  const path = new URL(request.url).pathname;
  if (!["GET", "HEAD"].includes(request.method))
    throw new ApiError(405, "روش درخواست مجاز نیست.");
  const dir = join(dataDir, "updates", "android");
  if (path === "/updates/android/latest.json") {
    let value;
    try {
      const file = join(dir, "latest.json");
      if (statSync(file).size > 65536) throw new Error();
      value = JSON.parse(readFileSync(file, "utf8"));
    } catch {
      throw new ApiError(404, "هنوز نسخه‌ای برای به‌روزرسانی منتشر نشده است.");
    }
    const manifest = updateManifest(value);
    try {
      if (
        statSync(join(dir, `peygir-${manifest.versionCode}.apk`)).size !==
        manifest.size
      )
        throw new Error();
    } catch {
      throw new ApiError(503, "فایل به‌روزرسانی هنوز آماده نیست.");
    }
    const response = json(manifest);
    return request.method === "HEAD"
      ? new Response(null, { headers: response.headers })
      : response;
  }
  const match = path.match(/^\/updates\/android\/(peygir-[1-9]\d{0,9}\.apk)$/);
  if (!match) throw new ApiError(404, "فایل به‌روزرسانی پیدا نشد.");
  const file = join(dir, match[1]);
  let stat;
  try {
    stat = statSync(file);
    if (!stat.isFile()) throw new Error();
  } catch {
    throw new ApiError(404, "فایل به‌روزرسانی پیدا نشد.");
  }
  if (stat.size < 1 || stat.size > 1073741824)
    throw new ApiError(503, "فایل به‌روزرسانی معتبر نیست.");
  return new Response(
    request.method === "HEAD" ? null : Readable.toWeb(createReadStream(file)),
    {
      headers: {
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": String(stat.size),
        "Content-Disposition": `attachment; filename="${match[1]}"`,
      },
    },
  );
}

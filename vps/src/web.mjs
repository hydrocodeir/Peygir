import { readFileSync } from "node:fs";
import { randomUUID, createHash } from "node:crypto";
import webpush from "web-push";
import {
  ApiError,
  authenticate,
  body,
  json,
  requireValue,
  ID,
  getRecord,
} from "../../cloudflare/src/worker.mjs";
import { visible } from "../../cloudflare/src/organization.mjs";
import { equal, companyCode } from "./registry.mjs";

const hash = (value) => createHash("sha256").update(value).digest("hex");
const cookiePrefix = "peygir_web_";
const DAY = 86400000;
const allowedPushHost = (host) =>
  host === "web.push.apple.com" ||
  host.endsWith(".push.apple.com") ||
  host === "fcm.googleapis.com" ||
  host === "updates.push.services.mozilla.com" ||
  host.endsWith(".notify.windows.com");
export function subscriptionValue(s) {
  requireValue(
    s &&
      typeof s.endpoint === "string" &&
      s.endpoint.length <= 4096 &&
      s.keys &&
      typeof s.keys.p256dh === "string" &&
      typeof s.keys.auth === "string",
  );
  let u;
  try {
    u = new URL(s.endpoint);
  } catch {
    throw new ApiError(400, "نشانی سرویس اعلان معتبر نیست");
  }
  requireValue(
    u.protocol === "https:" &&
      !u.username &&
      !u.password &&
      !u.hash &&
      (!u.port || u.port === "443") &&
      allowedPushHost(u.hostname),
    "نشانی سرویس اعلان مجاز نیست",
  );
  requireValue(
    /^[\w-]+={0,2}$/.test(s.keys.p256dh) && /^[\w-]+={0,2}$/.test(s.keys.auth),
  );
  const key = Buffer.from(s.keys.p256dh, "base64url"),
    auth = Buffer.from(s.keys.auth, "base64url");
  requireValue(
    key.length === 65 && key[0] === 4 && auth.length === 16,
    "کلید اعلان معتبر نیست",
  );
  return {
    endpoint: u.toString(),
    expirationTime: null,
    keys: { p256dh: s.keys.p256dh, auth: s.keys.auth },
  };
}
export function webPreferences(value = {}, role = "manager") {
  const p = {
    enabled: role !== "assistant",
    daily: true,
    time: "08:00",
    meetingLead: -1,
    updates: "all",
    ...value,
  };
  requireValue(
    Object.keys(p).every((k) =>
      ["enabled", "daily", "time", "meetingLead", "updates"].includes(k),
    ) &&
      typeof p.enabled === "boolean" &&
      typeof p.daily === "boolean" &&
      /^([01]\d|2[0-3]):[0-5]\d$/.test(p.time) &&
      [-1, 0, 5, 10, 15, 30, 60, 1440].includes(p.meetingLead) &&
      ["all", "essential"].includes(p.updates),
    "تنظیم یادآوری معتبر نیست",
  );
  return p;
}
const dateParts = (at, timeZone = "Asia/Tehran") =>
  Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(at)
      .filter((x) => x.type !== "literal")
      .map((x) => [x.type, Number(x.value)]),
  );
export function officeDay(at = Date.now()) {
  const p = dateParts(at);
  return Math.floor(Date.UTC(p.year, p.month - 1, p.day) / DAY);
}
export function officeTime(day, minute) {
  const target = day * DAY + minute * 60000;
  let value = target;
  for (let i = 0; i < 3; i++) {
    const p = dateParts(value);
    const rendered = Date.UTC(
      p.year,
      p.month - 1,
      p.day,
      p.hour,
      p.minute,
      p.second,
    );
    value += target - rendered;
  }
  return value;
}
const titleFor = (kind) =>
  ({
    invitation: "به جلسه دعوت شده‌اید",
    assignment: "مصوبه‌ای به شما واگذار شد",
    review: "گزارش انجام مصوبه برای بررسی",
    meeting: "برنامهٔ جلسه به‌روز شد",
    daily: "مرور مصوبات امروز",
    reminder: "زمان پیگیری مصوبه",
    meetingReminder: "یادآوری جلسه",
  })[kind] || "مصوبه به‌روز شد";
const staticTypes = {
  html: "text/html; charset=utf-8",
  js: "text/javascript; charset=utf-8",
  mjs: "text/javascript; charset=utf-8",
  css: "text/css; charset=utf-8",
  svg: "image/svg+xml",
  png: "image/png",
  webmanifest: "application/manifest+json",
};
const staticFiles = new Set([
  "index.html",
  "app.js",
  "core.mjs",
  "store.mjs",
  "style.css",
  "sw.js",
  "manifest.webmanifest",
  "icon.svg",
  "icon-192.png",
  "icon-512.png",
  "apple-touch-icon.png",
]);

export function createWebApplication({
  registry,
  origin,
  proxy,
  send = webpush.sendNotification,
  now = Date.now,
}) {
  const db = registry.db,
    secure = origin.startsWith("https:");
  db.exec(`CREATE TABLE IF NOT EXISTS web_profiles(id TEXT PRIMARY KEY,company_id TEXT NOT NULL REFERENCES companies(id),user_id TEXT NOT NULL,token_hash TEXT NOT NULL,expires INTEGER NOT NULL,settings TEXT NOT NULL,created INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS web_profiles_user ON web_profiles(company_id,user_id);
    CREATE TABLE IF NOT EXISTS web_subscriptions(profile_id TEXT PRIMARY KEY REFERENCES web_profiles(id) ON DELETE CASCADE,endpoint_hash TEXT NOT NULL,value TEXT NOT NULL,created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS web_receipts(profile_id TEXT NOT NULL REFERENCES web_profiles(id) ON DELETE CASCADE,event_id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(profile_id,event_id));
    CREATE TABLE IF NOT EXISTS web_vapid(id INTEGER PRIMARY KEY CHECK(id=1),public_key TEXT NOT NULL,private_key TEXT NOT NULL);`);
  if (!db.prepare("SELECT 1 FROM web_vapid").get()) {
    const keys = webpush.generateVAPIDKeys();
    db.prepare("INSERT INTO web_vapid VALUES(1,?,?)").run(
      keys.publicKey,
      keys.privateKey,
    );
  }
  const keys = db.prepare("SELECT * FROM web_vapid WHERE id=1").get();
  const options = {
    vapidDetails: {
      subject: origin.startsWith("http:")
        ? "mailto:admin@example.invalid"
        : origin,
      publicKey: keys.public_key,
      privateKey: keys.private_key,
    },
    TTL: 3600,
    urgency: "high",
    timeout: 7000,
  };
  const inFlight = new Set();
  const cookies = (request) =>
    new Map(
      (request.headers.get("cookie") || "")
        .split(";")
        .map((s) => {
          const i = s.indexOf("=");
          return [s.slice(0, i).trim(), s.slice(i + 1)];
        })
        .filter(
          ([k]) =>
            k.startsWith(cookiePrefix) && ID.test(k.slice(cookiePrefix.length)),
        ),
    );
  const cookie = (id, token) =>
    `${cookiePrefix}${id}=${token}; Path=/app/api; HttpOnly; SameSite=Strict; Max-Age=${token ? 90 * 86400 : 0}${secure ? "; Secure" : ""}`;
  const csrf = (token) => hash("peygir-web-csrf-v1|" + token);
  function checkOrigin(request) {
    if (request.method !== "GET" && request.headers.get("origin") !== origin)
      throw new ApiError(403, "درخواست باید از صفحهٔ خود پیگیر ارسال شود");
  }
  async function auth(request, id, mutate = false) {
    requireValue(ID.test(id));
    const p = db
      .prepare("SELECT * FROM web_profiles WHERE id=? AND expires>?")
      .get(id, now());
    const token = cookies(request).get(cookiePrefix + id);
    if (!p || !token || !equal(hash(token), p.token_hash))
      throw new ApiError(401, "نشست ورود پایان یافته است");
    if (
      mutate &&
      !equal(request.headers.get("x-csrf-token") || "", csrf(token))
    )
      throw new ApiError(403, "صفحه را تازه کنید و دوباره تلاش کنید");
    const c = registry.byId(p.company_id);
    if (!c?.active) throw new ApiError(401, "شرکت غیرفعال است");
    const lease = registry.lease(c);
    try {
      const user = await authenticate(
        new Request(origin + "/v2/config", {
          headers: { authorization: "Bearer " + token },
        }),
        { DB: lease.d1 },
      );
      if (user.id !== p.user_id)
        throw new ApiError(401, "حساب ورود همخوان نیست");
      return { p, c, user, token };
    } finally {
      lease.release();
    }
  }
  const publicProfile = ({ p, c, user, token }) => ({
    id: p.id,
    company: c.code,
    companyName: c.name,
    name: user.name,
    role: user.role,
    userId: user.id,
    workspace: user.workspace,
    expires: p.expires,
    preferences: webPreferences(JSON.parse(p.settings), user.role),
    csrf: csrf(token),
  });
  async function route(request, ip) {
    const url = new URL(request.url),
      path = url.pathname;
    if (path === "/app")
      return new Response(null, {
        status: 302,
        headers: { Location: "/app/" },
      });
    if (
      request.method === "GET" &&
      path.startsWith("/app/") &&
      !path.startsWith("/app/api/")
    ) {
      const file = path === "/app/" ? "index.html" : path.slice("/app/".length);
      if (file === "font.ttf")
        return new Response(
          readFileSync(
            new URL(
              "../../app/src/main/res/font/vazirmatn_regular.ttf",
              import.meta.url,
            ),
          ),
          { headers: { "Content-Type": "font/ttf" } },
        );
      if (!staticFiles.has(file)) throw new ApiError(404, "صفحه پیدا نشد");
      const headers = { "Content-Type": staticTypes[file.split(".").at(-1)] };
      if (file === "sw.js") headers["Service-Worker-Allowed"] = "/app/";
      return new Response(
        readFileSync(new URL("../web/" + file, import.meta.url)),
        { headers },
      );
    }
    checkOrigin(request);
    if (path === "/app/api/push-config" && request.method === "GET")
      return json({
        publicKey: keys.public_key,
        timeZone: "Asia/Tehran",
        version: "4.7.0",
      });
    if (path === "/app/api/accounts" && request.method === "GET") {
      const profiles = [];
      for (const key of [...cookies(request).keys()].slice(0, 32)) {
        try {
          profiles.push(
            publicProfile(await auth(request, key.slice(cookiePrefix.length))),
          );
        } catch (e) {
          if (!(e instanceof ApiError) || ![401, 403].includes(e.status))
            throw e;
        }
      }
      return json({ profiles });
    }
    if (path === "/app/api/login" && request.method === "POST") {
      const b = await body(request, 8192);
      requireValue(
        companyCode(b.company) &&
          typeof b.username === "string" &&
          typeof b.password === "string",
      );
      const response = await proxy(
        b.company,
        null,
        "/v2/login",
        { username: b.username, password: b.password },
        ip,
      );
      const value = await response.json();
      if (!response.ok) return json(value, response.status);
      const c = registry.find(b.company),
        tokenHash = hash(value.token);
      const old = [...cookies(request)]
        .map(([key, token]) => ({
          p: db
            .prepare("SELECT * FROM web_profiles WHERE id=?")
            .get(key.slice(cookiePrefix.length)),
          token,
        }))
        .find(
          (x) =>
            x.p?.company_id === c.id &&
            x.p?.user_id === value.userId &&
            hash(x.token) === x.p.token_hash,
        );
      requireValue(
        old || cookies(request).size < 32,
        "حداکثر ۳۲ حساب را می‌توانید در این مرورگر نگه دارید",
      );
      const id = old?.p.id || randomUUID(),
        expires = now() + 90 * DAY,
        prefs = old
          ? webPreferences(JSON.parse(old.p.settings), value.role)
          : webPreferences({}, value.role);
      db.prepare(
        "INSERT INTO web_profiles(id,company_id,user_id,token_hash,expires,settings,created) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash,expires=excluded.expires",
      ).run(
        id,
        c.id,
        value.userId,
        tokenHash,
        expires,
        JSON.stringify(prefs),
        now(),
      );
      if (old) await proxy(c.code, old.token, "/v1/logout", {}, ip);
      const p = db.prepare("SELECT * FROM web_profiles WHERE id=?").get(id);
      const result = json({
        profile: publicProfile({
          p,
          c,
          user: {
            id: value.userId,
            name: value.name,
            role: value.role,
            workspace: value.workspace,
          },
          token: value.token,
        }),
      });
      result.headers.set("Set-Cookie", cookie(id, value.token));
      return result;
    }
    const m = path.match(
      /^\/app\/api\/profiles\/([a-f0-9-]{36})\/(sync|users|logout|push|preferences)$/,
    );
    if (!m || request.method !== "POST")
      throw new ApiError(404, "مسیر پیدا نشد");
    const { p, c, user, token } = await auth(request, m[1], true),
      action = m[2],
      b = await body(request, 4 * 1024 * 1024);
    if (action === "logout") {
      const response = await proxy(c.code, token, "/v1/logout", {}, ip);
      db.prepare("DELETE FROM web_profiles WHERE id=?").run(p.id);
      const result = json({ ok: true });
      result.headers.set("Set-Cookie", cookie(p.id, ""));
      return result;
    }
    if (action === "sync" || action === "users")
      return proxy(
        c.code,
        token,
        action === "sync" ? "/v2/sync" : "/v2/users",
        b,
        ip,
      );
    if (action === "preferences" || action === "push") {
      const preferences = webPreferences(
        b.preferences ?? JSON.parse(p.settings),
        user.role,
      );
      if (action === "push") {
        const subscription = subscriptionValue(b.subscription);
        db.prepare(
          "INSERT INTO web_subscriptions(profile_id,endpoint_hash,value,created) VALUES(?,?,?,?) ON CONFLICT(profile_id) DO UPDATE SET endpoint_hash=excluded.endpoint_hash,value=excluded.value",
        ).run(
          p.id,
          hash(subscription.endpoint),
          JSON.stringify(subscription),
          now(),
        );
      }
      db.prepare("UPDATE web_profiles SET settings=? WHERE id=?").run(
        JSON.stringify(preferences),
        p.id,
      );
      return json({ ok: true, preferences });
    }
  }
  function activeProfiles(c, lease, userId = null) {
    const rows = db
      .prepare(
        "SELECT p.*,s.value AS subscription,s.created AS subscribed FROM web_profiles p JOIN web_subscriptions s ON s.profile_id=p.id WHERE p.company_id=? AND p.expires>?" +
          (userId ? " AND p.user_id=?" : ""),
      )
      .all(c.id, now(), ...(userId ? [userId] : []));
    return rows
      .map((p) => ({
        p,
        user: lease.db
          .prepare(
            "SELECT u.id,u.workspace,u.name,COALESCE(u.access_role,u.role) AS role FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires>? AND u.active=1 AND u.id=?",
          )
          .get(p.token_hash, now(), p.user_id),
      }))
      .filter((x) => x.user);
  }
  async function enqueueScheduled(lease, c) {
    const at = now(),
      today = officeDay(at);
    const records = lease.db
      .prepare(
        "SELECT * FROM records WHERE deleted=0 AND kind IN ('meeting','task')",
      )
      .all()
      .map((r) => ({ ...r, payload: JSON.parse(r.payload) }));
    for (const { p, user } of activeProfiles(c, lease)) {
      const pref = webPreferences(JSON.parse(p.settings), user.role);
      if (!pref.enabled) continue;
      const allowed = [];
      for (const r of records)
        if (await visible({ DB: lease.d1 }, user, r)) allowed.push(r);
      function enqueue(kind, recordId, key, meta) {
        const id = p.id + "|" + key;
        if (lease.db.prepare("SELECT 1 FROM web_scheduled WHERE key=?").get(id))
          return;
        lease.db.exec("BEGIN IMMEDIATE");
        try {
          lease.db
            .prepare("INSERT OR IGNORE INTO web_scheduled VALUES(?,?)")
            .run(id, at);
          lease.db
            .prepare(
              "INSERT OR IGNORE INTO web_push_outbox(id,workspace,user_id,record_id,kind,profile_id,meta,created) VALUES(?,?,?,?,?,?,?,?)",
            )
            .run(
              id,
              user.workspace,
              user.id,
              recordId,
              kind,
              p.id,
              JSON.stringify(meta),
              at,
            );
          lease.db.exec("COMMIT");
        } catch (e) {
          lease.db.exec("ROLLBACK");
          throw e;
        }
      }
      const minute = pref.time.split(":").map(Number),
        daily = officeTime(today, minute[0] * 60 + minute[1]);
      if (pref.daily && daily <= at && daily >= p.subscribed - 60000)
        enqueue("daily", "", `daily:${today}`, { day: today });
      for (const r of allowed) {
        if (r.kind === "task" && r.payload.status !== "DONE")
          for (const time of r.payload.reminders || [])
            if (time <= at && time >= Math.max(at - DAY, p.subscribed - 60000))
              enqueue("reminder", r.id, `task:${r.id}:${time}`, { at: time });
        if (
          r.kind === "meeting" &&
          pref.meetingLead >= 0 &&
          r.payload.start !== null &&
          r.payload.end !== null
        ) {
          const time =
            officeTime(r.payload.date, r.payload.start) -
            pref.meetingLead * 60000;
          if (
            time <= at &&
            time >= Math.max(at - 15 * 60000, p.subscribed - 60000) &&
            officeTime(r.payload.date, r.payload.end) > at
          )
            enqueue(
              "meetingReminder",
              r.id,
              `meeting:${r.id}:${r.payload.date}:${r.payload.start}:${pref.meetingLead}`,
              {
                day: r.payload.date,
                start: r.payload.start,
                lead: pref.meetingLead,
              },
            );
        }
      }
    }
    lease.db
      .prepare("DELETE FROM web_scheduled WHERE created<?")
      .run(at - 100 * DAY);
  }
  async function flushCompany(lease, c, { scheduled = false } = {}) {
    if (!c.active || inFlight.has(c.id)) return;
    inFlight.add(c.id);
    try {
      if (scheduled) await enqueueScheduled(lease, c);
      const rows = lease.db
        .prepare(
          "SELECT * FROM web_push_outbox WHERE next_attempt<=? ORDER BY created,id LIMIT 50",
        )
        .all(now());
      for (const event of rows) {
        const record = event.record_id
            ? await getRecord(
                { DB: lease.d1 },
                event.workspace,
                event.record_id,
              )
            : null,
          meta = JSON.parse(event.meta);
        let retry = false;
        const profiles = activeProfiles(c, lease, event.user_id).filter(
          (x) => !event.profile_id || x.p.id === event.profile_id,
        );
        for (const { p, user } of profiles) {
          if (
            db
              .prepare(
                "SELECT 1 FROM web_receipts WHERE profile_id=? AND event_id=?",
              )
              .get(p.id, event.id)
          )
            continue;
          const pref = webPreferences(JSON.parse(p.settings), user.role);
          if (
            !pref.enabled ||
            (pref.updates === "essential" &&
              ["task", "meeting"].includes(event.kind))
          )
            continue;
          if (
            event.kind !== "daily" &&
            (!record ||
              record.deleted ||
              !(await visible({ DB: lease.d1 }, user, record)))
          )
            continue;
          if (
            event.kind === "daily" &&
            (!pref.daily || meta.day !== officeDay(now()))
          )
            continue;
          if (
            event.kind === "reminder" &&
            (record.payload.status === "DONE" ||
              !record.payload.reminders?.includes(meta.at))
          )
            continue;
          if (
            event.kind === "meetingReminder" &&
            (pref.meetingLead !== meta.lead ||
              record.payload.date !== meta.day ||
              record.payload.start !== meta.start ||
              officeTime(meta.day, record.payload.end) <= now())
          )
            continue;
          const payload = {
            title: c.name + " - " + titleFor(event.kind),
            body: "برای مشاهده و پیگیری، پیگیر را باز کنید",
            tag: p.id + ":" + event.id,
            data: {
              profileId: p.id,
              recordId: event.record_id,
              kind: event.kind,
              eventId: event.id,
            },
            url:
              "/app/?account=" +
              encodeURIComponent(p.id) +
              (event.record_id
                ? "&record=" + encodeURIComponent(event.record_id)
                : ""),
          };
          try {
            await send(
              JSON.parse(p.subscription),
              JSON.stringify(payload),
              options,
            );
            db.prepare("INSERT OR IGNORE INTO web_receipts VALUES(?,?,?)").run(
              p.id,
              event.id,
              now(),
            );
          } catch (e) {
            if (e.statusCode === 404 || e.statusCode === 410)
              db.prepare(
                "DELETE FROM web_subscriptions WHERE endpoint_hash=?",
              ).run(hash(JSON.parse(p.subscription).endpoint));
            else retry = true;
          }
        }
        if (retry && event.attempts < 8)
          lease.db
            .prepare(
              "UPDATE web_push_outbox SET attempts=attempts+1,next_attempt=? WHERE id=?",
            )
            .run(
              now() + Math.min(3600000, 30000 * 2 ** event.attempts),
              event.id,
            );
        else
          lease.db
            .prepare("DELETE FROM web_push_outbox WHERE id=?")
            .run(event.id);
      }
      db.prepare("DELETE FROM web_receipts WHERE created<?").run(
        now() - 100 * DAY,
      );
      db.prepare("DELETE FROM web_profiles WHERE expires<?").run(now());
    } finally {
      inFlight.delete(c.id);
    }
  }
  return { route, flushCompany };
}

import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID, createECDH, randomBytes } from "node:crypto";
import { mkdtempSync, mkdirSync, rmSync } from "node:fs";
import { join, resolve, sep } from "node:path";
import { createApp } from "../src/app.mjs";
import { midpoint } from "../../cloudflare/src/deadline.mjs";
import { makeBackup, restoreBackup } from "../src/maintenance.mjs";
import {
  subscriptionValue,
  webPreferences,
  officeTime,
  officeDay,
} from "../src/web.mjs";
const origin = "https://peygir.test",
  password = "synthetic-test-password-123",
  root = resolve(import.meta.dirname, "../../.local-build/pwa-tests");
mkdirSync(root, { recursive: true });
const op = (kind, payload, recordId = randomUUID(), baseRevision = 0) => ({
  id: randomUUID(),
  recordId,
  kind,
  payload,
  baseRevision,
  deleted: false,
});
const task = (title, assignee = null) => ({
  title,
  description: "",
  owner: "احمدی",
  due: 20755,
  priority: "NORMAL",
  status: "WAITING",
  meeting: null,
  reminders: [],
  assignee,
  submitted: 0,
  approved: 0,
  approvedBy: null,
});
const syncBody = (operations = [], inventory = []) => ({
  protocol: 2,
  cursor: 0,
  operations,
  inventory,
});
async function setup(t, options = {}) {
  const dir = mkdtempSync(join(root, "web-"));
  const sent = [];
  let at = Date.now();
  const app = await createApp({
    dataDir: dir,
    publicOrigin: origin,
    bootstrap: { username: "owner", password },
    sendWebPush: async (s, p, o) => {
      sent.push({ subscription: s, payload: JSON.parse(p), options: o });
    },
    clock: () => at,
    ...options,
  });
  let closed = false;
  async function close() {
    if (!closed) {
      closed = true;
      await app.close();
    }
  }
  t.after(async () => {
    await close();
    assert.ok(resolve(dir).startsWith(root + sep));
    rmSync(dir, { recursive: true, force: true });
  });
  return { app, sent, dir, close, time: (n) => (at = n) };
}
async function call(
  app,
  path,
  { body, cookie, csrf, company, token, requestOrigin = origin } = {},
) {
  const r = await app.fetch(
    new Request(origin + path, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        origin: requestOrigin,
        "content-type": "application/json",
        ...(cookie ? { cookie } : {}),
        ...(csrf ? { "x-csrf-token": csrf } : {}),
        ...(company ? { "x-peygir-company": company } : {}),
        ...(token ? { authorization: "Bearer " + token } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }),
  );
  const text = await r.text();
  return {
    status: r.status,
    headers: r.headers,
    text,
    data: r.headers.get("content-type")?.includes("application/json")
      ? JSON.parse(text)
      : null,
  };
}
async function company(app, code = "company-x") {
  return (
    await app.registry.create({
      code,
      name: "شرکت " + code,
      managerUsername: "manager",
      managerName: "رئیس",
      managerPassword: password,
    })
  ).company;
}
async function login(app, code = "company-x", username = "manager", cookie) {
  const r = await call(app, "/app/api/login", {
    body: { company: code, username, password },
    cookie,
  });
  assert.equal(r.status, 200, r.text);
  return {
    p: r.data.profile,
    cookie: r.headers.get("set-cookie").split(";")[0],
    raw: r,
  };
}
const web = (app, a, action, body = {}) =>
  call(app, `/app/api/profiles/${a.p.id}/${action}`, {
    body,
    cookie: a.cookie,
    csrf: a.p.csrf,
  });
async function user(app, a, name = "deputy", role = "deputy") {
  const id = randomUUID(),
    r = await web(app, a, "users", {
      action: "create",
      id,
      name: "نام " + name,
      username: name,
      password,
      role,
    });
  assert.equal(r.status, 200, r.text);
  return id;
}
function subscription() {
  const e = createECDH("prime256v1");
  e.generateKeys();
  return {
    endpoint: "https://web.push.apple.com/" + randomUUID(),
    keys: {
      p256dh: e.getPublicKey().toString("base64url"),
      auth: randomBytes(16).toString("base64url"),
    },
  };
}
async function settle(app) {
  await new Promise((r) => setTimeout(r, 15));
  await app.sweep();
}
test("PWA assets, manifest and cookie login expose no native bearer, with strict Origin/CSRF", async (t) => {
  const { app } = await setup(t);
  await company(app);
  for (const path of [
    "/app/",
    "/app/app.js",
    "/app/sw.js",
    "/app/font.ttf",
    "/app/icon-512.png",
  ]) {
    const r = await call(app, path);
    assert.equal(r.status, 200, path);
    assert.equal(r.headers.get("cache-control"), "no-store");
    assert.match(r.headers.get("content-security-policy"), /worker-src 'self'/);
  }
  const manifest = JSON.parse(
    (await call(app, "/app/manifest.webmanifest")).text,
  );
  assert.equal(manifest.scope, "/app/");
  assert.equal(manifest.display, "standalone");
  const a = await login(app);
  assert.equal(a.p.role, "manager");
  assert.equal("token" in a.p, false);
  assert.match(
    a.raw.headers.get("set-cookie"),
    /HttpOnly; SameSite=Strict.*Secure/,
  );
  assert.equal(
    (
      await call(app, "/app/api/login", {
        body: { company: "company-x", username: "manager", password },
        requestOrigin: "https://evil.test",
      })
    ).status,
    403,
  );
  assert.equal(
    (
      await call(app, `/app/api/profiles/${a.p.id}/sync`, {
        body: syncBody(),
        cookie: a.cookie,
      })
    ).status,
    403,
  );
  assert.equal((await web(app, a, "sync", syncBody())).status, 200);
  assert.equal(
    (await call(app, "/app/api/accounts", { cookie: a.cookie })).data.profiles
      .length,
    1,
  );
  assert.equal((await call(app, "/app/not-allowed")).status, 404);
});
test("company profiles with overlapping UUIDs stay isolated and logging out one keeps the other", async (t) => {
  const { app } = await setup(t);
  await company(app);
  await company(app, "company-y");
  const a = await login(app),
    b = await login(app, "company-y", "manager", a.cookie),
    id = randomUUID();
  const cookies = a.cookie + "; " + b.cookie;
  await web(
    app,
    { ...a, cookie: cookies },
    "sync",
    syncBody([op("task", task("PRIVATE X"), id)]),
  );
  await web(
    app,
    { ...b, cookie: cookies },
    "sync",
    syncBody([op("task", task("PRIVATE Y"), id)]),
  );
  const ra = await web(app, { ...a, cookie: cookies }, "sync", syncBody()),
    rb = await web(app, { ...b, cookie: cookies }, "sync", syncBody());
  assert.match(ra.text, /PRIVATE X/);
  assert.doesNotMatch(ra.text, /PRIVATE Y/);
  assert.match(rb.text, /PRIVATE Y/);
  assert.equal(
    (await web(app, { ...a, cookie: b.cookie }, "sync", syncBody())).status,
    401,
  );
  await web(app, a, "logout");
  assert.equal((await web(app, a, "sync", syncBody())).status, 401);
  assert.equal((await web(app, b, "sync", syncBody())).status, 200);
});
test("deputy web sessions read only assigned tasks/invitations and submit without approving", async (t) => {
  const { app } = await setup(t);
  await company(app);
  const chief = await login(app),
    id = await user(app, chief),
    d = await login(app, "company-x", "deputy");
  const mine = op("task", task("MINE", id));
  await web(
    app,
    chief,
    "sync",
    syncBody([mine, op("task", task("CHIEF PRIVATE"))]),
  );
  const read = await web(app, d, "sync", syncBody());
  assert.match(read.text, /MINE/);
  assert.doesNotMatch(read.text, /CHIEF PRIVATE/);
  const submit = op(
      "task",
      { ...mine.payload, status: "REVIEW" },
      mine.recordId,
      1,
    ),
    submitted = await web(app, d, "sync", syncBody([submit]));
  assert.equal(submitted.data.results[0].ok, true);
  assert.equal(
    (
      await web(
        app,
        d,
        "sync",
        syncBody([
          op(
            "task",
            { ...submitted.data.results[0].record.payload, status: "DONE" },
            mine.recordId,
            2,
          ),
        ]),
      )
    ).status,
    400,
  );
  assert.equal((await web(app, d, "users", { action: "list" })).status, 403);
});
test("relogin reuses a cookie profile but rotates the session and rejects its old CSRF", async (t) => {
  const { app } = await setup(t);
  await company(app);
  const a = await login(app),
    b = await login(app, "company-x", "manager", a.cookie);
  assert.equal(a.p.id, b.p.id);
  assert.notEqual(a.p.csrf, b.p.csrf);
  assert.equal((await web(app, a, "sync", syncBody())).status, 401);
  assert.equal(
    (
      await web(
        app,
        { ...b, p: { ...b.p, csrf: a.p.csrf } },
        "sync",
        syncBody(),
      )
    ).status,
    403,
  );
  assert.equal((await web(app, b, "sync", syncBody())).status, 200);
});
test("deleted users and suspended companies lose web access and web push eligibility immediately", async (t) => {
  const { app, sent } = await setup(t);
  const c = await company(app),
    chief = await login(app),
    uid = await user(app, chief),
    d = await login(app, "company-x", "deputy");
  await web(app, d, "push", { subscription: subscription() });
  await web(app, chief, "users", { action: "delete", id: uid });
  assert.equal((await web(app, d, "sync", syncBody())).status, 401);
  app.registry.update(c.id, { name: c.name, active: false });
  assert.equal((await web(app, chief, "sync", syncBody())).status, 401);
  await app.sweep();
  assert.equal(sent.length, 0);
});
test("Web Push endpoints reject SSRF and malformed keys; preferences validate independently", () => {
  const good = subscription();
  assert.deepEqual(subscriptionValue(good).keys, good.keys);
  for (const endpoint of [
    "http://127.0.0.1/x",
    "https://127.0.0.1/x",
    "https://web.push.apple.com.evil.test/x",
    "https://user:password@web.push.apple.com/x",
    "https://web.push.apple.com:8443/x",
  ])
    assert.throws(() => subscriptionValue({ ...good, endpoint }));
  assert.throws(() =>
    subscriptionValue({ ...good, keys: { ...good.keys, auth: "x" } }),
  );
  assert.equal(webPreferences({}, "assistant").enabled, false);
  assert.throws(() => webPreferences({ time: "25:00" }));
  assert.throws(() => webPreferences({ bogus: true }));
  assert.equal(webPreferences().progressPercent, 50);
  assert.equal(webPreferences().progressEnabled, true);
  for (const invalid of [
    { progressPercent: 0 },
    { progressPercent: 100 },
    { progressPercent: 50.5 },
    { progressEnabled: 1 },
    { taskReminders: "false" },
  ])
    assert.throws(() => webPreferences(invalid));
});
test("native Android mutations deliver company-scoped web invitations without Firebase", async (t) => {
  const { app, sent } = await setup(t);
  await company(app);
  const chief = await login(app),
    uid = await user(app, chief),
    d = await login(app, "company-x", "deputy");
  await web(app, d, "push", {
    subscription: subscription(),
    preferences: { daily: false },
  });
  const native = await call(app, "/v2/login", {
      company: "company-x",
      body: { username: "manager", password },
    }),
    record = op("task", task("SECRET TASK TITLE", uid));
  const r = await call(app, "/v2/sync", {
    company: "company-x",
    token: native.data.token,
    body: syncBody([record]),
  });
  assert.equal(r.status, 200);
  await settle(app);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].payload.data.profileId, d.p.id);
  assert.equal(sent[0].payload.data.kind, "assignment");
  assert.doesNotMatch(JSON.stringify(sent[0].payload), /SECRET TASK TITLE/);
  await call(app, "/v2/sync", {
    company: "company-x",
    token: native.data.token,
    body: syncBody([record]),
  });
  await settle(app);
  assert.equal(sent.length, 1);
});
test("one browser endpoint can receive separate profiles, with independent settings", async (t) => {
  const { app, sent } = await setup(t);
  await company(app);
  const a = await login(app),
    uid = await user(app, a),
    d = await login(app, "company-x", "deputy"),
    sub = subscription();
  await web(app, a, "push", {
    subscription: sub,
    preferences: { daily: false },
  });
  await web(app, d, "push", {
    subscription: sub,
    preferences: { enabled: false, daily: false },
  });
  await web(app, a, "sync", syncBody([op("task", task("assigned", uid))]));
  await settle(app);
  assert.equal(sent.length, 0);
  await web(app, d, "preferences", {
    preferences: { enabled: true, daily: false },
  });
  await web(app, a, "sync", syncBody([op("task", task("assigned2", uid))]));
  await settle(app);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].payload.data.profileId, d.p.id);
});
test("VPS reminder and morning scheduling uses Tehran time and persists deduplication", async (t) => {
  const { app, sent, time } = await setup(t);
  await company(app);
  const a = await login(app),
    day = officeDay();
  time(officeTime(day, 470));
  await web(app, a, "push", {
    subscription: subscription(),
    preferences: { enabled: true, daily: true, time: "08:00", meetingLead: 10 },
  });
  await web(
    app,
    a,
    "sync",
    syncBody([
      op("task", { ...task("REMINDER"), reminders: [officeTime(day, 480)] }),
      op("meeting", {
        title: "MEETING",
        date: day,
        start: 490,
        end: 550,
        notes: "",
        location: "",
        attendees: [a.p.userId],
        guests: [],
        repeat: "NONE",
        until: null,
        series: null,
      }),
    ]),
  );
  await settle(app);
  assert.equal(sent.length, 0);
  time(officeTime(day, 481));
  await app.sweep();
  assert.deepEqual(sent.map((x) => x.payload.data.kind).sort(), [
    "daily",
    "meetingReminder",
    "reminder",
  ]);
  await app.sweep();
  assert.equal(sent.length, 3);
  assert.equal(officeDay(officeTime(day, 480)), day);
});
test("halfway Web Push asks only the assignee once, including essential-only notification mode", async (t) => {
  const { app, sent, time } = await setup(t);
  await company(app);
  const manager = await login(app),
    uid = await user(app, manager),
    deputy = await login(app, "company-x", "deputy");
  for (const a of [manager, deputy])
    await web(app, a, "push", {
      subscription: subscription(),
      preferences: {
        enabled: true,
        daily: false,
        meetingLead: -1,
        updates: "essential",
      },
    });
  const created = Date.now(),
    due = officeDay(created) + 2;
  const record = op("task", { ...task("HALFWAY", uid), created, due });
  await web(app, manager, "sync", syncBody([record]));
  await settle(app);
  sent.length = 0;
  const at = midpoint(record.payload);
  time(at - 1);
  await app.sweep();
  assert.equal(sent.length, 0);
  time(at);
  await app.sweep();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].payload.data.kind, "midpoint");
  assert.equal(sent[0].payload.data.profileId, deputy.p.id);
  await app.sweep();
  assert.equal(sent.length, 1);
});

test("progress preferences suppress disabled notifications and use the saved percentage per profile", async (t) => {
  const { app, sent, time } = await setup(t);
  await company(app);
  const chief = await login(app),
    uid = await user(app, chief),
    deputy = await login(app, "company-x", "deputy");
  const prefs = {
    enabled: true,
    daily: false,
    meetingLead: -1,
    updates: "off",
    progressEnabled: false,
    progressPercent: 75,
    taskReminders: false,
  };
  await web(app, deputy, "push", {
    subscription: subscription(),
    preferences: prefs,
  });
  const created = Date.now(),
    due = officeDay(created) + 2,
    record = op("task", {
      ...task("CUSTOM-PROGRESS", uid),
      created,
      due,
      reminders: [created + 60000],
    });
  await web(app, chief, "sync", syncBody([record]));
  await settle(app);
  assert.equal(sent.length, 0);
  time(midpoint(record.payload, 75) - 1);
  await app.sweep();
  assert.equal(sent.length, 0);
  await web(app, deputy, "preferences", {
    preferences: { ...prefs, progressEnabled: true },
  });
  await app.sweep();
  assert.equal(sent.length, 0);
  time(midpoint(record.payload, 75));
  await app.sweep();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].payload.data.kind, "midpoint");
  assert.match(sent[0].payload.title, /۷۵/);
  await web(app, deputy, "preferences", { preferences: prefs });
  await web(app, deputy, "preferences", {
    preferences: { ...prefs, progressEnabled: true },
  });
  await app.sweep();
  assert.equal(sent.length, 1);
  const p2 = await login(app, "company-x", "deputy");
  assert.equal(p2.p.preferences.progressPercent, 50);
  assert.equal(p2.p.preferences.progressEnabled, true);
});

test("transient push failures retry without losing the transactional web outbox", async (t) => {
  let failures = 1;
  const deliveries = [];
  const { app, time } = await setup(t, {
    sendWebPush: async (s, p) => {
      if (failures-- > 0)
        throw Object.assign(new Error("retry"), { statusCode: 503 });
      deliveries.push(JSON.parse(p));
    },
  });
  const c = await company(app),
    a = await login(app),
    uid = await user(app, a),
    d = await login(app, "company-x", "deputy");
  await web(app, d, "push", {
    subscription: subscription(),
    preferences: { daily: false },
  });
  await web(app, a, "sync", syncBody([op("task", task("retry", uid))]));
  await new Promise((r) => setTimeout(r, 15));
  assert.equal(deliveries.length, 0);
  time(Date.now() + 60000);
  await app.sweep();
  assert.equal(deliveries.length, 1);
  const l = app.registry.lease(c);
  try {
    assert.equal(
      l.db.prepare("SELECT COUNT(*) n FROM web_push_outbox").get().n,
      0,
    );
  } finally {
    l.release();
  }
});

test("expired push endpoints are removed and a reassigned task is rechecked before delivery", async (t) => {
  let status = 503,
    delivered = 0;
  const { app, time } = await setup(t, {
    sendWebPush: async () => {
      if (status)
        throw Object.assign(new Error("synthetic delivery error"), {
          statusCode: status,
        });
      delivered++;
    },
  });
  const c = await company(app),
    a = await login(app),
    uid = await user(app, a),
    d = await login(app, "company-x", "deputy");
  await web(app, d, "push", {
    subscription: subscription(),
    preferences: { daily: false },
  });
  const o = op("task", task("PRIVATE", uid));
  await web(app, a, "sync", syncBody([o]));
  await new Promise((r) => setTimeout(r, 15));
  await web(
    app,
    a,
    "sync",
    syncBody([op("task", { ...o.payload, assignee: null }, o.recordId, 1)]),
  );
  time(Date.now() + 120000);
  status = 0;
  await app.sweep();
  assert.equal(delivered, 0);
  status = 410;
  await web(app, a, "sync", syncBody([op("task", task("expired", uid))]));
  await settle(app);
  assert.equal(
    app.registry.db.prepare("SELECT COUNT(*) n FROM web_subscriptions").get().n,
    0,
  );
});
test("VAPID public configuration excludes private key material and tenant migration preserves original tables", async (t) => {
  const { app } = await setup(t);
  const c = await company(app),
    a = await login(app);
  await web(app, a, "push", {
    subscription: subscription(),
    preferences: { daily: false },
  });
  const before = app.registry.db
    .prepare("SELECT public_key FROM web_vapid")
    .get().public_key;
  assert.equal(typeof before, "string");
  const lease = app.registry.lease(c);
  try {
    assert.ok(
      lease.db
        .prepare("SELECT 1 FROM sqlite_master WHERE name='web_push_outbox'")
        .get(),
    );
    const tables = lease.db
      .prepare("SELECT name FROM sqlite_master WHERE type='table'")
      .all();
    assert.ok(tables.some((t) => t.name === "records"));
  } finally {
    lease.release();
  }
  const config = await call(app, "/app/api/push-config");
  assert.equal(config.data.publicKey, before);
  assert.equal("privateKey" in config.data, false);
  assert.doesNotMatch(config.text, /private_key/);
});

test("backup preserves VAPID identity and business data but restore revokes web sessions and pending delivery", async (t) => {
  const { app, dir, close } = await setup(t);
  await company(app);
  const a = await login(app);
  await web(app, a, "push", {
    subscription: subscription(),
    preferences: { daily: false },
  });
  await web(app, a, "sync", syncBody([op("task", task("BACKUP WEB DATA"))]));
  const key = app.registry.db
    .prepare("SELECT public_key FROM web_vapid")
    .get().public_key;
  await close();
  const backups = mkdtempSync(join(root, "backup-")),
    target = mkdtempSync(join(root, "restored-"));
  t.after(() => {
    for (const p of [backups, target]) {
      assert.ok(resolve(p).startsWith(root + sep));
      rmSync(p, { recursive: true, force: true });
    }
  });
  const backup = await makeBackup(dir, backups);
  restoreBackup(target, backup);
  const fresh = await createApp({
    dataDir: target,
    publicOrigin: origin,
    bootstrap: { username: "owner", password },
  });
  try {
    assert.equal(
      fresh.registry.db.prepare("SELECT public_key FROM web_vapid").get()
        .public_key,
      key,
    );
    assert.equal(
      fresh.registry.db.prepare("SELECT COUNT(*) n FROM web_profiles").get().n,
      0,
    );
    assert.equal(
      fresh.registry.db
        .prepare("SELECT COUNT(*) n FROM web_subscriptions")
        .get().n,
      0,
    );
    assert.equal((await web(fresh, a, "sync", syncBody())).status, 401);
    const b = await login(fresh);
    assert.match(
      (await web(fresh, b, "sync", syncBody())).text,
      /BACKUP WEB DATA/,
    );
  } finally {
    await fresh.close();
  }
});

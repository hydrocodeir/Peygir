import { test, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
const password = "synthetic-test-password-123";
async function login(page, company = "company-x", username = "manager") {
  await page.goto("/app/");
  await expect(page.locator("html")).toHaveAttribute("data-ready", "true");
  await page.locator("[name=company]").fill(company);
  await page.locator("[name=username]").fill(username);
  await page.locator("[name=password]").fill(password);
  const [loginResponse] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith("/app/api/login") && r.request().method() === "POST",
    ),
    page.getByRole("button", { name: "ورود", exact: true }).click(),
  ]);
  expect((await loginResponse.allHeaders())["set-cookie"]).toContain(
    "SameSite=Strict",
  );
  await expect(
    page.getByRole("button", { name: "انتخاب شرکت و حساب" }),
  ).toBeVisible();
  await expect
    .poll(
      () =>
        page.evaluate(async () => {
          const S = await import("/app/store.mjs");
          const ps = await S.allProfiles();
          return (await S.snapshot(ps[0].id)).records.length;
        }),
      { timeout: 30000 },
    )
    .toBeGreaterThanOrEqual(username === "manager" ? 700 : 1);
}
async function data(page) {
  return page.evaluate(async () => {
    const S = await import("/app/store.mjs"),
      profiles = await S.allProfiles();
    return Promise.all(
      profiles.map(async (p) => ({ profile: p, ...(await S.snapshot(p.id)) })),
    );
  });
}
async function closeTop(page) {
  await page
    .locator("dialog[open]")
    .last()
    .getByRole("button", { name: "بستن", exact: true })
    .click();
}
async function openTasks(page) {
  await page
    .locator("nav")
    .getByRole("button", { name: "مصوبات", exact: true })
    .click();
}

test("direct meeting search shows dates, people and actions without opening a search dialog", async ({
  page,
}) => {
  await login(page);
  await page
    .getByRole("searchbox", { name: "جست‌وجوی مستقیم جلسات" })
    .fill("جلسه 200");
  const results = page.locator(".meeting-search-results");
  await expect(results.locator(".meeting-card")).toHaveCount(1);
  await expect(results).toContainText("اتاق هیئت‌مدیره");
  await expect(
    results.getByRole("button", { name: "ویرایش", exact: true }),
  ).toBeVisible();
  await expect(
    results.getByRole("button", { name: "ثبت مصوبه", exact: true }),
  ).toBeVisible();
  await expect(page.locator("dialog[open]")).toHaveCount(0);
  await results.locator(".meeting-body").click();
  await expect(page.locator("dialog .detail-data-grid")).toContainText(
    "محل یا لینک",
  );
  await expect(page.locator("dialog")).toContainText("یادداشت آزمایشی");
  await expect(page.locator("dialog details")).toHaveCount(0);
});

test("workspace keeps detailed cards and visible form fields accessible on desktop and small dark screens", async ({
  page,
}) => {
  await login(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.locator(".sidebar-profile")).toBeVisible();
  await page
    .locator(".sidebar-tools")
    .getByRole("button", { name: "مدیریت کاربران", exact: true })
    .click();
  await expect(
    page
      .locator("dialog")
      .getByRole("heading", { name: "مدیریت کاربران", exact: true }),
  ).toBeVisible();
  await closeTop(page);
  await openTasks(page);
  const card = page.locator(".task-card").first();
  await expect(card.locator(".card-excerpt")).toContainText("دادهٔ ساختگی");
  await expect(card.locator(".linked-meeting")).toBeVisible();
  await expect(
    card.getByRole("button", { name: "پیگیری", exact: true }),
  ).toBeVisible();
  await card.getByRole("button", { name: "ویرایش", exact: true }).click();
  for (const label of ["اولویت", "جلسه", "شرح"])
    await expect(
      page.locator("dialog").getByLabel(label, { exact: true }),
    ).toBeVisible();
  await expect(page.locator("dialog details")).toHaveCount(0);
  await closeTop(page);
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  for (const [width, height] of [
    [375, 812],
    [812, 375],
    [768, 1024],
  ]) {
    await page.setViewportSize({ width, height });
    await expect(page.locator(".metric-card").first()).toHaveCSS(
      "background-color",
      "rgb(28, 30, 43)",
    );
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await expect(
      page.getByRole("searchbox", { name: "جست‌وجوی مستقیم مصوبات" }),
    ).toBeVisible();
  }
});

test("chief approves an extension directly from its card while a rejection action is also visible", async ({
  page,
  request,
}, info) => {
  test.setTimeout(90000);
  const origin = "http://127.0.0.1:18748",
    loginApi = async (username) => {
      const r = await request.post("/app/api/login", {
        headers: { origin },
        data: { company: "company-x", username, password },
      });
      expect(r.ok()).toBe(true);
      return (await r.json()).profile;
    },
    manager = await loginApi("manager"),
    deputy = await loginApi("user2");
  const syncApi = async (profile, operations = []) => {
    const response = await request.post(
      `/app/api/profiles/${profile.id}/sync`,
      {
        headers: { origin, "x-csrf-token": profile.csrf },
        data: { protocol: 2, cursor: 0, operations, inventory: [] },
      },
    );
    expect(response.ok()).toBe(true);
    return response.json();
  };
  const day = Math.floor((Date.now() + 210 * 60000) / 86400000),
    id = crypto.randomUUID(),
    title = "بررسی مستقیم تمدید " + info.project.name;
  const payload = {
    title,
    owner: deputy.name,
    assignee: deputy.userId,
    due: day + 3,
    description: "تحویل گزارش مالی",
    status: "ACTIVE",
    priority: "HIGH",
    meeting: null,
    reminders: [],
    submitted: 0,
    approved: 0,
    approvedBy: null,
  };
  const original = (
    await syncApi(manager, [
      {
        id: crypto.randomUUID(),
        recordId: id,
        kind: "task",
        baseRevision: 0,
        deleted: false,
        payload,
      },
    ])
  ).results[0].record;
  const extension = {
    id: crypto.randomUUID(),
    status: "PENDING",
    progress: "گزارش اولیه آماده است",
    reason: "منتظر تأیید اطلاعات مالی",
    due: day + 7,
    baseDue: day + 3,
    requestedBy: deputy.userId,
    requestedAt: Date.now(),
    decidedBy: null,
    decidedAt: 0,
  };
  await syncApi(deputy, [
    {
      id: crypto.randomUUID(),
      recordId: id,
      kind: "task",
      baseRevision: original.revision,
      deleted: false,
      payload: { ...original.payload, extension },
    },
  ]);
  await login(page);
  await openTasks(page);
  await page.locator("[name=filter]").selectOption("extensions");
  await page
    .getByRole("searchbox", { name: "جست‌وجوی مستقیم مصوبات" })
    .fill(title);
  const card = page.locator(".task-card");
  await expect(card).toHaveCount(1);
  await expect(
    card.getByRole("button", { name: "رد تمدید", exact: true }),
  ).toBeVisible();
  await card.getByRole("button", { name: "تأیید تمدید", exact: true }).click();
  await expect(page.locator("dialog")).toContainText(extension.reason);
  await page
    .locator("dialog")
    .getByRole("button", { name: "تأیید تمدید", exact: true })
    .click();
  await expect
    .poll(async () => {
      const r = (await data(page))[0].records.find((r) => r.id === id);
      return [r.payload.due, r.payload.extension.status];
    })
    .toEqual([day + 7, "APPROVED"]);
  await expect.poll(async () => (await data(page))[0].drafts.length).toBe(0);
});

test("reservation checkbox produces yellow calendar events and prevents resolution creation", async ({
  page,
}, info) => {
  await login(page);
  await page.getByRole("button", { name: "جلسهٔ جدید", exact: true }).click();
  const form = page.locator("dialog").last(),
    title = "رزرو آزمایشی " + info.project.name;
  await form.locator("[name=title]").fill(title);
  await form.getByLabel("ثبت به‌صورت رزرو", { exact: true }).check();
  await form
    .locator(".dialog-header")
    .getByRole("button", { name: "ثبت", exact: true })
    .click();
  await expect
    .poll(
      async () =>
        (await data(page))[0].records.find((r) => r.payload.title === title)
          ?.payload.reserved,
    )
    .toBe(true);
  await page
    .getByRole("button", { name: "به‌روزرسانی اتصال و اطلاعات" })
    .click();
  await expect.poll(async () => (await data(page))[0].drafts.length).toBe(0);
  const event = page.locator(".event.reserved").filter({ hasText: title });
  await expect(event).toBeVisible();
  await expect(event).toHaveCSS("background-color", "rgb(255, 224, 130)");
  await event.click();
  await expect(
    page
      .locator("dialog")
      .getByRole("button", { name: "ثبت مصوبه", exact: true }),
  ).toHaveCount(0);
  await expect(page.locator("dialog")).toContainText("جلسه رزرو است");
  await page.screenshot({
    path: fileURLToPath(
      new URL(
        "../../../verification/reservation-" + info.project.name + ".png",
        import.meta.url,
      ),
    ),
  });
});

test("assignee can report progress and request extension without changing the current due", async ({
  page,
  request,
}, info) => {
  const origin = "http://127.0.0.1:18748";
  const session = await (
    await request.post("/app/api/login", {
      headers: { origin },
      data: { company: "company-x", username: "manager", password },
    })
  ).json();
  const profile = session.profile,
    headers = { origin, "x-csrf-token": profile.csrf };
  const directory = await (
    await request.post(`/app/api/profiles/${profile.id}/sync`, {
      headers,
      data: { protocol: 2, cursor: 0, operations: [], inventory: [] },
    })
  ).json();
  const deputy = directory.users.find((u) => u.username === "user2"),
    recordId = crypto.randomUUID(),
    due = Math.floor((Date.now() + 210 * 60000) / 86400000) + 5;
  const payload = {
    title: "درخواست اختصاصی " + info.project.name,
    owner: deputy.name,
    assignee: deputy.id,
    due,
    description: "شرح آزمون تمدید",
    status: "WAITING",
    priority: "NORMAL",
    meeting: null,
    reminders: [],
    submitted: 0,
    approved: 0,
    approvedBy: null,
  };
  const created = await request.post(`/app/api/profiles/${profile.id}/sync`, {
    headers,
    data: {
      protocol: 2,
      cursor: 0,
      operations: [
        {
          id: crypto.randomUUID(),
          recordId,
          kind: "task",
          baseRevision: 0,
          deleted: false,
          payload,
        },
      ],
      inventory: [],
    },
  });
  expect(created.ok()).toBe(true);
  await login(page, "company-x", "user2");
  await openTasks(page);
  await page.locator("[name=filter]").selectOption("WAITING");
  const snapshot = (await data(page))[0],
    original = snapshot.records.find((r) => r.id === recordId);
  await page
    .locator(".task-card")
    .filter({ hasText: original.payload.title })
    .first()
    .locator(".task-body")
    .click();
  await page
    .getByRole("button", { name: "ثبت وضعیت و نیاز به تمدید", exact: true })
    .click();
  const form = page.locator("dialog").last();
  await form
    .getByLabel("کار در چه مرحله‌ای است؟", { exact: true })
    .fill("نیمی از گزارش آماده شده است");
  await form.getByLabel("نیاز به تمدید مهلت دارم", { exact: true }).check();
  await form
    .getByLabel("دلیل نیاز به تمدید", { exact: true })
    .fill("منتظر دریافت اطلاعات مالی هستم");
  await form
    .locator(".dialog-header")
    .getByRole("button", { name: "ثبت", exact: true })
    .click();
  await expect(page.locator("dialog").last()).toContainText(
    "درخواست تمدید منتظر بررسی رئیس",
  );
  await expect
    .poll(async () => {
      const r = (await data(page))[0].records.find((r) => r.id === original.id);
      return [r.payload.due, r.payload.extension?.status];
    })
    .toEqual([original.payload.due, "PENDING"]);
  await expect(
    page.getByRole("button", { name: "تأیید تمدید", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: fileURLToPath(
      new URL(
        "../../../verification/extension-" + info.project.name + ".png",
        import.meta.url,
      ),
    ),
  });
});
test("700 records, compact lists, Persian calendar, participant suggestions reopen and forms discard", async ({
  page,
}, info) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page);
  await expect(
    page.getByRole("button", { name: "روزانه", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".time-day")).toHaveCount(1);
  await page.getByRole("button", { name: "هفتگی", exact: true }).click();
  await expect(page.locator(".time-day")).toHaveCount(7);
  expect(
    await page.locator(".timeline-scroll").evaluate((e) => e.scrollTop),
  ).toBeGreaterThan(0);
  await page.getByRole("button", { name: "جلسهٔ جدید", exact: true }).click();
  const form = page.locator("dialog").last();
  await form.locator("[name=title]").fill("فرم ذخیره‌نشده");
  const people = form.getByRole("combobox", {
    name: "شرکت‌کنندگان",
    exact: true,
  });
  await people.click();
  await expect(
    form.locator(".person-list").getByRole("option").first(),
  ).toBeVisible();
  await form
    .locator(".person-list")
    .getByRole("option")
    .filter({ hasText: "کاربر 49 احمدی" })
    .click();
  await expect(form.locator(".chips")).toContainText("کاربر 49 احمدی");
  await form.getByRole("heading").first().click();
  await people.click();
  await expect(
    form.locator(".person-list").getByRole("option").first(),
  ).toBeVisible();
  await people.fill("احمدی");
  await expect(form.locator(".person-list").getByRole("option")).toHaveCount(
    48,
  );
  await closeTop(page);
  await page.getByRole("button", { name: "جلسهٔ جدید", exact: true }).click();
  await expect(page.locator("dialog [name=title]")).toHaveValue("");
  await closeTop(page);
  await openTasks(page);
  expect(await page.locator(".task-card").count()).toBeLessThanOrEqual(100);
  await page.locator("[name=filter]").selectOption("all");
  await page
    .getByRole("searchbox", { name: "جست‌وجوی مستقیم مصوبات" })
    .fill("مصوبه 500");
  await expect(page.locator(".task-card")).toHaveCount(1);
  await expect(page.locator(".task-title")).toContainText("500");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: fileURLToPath(
      new URL(
        "../../../verification/pwa-" + info.project.name + ".png",
        import.meta.url,
      ),
    ),
  });
  expect(errors).toEqual([]);
});
test("offline creation survives reload, reconnects once and never caches account API responses", async ({
  page,
  context,
  browserName,
  request,
}) => {
  const offlineTitle = "مصوبه آفلاین " + browserName;
  await login(page);
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.waitForFunction(() => !!navigator.serviceWorker.controller);
  await openTasks(page); // WebKit offline emulation rejects even literal SW responses: microsoft/playwright#42775.
  if (browserName === "webkit")
    await request.post("/__test/network", { data: { offline: true } });
  else await context.setOffline(true);
  try {
    await page
      .getByRole("button", { name: "مصوبهٔ جدید", exact: true })
      .click();
    await page.locator("dialog [name=title]").fill(offlineTitle);
    await page.locator("dialog [name=person]").fill("مسئول بیرونی وب");
    await page
      .locator("dialog")
      .getByRole("heading", { level: 2 })
      .first()
      .click();
    await page
      .locator("dialog .dialog-header")
      .getByRole("button", { name: "ثبت", exact: true })
      .click();
    await expect.poll(async () => (await data(page))[0].drafts.length).toBe(1);
    await page.reload();
    await expect(
      page.getByRole("button", { name: "انتخاب شرکت و حساب" }),
    ).toBeVisible();
    expect(
      (await data(page))[0].records.some(
        (r) => r.payload.title === offlineTitle,
      ),
    ).toBe(true);
  } finally {
    if (browserName === "webkit")
      await request.post("/__test/network", { data: { offline: false } });
    else await context.setOffline(false);
  }
  await page
    .getByRole("button", { name: "به‌روزرسانی اتصال و اطلاعات" })
    .click();
  await expect.poll(async () => (await data(page))[0].drafts.length).toBe(0);
  const rows = (await data(page))[0].records.filter(
    (r) => r.payload.title === offlineTitle,
  );
  expect(rows).toHaveLength(1);
  expect(rows[0].revision).toBe(1);
  const cached = await page.evaluate(async () => {
    const keys = [];
    for (const n of await caches.keys())
      keys.push(...(await (await caches.open(n)).keys()).map((r) => r.url));
    return keys;
  });
  expect(cached.some((u) => u.includes("/api/"))).toBe(false);
});
test("multiple companies switch safely and notification links choose their own account", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("button", { name: "انتخاب شرکت و حساب" }).click();
  await page
    .getByRole("button", { name: "افزودن حساب شرکت", exact: true })
    .click();
  await page.locator("dialog [name=company]").fill("company-y");
  await page.locator("dialog [name=username]").fill("manager");
  await page.locator("dialog [name=password]").fill(password);
  await page
    .locator("dialog")
    .getByRole("button", { name: "ورود", exact: true })
    .click();
  await expect.poll(async () => (await data(page)).length).toBe(2);
  await expect(page.locator(".company-button")).toContainText("ایگرگ");
  const ps = await data(page),
    a = ps.find((x) => x.profile.company === "company-x"),
    r = a.records.find((r) => r.kind === "task");
  await page.goto("/app/?account=" + a.profile.id + "&record=" + r.id);
  await expect(page.locator(".company-button")).toContainText("ایکس");
  await expect(page.locator("dialog .detail-title")).toHaveText(
    r.payload.title,
  );
  const cookieNames = (await page.context().cookies()).filter((c) =>
    c.name.startsWith("peygir_web_"),
  );
  expect(cookieNames.every((c) => c.httpOnly)).toBe(true);
  expect(
    await page.evaluate(() => document.cookie.includes("peygir_web_")),
  ).toBe(false);
});
test("deputy sees only personal records and submits completion with one follow-up", async ({
  page,
}) => {
  await login(page, "company-x", "user2");
  const snapshot = (await data(page))[0],
    uid = snapshot.profile.userId;
  expect(
    snapshot.records
      .filter((r) => r.kind === "task")
      .every((r) => r.payload.assignee === uid),
  ).toBe(true);
  expect(
    snapshot.records
      .filter((r) => r.kind === "meeting")
      .every((r) => r.payload.attendees.includes(uid)),
  ).toBe(true);
  await expect(
    page.getByRole("button", { name: "جلسهٔ جدید", exact: true }),
  ).toHaveCount(0);
  await openTasks(page);
  await page.locator("[name=filter]").selectOption("WAITING");
  const card = page.locator(".task-card").first();
  await card.locator(".task-body").click();
  await page
    .locator("dialog [name=followup]")
    .fill("پیگیری در نسخه وب انجام شد");
  await page
    .locator("dialog")
    .getByRole("button", { name: "ثبت نتیجه", exact: true })
    .click();
  await expect(page.locator("dialog .note")).toContainText(
    "پیگیری در نسخه وب انجام شد",
  );
  await page
    .locator("dialog")
    .getByRole("button", { name: "ارسال برای تأیید", exact: true })
    .click();
  await page
    .locator("dialog")
    .last()
    .getByRole("button", { name: "ارسال برای تأیید", exact: true })
    .click();
  await expect.poll(async () => (await data(page))[0].drafts.length).toBe(0);
});
test("IndexedDB operations retain IDs on retry, preserve in-flight edits and revoke children", async ({
  page,
}) => {
  await page.goto("/app/");
  await expect(page.locator("html")).toHaveAttribute("data-ready", "true");
  const result = await page.evaluate(async () => {
    const S = await import("/app/store.mjs"),
      id = crypto.randomUUID(),
      taskId = crypto.randomUUID(),
      child = crypto.randomUUID(),
      p = { id, userId: "synthetic", role: "manager" };
    await S.putProfile(p);
    await S.queueChanges(id, [
      {
        id: taskId,
        kind: "task",
        payload: { title: "first", meeting: null, submitted: 0 },
      },
      { id: child, kind: "followup", payload: { task: taskId, text: "note" } },
    ]);
    const first = await S.prepare(id),
      retry = await S.prepare(id);
    await S.queueChanges(id, [
      {
        id: taskId,
        kind: "task",
        payload: { title: "second", meeting: null, submitted: 0 },
      },
    ]);
    const r = {
      id: taskId,
      kind: "task",
      revision: 1,
      payload: { title: "first", meeting: null, submitted: 42 },
      deleted: false,
    };
    await S.receive(
      id,
      {
        results: [{ opId: first[0].operation.id, ok: true, record: r }],
        changes: [],
      },
      [first[0]],
    );
    const second = await S.prepare(id),
      before = await S.snapshot(id);
    await S.receive(
      id,
      {
        results: [],
        changes: [
          {
            id: taskId,
            kind: "task",
            revision: 2,
            revoked: true,
            deleted: true,
            payload: {},
          },
        ],
      },
      [],
    );
    const after = await S.snapshot(id);
    await S.clearData(id, true);
    return {
      stable: first[0].operation.id === retry[0].operation.id,
      newId: second[0].operation.id !== first[0].operation.id,
      base: second[0].operation.baseRevision,
      title: before.records.find((r) => r.id === taskId).payload.title,
      submitted: before.records.find((r) => r.id === taskId).payload.submitted,
      remaining: after.records.length + after.drafts.length + after.ops.length,
    };
  });
  expect(result).toEqual({
    stable: true,
    newId: true,
    base: 1,
    title: "second",
    submitted: 42,
    remaining: 0,
  });
});

test("meeting creation validates times, selects accounts, persists and copies people", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("button", { name: "جلسهٔ جدید", exact: true }).click();
  let form = page.locator("dialog").last();
  await form.locator("[name=title]").fill("جلسه جدید نسخه وب");
  await form.locator("[name=start]").fill("06:00");
  await form.locator("[name=end]").fill("05:00");
  await form
    .locator(".dialog-header")
    .getByRole("button", { name: "ثبت", exact: true })
    .click();
  await expect(form.locator(".error-text")).toBeVisible();
  await form.locator("[name=end]").fill("06:30");
  const combo = form.getByRole("combobox", {
    name: "شرکت‌کنندگان",
    exact: true,
  });
  await combo.fill("کاربر 48");
  await form.locator(".person-list").getByRole("option").first().click();
  await form.getByRole("heading").first().click();
  await form
    .locator(".dialog-header")
    .getByRole("button", { name: "ثبت", exact: true })
    .click();
  await expect.poll(async () => (await data(page))[0].drafts.length).toBe(0);
  const record = (await data(page))[0].records.find(
    (r) => r.payload.title === "جلسه جدید نسخه وب",
  );
  expect(record.kind).toBe("meeting");
  expect(record.payload.attendees).toHaveLength(1);
  await page.goto(
    "/app/?account=" +
      (await data(page))[0].profile.id +
      "&record=" +
      record.id,
  );
  await page
    .locator("dialog")
    .getByRole("button", { name: "کپی جلسه" })
    .click();
  form = page.locator("dialog").last();
  await expect(form.locator("[name=title]")).toHaveValue("جلسه جدید نسخه وب");
  await expect(form.locator(".chip")).toContainText("کاربر 48 احمدی");
  await expect(form.locator("[name=start]")).toHaveValue("06:00");
});
test("failed validation does not block other queued edits and remains correctable", async ({
  page,
}) => {
  await login(page);
  const outcome = await page.evaluate(async () => {
    const S = await import("/app/store.mjs"),
      ps = await S.allProfiles(),
      p = ps[0],
      id = crypto.randomUUID(),
      good = crypto.randomUUID(),
      payload = {
        title: "valid after invalid",
        description: "",
        owner: "نام",
        due: 20755,
        priority: "NORMAL",
        status: "WAITING",
        meeting: null,
        reminders: [],
        assignee: null,
        submitted: 0,
        approved: 0,
        approvedBy: null,
      };
    await S.queueChanges(p.id, [
      { id, kind: "task", payload: { ...payload, title: "" } },
      { id: good, kind: "task", payload },
    ]);
    await S.synchronize(p);
    const first = await S.snapshot(p.id);
    await S.queueChanges(p.id, [
      { id, kind: "task", payload: { ...payload, title: "corrected invalid" } },
    ]);
    await S.synchronize(p);
    const last = await S.snapshot(p.id);
    return {
      bad: first.drafts.find((d) => d.id === id)?.conflict.reason,
      good: first.canonical.some((r) => r.id === good),
      remaining: last.drafts.length,
      corrected: last.canonical.some(
        (r) => r.id === id && r.payload.title === "corrected invalid",
      ),
    };
  });
  expect(outcome).toEqual({
    bad: "invalid",
    good: true,
    remaining: 0,
    corrected: true,
  });
});

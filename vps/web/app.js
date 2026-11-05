import * as C from "./core.mjs";
import * as S from "./store.mjs";
const root = document.querySelector("#app"),
  dialogs = document.querySelector("#dialogs"),
  toastBox = document.querySelector("#toast");
const state = {
  profiles: [],
  profile: null,
  records: [],
  drafts: [],
  tab: "meetings",
  day: C.today(),
  mode: "day",
  filter: "open",
  query: "",
  meetingQuery: "",
  limit: 50,
  busy: false,
  backoff: 0,
  theme: "system",
};
let timer,
  refreshRequested = false,
  installPrompt,
  observer,
  renderGeneration = 0,
  scrollKey = "",
  registration,
  authGeneration = 0;
const uuid = () => crypto.randomUUID();
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (
      ["checked", "disabled", "hidden", "required", "readOnly"].includes(key)
    )
      node[key] = value;
    else if (key === "value") node.value = value;
    else node.setAttribute(key, value);
  }
  for (const child of children.flat(Infinity))
    if (child !== null && child !== undefined)
      node.append(
        child instanceof Node ? child : document.createTextNode(String(child)),
      );
  return node;
}
const paths = {
  calendar:
    "M8 2v4m8-4v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14H3V6a2 2 0 0 1 2-2ZM7 14h2m3 0h2m3 0h1M7 18h2m3 0h2",
  check: "m5 12 4 4L20 5M20 12v8H3V3h12",
  tasks: "m3 6 1 1 2-3m3 2h12M3 12h3m3 0h12M3 18h3m3 0h12",
  search: "m21 21-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z",
  settings:
    "M9 3h6l1 3 4 3v6l-4 3-1 3H9l-1-3-4-3V9l4-3Zm7 9a4 4 0 1 0-8 0 4 4 0 0 0 8 0Z",
  refresh: "M20 8a8 8 0 1 0 0 8M20 3v5h-5",
  plus: "M12 4v16M4 12h16",
  close: "m6 6 12 12M6 18 18 6",
  back: "m14 5-7 7 7 7",
  next: "m10 5 7 7-7 7",
  share:
    "M8 12 16 6M8 12l8 6M8 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm12-7a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm0 14a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
  edit: "m4 16 12-12 4 4L8 20H4Zm10-10 4 4",
  clock: "M12 8v4l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z",
  location:
    "M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
  dashboard: "M3 3h7v7H3Zm11 0h7v4h-7ZM3 14h7v7H3Zm11-3h7v10h-7Z",
  arrow: "M19 12H5m6-6-6 6 6 6",
  note: "M5 3h14v18H5ZM8 8h8M8 12h8M8 16h5",
  users:
    "M15 8a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM4 21v-3a8 8 0 0 1 16 0v3M18 3a3 3 0 0 1 1 6m0 3a6 6 0 0 1 4 6",
};
function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.classList.add("icon-art");
  const p = document.createElementNS(svg.namespaceURI, "path");
  p.setAttribute("d", paths[name] || paths.calendar);
  svg.append(p);
  return svg;
}
const button = (text, fn, cls = "", attrs = {}) =>
  el("button", { type: "button", class: cls, onclick: fn, ...attrs }, text);
const ib = (name, label, fn) =>
  button(icon(name), fn, "icon", { "aria-label": label, title: label });
const actionButton = (name, label, fn, cls = "outline", attrs = {}) =>
  button(
    [icon(name), el("span", {}, label)],
    fn,
    "action-button " + cls,
    attrs,
  );
const section = (title, ...children) =>
  el("section", { class: "content-section" }, el("h3", {}, title), ...children);
const statusPill = (p) =>
  el("span", { class: "status-pill status-" + p.status }, C.statuses[p.status]);
const metaItem = (name, label, value) =>
  el(
    "div",
    { class: "meta-item" },
    icon(name),
    el("span", {}, el("small", {}, label), el("strong", {}, value)),
  );
function goTasks(filter = "open") {
  state.tab = "tasks";
  state.filter = filter;
  state.query = "";
  state.limit = 50;
  render();
}
function dueLabel(p) {
  const n = p.due - C.today();
  return p.status === "DONE"
    ? "انجام‌شده"
    : n < 0
      ? C.fa(-n) + " روز تأخیر"
      : n === 0
        ? "سررسید امروز"
        : C.fa(n) + " روز تا سررسید";
}
function quickReport(r) {
  const note = el("textarea", {
    name: "quickFollowup",
    maxlength: 1000,
    placeholder: "آخرین وضعیت کار و اقدام بعدی را بنویسید",
  });
  const m = modal("ثبت نتیجهٔ پیگیری", {
    saveText: "ثبت گزارش",
    save: async () => {
      if (!note.value.trim()) throw new Error("نتیجهٔ پیگیری را بنویسید");
      await saveChanges([
        {
          id: uuid(),
          kind: "followup",
          payload: { task: r.id, text: note.value.trim(), created: Date.now() },
        },
      ]);
    },
  });
  m.body.append(
    el("p", { class: "context-title" }, r.payload.title),
    field("نتیجهٔ پیگیری", note),
  );
  note.focus();
}
function taskSearchText(r) {
  return [
    r.payload.title,
    r.payload.owner,
    r.payload.description,
    meetings().find((m) => m.id === r.payload.meeting)?.payload.title || "",
  ].join(" ");
}
async function saveExtensionDecision(r, approve) {
  const current = state.records.find((x) => x.id === r.id) || r,
    p = current.payload,
    e = p.extension;
  if (
    state.profile.role !== "manager" ||
    e?.status !== "PENDING" ||
    ["DONE", "REVIEW"].includes(p.status)
  )
    throw new Error("این درخواست دیگر قابل بررسی نیست؛ اطلاعات را تازه کنید");
  if (approve && (e.baseDue !== p.due || e.due < C.today()))
    throw new Error(
      "مهلت پیشنهادی معتبر نیست؛ درخواست را رد کنید تا مسئول درخواست تازه ثبت کند",
    );
  const extension = {
      ...e,
      status: approve ? "APPROVED" : "REJECTED",
      decidedBy: state.profile.userId,
      decidedAt: Date.now(),
    },
    changes = [
      {
        id: current.id,
        kind: "task",
        base: current,
        payload: { ...p, extension, due: approve ? e.due : p.due },
      },
    ];
  if (approve)
    changes.push({
      id: uuid(),
      kind: "history",
      payload: {
        task: current.id,
        old: p.due,
        new: e.due,
        changed: Date.now(),
        kind: "EXTEND",
        reason: e.reason,
      },
    });
  await saveChanges(changes);
}
async function reviewExtension(r, approve) {
  if (
    !(await confirm(
      approve ? "تأیید تمدید مهلت" : "رد درخواست تمدید",
      r.payload.title +
        "\nدلیل: " +
        r.payload.extension.reason +
        "\nتاریخ پیشنهادی: " +
        C.label(r.payload.extension.due),
      approve ? "تأیید تمدید" : "رد درخواست",
    ))
  )
    return;
  try {
    await saveExtensionDecision(r, approve);
  } catch (e) {
    fail(e);
  }
}
const badge = (n) => el("span", { class: "badge" }, C.fa(n));
function field(label, node, help = null) {
  const control = node.matches("input,textarea,select,button")
    ? node
    : node.querySelector("input,textarea,select");
  if (control && !control.id) control.id = "field-" + uuid();
  return el(
    "div",
    { class: "field" },
    el("label", { for: control?.id }, label),
    node,
    help ? el("small", {}, help) : null,
  );
}
const input = (name, value = "", attrs = {}) =>
  el("input", { name, value, ...attrs });
const select = (name, options, value) =>
  el(
    "select",
    { name },
    Object.entries(options).map(([v, t]) =>
      el("option", { value: v, selected: v === String(value) ? "" : null }, t),
    ),
  );
function toast(text) {
  toastBox.replaceChildren(el("div", { class: "toast", role: "status" }, text));
  clearTimeout(toastBox.timer);
  toastBox.timer = setTimeout(() => toastBox.replaceChildren(), 4500);
}
function fail(e) {
  toast(
    e.name === "AbortError"
      ? "پاسخ سرور دیر شد - دوباره تلاش می‌کنیم"
      : e.message || "عملیات انجام نشد",
  );
}
function modal(title, { sheet = false, save = null, saveText = "ثبت" } = {}) {
  const d = el("dialog", { class: sheet ? "sheet" : "" }),
    body = el("div", { class: "dialog-body" }),
    error = el("p", {
      class: "error-text",
      role: "alert",
      tabindex: "-1",
      hidden: true,
    });
  let resolve;
  const done = new Promise((r) => (resolve = r)),
    close = () => {
      if (d.open) d.close();
    };
  const header = el(
    "div",
    { class: "dialog-header" },
    ib("close", "بستن", close),
    el("h2", {}, title),
  );
  let saveButton;
  if (save) {
    saveButton = button(
      saveText,
      async () => {
        saveButton.disabled = true;
        error.hidden = true;
        try {
          await save();
          close();
        } catch (e) {
          error.textContent = e.message;
          error.hidden = false;
          error.focus({ preventScroll: true });
          error.scrollIntoView({ block: "nearest" });
        } finally {
          saveButton.disabled = false;
        }
      },
      "primary",
    );
    header.append(saveButton);
  }
  d.append(el("div", { class: "dialog-layout" }, header, body));
  body.append(error);
  dialogs.append(d);
  d.addEventListener("close", () => {
    d.remove();
    resolve();
    if (!dialogs.querySelector("dialog[open]")) render();
  });
  d.showModal();
  return { d, body, header, error, close, done, saveButton };
}
async function confirm(title, text, yes = "تأیید") {
  let answer = false;
  const m = modal(title);
  m.body.append(
    el("p", {}, text),
    el(
      "div",
      { class: "row" },
      button(
        yes,
        () => {
          answer = true;
          m.close();
        },
        "primary",
      ),
      button("انصراف", m.close),
    ),
  );
  await m.done;
  return answer;
}
const details = (...children) =>
  el(
    "details",
    {},
    el("summary", {}, "جزئیات بیشتر"),
    el("div", {}, ...children),
  );
const empty = (text, action = null) =>
  el("div", { class: "empty" }, icon("calendar"), el("p", {}, text), action);
function applyTheme(value) {
  state.theme = value;
  document.documentElement.dataset.theme = value;
  S.meta("theme", value).catch(fail);
}
const records = (kind) => state.records.filter((r) => r.kind === kind),
  tasks = () => {
    const day = C.today(),
      role = state.profile.role;
    return records("task").sort((a, b) => C.taskOrder(a, b, day, role));
  },
  meetings = () =>
    records("meeting").sort(
      (a, b) =>
        a.payload.date - b.payload.date || a.payload.start - b.payload.start,
    );
const related = (kind, id) =>
  records(kind)
    .filter((r) => r.payload.task === id)
    .sort(
      (a, b) =>
        (b.payload.created || b.payload.changed) -
        (a.payload.created || a.payload.changed),
    );
const personName = (id) =>
  state.profile.users?.find((u) => u.id === id)?.name || "کاربر شرکت";
async function load() {
  state.profiles = await S.allProfiles();
  state.profile =
    state.profiles.find((p) => p.id === state.profile?.id) ||
    state.profiles.find((p) => !p.expired) ||
    state.profiles[0] ||
    null;
  if (state.profile) {
    const data = await S.snapshot(state.profile.id);
    state.records = data.records;
    state.drafts = data.drafts;
  } else {
    state.records = [];
    state.drafts = [];
  }
}
async function activate(profile) {
  for (const d of dialogs.querySelectorAll("dialog")) d.close();
  state.profile = profile;
  state.limit = 50;
  state.query = "";
  state.day = C.today();
  scrollKey = "";
  await S.meta("active", profile.id);
  await load();
  render();
  schedule(0);
}
function schedule(delay = 30000) {
  clearTimeout(timer);
  timer = setTimeout(refresh, delay);
}
async function refresh() {
  if (state.busy) {
    refreshRequested = true;
    return;
  }
  if (!navigator.onLine) {
    renderStatus();
    schedule(10000);
    return;
  }
  state.busy = true;
  renderStatus();
  let error = false;
  await Promise.allSettled(
    state.profiles
      .filter((p) => !p.expired)
      .map(async (p) => {
        try {
          await S.synchronize(p, async (id) => {
            if (state.profile?.id === id) {
              await load();
              render();
            }
          });
        } catch (e) {
          error = true;
        }
      }),
  );
  state.busy = false;
  state.backoff = error ? Math.min(state.backoff + 1, 5) : 0;
  await load();
  render();
  const delay = refreshRequested
    ? 0
    : error
      ? [10000, 30000, 60000, 120000, 300000][Math.max(0, state.backoff - 1)]
      : 30000;
  refreshRequested = false;
  schedule(delay);
}
function renderStatus() {
  const n = document.querySelector("#connection");
  if (n)
    n.replaceChildren(
      el("span", {
        class:
          "connection-dot" +
          (!state.profile?.error && navigator.onLine && state.profile?.lastSync
            ? " connected"
            : ""),
      }),
      state.busy
        ? "در حال به‌روزرسانی"
        : !navigator.onLine
          ? "بدون اینترنت"
          : state.profile?.expired
            ? "ورود دوباره لازم است"
            : state.profile?.error
              ? "قطع - تلاش دوباره"
              : state.profile?.lastSync
                ? "متصل"
                : "در حال اتصال",
    );
}
async function saveChanges(changes) {
  if (!state.profile || state.profile.expired)
    throw new Error("ابتدا دوباره وارد حساب شوید");
  await S.queueChanges(state.profile.id, changes);
  if (state.busy) refreshRequested = true;
  await load();
  render();
  if (state.busy) refreshRequested = true;
  else schedule(0);
  toast(
    navigator.onLine
      ? "ثبت شد - در حال همگام‌سازی"
      : "روی این گوشی ثبت شد - پس از اتصال ارسال می‌شود",
  );
}
const changeRecord = (r, payload) =>
  saveChanges([{ id: r.id, kind: r.kind, payload, base: r }]);
function render() {
  if (dialogs.querySelector("dialog[open]")) {
    renderStatus();
    return;
  }
  renderGeneration++;
  const focused = document.activeElement,
    searchName = focused?.matches('input[type="search"]') ? focused.name : null,
    selection = searchName
      ? [focused.selectionStart, focused.selectionEnd]
      : null;
  observer?.disconnect();
  if (!state.profile) {
    loginPage();
    return;
  }
  const p = state.profile,
    admin = C.admin(p),
    today = C.today(),
    all = tasks(),
    open = all.filter((r) => r.payload.status !== "DONE"),
    pending = all.filter((r) => r.payload.status === "REVIEW"),
    extensions = open.filter((r) => r.payload.extension?.status === "PENDING");
  const labels = {
    meetings: [
      "برنامهٔ جلسات",
      "زمان‌بندی، افراد و مصوبات هر جلسه، در یک نگاه",
    ],
    tasks: ["مصوبات و کارها", "مسئول، مهلت و آخرین وضعیت هر کار را دنبال کنید"],
    followup: ["میز پیگیری", "کارهای نیازمند توجه و درخواست‌های منتظر بررسی"],
    review: ["مرور روزانه", "برنامهٔ تحویل کارها را روز به روز بررسی کنید"],
  };
  const header = el(
    "header",
    { class: "app-header" },
    button(
      el(
        "div",
        {},
        el("div", { class: "name" }, p.companyName || p.company, icon("next")),
        el("div", { class: "connection", id: "connection" }),
      ),
      accountPicker,
      "company-button",
      { "aria-label": "انتخاب شرکت و حساب" },
    ),
    el(
      "div",
      { class: "header-actions" },
      actionButton("refresh", "تازه‌سازی", refresh, "quiet", {
        "aria-label": "به‌روزرسانی اتصال و اطلاعات",
      }),
      actionButton("settings", "تنظیمات", settings, "quiet"),
    ),
  );
  const nav = el(
    "nav",
    { class: "tabs", "aria-label": "بخش‌های برنامه" },
    [
      ["meetings", "جلسات", "calendar"],
      ["followup", "پیگیری", "dashboard"],
      ["tasks", "مصوبات", "tasks"],
      ["review", "مرور", "clock"],
    ].map(([id, text, name]) =>
      button(
        [
          el("span", { class: "tab-icon" }, icon(name)),
          el("span", {}, text),
          id === "followup" && pending.length + extensions.length > 0
            ? el(
                "span",
                { class: "nav-count", id: "pending-nav-count" },
                C.fa(pending.length + extensions.length),
              )
            : null,
        ],
        () => {
          state.tab = id;
          state.query = "";
          state.limit = 50;
          render();
        },
        "tab",
        {
          "aria-label": text,
          "aria-describedby":
            id === "followup" && pending.length + extensions.length > 0
              ? "pending-nav-count"
              : null,
          "aria-current": state.tab === id ? "page" : null,
        },
      ),
    ),
  );
  const sidebar = el(
    "aside",
    { class: "sidebar" },
    el(
      "div",
      { class: "workspace-brand" },
      el("img", { src: "/app/icon.svg", width: 40, height: 40, alt: "" }),
      el(
        "div",
        {},
        el("strong", {}, "پیگیر"),
        el("small", {}, "دفتر کار تیم شما"),
      ),
    ),
    el("div", { class: "sidebar-caption" }, "فضای کاری"),
    nav,
    el(
      "div",
      { class: "sidebar-tools" },
      el("small", {}, "دسترسی سریع"),
      admin
        ? actionButton("users", "مدیریت کاربران", userManagement, "quiet")
        : null,
      actionButton("settings", "اعلان‌ها و نمایش", settings, "quiet"),
      actionButton("users", "حساب‌ها و شرکت‌ها", accountPicker, "quiet"),
    ),
    el(
      "div",
      { class: "sidebar-profile" },
      el("span", { class: "avatar" }, p.name?.trim().slice(0, 1) || "پ"),
      el("div", {}, el("strong", {}, p.name), el("small", {}, C.roles[p.role])),
    ),
  );
  const main = el("main", { id: "main", tabindex: "-1" }),
    workspace = el("div", { class: "workspace" }, header),
    shell = el("div", { class: "app-shell" }, sidebar, workspace);
  if (p.expired)
    workspace.append(
      el(
        "div",
        { class: "banner error" },
        button("نشست پایان یافته است - ورود دوباره", () => loginDialog(p)),
      ),
    );
  else if (state.drafts.length)
    workspace.append(
      el(
        "div",
        { class: "banner" },
        button(["در انتظار ارسال", badge(state.drafts.length)], () =>
          state.drafts.some((d) => d.conflict) ? conflictDialog() : refresh(),
        ),
      ),
    );
  workspace.append(main);
  main.append(
    el(
      "div",
      { class: "page-heading" },
      el(
        "div",
        {},
        el(
          "div",
          { class: "eyebrow" },
          "فضای کاری / " +
            (state.tab === "meetings"
              ? "تقویم"
              : state.tab === "tasks"
                ? "مصوبات"
                : state.tab === "followup"
                  ? "پیگیری"
                  : "مرور"),
        ),
        el("h1", {}, labels[state.tab][0]),
        el("p", {}, labels[state.tab][1]),
      ),
      el("div", { class: "today-stamp" }, icon("calendar"), C.label(today)),
    ),
  );
  const metrics = [
    [
      "جلسات امروز",
      meetings().filter((r) => r.payload.date === today).length,
      "calendar",
      "neutral",
      () => {
        state.tab = "meetings";
        state.day = today;
        render();
      },
    ],
    ["مصوبات باز", open.length, "tasks", "purple", () => goTasks("open")],
    [
      "سررسید گذشته",
      open.filter((r) => r.payload.due < today).length,
      "clock",
      "red",
      () => goTasks("late"),
    ],
    p.role === "manager"
      ? [
          "منتظر تأیید شما",
          pending.length + extensions.length,
          "check",
          "amber",
          () => {
            state.tab = "followup";
            render();
          },
        ]
      : [
          "سررسید امروز",
          open.filter((r) => r.payload.due === today).length,
          "check",
          "amber",
          () => goTasks("today"),
        ],
  ];
  main.append(
    el(
      "div",
      { class: "workspace-stats" },
      metrics.map(([label, count, name, tone, fn]) =>
        button(
          [
            el("span", { class: "metric-icon " + tone }, icon(name)),
            el(
              "span",
              { class: "metric-copy" },
              el("span", {}, label),
              el("strong", {}, C.fa(count)),
            ),
          ],
          fn,
          "metric-card",
        ),
      ),
    ),
  );
  root.replaceChildren(shell);
  renderStatus();
  ({
    meetings: renderMeetings,
    tasks: renderTasks,
    review: renderReview,
    followup: renderFollowup,
  })[state.tab](main);
  if (searchName) {
    const search = main.querySelector(
      `input[name="${CSS.escape(searchName)}"]`,
    );
    if (search) {
      search.focus({ preventScroll: true });
      search.setSelectionRange(...selection);
    }
  }
}
function dateNav() {
  return el(
    "div",
    { class: "date-nav" },
    ib("next", "روز یا دورهٔ قبل", () => {
      state.day =
        state.mode === "month" && state.tab === "meetings"
          ? C.previousMonth(state.day)
          : state.day -
            (state.mode === "week" && state.tab === "meetings" ? 7 : 1);
      scrollKey = "";
      render();
    }),
    button(
      C.label(state.day),
      () =>
        datePicker(state.day, (day) => {
          state.day = day;
          scrollKey = "";
          render();
        }),
      "date-label",
    ),
    ib("back", "روز یا دورهٔ بعد", () => {
      state.day =
        state.mode === "month" && state.tab === "meetings"
          ? C.nextMonth(state.day)
          : state.day +
            (state.mode === "week" && state.tab === "meetings" ? 7 : 1);
      scrollKey = "";
      render();
    }),
    button("امروز", () => {
      state.day = C.today();
      scrollKey = "";
      render();
    }),
  );
}
function swipe(node, fn) {
  let start;
  node.addEventListener(
    "touchstart",
    (e) => {
      if (
        e.touches.length === 1 &&
        !e.target.closest("button,input,select,textarea")
      )
        start = { x: e.touches[0].clientX, y: e.touches[0].clientY };
    },
    { passive: true },
  );
  node.addEventListener(
    "touchend",
    (e) => {
      if (!start) return;
      const dx = e.changedTouches[0].clientX - start.x,
        dy = e.changedTouches[0].clientY - start.y;
      start = null;
      if (Math.abs(dx) > 65 && Math.abs(dx) > Math.abs(dy) * 1.5)
        fn(dx > 0 ? 1 : -1);
    },
    { passive: true },
  );
}
function renderMeetings(main) {
  const segment = el(
    "div",
    { class: "segmented" },
    Object.entries({ day: "روزانه", week: "هفتگی", month: "ماهانه" }).map(
      ([id, name]) =>
        button(
          name,
          () => {
            state.mode = id;
            scrollKey = "";
            render();
          },
          "",
          { "aria-pressed": String(state.mode === id) },
        ),
    ),
  );
  const query = input("meetingQuery", state.meetingQuery, {
      type: "search",
      placeholder: "عنوان جلسه، محل یا نام افراد…",
      "aria-label": "جست‌وجوی مستقیم جلسات",
    }),
    results = el("section", { class: "meeting-search-results", hidden: true });
  let searchTimer;
  query.addEventListener("input", () => {
    clearTimeout(searchTimer);
    state.meetingQuery = query.value;
    searchTimer = setTimeout(paintSearch, 120);
  });
  function paintSearch() {
    results.hidden = !state.meetingQuery.trim();
    if (results.hidden) {
      results.replaceChildren();
      return;
    }
    const rows = meetings().filter((r) =>
      C.matches(
        [
          r.payload.title,
          r.payload.location,
          r.payload.notes,
          ...(r.payload.attendees || []).map(personName),
          ...(r.payload.guests || []),
        ].join(" "),
        state.meetingQuery,
      ),
    );
    results.replaceChildren(
      el(
        "div",
        { class: "section-heading" },
        el("h2", {}, "نتایج در همهٔ تاریخ‌ها"),
        badge(rows.length),
      ),
      el("div", { class: "cards" }, rows.slice(0, 20).map(meetingCard)),
    );
    if (rows.length > 20)
      results.append(
        button(
          "نمایش همهٔ " + C.fa(rows.length) + " نتیجه",
          () =>
            results
              .querySelector(".cards")
              .replaceChildren(...rows.map(meetingCard)),
          "outline",
        ),
      );
    if (!rows.length) results.append(empty("جلسه‌ای با این مشخصات پیدا نشد"));
  }
  main.append(
    el(
      "div",
      { class: "toolbar" },
      segment,
      el(
        "div",
        { class: "inline-search calendar-search" },
        icon("search"),
        query,
      ),
      C.admin(state.profile)
        ? actionButton(
            "plus",
            "جلسهٔ جدید",
            () => meetingForm(null, state.day),
            "primary",
          )
        : null,
    ),
    results,
    dateNav(),
  );
  paintSearch();
  const calendar = el("section", {
      class: "calendar-panel",
      "aria-label": "تقویم جلسات",
    }),
    agenda = el("aside", { class: "agenda-panel" });
  const dayRows = meetings().filter((r) => r.payload.date === state.day);
  agenda.append(
    el(
      "div",
      { class: "panel-heading" },
      el(
        "div",
        {},
        el("h2", {}, "دستور روز"),
        el("small", {}, C.label(state.day)),
      ),
      badge(dayRows.length),
    ),
    el(
      "div",
      { class: "calendar-legend" },
      el("span", {}, el("i", { class: "legend-confirmed" }), "جلسهٔ قطعی"),
      el("span", {}, el("i", { class: "legend-reserved" }), "رزرو"),
    ),
  );
  agenda.append(...dayRows.map(meetingCard));
  if (!dayRows.length) agenda.append(empty("برای این روز جلسه‌ای ندارید"));
  const layout = el("div", { class: "calendar-layout" }, calendar, agenda);
  main.append(layout);
  main = calendar;
  if (state.mode === "month") {
    const content = el("div", { class: "page-content" });
    content.append(
      monthGrid(
        state.day,
        (day) => {
          state.day = day;
          render();
          if (
            !meetings().some((r) => r.payload.date === day) &&
            C.admin(state.profile)
          )
            meetingForm(null, day);
        },
        (day) => meetings().filter((r) => r.payload.date === day).length,
      ),
    );
    main.append(content);
    return;
  }
  const days = Array.from(
      { length: state.mode === "week" ? 7 : 1 },
      (_, i) =>
        (state.mode === "week" ? C.weekStart(state.day) : state.day) + i,
    ),
    heading = el("div", { class: "calendar-week-head" });
  heading.style.setProperty("--days", days.length);
  heading.append(el("small", {}, "ساعت"));
  for (const day of days)
    heading.append(
      button(
        [
          C.weekdays[(new Date(day * 86400000).getUTCDay() + 1) % 7].slice(
            0,
            days.length === 7 ? 1 : 20,
          ),
          el("span", { class: "day-number" }, C.fa(C.solar(day).day)),
        ],
        () => {
          state.day = day;
          state.mode = "day";
          scrollKey = "";
          render();
        },
        day === C.today() ? "selected" : "",
        { "aria-label": C.label(day) },
      ),
    );
  main.append(heading);
  const untimed = meetings().filter(
    (r) =>
      days.includes(r.payload.date) &&
      (r.payload.start === null || r.payload.end === null),
  );
  if (untimed.length)
    main.append(
      el(
        "div",
        { class: "untimed" },
        untimed.map((r) =>
          button(
            r.payload.title + " - نیاز به تعیین ساعت",
            () => meetingDetail(r),
            "tonal",
          ),
        ),
      ),
    );
  const scroller = el("div", {
      class: "timeline-scroll",
      "aria-label": "تقویم ساعتی جلسات",
      tabindex: "0",
    }),
    grid = el("div", {
      class: "timeline" + (days.length === 7 ? " weekly" : ""),
    });
  grid.style.setProperty("--days", days.length);
  const hours = el("div", { class: "hours" });
  for (let h = 0; h < 24; h++) {
    const n = el("span", { class: "hour" }, C.time(h * 60));
    n.style.top = h * 60 + "px";
    hours.append(n);
  }
  grid.append(hours);
  const all = meetings();
  for (const day of days) {
    const column = el("div", { class: "time-day", "data-day": day });
    column.addEventListener("click", (e) => {
      if (e.target !== column || !C.admin(state.profile)) return;
      const minute = Math.max(
        0,
        Math.min(
          1380,
          Math.floor((e.clientY - column.getBoundingClientRect().top) / 15) *
            15,
        ),
      );
      meetingForm(null, day, minute);
    });
    for (const entry of C.displayGroups(
      all.filter((r) => r.payload.date === day),
      days.length === 7 ? 2 : 3,
    )) {
      const dense = entry.dense,
        r = entry.record,
        start = dense ? entry.start : r.payload.start,
        end = dense ? entry.end : r.payload.end;
      const b = button(
        dense
          ? [el("span", {}, "جلسات هم‌زمان"), badge(dense.length)]
          : [
              el(
                "strong",
                {},
                (r.payload.reserved ? "رزرو: " : "") + r.payload.title,
              ),
              el("small", {}, C.time(start) + " تا " + C.time(end)),
            ],
        () => (dense ? dayMeetingList(dense) : meetingDetail(r)),
        "event" +
          (dense
            ? " dense"
            : r.payload.reserved
              ? " reserved"
              : " tone-" + (C.solar(day).day % 3)),
        {
          "aria-label": dense
            ? C.fa(`${dense.length} جلسه هم‌زمان`)
            : r.payload.title + " - " + C.time(start) + " تا " + C.time(end),
        },
      );
      b.style.top = start + "px";
      b.style.height = Math.max(22, end - start - 2) + "px";
      b.style.insetInlineStart =
        (dense ? 0 : (entry.lane * 100) / entry.lanes) + "%";
      b.style.width = (dense ? 100 : 100 / entry.lanes) + "%";
      column.append(b);
    }
    if (day === C.today()) {
      const line = el("div", { class: "now-line" });
      line.style.top = C.nowMinute() + "px";
      column.append(line);
    }
    grid.append(column);
  }
  scroller.append(grid);
  main.append(scroller);
  swipe(scroller, (step) => {
    state.day += step * (state.mode === "week" ? 7 : 1);
    scrollKey = "";
    render();
  });
  const key = state.mode + ":" + state.day;
  if (scrollKey !== key) {
    scrollKey = key;
    requestAnimationFrame(
      () => (scroller.scrollTop = Math.max(0, C.nowMinute() - 90)),
    );
  } else
    scroller.scrollTop = Number(
      sessionStorage.getItem("timeline-scroll") ||
        Math.max(0, C.nowMinute() - 90),
    );
  scroller.addEventListener(
    "scroll",
    () => sessionStorage.setItem("timeline-scroll", scroller.scrollTop),
    { passive: true },
  );
}
function meetingCard(r) {
  const p = r.payload,
    items = records("task").filter((t) => t.payload.meeting === r.id),
    names = [...(p.attendees || []).map(personName), ...(p.guests || [])];
  return el(
    "article",
    { class: "meeting-card" + (p.reserved ? " reserved" : "") },
    button(
      [
        el(
          "div",
          { class: "card-topline" },
          el(
            "span",
            {
              class:
                "status-pill " +
                (p.reserved ? "reservation-pill" : "confirmed-pill"),
            },
            p.reserved ? "رزرو جلسه" : "قطعی",
          ),
          el(
            "span",
            { class: "meeting-time" },
            C.time(p.start) + " – " + C.time(p.end),
          ),
        ),
        el("strong", { class: "meeting-title" }, p.title),
        el("small", { class: "meeting-date" }, C.label(p.date)),
        p.location
          ? el("span", { class: "inline-meta" }, icon("location"), p.location)
          : null,
        names.length
          ? el(
              "span",
              { class: "inline-meta" },
              icon("users"),
              names.slice(0, 2).join("، ") +
                (names.length > 2
                  ? " و " + C.fa(names.length - 2) + " نفر دیگر"
                  : ""),
            )
          : el("small", {}, "شرکت‌کننده‌ای انتخاب نشده"),
        p.notes ? el("span", { class: "card-excerpt" }, p.notes) : null,
        el(
          "span",
          { class: "meeting-counts" },
          el("span", {}, C.fa(names.length) + " شرکت‌کننده"),
          el("span", {}, C.fa(items.length) + " مصوبه"),
        ),
      ],
      () => meetingDetail(r),
      "meeting-body",
    ),
    el(
      "div",
      { class: "card-actions" },
      actionButton("arrow", "مشاهده", () => meetingDetail(r), "quiet"),
      C.admin(state.profile)
        ? actionButton("edit", "ویرایش", () => meetingForm(r), "quiet")
        : null,
      C.admin(state.profile) && !p.reserved
        ? actionButton("plus", "ثبت مصوبه", () => taskForm(null, r), "quiet")
        : null,
    ),
  );
}
function dayMeetingList(items) {
  const m = modal("جلسات هم‌زمان", { sheet: true });
  m.body.append(
    ...items.sort((a, b) => a.payload.start - b.payload.start).map(meetingCard),
  );
}
function taskCard(r) {
  const p = r.payload,
    action = C.primaryAction(state.profile, p),
    late = p.due < C.today() && p.status !== "DONE",
    meeting = meetings().find((m) => m.id === p.meeting),
    note = related("followup", r.id)[0],
    pending = p.extension?.status === "PENDING";
  const due = C.officeAt(p.due + 1, 0),
    elapsed =
      p.created > 0 && due > p.created
        ? Math.min(
            100,
            Math.max(
              0,
              Math.round(((Date.now() - p.created) / (due - p.created)) * 100),
            ),
          )
        : null;
  return el(
    "article",
    {
      class:
        "task-card" +
        (p.status === "DONE" ? " done" : "") +
        (late ? " overdue" : ""),
    },
    button(
      [
        el(
          "div",
          { class: "card-topline" },
          statusPill(p),
          el(
            "span",
            { class: "priority-pill priority-" + p.priority },
            C.priorities[p.priority],
          ),
        ),
        el("div", { class: "task-title" }, p.title),
        el(
          "div",
          { class: "task-meta" },
          el("span", { class: "inline-meta" }, icon("users"), p.owner),
          el(
            "span",
            { class: "inline-meta" },
            icon("calendar"),
            C.label(p.due),
          ),
        ),
        el(
          "div",
          { class: "task-status" + (late ? " late" : "") },
          dueLabel(p),
          r.local
            ? el("span", { class: "local-badge" }, "در انتظار ارسال")
            : null,
        ),
        p.description
          ? el("p", { class: "card-excerpt" }, p.description)
          : null,
        meeting
          ? el(
              "div",
              { class: "linked-meeting" },
              icon("calendar"),
              meeting.payload.title,
            )
          : null,
        pending
          ? el(
              "div",
              { class: "extension-notice" },
              icon("clock"),
              "درخواست تمدید منتظر بررسی رئیس",
            )
          : null,
        note
          ? el(
              "div",
              { class: "latest-note" },
              icon("note"),
              el(
                "span",
                {},
                el("small", {}, "آخرین پیگیری"),
                el("span", {}, note.payload.text),
              ),
            )
          : null,
        elapsed !== null && p.status !== "DONE"
          ? el(
              "div",
              { class: "deadline-meter" },
              el(
                "div",
                { class: "meter-label" },
                el("span", {}, "زمان سپری‌شده از مهلت"),
                el("strong", {}, C.fa(elapsed) + "٪"),
              ),
              el("progress", {
                max: 100,
                value: elapsed,
                "aria-label": "زمان سپری‌شده از مهلت",
              }),
            )
          : null,
      ],
      () => taskDetail(r),
      "task-body",
    ),
    el(
      "div",
      { class: "card-actions" },
      pending &&
        state.profile.role === "manager" &&
        !["DONE", "REVIEW"].includes(p.status)
        ? [
            actionButton(
              "check",
              "تأیید تمدید",
              () => reviewExtension(r, true),
              "tonal",
            ),
            actionButton(
              "close",
              "رد تمدید",
              () => reviewExtension(r, false),
              "danger",
            ),
          ]
        : null,
      action
        ? actionButton(
            "check",
            action[0],
            () => taskAction(r),
            "task-state tonal",
            { "aria-label": action[0] + " - " + p.title },
          )
        : null,
      actionButton("note", "پیگیری", () => quickReport(r), "quiet"),
      C.admin(state.profile)
        ? actionButton("edit", "ویرایش", () => taskForm(r), "quiet")
        : null,
      actionButton("arrow", "جزئیات", () => taskDetail(r), "quiet"),
    ),
  );
}
function listTasks(container, rows) {
  let limit = state.limit;
  const list = el("div", { class: "cards" }),
    more = button(
      "نمایش بیشتر",
      () => {
        limit += 50;
        state.limit = limit;
        show();
      },
      "load-more tonal",
    );
  const show = () => {
    list.replaceChildren(...rows.slice(0, limit).map(taskCard));
    more.hidden = limit >= rows.length;
  };
  container.append(list, more);
  show();
  const generation = renderGeneration;
  observer = new IntersectionObserver(
    (entries) => {
      if (
        entries.some((e) => e.isIntersecting) &&
        generation === renderGeneration &&
        limit < rows.length
      ) {
        limit += 50;
        state.limit = limit;
        show();
      }
    },
    { rootMargin: "150px" },
  );
  observer.observe(more);
}
function renderTasks(main) {
  const filter = select("filter", C.filters, state.filter);
  filter.setAttribute("aria-label", "فیلتر مصوبات");
  filter.addEventListener("change", () => {
    state.filter = filter.value;
    state.limit = 50;
    render();
  });
  const q = input("taskQuery", state.query, {
    type: "search",
    placeholder: "جست‌وجو در عنوان، مسئول، شرح یا جلسه…",
    "aria-label": "جست‌وجوی مستقیم مصوبات",
  });
  const body = el("div", { class: "page-content" }),
    count = el("span", { class: "result-count", role: "status" });
  const paint = () => {
    observer?.disconnect();
    const rows = tasks().filter(
      (r) =>
        C.filterTask(r.payload, state.filter) &&
        C.matches(taskSearchText(r), state.query),
    );
    count.textContent = C.fa(rows.length) + " مصوبه";
    body.replaceChildren();
    if (rows.length) listTasks(body, rows);
    else
      body.append(
        empty(
          "مصوبه‌ای با این مشخصات پیدا نشد",
          state.query
            ? button(
                "پاک کردن جست‌وجو",
                () => {
                  state.query = "";
                  render();
                },
                "outline",
              )
            : null,
        ),
      );
  };
  let debounce;
  q.addEventListener("input", () => {
    clearTimeout(debounce);
    state.query = q.value;
    state.limit = 50;
    debounce = setTimeout(paint, 120);
  });
  const chips = el(
    "div",
    { class: "filter-chips", "aria-label": "فیلترهای سریع مصوبات" },
    ["open", "today", "late", "REVIEW", "extensions", "DONE", "all"].map((f) =>
      button(
        [
          C.filters[f],
          badge(tasks().filter((r) => C.filterTask(r.payload, f)).length),
        ],
        () => {
          state.filter = f;
          state.limit = 50;
          render();
        },
        "filter-chip",
        { "aria-pressed": String(state.filter === f) },
      ),
    ),
  );
  main.append(
    el(
      "div",
      { class: "task-controls" },
      el(
        "div",
        { class: "toolbar" },
        el("div", { class: "inline-search" }, icon("search"), q),
        filter,
        C.admin(state.profile)
          ? actionButton("plus", "مصوبهٔ جدید", () => taskForm(), "primary")
          : null,
      ),
      chips,
      el(
        "div",
        { class: "list-caption" },
        count,
        el("span", {}, "برای هر مصوبه، وضعیت و اقدام بعدی را همین‌جا ببینید"),
      ),
    ),
    body,
  );
  paint();
}
function renderFollowup(main) {
  const body = el("div", { class: "page-content followup-board" }),
    all = tasks();
  let count = 0;
  main.append(
    el(
      "div",
      { class: "toolbar followup-toolbar" },
      el(
        "p",
        { class: "grow muted" },
        "ابتدا درخواست‌ها و کارهای نزدیک به سررسید را بررسی کنید",
      ),
      C.admin(state.profile)
        ? actionButton("plus", "ثبت مصوبه", () => taskForm(), "primary")
        : null,
    ),
  );
  for (const f of [
    ...(state.profile.role === "manager" ? ["extensions", "REVIEW"] : []),
    "late",
    "today",
    "near",
  ]) {
    const rows = all.filter((r) => C.filterTask(r.payload, f));
    if (!rows.length) continue;
    count += rows.length;
    body.append(
      el(
        "section",
        { class: "followup-group" },
        el(
          "div",
          { class: "section-heading" },
          el("h2", {}, C.filters[f]),
          badge(rows.length),
          button(["مشاهدهٔ همه", icon("arrow")], () => goTasks(f), "quiet"),
        ),
        el("div", { class: "cards" }, rows.slice(0, 4).map(taskCard)),
      ),
    );
  }
  if (!count) body.append(empty("همه‌چیز مرتب است؛ پیگیری فوری ندارید"));
  main.append(body);
}
function renderReview(main) {
  main.append(dateNav());
  const start = C.weekStart(state.day),
    strip = el("div", { class: "week-strip" });
  for (let i = 0; i < 7; i++) {
    const day = start + i,
      count = tasks().filter((r) => r.payload.due === day).length;
    strip.append(
      button(
        [
          C.weekdays[i].slice(0, 1),
          el("strong", {}, C.fa(C.solar(day).day)),
          count ? badge(count) : null,
        ],
        () => {
          state.day = day;
          render();
        },
        day === state.day ? "selected" : "",
        { "aria-label": C.label(day) },
      ),
    );
  }
  main.append(strip);
  const body = el("div", { class: "page-content" }),
    rows = tasks().filter((r) => r.payload.due === state.day);
  main.append(body);
  if (rows.length) listTasks(body, rows);
  else body.append(empty("مصوبه‌ای برای این روز ندارید"));
  swipe(body, (step) => {
    state.day += step;
    render();
  });
}
function monthGrid(day, onChoose, count = () => 0, selectedDay = state.day) {
  const first = C.monthStart(day),
    offset = first - C.weekStart(first),
    grid = el("div", { class: "month-grid" }),
    box = el(
      "div",
      { class: "month" },
      el(
        "div",
        { class: "month-labels" },
        C.weekdays.map((w) => el("span", {}, w.slice(0, 1))),
      ),
      grid,
    );
  for (let i = 0; i < offset; i++) grid.append(el("span", {}));
  for (let i = 0; i < C.monthDays(day); i++) {
    const d = first + i,
      n = count(d);
    grid.append(
      button(
        [el("span", {}, C.fa(i + 1)), n ? badge(n) : null],
        () => onChoose(d),
        (d === selectedDay ? "selected " : "") + (n ? "has-events" : ""),
        { "aria-label": C.label(d) + (n ? C.fa(` - ${n} جلسه`) : "") },
      ),
    );
  }
  return box;
}
function datePicker(value, chosen) {
  let day = value,
    view = C.monthStart(value);
  const m = modal("انتخاب تاریخ شمسی"),
    content = el("div"),
    manual = input("date", C.numeric(day), {
      inputmode: "numeric",
      placeholder: "۱۴۰۵/۰۸/۱۵",
      "aria-label": "تاریخ شمسی",
    });
  const show = () => {
    const p = C.solar(view),
      header = el(
        "div",
        { class: "picker-header" },
        ib("next", "ماه قبل", () => {
          view = C.previousMonth(view);
          show();
        }),
        el("strong", {}, C.months[p.month - 1] + " " + C.fa(p.year)),
        ib("back", "ماه بعد", () => {
          view = C.nextMonth(view);
          show();
        }),
      );
    content.replaceChildren(
      header,
      monthGrid(
        view,
        (d) => {
          day = d;
          manual.value = C.numeric(d);
          chosen(d);
          m.close();
        },
        () => 0,
        day,
      ),
    );
  };
  m.body.append(
    content,
    field("ورود تاریخ", manual),
    button(
      "انتخاب تاریخ",
      () => {
        const d = C.parseSolar(manual.value);
        if (d === null) {
          m.error.textContent = "تاریخ شمسی معتبر را وارد کنید";
          m.error.hidden = false;
          return;
        }
        chosen(d);
        m.close();
      },
      "primary",
    ),
  );
  show();
}
function dateField(title, initial) {
  let value = initial;
  const b = button(
    C.label(value),
    () =>
      datePicker(value, (d) => {
        value = d;
        b.textContent = C.label(d);
      }),
    "outline full text-start",
  );
  return {
    node: field(title, b),
    get: () => value,
    set: (d) => {
      value = d;
      b.textContent = C.label(d);
    },
  };
}
function personInput({
  multiple = false,
  initial = [],
  onSelect = () => {},
} = {}) {
  let selected = [...initial];
  const box = el("div", { class: "stack" }),
    q = input("person", "", {
      type: "text",
      autocomplete: "off",
      placeholder: multiple ? "نام فرد را بنویسید" : "نام مسئول",
      role: "combobox",
      "aria-expanded": "false",
      "aria-autocomplete": "list",
    }),
    list = el("div", {
      class: "person-list",
      role: "listbox",
      id: "people-" + uuid(),
      hidden: true,
    }),
    chips = el("div", { class: "chips" });
  q.setAttribute("aria-controls", list.id);
  let expanded = false;
  const showChips = () => {
    chips.replaceChildren(
      ...selected.slice(0, expanded ? selected.length : 4).map((p) =>
        el(
          "span",
          { class: "chip" },
          p.name,
          button(
            "×",
            () => {
              selected = selected.filter((x) => x !== p);
              showChips();
              show();
              onSelect(selected);
            },
            "",
            { "aria-label": "حذف " + p.name },
          ),
        ),
      ),
    );
    if (!expanded && selected.length > 4)
      chips.append(
        button(
          C.fa(`${selected.length - 4} نفر دیگر`),
          () => {
            expanded = true;
            showChips();
          },
          "tonal",
        ),
      );
  };
  const choose = (p) => {
    if (multiple) {
      if (
        !selected.some((x) =>
          p.id ? x.id === p.id : C.personKey(x.name) === C.personKey(p.name),
        )
      )
        selected.push(p);
      q.value = "";
      showChips();
      onSelect(selected);
      show();
      q.focus();
    } else {
      selected = [p];
      q.value = p.name;
      hide();
      onSelect(p);
    }
  };
  const hide = () => {
    list.hidden = true;
    q.setAttribute("aria-expanded", "false");
  };
  const show = () => {
    const options = C.suggestions(
      state.profile.users || [],
      state.profile.contacts || [],
      q.value,
      multiple ? selected.map((p) => p.id || C.personKey(p.name)) : [],
    );
    list.replaceChildren(
      ...options
        .slice(0, 50)
        .map((p) =>
          button(
            el(
              "span",
              { class: "grow" },
              el("strong", {}, p.name),
              el("small", {}, p.id ? "کاربر شرکت" : "مخاطب"),
            ),
            () => choose(p),
            "",
            { role: "option", "aria-selected": "false" },
          ),
        ),
    );
    if (
      q.value.trim() &&
      !options.some((p) => C.personKey(p.name) === C.personKey(q.value))
    )
      list.append(
        button(
          "افزودن «" + q.value.trim() + "»",
          () => choose({ id: null, name: q.value.trim() }),
          "tonal",
        ),
      );
    if (!options.length && !q.value.trim())
      list.append(
        el("p", { class: "person-count" }, "نام فرد جدید را بنویسید"),
      );
    list.hidden = false;
    q.setAttribute("aria-expanded", "true");
  };
  q.addEventListener("focus", show);
  q.addEventListener("click", show);
  q.addEventListener("input", () => {
    if (!multiple) {
      selected = [];
      onSelect({ id: null, name: q.value });
    }
    show();
  });
  q.addEventListener("keydown", (e) => {
    if (e.key === "Escape") hide();
    if (e.key === "ArrowDown") {
      e.preventDefault();
      list.querySelector("button")?.focus();
    }
    if (e.key === "Enter" && multiple && q.value.trim()) {
      e.preventDefault();
      choose({ id: null, name: q.value.trim() });
    }
  });
  list.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const buttons = [...list.querySelectorAll("button")],
        i = buttons.indexOf(document.activeElement);
      buttons[
        (i + (e.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length
      ]?.focus();
    }
  });
  // Safari does not focus option buttons on a tap; closing on blur would remove
  // the option before its click. Close on an actual outside tap or keyboard focus.
  box.addEventListener("keydown", (e) => {
    if (e.key === "Tab")
      setTimeout(() => {
        if (!box.contains(document.activeElement)) hide();
      }, 0);
  });
  queueMicrotask(() =>
    box.closest("dialog")?.addEventListener("pointerdown", (e) => {
      if (!box.contains(e.target)) hide();
    }),
  );
  if (!multiple && selected[0]) q.value = selected[0].name;
  showChips();
  box.append(chips, q, list);
  return {
    node: box,
    get: () =>
      multiple
        ? [
            ...selected,
            ...(q.value.trim() ? [{ id: null, name: q.value.trim() }] : []),
          ]
        : selected[0] || { id: null, name: q.value.trim() },
    input: q,
  };
}
async function taskAction(r) {
  const a = C.primaryAction(state.profile, r.payload);
  if (!a) return;
  if (!(await confirm(a[0], r.payload.title, a[0]))) return;
  try {
    await changeRecord(r, { ...r.payload, status: a[1] });
  } catch (e) {
    fail(e);
  }
}
function taskDetail(record) {
  const r = state.records.find((x) => x.id === record.id) || record,
    p = r.payload,
    m = modal("مصوبه", { sheet: true });
  m.body.append(
    el("h2", { class: "detail-title" }, p.title),
    el(
      "div",
      { class: "detail-main" },
      el(
        "div",
        { class: "detail-data-grid" },
        metaItem("users", "مسئول انجام", p.owner),
        metaItem("calendar", "سررسید", C.label(p.due)),
        metaItem("check", "وضعیت", C.statuses[p.status]),
        metaItem("tasks", "اولویت", C.priorities[p.priority]),
      ),
      actionButton("users", "مصوبات این مسئول", () => personTasks(p), "tonal"),
    ),
    el(
      "div",
      { class: "row wrap" },
      actionButton("share", "اشتراک‌گذاری خلاصه", () => share(p), "outline"),
      C.admin(state.profile)
        ? actionButton("edit", "ویرایش مصوبه", () => {
            m.close();
            taskForm(r);
          })
        : null,
      C.admin(state.profile)
        ? actionButton(
            "clock",
            "تمدید یا تعویق",
            () => reschedule(r, m),
            "outline",
            {
              disabled:
                p.extension?.status === "PENDING" || p.status === "DONE",
            },
          )
        : null,
    ),
  );
  deadlinePanel(r, m);
  const action = C.primaryAction(state.profile, p);
  if (action)
    m.body.append(
      button(
        action[0],
        async () => {
          m.close();
          await taskAction(r);
        },
        "primary",
      ),
    );
  if (p.status === "WAITING")
    m.body.append(
      button(
        "شروع انجام",
        async () => {
          try {
            await changeRecord(r, { ...p, status: "ACTIVE" });
            m.close();
            taskDetail({ ...r, payload: { ...p, status: "ACTIVE" } });
          } catch (e) {
            fail(e);
          }
        },
        "outline",
      ),
    );
  const notes = related("followup", r.id),
    note = input("followup", "", {
      placeholder: "نتیجهٔ پیگیری در یک خط",
      maxlength: 1000,
      "aria-label": "نتیجهٔ پیگیری",
    }),
    add = button(
      "ثبت نتیجه",
      async () => {
        if (!note.value.trim()) return;
        add.disabled = true;
        try {
          await saveChanges([
            {
              id: uuid(),
              kind: "followup",
              payload: {
                task: r.id,
                text: note.value.trim(),
                created: Date.now(),
              },
            },
          ]);
          m.close();
          taskDetail(r);
        } catch (e) {
          fail(e);
          add.disabled = false;
        }
      },
      "tonal",
    );
  m.body.append(
    el("h3", {}, "آخرین پیگیری"),
    notes.length
      ? noteView(notes[0])
      : el("p", { class: "help" }, "هنوز نتیجه‌ای ثبت نشده"),
    field("نتیجهٔ پیگیری", note),
    add,
  );
  const history = related("history", r.id);
  const reminders = section(
    "زمان‌بندی و یادآوری‌ها",
    el(
      "div",
      { class: "detail-data-grid" },
      metaItem("clock", "وضعیت مهلت", dueLabel(p)),
      metaItem("note", "تعداد پیگیری‌ها", C.fa(notes.length)),
    ),
  );
  if (p.created > 0)
    reminders.append(
      metaItem("calendar", "تاریخ ثبت", C.label(C.today(p.created))),
    );
  if (r.actor) reminders.append(metaItem("users", "آخرین ثبت توسط", r.actor));
  if (p.submitted > 0)
    reminders.append(
      metaItem("check", "ارسال گزارش انجام", C.label(C.today(p.submitted))),
    );
  if (p.approved > 0)
    reminders.append(
      metaItem("check", "تأیید نهایی", C.label(C.today(p.approved))),
    );
  reminders.append(
    el("p", { class: "help" }, progressReminderHelp()),
    ...(p.reminders || []).map((at) =>
      metaItem(
        "clock",
        "یادآوری پیگیری",
        C.label(C.today(at)) + " — " + C.time(C.nowMinute(at)),
      ),
    ),
  );
  if (!p.reminders?.length)
    reminders.append(
      el("p", { class: "help" }, "یادآوری دستی دیگری ثبت نشده است."),
    );
  m.body.append(reminders);
  m.body.append(
    section(
      "شرح، جلسه و سوابق",
      p.description ? el("p", { class: "text-pre" }, p.description) : null,
      p.meeting
        ? button("مشاهدهٔ جلسه", () => {
            const meeting = meetings().find((x) => x.id === p.meeting);
            if (meeting) meetingDetail(meeting);
            else toast("جلسه در دسترس این حساب نیست");
          })
        : null,
      ...notes.slice(1).map(noteView),
      ...history.map((h) =>
        el(
          "p",
          { class: "help" },
          C.label(h.payload.old) +
            " ← " +
            C.label(h.payload.new) +
            " - " +
            h.payload.reason,
        ),
      ),
      C.admin(state.profile)
        ? button(
            "حذف مصوبه",
            async () => {
              if (
                await confirm(
                  "حذف مصوبه",
                  "مصوبه و نتایج پیگیری آن حذف شوند؟",
                  "حذف",
                )
              ) {
                await saveChanges([{ ...r, deleted: true, base: r }]);
                m.close();
              }
            },
            "danger",
          )
        : null,
    ),
  );
}
function progressReminderHelp() {
  const prefs = state.profile.preferences || {};
  return prefs.progressEnabled === false
    ? "یادآوری پیشرفت این حساب روی این مرورگر خاموش است؛ ثبت گزارش و درخواست تمدید در دسترس است."
    : `یادآوری این حساب روی این مرورگر پس از گذشت ${(prefs.progressPercent ?? 50).toLocaleString("fa-IR")}٪ مهلت؛ قابل تغییر در تنظیمات یادآوری‌ها.`;
}
function deadlinePanel(r, parent) {
  const p = r.payload,
    e = p.extension,
    box = el(
      "section",
      { class: "deadline-panel" },
      el("h3", {}, "پیگیری پیشرفت و تمدید"),
    );
  box.append(el("p", { class: "help" }, progressReminderHelp()));
  if (e) {
    box.append(
      el(
        "strong",
        {},
        e.status === "PENDING"
          ? "درخواست تمدید منتظر بررسی رئیس"
          : e.status === "APPROVED"
            ? "تمدید مهلت تأیید شد"
            : "درخواست رد شد؛ مهلت قبلی برقرار است",
      ),
      el("p", {}, "وضعیت کار: " + e.progress),
      el("p", {}, "دلیل: " + e.reason),
      el("p", {}, "مهلت پیشنهادی: " + C.label(e.due)),
    );
    if (
      e.status === "PENDING" &&
      state.profile.role === "manager" &&
      !["DONE", "REVIEW"].includes(p.status)
    ) {
      const decide = async (approve) => {
        await saveExtensionDecision(r, approve);
        parent.close();
        taskDetail(r);
      };
      const run = async (approve) => {
        const controls = box.querySelectorAll("button");
        controls.forEach((b) => (b.disabled = true));
        try {
          await decide(approve);
        } catch (error) {
          parent.error.textContent = error.message;
          parent.error.hidden = false;
          controls.forEach((b) => (b.disabled = false));
        }
      };
      box.append(
        button("تأیید تمدید", () => run(true), "primary"),
        button("رد درخواست", () => run(false), "outline"),
      );
    }
  }
  if (
    p.assignee === state.profile.userId &&
    e?.status !== "PENDING" &&
    !["DONE", "REVIEW"].includes(p.status)
  )
    box.append(
      button(
        "ثبت وضعیت و نیاز به تمدید",
        () => {
          const progress = el("textarea", { maxlength: 970 }),
            reason = el("textarea", { maxlength: 2000 }),
            needs = input("needsExtension", "", { type: "checkbox" }),
            due = dateField("مهلت پیشنهادی", Math.max(p.due + 1, C.today())),
            form = modal("درخواست تمدید", {
              sheet: true,
              save: async () => {
                if (
                  !progress.value.trim() ||
                  (needs.checked &&
                    (!reason.value.trim() ||
                      due.get() <= p.due ||
                      due.get() < C.today()))
                )
                  throw new Error(
                    "وضعیت، دلیل و تاریخی پس از مهلت فعلی را وارد کنید",
                  );
                const extension = {
                  id: uuid(),
                  status: "PENDING",
                  progress: progress.value.trim(),
                  reason: reason.value.trim(),
                  due: due.get(),
                  baseDue: p.due,
                  requestedBy: state.profile.userId,
                  requestedAt: Date.now(),
                  decidedBy: null,
                  decidedAt: 0,
                };
                if (needs.checked) await changeRecord(r, { ...p, extension });
                else
                  await saveChanges([
                    {
                      id: uuid(),
                      kind: "followup",
                      payload: {
                        task: r.id,
                        text: "نیاز به تمدید ندارم؛ " + progress.value.trim(),
                        created: Date.now(),
                      },
                    },
                  ]);
                parent.close();
                taskDetail(r);
              },
            });
          const extra = el(
            "div",
            { hidden: true },
            field("دلیل نیاز به تمدید", reason),
            due.node,
            el(
              "p",
              { class: "help" },
              "تا تأیید رئیس، مهلت فعلی تغییر نمی‌کند.",
            ),
          );
          needs.addEventListener(
            "change",
            () => (extra.hidden = !needs.checked),
          );
          form.body.append(
            field("کار در چه مرحله‌ای است؟", progress),
            el(
              "label",
              { class: "switch-label" },
              "نیاز به تمدید مهلت دارم",
              needs,
            ),
            extra,
          );
        },
        "tonal",
      ),
    );
  parent.body.append(box);
}
function noteView(r) {
  return el(
    "div",
    { class: "note" },
    el("p", { class: "text-pre" }, r.payload.text),
    el(
      "time",
      {},
      C.label(C.today(r.payload.created)) +
        " - " +
        C.time(C.nowMinute(r.payload.created)),
    ),
  );
}
function personTasks(p) {
  const rows = tasks().filter(
      (r) =>
        r.payload.status !== "DONE" &&
        (p.assignee
          ? r.payload.assignee === p.assignee
          : C.personKey(r.payload.owner) === C.personKey(p.owner)),
    ),
    m = modal(p.owner, { sheet: true });
  m.body.append(
    el("div", { class: "count-label" }, "مصوبات باز", badge(rows.length)),
    ...rows.map(taskCard),
  );
}
async function share(p) {
  const text = C.shareText(p);
  try {
    if (navigator.share) await navigator.share({ title: "خلاصهٔ مصوبه", text });
    else if (navigator.clipboard) {
      await navigator.clipboard.writeText(text);
      toast("خلاصه کپی شد");
    } else {
      const m = modal("خلاصهٔ مصوبه");
      m.body.append(el("textarea", { readOnly: true, value: text }));
    }
  } catch (e) {
    if (e.name !== "AbortError") fail(e);
  }
}

function taskForm(record = null, meeting = null) {
  if (meeting?.payload.reserved) {
    toast("برای جلسهٔ رزرو نمی‌توان مصوبه ثبت کرد");
    return;
  }
  const original = record?.payload,
    title = input("title", original?.title || "", {
      required: true,
      maxlength: 500,
    }),
    owner = personInput({
      initial: original?.owner
        ? [{ id: original.assignee, name: original.owner }]
        : [],
    }),
    due = dateField("سررسید", original?.due ?? state.day),
    description = el("textarea", {
      name: "description",
      maxlength: 10000,
      value: original?.description || "",
    }),
    priority = select("priority", C.priorities, original?.priority || "NORMAL"),
    meetingSelect = select(
      "meeting",
      {
        "": "بدون جلسه",
        ...Object.fromEntries(
          meetings()
            .filter((r) => !r.payload.reserved)
            .map((r) => [
              r.id,
              r.payload.title + " - " + C.label(r.payload.date),
            ]),
        ),
      },
      original?.meeting || meeting?.id || "",
    ),
    reminder = input("reminder", "", {
      type: "checkbox",
      checked: original ? !!original.reminders?.length : true,
    }),
    reminderTime = input(
      "reminderTime",
      original?.reminders?.[0]
        ? C.inputTime(C.nowMinute(original.reminders[0]))
        : "08:00",
      { type: "time" },
    ),
    reminderDate = dateField(
      "روز پیگیری",
      original?.reminders?.[0]
        ? C.today(original.reminders[0])
        : (original?.due ?? state.day) - 1,
    );
  const m = modal(record ? "ویرایش مصوبه" : "مصوبهٔ جدید", {
    sheet: true,
    save: async () => {
      const person = owner.get();
      if (!title.value.trim() || !person.name.trim())
        throw new Error("عنوان و مسئول را وارد کنید");
      let reminders = original?.reminders || [];
      if (reminder.checked) {
        const minute = C.parseTime(reminderTime.value);
        if (minute === null) throw new Error("ساعت پیگیری را انتخاب کنید");
        const day = original ? reminderDate.get() : due.get() - 1,
          at = C.officeAt(day, minute);
        const existing = original?.reminders || [];
        const unchanged =
          original &&
          existing[0] &&
          reminderDate.get() === C.today(existing[0]) &&
          minute === C.nowMinute(existing[0]);
        reminders = unchanged
          ? existing
          : [...(at > Date.now() ? [at] : []), ...existing.slice(1)];
      } else reminders = [];
      if (
        meetings().find((r) => r.id === meetingSelect.value)?.payload.reserved
      )
        throw new Error("برای جلسهٔ رزرو نمی‌توان مصوبه ثبت کرد");
      const payload = {
        ...(original || {}),
        created: original?.created || Date.now(),
        extension: original?.extension || null,
        title: title.value.trim(),
        description: description.value.trim(),
        owner: person.name.trim(),
        due: due.get(),
        priority: priority.value,
        status: original?.status || "WAITING",
        meeting: meetingSelect.value || null,
        reminders,
        assignee: person.id || null,
        submitted: original?.submitted || 0,
        approved: original?.approved || 0,
        approvedBy: original?.approvedBy || null,
      };
      const changes = [
        { id: record?.id || uuid(), kind: "task", payload, base: record },
      ];
      if (record && original.due !== payload.due)
        changes.push({
          id: uuid(),
          kind: "history",
          payload: {
            task: record.id,
            old: original.due,
            new: payload.due,
            changed: Date.now(),
            kind: "EDIT",
            reason: "ویرایش سررسید",
          },
        });
      await saveChanges(changes);
    },
  });
  if (original?.extension?.status === "PENDING")
    due.node.querySelector("button").disabled = true;
  m.body.classList.add("editor-form");
  m.body.append(
    field("عنوان", title),
    field("مسئول انجام", owner.node),
    el("p", { class: "help" }, progressReminderHelp()),
    due.node,
    section(
      "جزئیات مصوبه",
      field("اولویت", priority),
      field("جلسه", meetingSelect),
      field("شرح", description),
      el("label", { class: "switch-label" }, "یادآوری پیگیری", reminder),
      el(
        "p",
        { class: "help" },
        "پیش‌فرض یک روز قبل از سررسید - ساعت‌ها به وقت تهران هستند",
      ),
      ...(original ? [reminderDate.node] : []),
      field("ساعت پیگیری", reminderTime),
    ),
  );
}
function reschedule(r, parent) {
  const date = dateField("سررسید جدید", Math.max(C.today(), r.payload.due) + 1),
    kind = select("kind", { EXTEND: "تمدید", POSTPONE: "تعویق" }, "EXTEND"),
    reason = input("reason", "", { placeholder: "علت تغییر", maxlength: 1000 });
  const m = modal("تمدید یا تعویق", {
    save: async () => {
      if (date.get() === r.payload.due)
        throw new Error("تاریخ جدید را انتخاب کنید");
      const at = C.officeAt(date.get() - 1, 480);
      await saveChanges([
        {
          id: r.id,
          kind: "task",
          base: r,
          payload: {
            ...r.payload,
            due: date.get(),
            reminders: at > Date.now() ? [at] : [],
          },
        },
        {
          id: uuid(),
          kind: "history",
          payload: {
            task: r.id,
            old: r.payload.due,
            new: date.get(),
            changed: Date.now(),
            kind: kind.value,
            reason: reason.value.trim(),
          },
        },
      ]);
      parent.close();
    },
  });
  m.body.append(date.node, field("نوع تغییر", kind), field("علت", reason));
}
function meetingDetail(r) {
  const p = r.payload,
    m = modal("جلسه", { sheet: true }),
    people = [...(p.attendees || []).map(personName), ...(p.guests || [])],
    items = tasks().filter((t) => t.payload.meeting === r.id);
  m.body.append(
    el("h2", { class: "detail-title" }, p.title),
    el(
      "div",
      { class: "detail-main" },
      el(
        "div",
        { class: "detail-data-grid" },
        metaItem("calendar", "روز جلسه", C.label(p.date)),
        metaItem("clock", "ساعت", C.time(p.start) + " تا " + C.time(p.end)),
        metaItem("location", "محل یا لینک", p.location || "تعیین نشده"),
        metaItem("users", "شرکت‌کنندگان", C.fa(people.length) + " نفر"),
      ),
      el(
        "span",
        {
          class:
            "status-pill " +
            (p.reserved ? "reservation-pill" : "confirmed-pill"),
        },
        p.reserved ? "جلسهٔ رزرو" : "جلسهٔ قطعی",
      ),
    ),
    el(
      "div",
      { class: "chips" },
      people.map((n) => el("span", { class: "chip" }, n)),
    ),
    section(
      "دستور جلسه و یادداشت‌ها",
      el("p", { class: "text-pre" }, p.notes || "یادداشتی ثبت نشده"),
      people.length > 4 ? el("p", {}, people.join(" - ")) : null,
    ),
  );
  if (C.admin(state.profile))
    m.body.append(
      el(
        "div",
        { class: "row wrap" },
        button(
          [icon("edit"), " ویرایش"],
          () => {
            m.close();
            meetingForm(r);
          },
          "outline",
        ),
        button(
          [icon("calendar"), " کپی جلسه"],
          () => meetingForm(r, C.today(), p.start, true),
          "outline",
        ),
        p.reserved
          ? el(
              "p",
              { class: "help" },
              "جلسه رزرو است؛ برای ثبت مصوبه ابتدا رزرو را بردارید.",
            )
          : button("ثبت مصوبه", () => taskForm(null, r), "tonal"),
      ),
    );
  m.body.append(
    el("div", { class: "section-heading" }, "مصوبات جلسه", badge(items.length)),
    ...items.map(taskCard),
  );
  if (C.admin(state.profile))
    m.body.append(
      section(
        "مدیریت جلسه",
        button(
          "حذف جلسه",
          async () => {
            if (
              await confirm(
                "حذف جلسه",
                "جلسه حذف شود؟ مصوبات آن به‌صورت مستقل باقی می‌مانند.",
                "حذف",
              )
            ) {
              await saveChanges([{ ...r, deleted: true, base: r }]);
              m.close();
            }
          },
          "danger",
        ),
      ),
    );
}
function meetingForm(
  record = null,
  day = state.day,
  start = 540,
  copy = false,
) {
  const p = record?.payload,
    title = input("title", p?.title || "", { maxlength: 500, required: true }),
    reserved = input("reserved", "", {
      type: "checkbox",
      checked: !!p?.reserved,
    }),
    date = dateField("روز جلسه", copy ? day : (p?.date ?? day)),
    from = input("start", C.inputTime(copy ? start : (p?.start ?? start)), {
      type: "time",
      required: true,
    }),
    to = input(
      "end",
      C.inputTime(
        copy
          ? Math.min(1439, start + (p.end - p.start))
          : (p?.end ?? Math.min(1439, start + 60)),
      ),
      { type: "time", required: true },
    ),
    people = personInput({
      multiple: true,
      initial: [
        ...(p?.attendees || []).map((id) => ({ id, name: personName(id) })),
        ...(p?.guests || []).map((name) => ({ id: null, name })),
      ],
    }),
    location = input("location", p?.location || "", { maxlength: 500 }),
    notes = el("textarea", {
      name: "notes",
      value: p?.notes || "",
      maxlength: 10000,
    }),
    repeat = select(
      "repeat",
      {
        NONE: "بدون تکرار",
        DAILY: "هر روز",
        WEEKLY: "هر هفته",
        MONTHLY: "هر ماه شمسی",
      },
      record && !copy ? p.repeat || "NONE" : "NONE",
    ),
    until = dateField("پایان تکرار", p?.until ?? day + 30);
  if (record && !copy) repeat.disabled = true;
  const m = modal(copy ? "کپی جلسه" : record ? "ویرایش جلسه" : "جلسهٔ جدید", {
    sheet: true,
    save: async () => {
      const selected = people.get();
      if (selected.length > 200)
        throw new Error("حداکثر ۲۰۰ شرکت‌کننده انتخاب کنید");
      if (
        reserved.checked &&
        record &&
        !copy &&
        tasks().some((t) => t.payload.meeting === record.id)
      )
        throw new Error("جلسهٔ دارای مصوبه را نمی‌توان رزرو کرد");
      const payload = {
        reserved: reserved.checked,
        title: title.value.trim(),
        date: date.get(),
        notes: notes.value.trim(),
        start: C.parseTime(from.value),
        end: C.parseTime(to.value),
        location: location.value.trim(),
        attendees: [...new Set(selected.filter((x) => x.id).map((x) => x.id))],
        guests: selected
          .filter((x) => !x.id && x.name.trim())
          .map((x) => x.name.trim())
          .filter(
            (n, i, a) =>
              a.findIndex((x) => C.personKey(x) === C.personKey(n)) === i,
          ),
        repeat: repeat.value,
        until: repeat.value === "NONE" ? null : until.get(),
        series:
          repeat.value === "NONE" ? null : record && !copy ? p.series : uuid(),
      };
      const instances =
          record && !copy ? [payload] : C.meetingInstances(payload),
        collisions = C.conflicts(
          record && !copy ? { ...payload, repeat: "NONE" } : payload,
          meetings(),
          !copy ? record?.id : null,
        );
      if (
        collisions.length &&
        !(await confirm(
          "تداخل زمانی",
          C.fa(`${collisions.length} جلسه با شرکت‌کنندهٔ مشترک تداخل دارد`) +
            ": " +
            collisions
              .slice(0, 3)
              .map((r) => r.payload.title)
              .join(" - "),
          "ثبت با وجود تداخل",
        ))
      )
        throw new Error("زمان یا شرکت‌کنندگان را اصلاح کنید");
      await saveChanges(
        instances.map((x, i) => ({
          id: record && !copy && i === 0 ? record.id : uuid(),
          kind: "meeting",
          payload: x,
          base: record && !copy && i === 0 ? record : null,
        })),
      );
    },
  });
  m.body.classList.add("editor-form");
  m.body.append(
    field("عنوان جلسه", title),
    el("label", { class: "switch-label" }, "ثبت به‌صورت رزرو", reserved),
    el("p", { class: "help" }, "جلسهٔ رزرو زرد است و امکان ثبت مصوبه ندارد."),
    date.node,
    el("div", { class: "two-fields" }, field("شروع", from), field("پایان", to)),
    field("شرکت‌کنندگان", people.node, "انتخاب کاربر شرکت یا افزودن نام مخاطب"),
    section(
      "جزئیات و تکرار جلسه",
      field("محل جلسه", location),
      field("یادداشت", notes),
      field("تکرار", repeat),
      until.node,
      record && !copy
        ? el(
            "p",
            { class: "help" },
            "ویرایش فقط روی همین نوبت جلسه اعمال می‌شود.",
          )
        : null,
    ),
  );
}
function conflictValues(p, kind) {
  if (!Object.keys(p).length)
    return [el("p", { class: "help" }, "موردی ثبت نشده است")];
  return [
    el("p", {}, p.title || p.text || ""),
    p.owner ? el("p", {}, "مسئول: " + p.owner) : null,
    Number.isInteger(p.due) ? el("p", {}, "سررسید: " + C.label(p.due)) : null,
    p.status ? el("p", {}, C.statuses[p.status]) : null,
    Number.isInteger(p.date)
      ? el(
          "p",
          {},
          C.label(p.date) + " - " + C.time(p.start) + " تا " + C.time(p.end),
        )
      : null,
    p.description || p.notes
      ? el("p", { class: "text-pre" }, p.description || p.notes)
      : null,
  ].filter(Boolean);
}
function conflictDialog() {
  const rows = state.drafts.filter((d) => d.conflict),
    m = modal("تغییرات هم‌زمان", { sheet: true });
  for (const d of rows) {
    const remote = d.conflict.remote;
    m.body.append(
      el(
        "div",
        { class: "settings-group" },
        el("h3", {}, d.payload.title || "نتیجهٔ پیگیری"),
        el(
          "p",
          { class: "help" },
          d.conflict.message ||
            (remote?.deleted
              ? "این مورد در سرور حذف شده است"
              : "این مورد در گوشی دیگری هم تغییر کرده است"),
        ),
        el(
          "div",
          { class: "detail-main" },
          el("h3", {}, "تغییر شما"),
          ...conflictValues(d.payload, d.kind),
        ),
        el(
          "div",
          { class: "detail-main" },
          el("h3", {}, "نسخهٔ سرور"),
          ...conflictValues(remote?.payload || {}, d.kind),
        ),
        C.admin(state.profile) && ["task", "meeting"].includes(d.kind)
          ? button(
              "اصلاح اطلاعات",
              () => {
                m.close();
                const r = state.records.find((r) => r.id === d.id);
                if (r) (d.kind === "task" ? taskForm : meetingForm)(r);
              },
              "primary",
            )
          : null,
        button(
          "نسخهٔ سرور",
          async () => {
            await S.resolveConflict(state.profile.id, d.id, "server");
            m.close();
            await load();
            render();
          },
          "outline",
        ),
        button(
          "نگه داشتن تغییر من",
          async () => {
            try {
              await S.resolveConflict(state.profile.id, d.id, "mine");
              m.close();
              await load();
              render();
              schedule(0);
            } catch (e) {
              fail(e);
            }
          },
          "tonal",
        ),
      ),
    );
  }
}
async function loginRequest(company, username, password) {
  authGeneration++;
  const r = await fetch("/app/api/login", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ company, username, password }),
  });
  const value = await r.json();
  if (!r.ok) throw new Error(value.error || "ورود ناموفق بود");
  await S.putProfile({ ...value.profile, expired: false, error: null });
  return value.profile;
}
function loginFields(existing) {
  const company = input("company", existing?.company || "", {
      required: true,
      dir: "ltr",
      autocapitalize: "none",
      autocomplete: "organization",
    }),
    username = input("username", "", {
      required: true,
      dir: "ltr",
      autocapitalize: "none",
      autocomplete: "username",
    }),
    password = input("password", "", {
      required: true,
      type: "password",
      dir: "ltr",
      autocomplete: "current-password",
    }),
    form = el(
      "form",
      { class: "stack" },
      field("کد شرکت", company),
      field("نام کاربری", username),
      field("رمز عبور", password),
    ),
    error = el("p", { class: "error-text", role: "alert", hidden: true }),
    submit = el("button", { type: "submit", class: "primary" }, "ورود");
  form.append(error, submit, el("small", { dir: "ltr" }, location.origin));
  return { form, company, username, password, error, submit };
}
function loginPage() {
  const f = loginFields(),
    card = el(
      "div",
      { class: "login-card" },
      el(
        "div",
        { class: "login-brand" },
        el("img", { src: "/app/icon.svg", width: "64", height: "64", alt: "" }),
        el(
          "div",
          {},
          el("h1", {}, "پیگیر"),
          el("p", {}, "جلسات و مصوبات شرکت شما"),
        ),
      ),
      f.form,
      el(
        "div",
        { class: "install-guide" },
        "روی آیفون: در Safari گزینهٔ اشتراک‌گذاری و سپس «افزودن به صفحهٔ اصلی» را بزنید.",
      ),
    );
  root.replaceChildren(el("main", { class: "login-page", id: "main" }, card));
  f.form.addEventListener("submit", async (e) => {
    e.preventDefault();
    f.submit.disabled = true;
    f.error.hidden = true;
    try {
      await activate(
        await loginRequest(
          f.company.value.trim().toLowerCase(),
          f.username.value.trim(),
          f.password.value,
        ),
      );
      f.password.value = "";
    } catch (e) {
      f.error.textContent = e.message;
      f.error.hidden = false;
    } finally {
      f.submit.disabled = false;
    }
  });
}
function loginDialog(existing = null) {
  const m = modal(existing ? "ورود دوباره" : "افزودن حساب شرکت"),
    f = loginFields(existing);
  m.body.append(f.form);
  f.form.addEventListener("submit", async (e) => {
    e.preventDefault();
    f.submit.disabled = true;
    f.error.hidden = true;
    try {
      const p = await loginRequest(
        f.company.value.trim().toLowerCase(),
        f.username.value.trim(),
        f.password.value,
      );
      f.password.value = "";
      m.close();
      await activate(p);
      await attachPush(false);
    } catch (e) {
      f.error.textContent = e.message;
      f.error.hidden = false;
    } finally {
      f.submit.disabled = false;
    }
  });
}
function accountPicker() {
  const m = modal("حساب‌های شما");
  m.body.append(
    ...state.profiles.map((p) =>
      button(
        el(
          "div",
          {},
          el("strong", {}, p.companyName || p.company),
          el(
            "p",
            {},
            p.name +
              " - " +
              C.roles[p.role] +
              (p.expired ? " - ورود دوباره" : ""),
          ),
        ),
        async () => {
          m.close();
          if (p.expired) loginDialog(p);
          else await activate(p);
        },
        "profile-card" + (p.id === state.profile.id ? " selected" : ""),
      ),
    ),
    button(
      "افزودن حساب شرکت",
      () => {
        m.close();
        loginDialog();
      },
      "tonal",
    ),
  );
}

async function settings() {
  const m = modal("تنظیمات", { sheet: true }),
    p = state.profile,
    prefs = p.preferences || {},
    theme = select(
      "theme",
      { system: "هماهنگ با گوشی", light: "روشن", dark: "تیره" },
      state.theme,
    );
  theme.addEventListener("change", () => applyTheme(theme.value));
  const enabled = input("enabled", "", {
      type: "checkbox",
      checked: prefs.enabled !== false,
    }),
    daily = input("daily", "", {
      type: "checkbox",
      checked: prefs.daily !== false,
    }),
    progressEnabled = input("progressEnabled", "", {
      type: "checkbox",
      checked: prefs.progressEnabled !== false,
    }),
    progressPercent = input("progressPercent", prefs.progressPercent ?? 50, {
      type: "number",
      min: 1,
      max: 99,
      step: 1,
    }),
    taskReminders = input("taskReminders", "", {
      type: "checkbox",
      checked: prefs.taskReminders !== false,
    }),
    at = input("time", prefs.time || "08:00", { type: "time" }),
    lead = select(
      "lead",
      {
        "-1": "خاموش",
        0: "هنگام شروع",
        5: "۵ دقیقه قبل",
        10: "۱۰ دقیقه قبل",
        15: "۱۵ دقیقه قبل",
        30: "۳۰ دقیقه قبل",
        60: "یک ساعت قبل",
        1440: "یک روز قبل",
      },
      prefs.meetingLead ?? -1,
    ),
    updates = select(
      "updates",
      { all: "همهٔ تغییرات", essential: "فقط رویدادهای اصلی", off: "خاموش" },
      prefs.updates || "all",
    ),
    status = el("p", { class: "help" }, notificationStatus());
  progressPercent.disabled = !progressEnabled.checked;
  progressEnabled.addEventListener("change", () => {
    progressPercent.disabled = !progressEnabled.checked;
  });
  const permissionButton = button(
      "فعال کردن اعلان روی این گوشی",
      async () => {
        try {
          await attachPush(true);
          status.textContent = notificationStatus();
        } catch (e) {
          fail(e);
        }
      },
      "primary",
    ),
    save = button(
      "ذخیرهٔ یادآوری‌های این حساب",
      async () => {
        save.disabled = true;
        try {
          const percent = progressEnabled.checked
            ? Number(progressPercent.value)
            : (prefs.progressPercent ?? 50);
          if (!Number.isInteger(percent) || percent < 1 || percent > 99) {
            progressPercent.focus();
            throw new Error("درصد یادآوری باید عددی بین ۱ تا ۹۹ باشد");
          }
          const preferences = {
              enabled: enabled.checked,
              daily: daily.checked,
              time: at.value,
              meetingLead: Number(lead.value),
              updates: updates.value,
              progressEnabled: progressEnabled.checked,
              progressPercent: percent,
              taskReminders: taskReminders.checked,
            },
            value = await S.api(p, "preferences", { preferences });
          await S.putProfile({ ...p, preferences: value.preferences });
          await load();
          toast("تنظیمات ذخیره شد");
        } catch (e) {
          fail(e);
        } finally {
          save.disabled = false;
        }
      },
      "tonal",
    );
  m.body.append(
    el(
      "div",
      { class: "settings-group" },
      el("h3", {}, "نمایش"),
      field("رنگ برنامه", theme),
    ),
    el(
      "div",
      { class: "settings-group" },
      el("h3", {}, "اعلان‌های " + (p.companyName || p.company)),
      status,
      permissionButton,
      el("label", { class: "switch-label" }, "اعلان این حساب", enabled),
      el(
        "label",
        { class: "switch-label" },
        "یادآوری درصدی از مهلت",
        progressEnabled,
      ),
      field("درصد سپری‌شدن مهلت (۱ تا ۹۹)", progressPercent),
      el(
        "p",
        { class: "help" },
        "پیش‌فرض ۵۰٪ است؛ از زمان ثبت تا پایان روز سررسید محاسبه می‌شود. اگر زمان انتخاب‌شده گذشته باشد، یادآوری پس از ذخیره ارسال می‌شود.",
      ),
      el(
        "label",
        { class: "switch-label" },
        "یادآوری‌های دستی مصوبات",
        taskReminders,
      ),
      el("label", { class: "switch-label" }, "مرور روزانهٔ مصوبات", daily),
      field("ساعت مرور - به وقت تهران", at),
      field("یادآوری جلسه", lead),
      field("تغییرات", updates),
      el(
        "p",
        { class: "help" },
        "تنظیمات برای این حساب روی همین مرورگر است. خاموش‌کردن یادآوری، امکان ثبت گزارش و درخواست تمدید را تغییر نمی‌دهد.",
      ),
      save,
    ),
    webUpdateSettings(),
    el(
      "div",
      { class: "install-guide" },
      el("h3", {}, "نصب روی گوشی"),
      el(
        "p",
        {},
        "آیفون: در Safari دکمهٔ اشتراک‌گذاری را بزنید، «افزودن به صفحهٔ اصلی» را انتخاب کنید و پیگیر را از آیکون جدید باز کنید. اعلان وب به iOS 16.4 یا جدیدتر و اجازهٔ شما نیاز دارد.",
      ),
      installPrompt
        ? button(
            "نصب پیگیر",
            async () => {
              await installPrompt.prompt();
              installPrompt = null;
            },
            "tonal",
          )
        : null,
    ),
    el(
      "div",
      { class: "settings-group" },
      el("strong", {}, p.name),
      el("small", {}, p.companyName + " - " + C.roles[p.role]),
      C.admin(p) ? button("مدیریت کاربران", userManagement, "outline") : null,
      button("تغییر شرکت", accountPicker, "outline"),
      button(
        "خروج از این حساب",
        async () => {
          const data = await S.snapshot(p.id);
          if (
            !(await confirm(
              "خروج از حساب",
              data.drafts.length
                ? "تغییرات ارسال‌نشده روی این گوشی حذف می‌شوند. خارج شوید؟"
                : "از این حساب خارج شوید؟",
              "خروج",
            ))
          )
            return;
          try {
            await S.api(p, "logout");
            await S.clearData(p.id, true);
            m.close();
            state.profile = null;
            await load();
            render();
          } catch (e) {
            fail(e);
          }
        },
        "danger",
      ),
    ),
    el(
      "small",
      {},
      "پیگیر وب ۴.۱۰.۰ - اطلاعات هر شرکت جداگانه نگهداری می‌شوند",
    ),
  );
}
function webUpdateSettings() {
  const status = el(
      "p",
      { class: "help", role: "status" },
      "نسخهٔ وب نصب‌شده: ۴.۱۰.۰",
    ),
    apply = button(
      "اعمال نسخهٔ جدید",
      async () => {
        const reg =
          registration ||
          (await navigator.serviceWorker.getRegistration("/app/"));
        if (!reg?.waiting) {
          status.textContent = "نسخهٔ آماده‌ای وجود ندارد؛ دوباره بررسی کنید.";
          return;
        }
        if (
          !(await confirm(
            "به‌روزرسانی پیگیر",
            "برنامه دوباره باز می‌شود. تغییرات ذخیره‌شده و ارسال‌نشده حفظ می‌شوند.",
            "به‌روزرسانی",
          ))
        )
          return;
        apply.disabled = true;
        navigator.serviceWorker.addEventListener(
          "controllerchange",
          () => location.reload(),
          { once: true },
        );
        reg.waiting.postMessage({ type: "SKIP_WAITING" });
      },
      "primary",
    ),
    check = button(
      "بررسی به‌روزرسانی وب",
      async () => {
        check.disabled = true;
        status.textContent = "در حال بررسی نسخهٔ جدید…";
        try {
          if (!("serviceWorker" in navigator))
            throw new Error("به‌روزرسانی در این مرورگر در دسترس نیست.");
          const reg =
            registration ||
            (await navigator.serviceWorker.getRegistration("/app/"));
          if (!reg)
            throw new Error(
              "برای فعال‌شدن به‌روزرسانی، پیگیر را یک بار ببندید و باز کنید.",
            );
          await reg.update();
          if (reg.installing)
            await new Promise((resolve, reject) => {
              const worker = reg.installing;
              const timeout = setTimeout(() => {
                cleanup();
                reject(new Error("بررسی طول کشید؛ دوباره تلاش کنید."));
              }, 30000);
              const changed = () => {
                if (["installed", "redundant"].includes(worker.state)) {
                  cleanup();
                  resolve();
                }
              };
              function cleanup() {
                clearTimeout(timeout);
                worker.removeEventListener("statechange", changed);
              }
              worker.addEventListener("statechange", changed);
              changed();
            });
          apply.hidden = !reg.waiting;
          status.textContent = reg.waiting
            ? "نسخهٔ جدید دریافت شده و آمادهٔ اعمال است."
            : "نسخهٔ وب به‌روز است.";
        } catch (e) {
          status.textContent = e.message;
        } finally {
          check.disabled = false;
        }
      },
      "outline",
    );
  apply.hidden = true;
  return el(
    "div",
    { class: "settings-group" },
    el("h3", {}, "به‌روزرسانی برنامه"),
    status,
    check,
    apply,
  );
}
function notificationStatus() {
  if (!("Notification" in window) || !("PushManager" in window))
    return "اعلان وب در این مرورگر در دسترس نیست - روی آیفون نسخهٔ نصب‌شده از صفحهٔ اصلی را باز کنید";
  return Notification.permission === "granted"
    ? "مجوز اعلان فعال است"
    : Notification.permission === "denied"
      ? "اعلان مسدود شده است - از تنظیمات مرورگر یا گوشی اجازه بدهید"
      : "برای دریافت دعوت‌ها و یادآوری‌ها، اعلان را فعال کنید";
}
async function attachPush(ask) {
  if (!("PushManager" in window) || !("Notification" in window)) {
    if (!ask) return;
    throw new Error(
      "برای اعلان آیفون، ابتدا پیگیر را به صفحهٔ اصلی اضافه و از همان‌جا باز کنید",
    );
  }
  if (ask && Notification.permission !== "granted")
    await Notification.requestPermission();
  if (Notification.permission !== "granted") {
    if (ask) throw new Error("مجوز اعلان صادر نشده است");
    return;
  }
  const reg = registration || (await navigator.serviceWorker.ready),
    config = await (await fetch("/app/api/push-config")).json();
  let subscription = await reg.pushManager.getSubscription();
  if (!subscription) {
    const s = config.publicKey.replace(/-/g, "+").replace(/_/g, "/"),
      key = Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
    subscription = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: key,
    });
  }
  for (const p of state.profiles.filter((x) => !x.expired))
    await S.api(p, "push", { subscription: subscription.toJSON() });
  if (ask) toast("این مرورگر به سرویس اعلان متصل شد");
}
async function userManagement() {
  const p = state.profile,
    m = modal("مدیریت کاربران", { sheet: true }),
    list = el("div", { class: "stack" }),
    q = input("users", "", {
      type: "search",
      placeholder: "نام یا نام کاربری",
      "aria-label": "جست‌وجوی کاربران",
    });
  m.body.append(
    q,
    button("کاربر جدید", () => userForm(null, show), "tonal"),
    list,
  );
  let users = [];
  function paint() {
    list.replaceChildren(
      ...users
        .filter((u) => C.matches(u.name + " " + u.username, q.value))
        .map((u) =>
          button(
            el(
              "div",
              {},
              el("strong", {}, u.name),
              el(
                "p",
                { class: "help" },
                u.username +
                  " - " +
                  C.roles[u.role] +
                  (!u.active ? " - غیرفعال" : ""),
              ),
            ),
            () => userForm(u, show),
            "profile-card",
          ),
        ),
    );
  }
  async function show() {
    try {
      const r = await S.api(p, "users", { action: "list" });
      users = r.users;
      paint();
    } catch (e) {
      fail(e);
    }
  }
  q.addEventListener("input", paint);
  await show();
}
function userForm(user, reload) {
  const name = input("name", user?.name || "", { maxlength: 200 }),
    username = input("username", user?.username || "", {
      dir: "ltr",
      autocapitalize: "none",
    }),
    password = input("password", "", {
      type: "password",
      dir: "ltr",
      autocomplete: "new-password",
    }),
    role = select(
      "role",
      state.profile.role === "manager" ? C.roles : { deputy: "کاربر" },
      user?.role || "deputy",
    ),
    active = input("active", "", {
      type: "checkbox",
      checked: user?.active !== false,
    });
  const m = modal(user ? "ویرایش کاربر" : "کاربر جدید", {
    save: async () => {
      const payload = {
          action: user ? "update" : "create",
          ...(user
            ? {
                id: user.id,
                expected: {
                  name: user.name,
                  username: user.username,
                  role: user.role,
                  active: user.active,
                },
                active: active.checked,
              }
            : { id: uuid(), password: password.value }),
          name: name.value.trim(),
          username: username.value.trim().toLowerCase(),
          role: role.value,
        },
        r = await S.api(state.profile, "users", payload);
      password.value = "";
      if (r.reauthenticate) {
        await S.clearData(state.profile.id);
        await S.putProfile({ ...state.profile, expired: true });
        await load();
        render();
        toast("برای این تغییر، ورود دوباره لازم است");
      } else await reload();
      schedule(0);
    },
  });
  m.body.append(
    field("نام", name),
    field("نام کاربری", username),
    field("نقش", role),
  );
  if (!user) m.body.append(field("رمز - حداقل ۱۲ نویسه", password));
  else
    m.body.append(
      el("label", { class: "switch-label" }, "حساب فعال", active),
      details(
        field("رمز جدید - حداقل ۱۲ نویسه", password),
        button(
          "تغییر رمز و قطع نشست‌های قبلی",
          async () => {
            try {
              const r = await S.api(state.profile, "users", {
                action: "reset",
                id: user.id,
                password: password.value,
              });
              password.value = "";
              toast("رمز تغییر کرد");
              if (r.reauthenticate) {
                await S.clearData(state.profile.id);
                await S.putProfile({ ...state.profile, expired: true });
                await load();
                render();
                m.close();
              }
            } catch (e) {
              fail(e);
            }
          },
          "outline",
        ),
        button(
          "حذف کامل کاربر",
          async () => {
            if (
              !(await confirm(
                "حذف کاربر",
                "دسترسی " + user.name + " قطع و حساب حذف شود؟",
                "حذف",
              ))
            )
              return;
            try {
              await S.api(state.profile, "users", {
                action: "delete",
                id: user.id,
              });
              m.close();
              await reload();
              schedule(0);
            } catch (e) {
              fail(e);
            }
          },
          "danger",
        ),
      ),
    );
}
async function openLink(data) {
  const p = state.profiles.find((x) => x.id === data.profileId);
  if (!p) {
    toast("برای دیدن این اعلان، وارد حساب مربوط به آن شوید");
    return;
  }
  if (p.expired) {
    loginDialog(p);
    return;
  }
  await activate(p);
  try {
    await S.synchronize(p);
    await load();
    render();
  } catch {}
  if (state.profile?.id !== p.id) return;
  const r = state.records.find((x) => x.id === data.recordId);
  if (r?.kind === "meeting") meetingDetail(r);
  else if (r?.kind === "task") taskDetail(r);
  else if (data.recordId) toast("این مورد حذف شده یا در دسترس این حساب نیست");
}
async function boot() {
  try {
    applyTheme((await S.meta("theme")) || "system");
    const active = await S.meta("active");
    state.profiles = await S.allProfiles();
    state.profile =
      state.profiles.find((p) => p.id === active) || state.profiles[0] || null;
    await load();
    render();
    try {
      const generation = authGeneration,
        initialProfiles = [...state.profiles];
      const r = await fetch("/app/api/accounts", {
        credentials: "same-origin",
      });
      if (r.ok && generation === authGeneration) {
        const value = await r.json(),
          ids = new Set(value.profiles.map((p) => p.id));
        for (const p of initialProfiles)
          if (!ids.has(p.id)) await S.clearData(p.id, true);
        for (const p of value.profiles)
          await S.putProfile({ ...p, expired: false });
        await load();
        if (state.profile) render();
      }
    } catch {}
    if ("serviceWorker" in navigator) {
      registration = await navigator.serviceWorker.register("/app/sw.js", {
        scope: "/app/",
      });
      registration.addEventListener("updatefound", () => {
        const worker = registration.installing;
        worker?.addEventListener("statechange", () => {
          if (
            worker.state === "installed" &&
            navigator.serviceWorker.controller
          )
            toast(
              "نسخهٔ جدید آماده است - پس از بستن و بازکردن پیگیر فعال می‌شود",
            );
        });
      });
      navigator.serviceWorker.addEventListener("message", (e) => {
        if (e.data?.type === "open-record") openLink(e.data);
        else if (e.data?.type === "sync") schedule(100);
      });
      attachPush(false).catch(() => {});
    }
    const params = new URLSearchParams(location.search);
    if (params.has("account")) {
      await openLink({
        profileId: params.get("account"),
        recordId: params.get("record"),
      });
      history.replaceState(null, "", "/app/");
    }
    document.documentElement.dataset.ready = "true";
    schedule(0);
  } catch (e) {
    root.replaceChildren(
      empty(
        "مرورگر امکان ذخیره‌سازی را فراهم نکرد - حالت خصوصی را ببندید و دوباره تلاش کنید",
        button("تلاش دوباره", () => location.reload(), "primary"),
      ),
    );
    console.error("Peygir initialization failed", e.name);
  }
}
window.addEventListener("online", () => schedule(0));
window.addEventListener("offline", renderStatus);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) schedule(0);
});
window.addEventListener("beforeinstallprompt", (e) => {
  e.preventDefault();
  installPrompt = e;
});
boot();

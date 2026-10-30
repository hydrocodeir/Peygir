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
  limit: 50,
  busy: false,
  backoff: 0,
  theme: "system",
};
let timer,
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
    error = el("p", { class: "error-text", role: "alert", hidden: true });
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
  tasks = () =>
    records("task").sort((a, b) =>
      C.taskOrder(a, b, C.today(), state.profile.role),
    ),
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
  if (state.busy) return;
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
  schedule(
    error
      ? [10000, 30000, 60000, 120000, 300000][Math.max(0, state.backoff - 1)]
      : 30000,
  );
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
  await load();
  render();
  schedule(0);
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
  observer?.disconnect();
  if (!state.profile) {
    loginPage();
    return;
  }
  const p = state.profile,
    header = el(
      "header",
      { class: "app-header" },
      button(
        el(
          "div",
          {},
          el("div", { class: "name" }, p.companyName || p.company, " ⌄"),
          el("div", { class: "connection", id: "connection" }),
        ),
        accountPicker,
        "company-button",
        { "aria-label": "انتخاب شرکت و حساب" },
      ),
      ib("refresh", "به‌روزرسانی اتصال و اطلاعات", refresh),
      ib("settings", "تنظیمات", settings),
    ),
    main = el("main", { id: "main", tabindex: "-1" }),
    shell = el("div", { class: "app-shell" }, header);
  if (p.expired)
    shell.append(
      el(
        "div",
        { class: "banner error" },
        button("نشست پایان یافته است - ورود دوباره", () => loginDialog(p)),
      ),
    );
  else if (state.drafts.length)
    shell.append(
      el(
        "div",
        { class: "banner" },
        button(
          el(
            "span",
            { class: "row" },
            "در انتظار ارسال",
            badge(state.drafts.length),
          ),
          () =>
            state.drafts.some((d) => d.conflict) ? conflictDialog() : refresh(),
        ),
      ),
    );
  shell.append(main);
  shell.append(
    el(
      "nav",
      { class: "tabs", "aria-label": "بخش‌های برنامه" },
      [
        ["meetings", "جلسات", "users"],
        ["followup", "پیگیری", "tasks"],
        ["tasks", "مصوبات", "check"],
        ["review", "مرور", "calendar"],
      ].map(([id, text, i]) =>
        button(
          [el("span", { class: "tab-icon" }, icon(i)), el("span", {}, text)],
          () => {
            state.tab = id;
            state.query = "";
            state.limit = 50;
            render();
          },
          "tab",
          { "aria-current": state.tab === id ? "page" : null },
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
  main.append(
    el(
      "div",
      { class: "toolbar" },
      segment,
      ib("search", "جست‌وجوی جلسه یا افراد", meetingSearch),
      C.admin(state.profile)
        ? ib("plus", "جلسهٔ جدید", () => meetingForm(null, state.day))
        : null,
    ),
    dateNav(),
  );
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
    const selected = meetings().filter((r) => r.payload.date === state.day);
    content.append(
      el(
        "div",
        { class: "section-heading" },
        C.label(state.day),
        badge(selected.length),
      ),
      el("div", { class: "cards" }, selected.map(meetingCard)),
    );
    if (!selected.length)
      content.append(
        empty(
          "جلسه‌ای برای این روز ثبت نشده",
          C.admin(state.profile)
            ? button("ثبت جلسه", () => meetingForm(null, state.day), "tonal")
            : null,
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
          : r.payload.title,
        () => (dense ? dayMeetingList(dense) : meetingDetail(r)),
        "event" + (dense ? " dense" : " tone-" + (C.solar(day).day % 3)),
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
  const p = r.payload;
  return button(
    [
      el("strong", {}, p.title),
      el(
        "small",
        {},
        C.label(p.date) + " - " + C.time(p.start) + " تا " + C.time(p.end),
      ),
      p.location ? el("small", {}, p.location) : null,
    ],
    () => meetingDetail(r),
    "meeting-card",
  );
}
function dayMeetingList(items) {
  const m = modal("جلسات هم‌زمان", { sheet: true });
  m.body.append(
    ...items.sort((a, b) => a.payload.start - b.payload.start).map(meetingCard),
  );
}
function meetingSearch() {
  const m = modal("جست‌وجوی جلسات", { sheet: true }),
    q = input("search", "", {
      type: "search",
      placeholder: "نام جلسه یا شرکت‌کننده",
      "aria-label": "جست‌وجوی جلسات",
    }),
    list = el("div", { class: "search-list" });
  m.body.append(q, list);
  let limit = 40;
  const show = () => {
    const rows = meetings().filter((r) =>
      C.matches(
        [
          r.payload.title,
          r.payload.location,
          ...(r.payload.attendees || []).map(personName),
          ...(r.payload.guests || []),
        ].join(" "),
        q.value,
      ),
    );
    list.replaceChildren(
      el("div", { class: "count-label" }, "نتیجه", badge(rows.length)),
      ...rows.slice(0, limit).map(meetingCard),
    );
    if (rows.length > limit)
      list.append(
        button(
          "نمایش بیشتر",
          () => {
            limit += 40;
            show();
          },
          "tonal",
        ),
      );
  };
  q.addEventListener("input", () => {
    limit = 40;
    show();
  });
  show();
  q.focus();
}

function taskCard(r) {
  const p = r.payload,
    action = C.primaryAction(state.profile, p),
    late = p.due < C.today() && p.status !== "DONE";
  return el(
    "article",
    { class: "task-card" + (p.status === "DONE" ? " done" : "") },
    button(
      [
        el("div", { class: "task-title" }, p.title),
        el(
          "div",
          { class: "task-meta" + (late ? " late" : "") },
          p.owner + " - " + C.label(p.due),
        ),
        el(
          "div",
          { class: "task-status" },
          C.statuses[p.status],
          r.local ? " - در انتظار ارسال" : "",
        ),
      ],
      () => taskDetail(r),
      "task-body",
    ),
    action
      ? button(icon("check"), () => taskAction(r), "task-state", {
          "aria-label": action[0] + " - " + p.title,
        })
      : null,
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
  const rows = tasks().filter(
    (r) =>
      C.filterTask(r.payload, state.filter) &&
      C.matches(
        [
          r.payload.title,
          r.payload.owner,
          r.payload.description,
          meetings().find((m) => m.id === r.payload.meeting)?.payload.title ||
            "",
        ].join(" "),
        state.query,
      ),
  );
  main.append(
    el(
      "div",
      { class: "toolbar" },
      filter,
      badge(rows.length),
      ib("search", "جست‌وجوی مصوبات", taskSearch),
      C.admin(state.profile)
        ? ib("plus", "مصوبهٔ جدید", () => taskForm())
        : null,
    ),
  );
  if (state.query)
    main.append(
      el(
        "div",
        { class: "banner row" },
        el("span", { class: "grow" }, state.query),
        button("پاک کردن", () => {
          state.query = "";
          render();
        }),
      ),
    );
  const body = el("div", { class: "page-content" });
  main.append(body);
  if (rows.length) listTasks(body, rows);
  else body.append(empty("مصوبه‌ای در این فهرست نیست"));
}
function taskSearch() {
  const m = modal("جست‌وجوی مصوبات"),
    q = input("query", state.query, {
      type: "search",
      placeholder: "عنوان، مسئول یا جلسه",
      "aria-label": "جست‌وجوی مصوبات",
    });
  const run = () => {
    state.query = q.value;
    state.tab = "tasks";
    state.filter = "all";
    state.limit = 50;
    m.close();
    render();
  };
  m.body.append(q, button("جست‌وجو", run, "primary"));
  q.addEventListener("keydown", (e) => {
    if (e.key === "Enter") run();
  });
  q.focus();
}
function renderFollowup(main) {
  main.append(
    el(
      "div",
      { class: "toolbar" },
      el("h1", { class: "grow" }, "پیگیری"),
      C.admin(state.profile)
        ? button("ثبت مصوبه", () => taskForm(), "tonal")
        : null,
    ),
  );
  const body = el("div", { class: "page-content" }),
    all = tasks();
  let count = 0;
  for (const f of [
    ...(state.profile.role === "manager" ? ["REVIEW"] : []),
    "late",
    "today",
    "near",
  ]) {
    const rows = all.filter((r) => C.filterTask(r.payload, f));
    if (!rows.length) continue;
    count += rows.length;
    body.append(
      el(
        "div",
        { class: "section-heading" },
        button([C.filters[f], badge(rows.length)], () => {
          state.tab = "tasks";
          state.filter = f;
          state.query = "";
          state.limit = 50;
          render();
        }),
      ),
      el("div", { class: "cards" }, rows.slice(0, 5).map(taskCard)),
    );
    if (rows.length > 5)
      body.append(
        button("دیدن همه", () => {
          state.tab = "tasks";
          state.filter = f;
          state.limit = 50;
          render();
        }),
      );
  }
  if (!count) body.append(empty("پیگیری فوری ندارید"));
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
      button(p.owner, () => personTasks(p), "text-start tonal"),
      el(
        "div",
        { class: "detail-meta" },
        C.label(p.due),
        C.statuses[p.status],
        C.priorities[p.priority],
      ),
    ),
    el(
      "div",
      { class: "row wrap" },
      ib("share", "اشتراک‌گذاری خلاصه", () => share(p)),
      C.admin(state.profile)
        ? ib("edit", "ویرایش مصوبه", () => {
            m.close();
            taskForm(r);
          })
        : null,
      C.admin(state.profile)
        ? button("تمدید یا تعویق", () => reschedule(r, m))
        : null,
    ),
  );
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
  m.body.append(
    details(
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
          meetings().map((r) => [
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
        reminders = at > Date.now() ? [at] : [];
      } else reminders = [];
      const payload = {
        ...(original || {}),
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
  m.body.append(
    field("عنوان", title),
    field("مسئول انجام", owner.node),
    due.node,
    details(
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
      C.label(p.date),
      C.time(p.start) + " تا " + C.time(p.end),
      p.location || null,
    ),
    el(
      "div",
      { class: "chips" },
      people.slice(0, 4).map((n) => el("span", { class: "chip" }, n)),
    ),
    details(
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
        button("ثبت مصوبه", () => taskForm(null, r), "tonal"),
      ),
    );
  m.body.append(
    el("div", { class: "section-heading" }, "مصوبات جلسه", badge(items.length)),
    ...items.map(taskCard),
  );
  if (C.admin(state.profile))
    m.body.append(
      details(
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
      "NONE",
    ),
    until = dateField("پایان تکرار", day + 30);
  const m = modal(copy ? "کپی جلسه" : record ? "ویرایش جلسه" : "جلسهٔ جدید", {
    sheet: true,
    save: async () => {
      const selected = people.get();
      if (selected.length > 200)
        throw new Error("حداکثر ۲۰۰ شرکت‌کننده انتخاب کنید");
      const payload = {
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
        series: repeat.value === "NONE" ? null : uuid(),
      };
      const instances = C.meetingInstances(payload),
        collisions = C.conflicts(
          payload,
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
  m.body.append(
    field("عنوان جلسه", title),
    date.node,
    el("div", { class: "two-fields" }, field("شروع", from), field("پایان", to)),
    field("شرکت‌کنندگان", people.node, "انتخاب کاربر شرکت یا افزودن نام مخاطب"),
    details(
      field("محل جلسه", location),
      field("یادداشت", notes),
      field("تکرار", repeat),
      until.node,
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
      { all: "همهٔ تغییرات", essential: "فقط دعوت، واگذاری و یادآوری" },
      prefs.updates || "all",
    ),
    status = el("p", { class: "help" }, notificationStatus());
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
          const preferences = {
              enabled: enabled.checked,
              daily: daily.checked,
              time: at.value,
              meetingLead: Number(lead.value),
              updates: updates.value,
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
      el("label", { class: "switch-label" }, "مرور روزانهٔ مصوبات", daily),
      field("ساعت مرور - به وقت تهران", at),
      field("یادآوری جلسه", lead),
      field("تغییرات", updates),
      save,
    ),
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
    el("small", {}, "پیگیر وب ۴.۷.۰ - اطلاعات هر شرکت جداگانه نگهداری می‌شوند"),
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
  if(state.profile?.id!==p.id)return;
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

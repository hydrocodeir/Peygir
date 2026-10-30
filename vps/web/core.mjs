export const months = [
  "فروردین",
  "اردیبهشت",
  "خرداد",
  "تیر",
  "مرداد",
  "شهریور",
  "مهر",
  "آبان",
  "آذر",
  "دی",
  "بهمن",
  "اسفند",
];
export const weekdays = [
  "شنبه",
  "یکشنبه",
  "دوشنبه",
  "سه‌شنبه",
  "چهارشنبه",
  "پنجشنبه",
  "جمعه",
];
export const statuses = {
  WAITING: "در انتظار",
  ACTIVE: "در حال انجام",
  REVIEW: "منتظر تأیید رئیس",
  DONE: "انجام‌شده",
};
export const priorities = {
  LOW: "کم",
  NORMAL: "عادی",
  HIGH: "مهم",
  URGENT: "فوری",
};
export const roles = { manager: "مدیر", assistant: "دستیار", deputy: "کاربر" };
export const filters = {
  open: "باز",
  today: "امروز",
  late: "معوق",
  near: "نزدیک سررسید",
  WAITING: "در انتظار",
  ACTIVE: "در حال انجام",
  REVIEW: "منتظر تأیید",
  DONE: "انجام‌شده",
  all: "همه",
};
export const fa = (value) =>
  String(value).replace(/\d/g, (d) => "۰۱۲۳۴۵۶۷۸۹"[Number(d)]);
export const latin = (value) =>
  String(value).replace(/[۰-۹٠-٩]/g, (c) =>
    String(
      c.charCodeAt(0) >= 1776 ? c.charCodeAt(0) - 1776 : c.charCodeAt(0) - 1632,
    ),
  );
export const clean = (value) =>
  latin(value ?? "")
    .normalize("NFKC")
    .replace(/[يى]/g, "ی")
    .replace(/ك/g, "ک")
    .replace(/[\u064b-\u065f\u0670ـ]/g, "")
    .replace(/\u200c/g, " ")
    .trim()
    .toLowerCase();
export const personKey = (value) =>
  clean(value)
    .replace(/^(?:(?:آقا|آقای|خانم|جناب|سرکار|دکتر|مهندس)\s+)+/, "")
    .replace(/[\s\p{P}]/gu, "");
export const matches = (text, query) =>
  clean(query)
    .split(/\s+/)
    .filter(Boolean)
    .every((part) => clean(text).includes(part));
const persian = new Intl.DateTimeFormat("en-u-ca-persian", {
    timeZone: "UTC",
    year: "numeric",
    month: "numeric",
    day: "numeric",
  }),
  cache = new Map();
export function solar(day) {
  if (!Number.isSafeInteger(day)) throw new Error("تاریخ معتبر نیست");
  if (cache.has(day)) return cache.get(day);
  const p = Object.fromEntries(
    persian
      .formatToParts(new Date(day * 86400000 + 43200000))
      .filter((x) => x.type !== "literal")
      .map((x) => [x.type, Number(x.value)]),
  );
  const value = { year: p.year, month: p.month, day: p.day };
  if (cache.size > 4000) cache.clear();
  cache.set(day, value);
  return value;
}
export function epoch(year, month, day) {
  if (
    ![year, month, day].every(Number.isInteger) ||
    year < 1200 ||
    year > 1600 ||
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > 31
  )
    return null;
  let lo = Math.floor(Date.UTC(year + 620, 0, 1) / 86400000),
    hi = Math.floor(Date.UTC(year + 623, 0, 1) / 86400000);
  while (lo <= hi) {
    const mid = Math.floor((lo + hi) / 2),
      p = solar(mid),
      d = p.year - year || p.month - month || p.day - day;
    if (d === 0) return mid;
    if (d < 0) lo = mid + 1;
    else hi = mid - 1;
  }
  return null;
}
export const numeric = (day) => {
  const p = solar(day);
  return fa(
    `${p.year}/${String(p.month).padStart(2, "0")}/${String(p.day).padStart(2, "0")}`,
  );
};
export const label = (day) => {
  const p = solar(day);
  return fa(`${p.day} ${months[p.month - 1]} ${p.year}`);
};
export const parseSolar = (value) => {
  const m = latin(value)
    .trim()
    .match(/^(1[2-6]\d{2})[\/-](\d{1,2})[\/-](\d{1,2})$/);
  return m ? epoch(...m.slice(1).map(Number)) : null;
};
export const monthStart = (day) => day - solar(day).day + 1;
export const nextMonth = (day) => monthStart(monthStart(day) + 32);
export const previousMonth = (day) => monthStart(monthStart(day) - 1);
export const weekStart = (day) =>
  day - ((new Date(day * 86400000).getUTCDay() + 1) % 7);
export const monthDays = (day) => nextMonth(day) - monthStart(day);
export const today = (now = Date.now()) => {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: "Asia/Tehran",
      year: "numeric",
      month: "numeric",
      day: "numeric",
    })
      .formatToParts(now)
      .filter((x) => x.type !== "literal")
      .map((x) => [x.type, Number(x.value)]),
  );
  return Math.floor(Date.UTC(p.year, p.month - 1, p.day) / 86400000);
};
export const nowMinute = (now = Date.now()) => {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: "Asia/Tehran",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(now)
      .filter((x) => x.type !== "literal")
      .map((x) => [x.type, Number(x.value)]),
  );
  return p.hour * 60 + p.minute;
};
export function officeAt(day, minute = 480) {
  const target = day * 86400000 + minute * 60000;
  let v = target;
  for (let i = 0; i < 3; i++) {
    const p = Object.fromEntries(
      new Intl.DateTimeFormat("en-US", {
        timeZone: "Asia/Tehran",
        year: "numeric",
        month: "numeric",
        day: "numeric",
        hour: "numeric",
        minute: "numeric",
        second: "numeric",
        hourCycle: "h23",
      })
        .formatToParts(v)
        .filter((x) => x.type !== "literal")
        .map((x) => [x.type, Number(x.value)]),
    );
    v +=
      target - Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  }
  return v;
}
export const time = (value) =>
  value === null || value === undefined
    ? "بدون ساعت"
    : fa(
        `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`,
      );
export const inputTime = (value) =>
  `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`;
export const parseTime = (value) => {
  const m = latin(value).match(/^([01]\d|2[0-3]):([0-5]\d)$/);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};
export const admin = (profile) =>
  profile?.role === "manager" || profile?.role === "assistant";
export function taskOrder(a, b, day = today(), role = "manager") {
  const rank = (p) =>
    p.status === "DONE"
      ? 5
      : role === "manager" && p.status === "REVIEW"
        ? 0
        : p.status === "REVIEW"
          ? 4
          : p.due < day
            ? 1
            : p.due === day
              ? 2
              : 3;
  const priority = (p) => Object.keys(priorities).indexOf(p.priority);
  return (
    rank(a.payload) - rank(b.payload) ||
    a.payload.due - b.payload.due ||
    priority(b.payload) - priority(a.payload) ||
    a.id.localeCompare(b.id)
  );
}
export function filterTask(p, filter, day = today()) {
  return (
    filter === "all" ||
    (filter === "open"
      ? p.status !== "DONE"
      : filter === "today"
        ? p.status !== "DONE" && p.due === day
        : filter === "late"
          ? p.status !== "DONE" && p.due < day
          : filter === "near"
            ? p.status !== "DONE" && p.due >= day && p.due <= day + 3
            : p.status === filter)
  );
}
export function primaryAction(profile, p) {
  if (p.status === "DONE") return null;
  if (p.status === "REVIEW")
    return profile.role === "manager" ? ["تأیید نهایی انجام", "DONE"] : null;
  return p.assignee || profile.role === "deputy"
    ? ["ارسال برای تأیید", "REVIEW"]
    : ["انجام شد", "DONE"];
}
export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
export function mergePayload(base, local, remote) {
  const out = {};
  for (const key of new Set([
    ...Object.keys(base || {}),
    ...Object.keys(local || {}),
    ...Object.keys(remote || {}),
  ])) {
    const b = base?.[key],
      l = local?.[key],
      r = remote?.[key];
    if (same(l, r)) out[key] = l;
    else if (same(l, b)) out[key] = r;
    else if (same(r, b)) out[key] = l;
    else if (key === "reminders" && [b, l, r].every(Array.isArray)) {
      const old = new Set(b);
      out[key] = [
        ...new Set([
          ...b.filter((x) => l.includes(x) && r.includes(x)),
          ...l.filter((x) => !old.has(x)),
          ...r.filter((x) => !old.has(x)),
        ]),
      ].sort((x, y) => x - y);
    } else return null;
  }
  return out;
}
export function meetingInstances(p) {
  if (
    !p.title.trim() ||
    !Number.isInteger(p.start) ||
    !Number.isInteger(p.end) ||
    p.start < 0 ||
    p.end > 1439 ||
    p.end <= p.start
  )
    throw new Error("عنوان و ساعت شروع و پایان معتبر را وارد کنید");
  if (!p.repeat || p.repeat === "NONE") return [p];
  if (
    !Number.isInteger(p.until) ||
    !["DAILY", "WEEKLY", "MONTHLY"].includes(p.repeat) ||
    p.until < p.date ||
    p.until - p.date > 366
  )
    throw new Error("پایان تکرار باید حداکثر یک سال بعد باشد");
  const records = [],
    wanted = solar(p.date).day;
  let day = p.date;
  while (day <= p.until) {
    records.push({ ...p, date: day });
    day =
      p.repeat === "DAILY"
        ? day + 1
        : p.repeat === "WEEKLY"
          ? day + 7
          : nextMonth(day) + Math.min(wanted, monthDays(nextMonth(day))) - 1;
  }
  return records;
}
export function conflicts(p, meetings, exclude = null) {
  const people = new Set([
    ...(p.attendees || []),
    ...(p.guests || []).map(personKey),
  ]);
  const instances = meetingInstances(p);
  return meetings.filter(
    (r) =>
      r.id !== exclude &&
      !r.deleted &&
      instances.some(
        (x) =>
          x.date === r.payload.date &&
          x.start < r.payload.end &&
          x.end > r.payload.start &&
          [
            ...(r.payload.attendees || []),
            ...(r.payload.guests || []).map(personKey),
          ].some((id) => people.has(id)),
      ),
  );
}
export function placements(records) {
  const sorted = records
    .filter((r) => r.payload.start !== null && r.payload.end > r.payload.start)
    .sort(
      (a, b) =>
        a.payload.start - b.payload.start ||
        a.payload.end - b.payload.end ||
        a.id.localeCompare(b.id),
    );
  const result = [];
  let group = [],
    end = -1;
  function flush() {
    const ends = [],
      rows = group.map((r) => {
        let lane = ends.findIndex((e) => e <= r.payload.start);
        if (lane < 0) lane = ends.length;
        ends[lane] = r.payload.end;
        return { record: r, lane };
      });
    result.push(...rows.map((r) => ({ ...r, lanes: ends.length })));
    group = [];
  }
  for (const r of sorted) {
    if (group.length && r.payload.start >= end) flush();
    end = group.length ? Math.max(end, r.payload.end) : r.payload.end;
    group.push(r);
  }
  if (group.length) flush();
  return result;
}
export function displayGroups(records, limit = 3) {
  const positioned = placements(records),
    out = [];
  let group = [],
    end = -1;
  function flush() {
    if (group.some((x) => x.lanes > limit))
      out.push({
        dense: group.map((x) => x.record),
        start: group[0].record.payload.start,
        end,
      });
    else out.push(...group);
    group = [];
  }
  for (const p of positioned) {
    if (group.length && p.record.payload.start >= end) flush();
    end = group.length
      ? Math.max(end, p.record.payload.end)
      : p.record.payload.end;
    group.push(p);
  }
  if (group.length) flush();
  return out;
}
export function suggestions(users, contacts, query = "", selected = []) {
  const used = new Set(selected),
    known = new Set(
      users.filter((u) => u.active).map((u) => personKey(u.name)),
    );
  return [
    ...users
      .filter((u) => u.active && !used.has(u.id))
      .map((u) => ({
        id: u.id,
        name: u.name,
        username: u.username || "حساب شرکت",
      })),
    ...contacts
      .filter((n) => n.trim() && !known.has(personKey(n)))
      .filter(
        (n, i, a) => a.findIndex((x) => personKey(x) === personKey(n)) === i,
      )
      .filter((n) => !used.has(personKey(n)))
      .map((name) => ({ id: null, name, username: "مخاطب" })),
  ].filter((p) => matches(p.name, query));
}
export function shareText(p) {
  return `مصوبه: ${p.title.trim()}\nمسئول: ${p.owner.trim()}\nسررسید: ${numeric(p.due)}`;
}

import test from "node:test";
import assert from "node:assert/strict";
import * as C from "../web/core.mjs";
test("Jalali conversion covers Nowruz, leap Esfand and all days across four centuries", () => {
  assert.equal(
    C.epoch(1405, 1, 1),
    Math.floor(Date.UTC(2026, 2, 21) / 86400000),
  );
  assert.notEqual(C.epoch(1403, 12, 30), null);
  assert.equal(C.epoch(1404, 12, 30), null);
  assert.equal(C.parseSolar("۱۴۰۵/۰۸/۱۵"), C.epoch(1405, 8, 15));
  for (let y = 1200; y <= 1600; y += 5)
    for (let m = 1; m <= 12; m++) {
      const d = C.epoch(y, m, 1);
      assert.deepEqual(C.solar(d), { year: y, month: m, day: 1 });
      assert.ok([29, 30, 31].includes(C.monthDays(d)));
    }
  assert.equal(C.parseSolar("1405/13/01"), null);
});
test("month navigation and weekly dates remain solar and Saturday based", () => {
  const d = C.epoch(1405, 12, 29);
  assert.deepEqual(C.solar(C.nextMonth(d)), { year: 1406, month: 1, day: 1 });
  assert.deepEqual(C.solar(C.previousMonth(C.epoch(1405, 1, 1))), {
    year: 1404,
    month: 12,
    day: 1,
  });
  assert.equal(new Date(C.weekStart(d) * 86400000).getUTCDay(), 6);
  assert.equal(C.today(C.officeAt(d, 0)), d);
  assert.equal(C.nowMinute(C.officeAt(d, 1439)), 1439);
});
test("recurring meetings are bounded, monthly occurrences clamp without drifting and need times", () => {
  const p = {
    title: "جلسه",
    date: C.epoch(1405, 6, 31),
    start: 540,
    end: 600,
    repeat: "MONTHLY",
    until: C.epoch(1405, 9, 30),
  };
  const rows = C.meetingInstances(p);
  assert.deepEqual(
    rows.map((x) => C.solar(x.date).day),
    [31, 30, 30, 30],
  );
  assert.throws(() => C.meetingInstances({ ...p, until: null }));
  assert.throws(() => C.meetingInstances({ ...p, end: 500 }));
  assert.throws(() => C.meetingInstances({ ...p, until: p.date + 367 }));
});
test("participant conflicts include normalized guest names but allow touching endpoints", () => {
  const base = {
      title: "جلسه",
      date: 20000,
      start: 540,
      end: 600,
      attendees: ["a"],
      guests: ["آقای احمدی"],
      repeat: "NONE",
    },
    r = { id: "existing", payload: base };
  assert.equal(
    C.conflicts({ ...base, start: 590, attendees: [], guests: ["احمدی"] }, [r])
      .length,
    1,
  );
  assert.equal(C.conflicts({ ...base, start: 600, end: 660 }, [r]).length, 0);
  assert.equal(
    C.conflicts({ ...base, attendees: ["b"], guests: [] }, [r]).length,
    0,
  );
  assert.equal(C.conflicts(base, [r], "existing").length, 0);
});
test("dense events stay selectable and suggestions search both first and family names across 50 people", () => {
  const rows = Array.from({ length: 8 }, (_, i) => ({
    id: String(i),
    payload: { start: 540 + i, end: 600 + i },
  }));
  assert.equal(C.displayGroups(rows, 2)[0].dense.length, 8);
  const users = Array.from({ length: 50 }, (_, i) => ({
    id: String(i),
    active: true,
    name: i === 49 ? "علی احمدی" : "کاربر " + i,
  }));
  assert.equal(C.suggestions(users, [], "احمدی")[0].id, "49");
  assert.equal(C.suggestions(users, [], "علی")[0].id, "49");
  assert.equal(
    C.suggestions(
      users,
      [],
      "",
      users.slice(0, 10).map((u) => u.id),
    ).length,
    40,
  );
  assert.equal(C.suggestions(users, ["آقای علی احمدی"]).length, 50);
});
test("task filters, manager approval and three-way changes preserve distinct edits and expose true conflicts", () => {
  const p = { status: "REVIEW", assignee: "a", due: 100 };
  assert.deepEqual(C.primaryAction({ role: "manager" }, p), [
    "تأیید نهایی انجام",
    "DONE",
  ]);
  assert.equal(C.primaryAction({ role: "deputy" }, p), null);
  assert.equal(C.filterTask(p, "late", 101), true);
  assert.equal(C.filterTask({ ...p, status: "DONE" }, "open", 101), false);
  assert.equal(C.filterTask({...p,extension:{status:'PENDING'}},'extensions',101),true);
  assert.equal(C.filterTask({...p,extension:{status:'REJECTED'}},'extensions',101),false);
  assert.equal(C.filterTask({...p,status:'DONE',extension:{status:'PENDING'}},'extensions',101),false);
  assert.deepEqual(
    C.mergePayload({ a: 1, b: 1 }, { a: 2, b: 1 }, { a: 1, b: 2 }),
    { a: 2, b: 2 },
  );
  assert.equal(C.mergePayload({ a: 1 }, { a: 2 }, { a: 3 }), null);
});

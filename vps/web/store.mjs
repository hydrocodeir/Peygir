import { mergePayload, same } from "./core.mjs";
export const DB_NAME = "peygir-web-v1";
let opened;
const req = (request) =>
  new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
const end = (tx) =>
  new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error("ذخیرهٔ اطلاعات متوقف شد"));
  });
export function database() {
  if (!opened)
    opened = new Promise((resolve, reject) => {
      const r = indexedDB.open(DB_NAME, 1);
      r.onupgradeneeded = () => {
        const db = r.result;
        db.createObjectStore("profiles", { keyPath: "id" });
        for (const name of ["records", "drafts", "ops"]) {
          const s = db.createObjectStore(name, { keyPath: "key" });
          s.createIndex("profile", "profile");
        }
        db.createObjectStore("meta", { keyPath: "key" });
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  return opened;
}
export async function allProfiles() {
  const db = await database();
  return req(db.transaction("profiles").objectStore("profiles").getAll());
}
export async function meta(key, value) {
  const db = await database(),
    tx = db.transaction("meta", value === undefined ? "readonly" : "readwrite"),
    s = tx.objectStore("meta");
  if (value === undefined) return (await req(s.get(key)))?.value;
  const done = end(tx);
  s.put({ key, value });
  await done;
}
export async function putProfile(p) {
  const db = await database();
  const old = await req(
    db.transaction("profiles").objectStore("profiles").get(p.id),
  );
  if (old && (old.userId !== p.userId || old.role !== p.role))
    await clearData(p.id);
  const tx = db.transaction("profiles", "readwrite"),
    done = end(tx);
  tx.objectStore("profiles").put({ ...old, ...p });
  await done;
}
export async function clearData(id, remove = false) {
  const db = await database(),
    tx = db.transaction(["records", "drafts", "ops", "profiles"], "readwrite"),
    done = end(tx);
  for (const n of ["records", "drafts", "ops"]) {
    const s = tx.objectStore(n),
      keys = await req(s.index("profile").getAllKeys(id));
    keys.forEach((k) => s.delete(k));
  }
  if (remove) tx.objectStore("profiles").delete(id);
  await done;
}
export async function snapshot(id) {
  const db = await database(),
    tx = db.transaction(["records", "drafts", "ops", "profiles"]);
  const [records, drafts, ops, profile] = await Promise.all(
    ["records", "drafts", "ops"]
      .map((n) => req(tx.objectStore(n).index("profile").getAll(id)))
      .concat(req(tx.objectStore("profiles").get(id))),
  );
  const map = new Map(records.map((r) => [r.id, r]));
  for (const d of drafts)
    map.set(d.id, {
      ...(map.get(d.id) || { revision: 0 }),
      id: d.id,
      kind: d.kind,
      payload: d.payload,
      deleted: d.deleted,
      local: true,
      conflict: d.conflict,
    });
  return {
    profile,
    records: [...map.values()].filter((r) => !r.deleted),
    canonical: records,
    drafts,
    ops,
  };
}
const key = (profile, id) => profile + "|" + id;
async function editState(id, fn) {
  const db = await database(),
    tx = db.transaction(["records", "drafts", "ops"], "readwrite"),
    done = end(tx);
  const lists = await Promise.all(
    ["records", "drafts", "ops"].map((n) =>
      req(tx.objectStore(n).index("profile").getAll(id)),
    ),
  );
  const maps = lists.map((rows) => new Map(rows.map((r) => [r.id, r]))),
    before = lists.map(
      (rows) => new Map(rows.map((r) => [r.id, JSON.stringify(r)])),
    );
  const value = fn(maps);
  for (let i = 0; i < maps.length; i++) {
    const s = tx.objectStore(["records", "drafts", "ops"][i]),
      oldKeys = new Set(lists[i].map((r) => r.id));
    for (const [rid, r] of maps[i]) {
      const row = { ...r, key: key(id, rid), profile: id };
      if (before[i].get(rid) !== JSON.stringify(row)) s.put(row);
      oldKeys.delete(rid);
    }
    oldKeys.forEach((rid) => s.delete(key(id, rid)));
  }
  await done;
  return value;
}
export async function queueChanges(id, changes) {
  return editState(id, ([canonical, drafts]) => {
    for (const change of changes) {
      const old = drafts.get(change.id),
        base = canonical.get(change.id);
      if (change.base && base?.deleted && !change.deleted)
        throw new Error("این مورد حذف شده است - فهرست را تازه کنید");
      let payload = structuredClone(change.payload);
      if (change.base && base && change.base.revision !== base.revision) {
        const merged = mergePayload(
          change.base.payload,
          payload,
          old?.payload || base.payload,
        );
        if (!merged)
          throw new Error(
            "این مورد هم‌زمان تغییر کرده است - فرم را دوباره باز کنید",
          );
        payload = merged;
      }
      drafts.set(change.id, {
        id: change.id,
        kind: change.kind,
        payload,
        deleted: !!change.deleted,
        version: (old?.version || 0) + 1,
        basePayload: base?.payload || null,
        conflict: null,
      });
    }
  });
}
export async function prepare(id, limit = 20) {
  return editState(id, ([canonical, drafts, ops]) => {
    const items = [...drafts.values()]
      .filter((d) => !d.conflict)
      .sort(
        (a, b) =>
          ["meeting", "task", "followup", "history"].indexOf(a.kind) -
          ["meeting", "task", "followup", "history"].indexOf(b.kind),
      );
    const prepared = [];
    for (const d of items) {
      if (prepared.length >= limit) break;
      const parent =
        d.kind === "task"
          ? d.payload.meeting
          : ["history", "followup"].includes(d.kind)
            ? d.payload.task
            : null;
      if (
        parent &&
        !canonical.has(parent) &&
        !prepared.some((o) => o.operation.recordId === parent)
      )
        continue;
      let op = ops.get(d.id);
      if (!op) {
        op = {
          id: d.id,
          version: d.version,
          basePayload: canonical.get(d.id)?.payload || null,
          operation: {
            id: crypto.randomUUID(),
            recordId: d.id,
            kind: d.kind,
            baseRevision: canonical.get(d.id)?.revision || 0,
            deleted: d.deleted,
            payload: structuredClone(d.payload),
          },
        };
        ops.set(d.id, op);
      }
      prepared.push(op);
    }
    return prepared;
  });
}
export async function receive(id, response, sent) {
  return editState(id, ([canonical, drafts, ops]) => {
    function revoke(r) {
      canonical.delete(r.id);
      drafts.delete(r.id);
      ops.delete(r.id);
      for (const [rid, row] of canonical)
        if (row.payload?.task === r.id) {
          canonical.delete(rid);
          drafts.delete(rid);
          ops.delete(rid);
        }
      for (const [rid, row] of drafts)
        if (row.payload?.task === r.id) {
          drafts.delete(rid);
          ops.delete(rid);
        }
    }
    function incoming(r) {
      if (r.revoked) {
        revoke(r);
        return;
      }
      const old = canonical.get(r.id);
      if (old && old.revision > r.revision) return;
      canonical.set(r.id, r);
      const d = drafts.get(r.id);
      if (d && !ops.has(r.id)) {
        const merged =
          !r.deleted &&
          mergePayload(
            d.basePayload || old?.payload || {},
            d.payload,
            r.payload,
          );
        if (merged) {
          d.payload = merged;
          d.basePayload = r.payload;
        } else d.conflict = { remote: r, reason: r.reason };
      }
    }
    for (const result of response.results || []) {
      const attempted = sent.find((s) => s.operation.id === result.opId);
      if (!attempted) throw new Error("پاسخ همگام‌سازی همخوان نیست");
      let r = result.record;
      if (!r) throw new Error("پاسخ همگام‌سازی ناقص است");
      if (r.revoked || result.denied) {
        revoke(r);
        continue;
      }
      const pending = ops.get(attempted.id);
      if (!pending || pending.operation.id !== attempted.operation.id) continue;
      ops.delete(attempted.id);
      const d = drafts.get(attempted.id);
      const newer = canonical.get(r.id);
      if (newer?.revision > r.revision) r = newer;
      canonical.set(r.id, r);
      if (result.ok) {
        if (d?.version === attempted.version) drafts.delete(r.id);
        else if (d) {
          const merged = mergePayload(
            attempted.operation.payload,
            d.payload,
            r.payload,
          );
          if (merged) {
            d.payload = merged;
            d.basePayload = r.payload;
          } else d.conflict = { remote: r, reason: "conflict" };
        }
      } else if (d) {
        const merged =
          !r.deleted &&
          !r.reason &&
          mergePayload(attempted.basePayload || {}, d.payload, r.payload);
        if (merged) {
          d.payload = merged;
          d.basePayload = r.payload;
        } else d.conflict = { remote: r, reason: r.reason || "conflict" };
      }
    }
    for (const r of response.changes || []) incoming(r);
  });
}
async function rejectOperation(id, sent, message) {
  return editState(id, ([canonical, drafts, ops]) => {
    const d = drafts.get(sent.id);
    if (d) {
      d.conflict = {
        remote: canonical.get(sent.id) || {
          id: sent.id,
          kind: d.kind,
          revision: 0,
          payload: {},
          deleted: true,
        },
        reason: "invalid",
        message,
      };
      ops.delete(sent.id);
    }
  });
}
export async function resolveConflict(id, recordId, choice) {
  return editState(id, ([canonical, drafts, ops]) => {
    const d = drafts.get(recordId);
    if (!d) return;
    if (choice === "server") {
      drafts.delete(recordId);
      ops.delete(recordId);
      return;
    }
    if (d.conflict?.remote?.revoked)
      throw new Error("دیگر به این مورد دسترسی ندارید");
    if (d.conflict?.reason === "parent_deleted") {
      if (d.kind !== "task") throw new Error("مورد اصلی حذف شده است");
      d.payload.meeting = null;
    }
    d.basePayload = canonical.get(recordId)?.payload || null;
    d.conflict = null;
    d.version++;
    ops.delete(recordId);
  });
}
export async function api(profile, action, payload = {}) {
  const controller = new AbortController(),
    timer = setTimeout(() => controller.abort(), 22000);
  try {
    const response = await fetch(
      `/app/api/profiles/${encodeURIComponent(profile.id)}/${action}`,
      {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-Token": profile.csrf,
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      },
    );
    const value = await response.json();
    if (!response.ok) {
      const e = new Error(value.error || "ارتباط برقرار نشد");
      e.status = response.status;
      throw e;
    }
    return value;
  } finally {
    clearTimeout(timer);
  }
}
const flights = new Map();
export function synchronize(profile, onChange = () => {}) {
  if (flights.has(profile.id)) return flights.get(profile.id);
  const promise = (async () => {
    try {
      let limit = 20;
      for (let page = 0; page < 100; page++) {
        const state = await snapshot(profile.id);
        if (!state.profile) return;
        const sent = await prepare(profile.id, limit),
          inventory = state.canonical.map((r) => ({
            id: r.id,
            kind: r.kind,
            revision: r.revision,
            view: r.view || "",
          }));
        let response;
        try {
          response = await api(state.profile, "sync", {
            protocol: 2,
            inventory,
            operations: sent.map((o) => o.operation),
            cursor: 0,
          });
        } catch (e) {
          if ([400, 403].includes(e.status) && sent.length) {
            if (sent.length > 1) {
              limit = 1;
              continue;
            }
            await rejectOperation(profile.id, sent[0], e.message);
            continue;
          }
          throw e;
        }
        const current = (await snapshot(profile.id)).profile;
        if (!current || current.csrf !== state.profile.csrf) return;
        if (response.role !== current.role) {
          await clearData(profile.id);
          profile = { ...current, role: response.role };
          await putProfile(profile);
          continue;
        }
        await receive(profile.id, response, sent);
        profile = {
          ...state.profile,
          role: response.role,
          name: response.name,
          companyName: response.workspaceName,
          users: response.users || [],
          contacts: response.contacts || [],
          lastSync: Date.now(),
          error: null,
        };
        await putProfile(profile);
        await onChange(profile.id);
        if (!response.hasMore && !(await prepare(profile.id)).length) return;
      }
      throw new Error("همگام‌سازی هنوز کامل نشده است");
    } catch (e) {
      const current = (await snapshot(profile.id)).profile;
      if (!current || current.csrf !== profile.csrf) throw e;
      if (e.status === 401) {
        await clearData(profile.id);
        await putProfile({
          ...profile,
          expired: true,
          error: "ورود دوباره لازم است",
        });
      } else await putProfile({ ...profile, error: e.message });
      await onChange(profile.id);
      throw e;
    }
  })().finally(() => flights.delete(profile.id));
  flights.set(profile.id, promise);
  return promise;
}

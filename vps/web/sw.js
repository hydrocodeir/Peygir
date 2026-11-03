const CACHE = "peygir-shell-4.9.0";
const SHELL = [
  "/app/",
  "/app/index.html",
  "/app/app.js",
  "/app/core.mjs",
  "/app/store.mjs",
  "/app/style.css",
  "/app/font.ttf",
  "/app/manifest.webmanifest",
  "/app/icon.svg",
  "/app/icon-192.png",
  "/app/icon-512.png",
  "/app/apple-touch-icon.png",
];
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
self.addEventListener("install", (event) =>
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL))),
);
self.addEventListener("activate", (event) =>
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys())
        if (name.startsWith("peygir-shell-") && name !== CACHE)
          await caches.delete(name);
      await self.clients.claim();
    })(),
  ),
);
self.addEventListener("fetch", (event) => {
  const u = new URL(event.request.url);
  if (
    event.request.method !== "GET" ||
    u.origin !== self.location.origin ||
    !SHELL.includes(u.pathname)
  )
    return;
  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE),
        cached = await cache.match(u.pathname);
      if (cached) return cached;
      const response = await fetch(event.request);
      if (response.ok) await cache.put(u.pathname, response.clone());
      return response;
    })(),
  );
});
async function knownProfile(id) {
  if (!UUID.test(id || "")) return null;
  return new Promise((resolve) => {
    const r = indexedDB.open("peygir-web-v1", 1);
    r.onupgradeneeded = () => {
      r.transaction.abort();
      resolve(null);
    };
    r.onerror = () => resolve(null);
    r.onsuccess = () => {
      const db = r.result;
      if (!db.objectStoreNames.contains("profiles")) {
        db.close();
        resolve(null);
        return;
      }
      const q = db.transaction("profiles").objectStore("profiles").get(id);
      q.onsuccess = () => {
        db.close();
        resolve(q.result);
      };
      q.onerror = () => {
        db.close();
        resolve(null);
      };
    };
  });
}
self.addEventListener("push", (event) =>
  event.waitUntil(
    (async () => {
      let payload = {};
      try {
        payload = event.data?.json() || {};
      } catch {}
      const data = payload.data || {},
        profile = await knownProfile(data.profileId);
      const valid = profile && !profile.expired && profile.expires > Date.now();
      const safe = {
        profileId: UUID.test(data.profileId || "") ? data.profileId : null,
        recordId: UUID.test(data.recordId || "") ? data.recordId : null,
      };
      await self.registration.showNotification(
        valid
          ? String(payload.title || "پیگیر").slice(0, 220)
          : "پیگیر - اطلاع تازه",
        {
          body: valid
            ? "برای مشاهده و پیگیری، پیگیر را باز کنید"
            : "برای مشاهده، وارد حساب مربوط به این اعلان شوید",
          icon: "/app/icon-192.png",
          badge: "/app/icon-192.png",
          tag:
            typeof payload.tag === "string"
              ? payload.tag.slice(0, 200)
              : "peygir",
          renotify: false,
          data: safe,
        },
      );
      for (const client of await self.clients.matchAll({ type: "window" }))
        if (new URL(client.url).pathname.startsWith("/app/"))
          client.postMessage({ type: "sync", profileId: safe.profileId });
    })(),
  ),
);
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    (async () => {
      const data = event.notification.data || {},
        profileId = UUID.test(data.profileId || "") ? data.profileId : null,
        recordId = UUID.test(data.recordId || "") ? data.recordId : null;
      const windows = await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
      });
      const target = windows.find(
        (c) =>
          new URL(c.url).origin === self.location.origin &&
          new URL(c.url).pathname.startsWith("/app/"),
      );
      if (target) {
        await target.focus();
        target.postMessage({ type: "open-record", profileId, recordId });
      } else
        await self.clients.openWindow(
          "/app/" +
            (profileId
              ? "?account=" +
                encodeURIComponent(profileId) +
                (recordId ? "&record=" + encodeURIComponent(recordId) : "")
              : ""),
        );
    })(),
  );
});

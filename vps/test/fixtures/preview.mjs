import { createServer } from "node:http";
import { Readable } from "node:stream";
import { mkdtempSync, mkdirSync, rmSync } from "node:fs";
import { resolve, join, sep } from "node:path";
import { randomUUID } from "node:crypto";
import { createApp } from "../../src/app.mjs";
import { today } from "../../web/core.mjs";
export const password = "synthetic-test-password-123";
export async function preview(port = 18747) {
  const testRoot = resolve(
    import.meta.dirname,
    "../../../.local-build/pwa-tests",
  );
  mkdirSync(testRoot, { recursive: true });
  const dir = mkdtempSync(join(testRoot, "preview-"));
  const origin = "http://127.0.0.1:" + port,
    app = await createApp({
      dataDir: dir,
      publicOrigin: origin,
      bootstrap: {
        username: "owner",
        password: "synthetic-owner-password-123",
      },
    });
  for (const code of ["company-x", "company-y"]) {
    const { company: c } = await app.registry.create({
      code,
      name: code === "company-x" ? "شرکت نمونه ایکس" : "شرکت نمونه ایگرگ",
      managerName: "مدیر نمونه",
      managerUsername: "manager",
      managerPassword: password,
    });
    const lease = app.registry.lease(c);
    try {
      const manager = lease.db
          .prepare("SELECT * FROM users WHERE id=?")
          .get(c.manager_id),
        users = [manager.id];
      lease.db.exec("BEGIN");
      for (let i = 1; i < 50; i++) {
        const id = randomUUID();
        users.push(id);
        lease.db
          .prepare(
            "INSERT INTO users(id,workspace,username,name,role,access_role,salt,password_hash,iterations) VALUES(?,?,?,?,?,?,?,?,100000)",
          )
          .run(
            id,
            c.id,
            "user" + i,
            "کاربر " + i + " احمدی",
            i === 1 ? "assistant" : "assistant",
            i === 1 ? "assistant" : "deputy",
            manager.salt,
            manager.password_hash,
          );
      }
      const insert = lease.db.prepare(
          "INSERT INTO records VALUES(?,?,?,?,?,0,?,?,?)",
        ),
        day = today(),
        ids = [];
      for (let i = 0; i < 200; i++) {
        const id = randomUUID();
        ids.push(id);
        const date = day + Math.floor(i / 8) - 5,
          start = 480 + (i % 8) * 60;
        insert.run(
          c.id,
          id,
          "meeting",
          1,
          JSON.stringify({
            title: "جلسه " + (i + 1) + " - " + code,
            date,
            notes: "یادداشت آزمایشی",
            start,
            end: start + 50,
            location: "اتاق هیئت‌مدیره",
            attendees: [users[0], users[(i % 49) + 1]],
            guests: i % 4 === 0 ? ["مهمان نمونه"] : [],
            repeat: "NONE",
            until: null,
            series: null,
          }),
          "دادهٔ ساختگی",
          Date.now(),
          randomUUID(),
        );
      }
      for (let i = 0; i < 500; i++)
        insert.run(
          c.id,
          randomUUID(),
          "task",
          1,
          JSON.stringify({
            title: "مصوبه " + (i + 1) + " - " + code,
            description: "دادهٔ ساختگی برای بررسی فهرست بزرگ",
            owner:
              i % 5 === 0
                ? "مخاطب بیرونی"
                : "کاربر " + ((i % 49) + 1) + " احمدی",
            due: day + (i % 31) - 10,
            priority: ["LOW", "NORMAL", "HIGH", "URGENT"][i % 4],
            status: ["WAITING", "ACTIVE", "REVIEW", "DONE"][i % 4],
            meeting: ids[i % 200],
            reminders: [],
            assignee: i % 5 === 0 ? null : users[(i % 49) + 1],
            submitted: 0,
            approved: 0,
            approvedBy: null,
          }),
          "دادهٔ ساختگی",
          Date.now(),
          randomUUID(),
        );
      lease.db.exec("COMMIT");
    } finally {
      lease.release();
    }
  }
  let offline = false;
  const server = createServer(async (req, res) => {
    try {
      if (req.url === "/__test/network" && req.method === "POST") {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        offline = JSON.parse(Buffer.concat(chunks)).offline === true;
        res
          .writeHead(200, { "Content-Type": "application/json" })
          .end('{"ok":true}');
        return;
      }
      if (offline) {
        res
          .writeHead(503, { "Content-Type": "application/json" })
          .end('{"error":"Synthetic network outage"}');
        return;
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 4194304) {
          res.writeHead(413).end();
          return;
        }
        chunks.push(chunk);
      }
      const r = await app.fetch(
        new Request(new URL(req.url, origin), {
          method: req.method,
          headers: req.headers,
          ...(!["GET", "HEAD"].includes(req.method)
            ? { body: Buffer.concat(chunks) }
            : {}),
        }),
      );
      res.writeHead(r.status, Object.fromEntries(r.headers));
      if (r.body) Readable.fromWeb(r.body).pipe(res);
      else res.end();
    } catch {
      res.writeHead(500).end();
    }
  });
  await new Promise((r) => server.listen(port, "127.0.0.1", r));
  return {
    app,
    origin,
    async close() {
      await new Promise((r) => server.close(r));
      await app.close();
      if (!resolve(dir).startsWith(testRoot + sep))
        throw new Error("Unexpected test directory");
      rmSync(dir, { recursive: true, force: true });
    },
  };
}
if (process.argv.includes("--serve")) {
  const p = await preview(Number(process.env.PREVIEW_PORT || 18747));
  console.log("Synthetic PWA preview ready at " + p.origin + "/app/");
  for (const signal of ["SIGINT", "SIGTERM"])
    process.on(signal, async () => {
      await p.close();
      process.exit(0);
    });
}

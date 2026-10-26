# Peygir shared service

Workers + D1 API for the two-account Persian Android app. Native fetch/Web Crypto/D1 bindings; no server runtime dependencies. Wrangler is pinned in package-lock.json. The undici override fixes the advisories reported by npm audit for the pinned toolchain.

`npm ci`, `npm test`, `npm run db:local`, `npm run dev` run the service locally. Tests use the actual Workers runtime and D1 through Miniflare and include tenant isolation, authentication, rate limiting, optimistic concurrency, idempotent receipts, cascades, and rejected child operations. They use synthetic data and never read production credentials.

Endpoints: `GET /health`, `POST /v1/login`, `POST /v1/logout`, `POST /v1/sync`. Sync accepts up to 20 operations and returns at most 200 ordered changes per page. Request bodies are limited to 1 MiB. Payloads are strictly validated; raw audio and backup files are not accepted. Both authenticated roles can edit their workspace, never another workspace.

The checked-in wrangler configuration belongs to this deployment. Before deploying to another account, change the Worker/database names, account ID and database ID. Apply migrations only once with `npm run db:remote`; `npm run deploy` updates the Worker. No paid-plan upgrade or custom domain is required by this project.

Provisioning is an administrator-only CLI workflow: `node scripts/provision.mjs` writes initial password hashes/SQL and plaintext credentials to the excluded `.device-private/cloudflare` directory. It refuses to overwrite existing credentials. Apply that private SQL once through the owner's authenticated Wrangler session. Never copy the SQL or credentials into source, logs, the APK or a message to another person. Distribute only the intended account's credential file through an appropriate user-controlled channel.

`scripts/smoke.mjs` is an explicit production check. It reads owner-local credentials, sends only labelled synthetic records, tests both accounts, tombstones its records and revokes its test sessions. Do not run it against an unrelated deployment.

See [Persian setup, behavior and operational limits](../verification/SHARED-SYNC.md).

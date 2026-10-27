# Peygir organization service (3.1.0)

Workers + D1 API for the Persian Android app. Chief (`manager`) and assistant administer the company; deputies see only account-assigned tasks and invited meetings. Enforcement is on the server, including mutation/conflict/receipt paths, child records, invitation changes and access revocation. Native fetch/Web Crypto/D1 bindings; no server runtime dependencies. Wrangler and the undici security override are pinned.

`npm ci`, `npm test`, `npm run db:local`, `npm run dev` run the service locally. Tests use the actual Workers runtime and D1 through Miniflare and include tenant isolation, authentication, rate limiting, optimistic concurrency, idempotent receipts, cascades, and rejected child operations. They use synthetic data and never read production credentials.

Organization endpoints (authenticated POST): `/v2/sync`, `/v2/users`, `/v2/config`, `/v2/device`. `/v2/login` authenticates; `/v1/logout` revokes that session and its device registrations. `GET /health` advertises `organizationProtocol:2`. Legacy v1 sync is blocked with 426 after first v2 sync or deputy creation for that workspace.

V2 sync accepts at most 20 strictly validated operations, a client inventory of at most 10000 IDs/revisions/view hashes and a 4 MiB body. Replies contain at most 200 currently authorized changes, never the old company-wide history. Unknown or revoked inventory IDs receive only generic tombstones. Readonly meeting links are projected away from task payloads for non-invitees. Task mutations and child insertion use a transactional current-assignee guard. Chief-only transition REVIEW → DONE stamps approval metadata on the server.

New and edited organization meetings require both start and end minutes (end strictly after start in the same day). Previously stored untimed meetings can still be read and deleted; no times are invented for old records. Upgrade clients to 3.1.0 before scheduling new meetings.

Account administration supports deputy creation, name/active updates and password reset with session revocation. Account role cannot be escalated by the public API. Account operations are audited. Initial two-account CLI provisioning is still for first installation only; do not reapply it to an existing DB.

Push: Firebase HTTP v1 with OAuth2 service-account JWT (Web Crypto), per-session device registration, a transactional D1 outbox, immediate waitUntil dispatch and five-minute cron retry. FCM payloads are generic and account-scoped; current authorization is checked before dispatch. Configure Worker secrets `FCM_SERVICE_ACCOUNT` (service-account JSON) and `FCM_PUBLIC_JSON` (`projectId`, `applicationId`, `senderId`, `apiKey`). Keep original files under excluded `.device-private/firebase`. The Android app receives public configuration after authentication; no private service key belongs in Gradle, APK or source artifacts. Firebase Messaging 26.0.1 is the only Firebase product dependency; no Analytics.

The checked-in wrangler configuration belongs to this deployment. Before deploying to another account, change the Worker/database names, account ID and database ID. Apply migrations only once with `npm run db:remote`; `npm run deploy` updates the Worker. No paid-plan upgrade or custom domain is required by this project.

Provisioning is an administrator-only CLI workflow: `node scripts/provision.mjs` writes initial password hashes/SQL and plaintext credentials to the excluded `.device-private/cloudflare` directory. It refuses to overwrite existing credentials. Apply that private SQL once through the owner's authenticated Wrangler session. Never copy the SQL or credentials into source, logs, the APK or a message to another person. Distribute only the intended account's credential file through an appropriate user-controlled channel.

`scripts/smoke-v2.mjs` is an explicit production check. It reads owner-local credentials, sends only labelled synthetic records, tests both accounts, tombstones its records and revokes its test sessions. Do not run it against an unrelated deployment.

See [Persian organization setup, access matrix and limits](../verification/ORGANIZATION.md). The old `smoke.mjs` and SHARED-SYNC guide document protocol 1 and are historical after organization activation.

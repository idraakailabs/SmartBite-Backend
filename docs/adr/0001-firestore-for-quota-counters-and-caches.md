# ADR-0001: Firestore (Admin SDK) for quota counters, caches and reports

- **Status:** Proposed (implements owner decision D-011, 2026-10-11: "counters live in Firestore through the
  Admin SDK; no new database")
- **Date:** 2026-10-11
- **Deciders:** owner; backend-devops-expert (recommendation)

## 1. Context

v2 needs durable, shared state that survives restarts and deploys:
- per-uid daily quota counters that must be atomic (no over-counting under concurrent requests);
- Places caches with a **hard deletion deadline** (lat/lng ≤ 30 days, Google Maps Service Specific Terms §14.3);
- a global daily Places call counter (spend cap);
- a recipe cache;
- suggestion reports (D-019) and pending account-deletion records.

Verified facts (2026-10-11): the backend has no database today (`pom.xml`: web + validation only). The app already
uses Firebase (Auth, Firestore `users/{uid}`). Firestore's free quota is per project: 50,000 reads, 20,000 writes,
20,000 deletes per day and 1 GiB stored; TTL deletes have no free usage
([Firestore pricing](https://firebase.google.com/docs/firestore/pricing)). Paid rates by location are $0.03–$0.033
per 100k reads, $0.09–$0.099 per 100k writes and $0.01–$0.011 per 100k deletes
([Cloud Firestore pricing](https://cloud.google.com/firestore/pricing), accessed 2026-10-11).

Load estimate (assumption): one pick set ≈ 4–6 reads and 2–4 writes (two quota transactions, cell and text cache
reads, the occasional cache write). That stays inside the free daily quota up to roughly 5,000 sets per day.

## 2. Decision

Use the existing Firebase project's Firestore through the Firebase Admin SDK for Java. Collections are server-only,
prefixed per environment (`FIRESTORE_COLLECTION_PREFIX`), and documented in `docs/api/README.md` §15. Quota uses
reserve/commit transactions on one document per uid. Cache expiry is enforced at read time **and** by a daily
scheduled deletion job (plus an optional TTL policy if the project is on Blaze). Hot, non-durable state
(entitlement cache, rate-limit buckets, idempotent replays) stays in memory.

## 3. Alternatives considered

| Option | Pros | Cons | Ops | Cost |
|---|---|---|---|---|
| **Firestore via Admin SDK (recommended)** | Already provisioned; transactions; no new vendor; free quota fits | gRPC client adds memory and startup time; per-document write rate (~1/s sustained) per uid doc | Low | ~$0 at current scale (estimate) |
| Render PostgreSQL / Key Value | SQL, simple counters | New paid component and backups; owner said no new database | Medium | Paid plan (not priced; owner decision) |
| In-memory only | Zero cost | Quota resets on every deploy/restart (Render free instances spin down); breaks TECH-001 criterion 2 | None | $0 |
| Upstash/Redis | Fast atomic counters | New vendor and key; no compliance-friendly deletion story for caches | Low | Free tier exists (not verified) |

## 4. Consequences

- **Performance:** two Firestore transactions add latency per pick set (estimate tens of ms each from Render; not
  measured; the Firestore region is unknown). Negligible next to the 3–15 s AI call.
- **Operations:** the Firestore security rules (not in this repository) must deny client access to these
  collections. Firestore indexes: single-field index on `suggestion_reports.uid` (automatic).
- **Memory:** `firebase-admin` brings `google-cloud-firestore` (gRPC, Netty, Guava). The current JVM flag is
  `-Xmx400m`; heap and container size must be checked on staging before production.
- **Security:** the service-account key is a high-value secret ([ENVIRONMENT.md](../ops/ENVIRONMENT.md)). Grant it
  only what it needs where possible.

## 5. Failure scenarios

| Scenario | Impact | Mitigation |
|---|---|---|
| Firestore unavailable | Can't reserve quota | `503 PROVIDER_UNAVAILABLE` for free users (never generate uncounted); Premium path doesn't need the quota doc. Caches miss → Places path degrades to `popular_around`. |
| Crash between reserve and commit | A slot held | Reservations expire after 2 minutes |
| Cleanup job fails | lat/lng kept too long | Job runs daily with a 7-day TTL, so there are 23 days of slack before the 30-day limit; alert on job failure |
| Free quota exceeded | Small bill or throttling (Spark plan) | Monitor daily reads/writes; revisit at 3,000 sets/day |

## 6. Why this option

It meets atomicity and durability with no new infrastructure or vendor, and the owner explicitly asked for it.

## 7. Revisit when

- Sustained > 3,000 pick sets/day (approaching the free write quota), or p95 Firestore transaction latency > 300 ms.
- A second backend instance is added (rate limits and replay caches would need to move out of memory).

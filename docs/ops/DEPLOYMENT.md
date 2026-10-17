# Deploying the SmartBite backend on Render

> **Status (2026-10-16): prepared, not deployed.** No Render service, setting, variable or secret has been
> created or changed. Each step below is for the owner to perform. Prices were checked on 2026-10-16 (sources in
> §2); confirm them in the Render dashboard before relying on them.

Related: every variable is listed in [ENVIRONMENT.md](ENVIRONMENT.md) §2; the architecture is in
[../TECHNICAL.md](../TECHNICAL.md); the API contract is in [../api/README.md](../api/README.md).

## 0. Readiness at a glance

| Area | State |
|---|---|
| Code | `staging` (`a00267b`, with the pre-production fixes for BE-BUG-007, -008, -009, -011) is deployed to staging. `./mvnw -B verify`: 220 tests pass, 11 Firestore emulator tests skipped there and run separately |
| Packaged jar | Starts locally in about 2 s at about 290 MB RSS; health, error bodies, auth and quota were checked by a local smoke test (§9.1) |
| Docker image | **Built by Render** for staging (2026-10-16) and running. Not built locally (WSL2 off) |
| Staging | Verified (`smartbite-api-staging.onrender.com`, Singapore, 1c-2g, Postgres stores, Free `smartbite-db`). **Suspended by the owner on 2026-10-17** to save cost; resume it before further tests. The Free database expires about 30 days after it was created (around 2026-11-15) |
| Production | **Deploy deferred by the owner (2026-10-17)** until the v2 app is nearer release. Plan: §10 (nine steps), then the custom domain (§16) |
| Real providers | Groq and Firebase Auth and Firestore work on staging. Gemini, RevenueCat (a Premium customer), the Remote Config template read and Places are not yet checked (BE-012) |
| Client IP behind Render's proxy | **Verified** on staging (2026-10-17): each network is its own client, and a spoofed header doesn't bypass the limit (§9.2 step 5) |
| Firestore security rules | **Replaced 2026-10-17** (write-only own `users/{uid}`, everything else denied; §7.3). Not in this repository |

**Blockers before production:**
- the remaining staging check: §15.2 step 5 (a second device)
- before turning D-024 on in production only: the $6 database, the hosted privacy policy and the Play Data safety
  form (§15.3)

There are no known code blockers.

## 1. What runs

- **The service:** one Spring Boot 4.1 / Java 21 web service (Docker). It serves:
  - `/api/v1/meal-suggestions`: the legacy endpoint that every released app version uses
  - `/api/v2/**`: authenticated with Firebase ID tokens
  - `/actuator/health`: the health check
  - `/`: a plain text reply, kept for whatever monitor already calls it
- **Firestore** holds the server's durable state (quota counters, caches, reports, deletion retries) through the
  Firebase Admin SDK, unless `SMARTBITE_STORES_MODE=postgres`. Collections are created on first write; the queries
  need only Firestore's automatic single-field indexes.
- **PostgreSQL (optional, D-024):** with `DATABASE_URL` set, user history and profiles live in a Render Postgres
  database. Flyway applies the migrations at startup (§15).
- **Outbound calls:**
  - Groq and Gemini (AI)
  - Firebase Auth, Firestore and Remote Config (Google)
  - RevenueCat API v2
  - Google Places, only when `PLACES_PROVIDER=google`
- **Background jobs (in-process):** the Remote Config read (every 5 minutes), cache clean-up, and account-deletion
  retries. The instance must stay running, which every paid Render instance does.
- **Built-in protections:**
  - A deployed instance refuses to start when it's misconfigured (`DeployedConfigGuard`): on Render without
    `SPRING_PROFILES_ACTIVE=prod|staging`, or with a required secret missing. Render then keeps the previous deploy
    serving, so a bad configuration becomes a failed deploy, not an outage.
  - Graceful shutdown: in-flight requests get up to 45 s.
  - The heap follows the instance size (`-XX:MaxRAMPercentage=75`), and the container runs as a non-root user.

## 2. Costs

| Item | Cost | Notes |
|---|---|---|
| Render **Pro workspace plan** | $25/month flat (paid) | A *workspace* plan: team members, unlimited services, **25 GB outbound bandwidth** and pipeline (build) minutes. Overages: $0.15/GB of bandwidth, $5 per 1,000 build minutes. **It does not include instances** |
| Production instance, **Standard** `1c-2g` (1 CPU, 2 GB) | ≈ $25/month | Recommended (§3). Billed per second. If the existing service already runs on a paid instance, this replaces that line rather than adding to it |
| Staging instance (optional, short-lived) | ≈ $0.83/day as Standard, billed per second | Only while a test round runs; suspend or delete afterwards (§4). Render staff say suspended services don't accrue charges; check your usage page after suspending |
| Bandwidth | Likely $0 extra | Responses are a few KB, and inbound traffic (including AI responses) isn't billed. Outbound requests to Google APIs probably count, but they're small |
| Build minutes | Likely $0 extra | A Maven build is a few minutes per deploy |
| Firestore (Google) | Likely within the free quota today | Each suggestion does a few reads and writes. The free tier is 50k reads and 20k writes per day *(verify on the Firebase pricing page and your project's plan)* |
| AI providers (Groq, Gemini) | Per token, as today | Unchanged by this deploy |
| Google Places | $0 | Stays `fake` or `disabled` until a key and budget exist (ENVIRONMENT.md §3) |
| Custom domain (optional) | Included up to the plan's allowance; $0.25/month per extra domain | Recommended later (§12) |

**Expected monthly total:** about $25 (Pro, already paid) plus about $25 (Standard production instance), plus a few
dollars a month if a staging service runs a couple of days per release.

**Check in the dashboard:**
- **Billing:** the plan shows "Pro". Note the instance type of the existing `smartbite-backend` service.
- **Usage:** bandwidth and pipeline minutes so far.

Sources (checked 2026-10-16):
- [Render: updated workspace plans (Pro $25 flat, overages)](https://render.com/changelog/updated-plans-for-render-workspaces)
- [Render outbound bandwidth (25 GB on Pro; $0.15/GB)](https://render.com/docs/outbound-bandwidth)
- [Render instance types](https://render.com/docs/compute-plans)
- [Render regions](https://render.com/docs/regions)
- [Render Blueprint spec](https://render.com/docs/blueprint-spec)
- [Instance prices, third-party calculator](https://makerkit.dev/pricing-calculator/render): Starter $7, Standard $25. Verify on render.com/pricing.

## 3. Instance size

- **Measured locally:** about 290 MB RSS after startup and a few requests, with in-memory stores. The Firebase Admin
  SDK and gRPC channels add memory in production.
- **Recommended: Standard (1 CPU, 2 GB).** The JVM gets about 1.5 GB of heap. One CPU keeps startup and request
  handling quick.
- **Starter (0.5 CPU, 512 MB) is not recommended.** It's likely to run out of memory with Firebase loaded, and half a
  CPU makes garbage-collection pauses and startup slow.
- **Free is not suitable** for production: it sleeps after 15 minutes, which stops the background jobs, and has
  only 0.1 CPU.
- **Scale up** to `2c-4g` only if Render's metrics show memory above about 80% or CPU often saturated. Stay on
  one instance: rate limits are in memory per instance (two instances would double them), and quota is safe either
  way because it's in Firestore.

## 4. Environments

### Recommendation
| | Production | Staging |
|---|---|---|
| Render service | **The existing service** (`smartbite-backend-zsyr.onrender.com`), upgraded in place | A **temporary** `smartbite-api-staging` service, from `deploy/render-staging.yaml`, created per test round and suspended or deleted afterwards |
| Why | Every installed app calls this hostname for v1. A new production service would force an app update and keep the old service running for old versions, which means two instances to pay for | Keeps testing away from production, for about $0.83/day only while it runs |
| Branch | `master`, auto-deploy **off**, manual deploys | **`staging`**, auto-deploy on |
| Profile | `prod` | `staging` |
| Firestore | Unprefixed collections | `stg_`-prefixed collections |
| RevenueCat customer deletion | On | **Off** (`BILLING_DELETION_ENABLED=false`): the RevenueCat project is shared |
| Places | `disabled` (or `google` later) | `fake` |
| Android client | Release builds | Debug builds with `SMARTBITE_STAGING_API_URL` |

**$0 alternative to a staging service:** run the backend on the development machine and connect the phone over USB
(`adb reverse`). It needs the same secrets locally, and the debug app currently accepts only `https://` addresses,
so this needs a small change before it works. Use it for quick API checks; use a staging service before production.

### Git workflow
```
feature branch ──merge──▶ staging ──(auto-deploy)──▶ staging service ──tested──▶ merge into master ──(manual deploy)──▶ production
```
- Work happens on feature branches, for example `branch-01/v2-api`.
- To test something, merge it into `staging` and push. The staging service deploys it automatically.
- When staging passes, merge `staging` into `master` and deploy production manually.
- `staging` and `master` only receive merges, never direct commits. Keeping them fast-forwardable from the
  feature branches avoids merge noise.
- Created 2026-10-16 from `branch-01/v2-api`.

### What isolation does and doesn't cover
- **Separated:**
  - server data (the `stg_` prefix)
  - RevenueCat deletions (disabled on staging)
  - the AI and Places spend caps (staging uses lower caps)
- **Shared, because there's one Firebase project:**
  - **Firebase Auth users and `users/{uid}`.** The app itself deletes these during "Delete account", whichever
    backend it uses. **Test account deletion only with a test Google account.**
  - Remote Config is read-only from the backend.
- **A fully separate staging** would need a second Firebase project, plus a separate RevenueCat project or sandbox
  setup. It costs nothing extra on Firebase's free tier, but the Android debug build would then need its own
  `google-services.json`. Not needed at the current stage.

## 5. Production service settings (Render dashboard)

Before the cutover (§10), on the **existing** service:

| Setting | Value |
|---|---|
| Runtime | Docker (`./Dockerfile`, context: repo root) |
| Branch | `master` (after `staging` is merged into it) |
| Auto-Deploy | **Off**. Turn it off *before* merging, or the merge deploys immediately |
| Instance type | Standard (1 CPU, 2 GB) |
| Health check path | `/actuator/health` (the current check is probably `/`, which keeps working) |
| Max shutdown delay | 60 s (Settings → Deploy; the default is 30 s) |
| Instances | 1 |
| Build / start command | None. Docker uses the Dockerfile's build and its `ENTRYPOINT` |
| Region | Unchanged. A Render service can't change region; moving it means a new service, a new hostname and an app update (§12) |

## 6. Environment variables

The full reference is [ENVIRONMENT.md](ENVIRONMENT.md) §2. Mark every secret as secret in Render, and never paste
values into Git, chat or tickets.

| Variable | Production | Staging | Secret |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | `staging` | |
| `GEMINI_API_KEY` | production key | a separate key if possible | **yes** |
| `GROQ_API_KEY` | production key | separate key if possible | **yes** |
| `FIREBASE_PROJECT_ID` | `smartbite-a75ad` (confirm in the Firebase console) | same | |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | base64 of the service-account JSON (§7.1) | same, or a separate service account | **yes** |
| `LOG_UID_HASH_KEY` | random 32 bytes, base64 | a *different* random value (the Blueprint generates one) | **yes** |
| `REVENUECAT_SECRET_API_KEY` | RevenueCat **v2** secret key | same (deletion is off on staging) | **yes** |
| `REVENUECAT_PROJECT_ID` | project ID | same | |
| `FIRESTORE_COLLECTION_PREFIX` | *(unset)* | `stg_` | |
| `BILLING_DELETION_ENABLED` | *(unset → true)* | `false` | |
| `PLACES_PROVIDER` | `disabled` until a Places key exists | `fake` | |
| `JAVA_TOOL_OPTIONS` | *(unset)* | *(unset)* | |

Everything else has a safe default. `PORT` is set by Render, and `RENDER=true` is set by Render too (the startup
guard uses it).

**Generating `LOG_UID_HASH_KEY`:** run `openssl rand -base64 32`, or in PowerShell
`[Convert]::ToBase64String((1..32 | % {[byte](Get-Random -Max 256)}))`.

## 7. Firebase and third-party setup (owner)

### 7.1 Service account
1. Go to Firebase console → Project settings → Service accounts → **Generate new private key**.
2. Encode it on one line: `base64 -w0 key.json`, or in PowerShell
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("key.json"))`.
3. Paste the result into Render as `FIREBASE_SERVICE_ACCOUNT_BASE64`, then **delete the local file**.

The Firebase Admin SDK service account has the roles the backend needs:
- Firestore read and write
- Auth token verification and the revocation check
- Remote Config template read

If you create a dedicated service account instead, give it *Cloud Datastore User*, *Firebase Authentication
Viewer* and *Firebase Remote Config Viewer*.

### 7.2 RevenueCat
1. Create a **v2** secret key with `customer_information:customers:read_write` and
   `project_configuration:entitlements:read`.
2. Note the project ID.
3. Keep the entitlement lookup key `smartbite_pro`, which matches the app's `PREMIUM_ENTITLEMENT`.

### 7.3 Firestore security rules (TECH-008 in the Android roadmap)
The backend uses the Admin SDK, which bypasses rules. Clients must not be able to read or write server
collections such as `quota`, `places_*`, `recipe_cache`, `suggestion_reports`, `deletion_requests` and every
`stg_` collection.

The Android app uses only `users/{uid}`. Compare the current rules (Firebase console → Firestore → Rules) with:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // Everything else is server-only (Admin SDK).
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

Use the Rules Playground to check that a signed-in user can still read and write their own `users/{uid}`. Do this
**before** publishing changed rules.

**What was done (2026-10-17, production project):** the published rules referred to an undefined `userId`, which
denied every app read and write (nothing was exposed). The owner replaced them with a **write-only** variant of the
rules above: `users/{uid}` allows `create, update, delete` for its own user and denies `read`, because the live app
overwrites the phone's profile with the Firestore copy when Profile opens, and old copies may exist. Playground:
create own document allowed, another user's denied, read denied, `quota/test` denied. Details: Android TECH-008.

### 7.4 Remote Config, for testing v2 with the debug app only
1. Add a condition "App is `com.idraak.ai.smartbite.debug`" and set `api_v2_enabled` to `true` under it.
2. Keep the default `false`.

The live app (v1.1.0) doesn't read this key at all.

### 7.5 Firestore location
Check Firebase console → Firestore → Settings. Region choice on Render is secondary: every request also waits
seconds for the AI.

## 8. Android configuration

- **Release builds:** both v1 and v2 use `https://smartbite-backend-zsyr.onrender.com/`
  (`app/build.gradle.kts`, `productionApiUrl`). This is unchanged, so the in-place production upgrade needs no app
  update.
- **Debug builds:** both clients use `SMARTBITE_STAGING_API_URL` from the Android repo's `local.properties`, for
  example `SMARTBITE_STAGING_API_URL=https://smartbite-api-staging.onrender.com/`. It must be `https://`. Without
  it, debug builds use production. (Since 2026-10-16 this includes v1; before, v1 was always production.)
- **v2 is off for everyone** until Remote Config `api_v2_enabled` is turned on (§7.4).

## 9. Testing

### 9.1 Already done locally (2026-10-16)

| Check | Result |
|---|---|
| `./mvnw -B verify` | 162 tests, 0 failures, 0 errors, 11 skipped (the emulator tests) |
| `FirestoreDocumentStoreEmulatorTest` (Firebase CLI emulator) | 11/11 pass |
| `dependency:go-offline` (the new Dockerfile's first build step) | Succeeds |
| Jar started with the Docker image's JVM flags, `prod` profile, secrets missing | Refuses to start and names `FIREBASE_PROJECT_ID, FIREBASE_SERVICE_ACCOUNT_BASE64, LOG_UID_HASH_KEY, REVENUECAT_SECRET_API_KEY, REVENUECAT_PROJECT_ID` (no values) |
| Jar with `RENDER=true` and no profile | Refuses to start |
| Jar in a local configuration (in-memory, fake tokens, dummy AI keys) | Up in about 2 s, about 290 MB RSS. `GET /` 200. `/actuator/health` 200 `UP`. `/actuator/env` 404. v1 `{}` → 400 with field errors. v1 with dummy AI keys → 502. v2 without a token → 401 problem+json. v2 quota → 200. v2 suggestion with dummy AI keys → 503 `PROVIDER_UNAVAILABLE`, and quota **not** used. Unknown v2 path → 404 problem+json |

**Not possible locally:** the Docker build, real provider calls, Render's proxy and health checks, and the real
Firestore.

### 9.2 Staging round (owner deploys; the checks can be run by anyone with the URL)

**Round 1: 2026-10-16, `https://smartbite-api-staging.onrender.com`, commit `a8e8e7d`, Singapore, 1c-2g.**
Created by the owner from the Blueprint, and Live. Checks run from the development machine:

| Check | Result |
|---|---|
| `GET /`, `/actuator/health`, `/actuator/health/liveness` | 200; `UP` (about 0.2–0.5 s) |
| `/actuator/env` | 404 (not exposed) |
| v2 quota without a token, and with a fake `test:` token | 401 `UNAUTHENTICATED` (fake tokens are off on staging, as intended) |
| v1 `{}` | 400 with field errors |
| Rate limit, 70 parallel requests in 5 s from one machine | 63 × 400 then 7 × 429: one consistent client identity (token bucket: 60 plus refill of 1/s). Sequential requests at about 2/s never hit the limit, which is expected |
| Spoofed `X-Forwarded-For: 1.2.3.4` right after the burst | 429: the header doesn't bypass the limit |
| One real v1 request (`golden/v1/request.json`) | 200 in 3.1 s, 3 suggestions: the AI keys work (Groq first) |
| **Outstanding** | A request from a second network during an exhausted window (step 5b); the startup log check (step 3); memory (step 8); the app on staging (step 6) |

**Round 2: 2026-10-16, commit `ff17a40` (D-024).** The owner merged `branch-02/user-history` into `staging` and
set `DATABASE_URL`; the log showed the migrations applied. Debug app on the Pixel 6a with a test account:

| Check | Result |
|---|---|
| Sign-in, server quota, picks | Work, until the free sets were used. Found BE-BUG-008 (recipes) and BE-BUG-009 (cuisine) |
| Profile, for an account with no profile anywhere | `GET /api/v2/me/profile` 404, onboarding, then `PUT` 200 |
| Device token | `PUT /api/v2/me/device` 204 |
| History | `GET /api/v2/me/history` 200; the sync worker succeeded |
| History upload (checked 2026-10-17 on the phone) | The app's outbox for the account is empty, the initial upload is marked done and a sync cursor is saved, with a meal logged on 2026-10-16. The app clears its outbox only after a successful push, so the meal reached the server. The `meal_history` row itself hasn't been looked at |
| Re-check 2026-10-17 (still `ff17a40`) | Cook at home picks 200 in 9.8 s (3 Pakistani dishes, quota `used` 2 of 3); v1 golden request 200 in 3.2 s; `/api/v2/me/history` without a token 401; a malformed v1 body still 500 with parser text (fixed on `branch-03/pre-production`, not deployed) |
| Re-check 2026-10-17 (`a00267b`, pre-production fixes) | A malformed v1 body is now 400 with fixed text (BE-BUG-007); the v1 golden request 200 in 3.1 s; with the free sets used up, picks return `429 DAILY_LIMIT_REACHED` and the app shows the paywall |
| Premium, 2026-10-17 | A RevenueCat Test Store purchase on the debug build: staging returned picks with `is_premium: true, unlimited: true` after the free sets were used, and an unlocked 9-step recipe on the first try (BE-BUG-008). Anywhere with Turkish: three Turkish picks (BE-BUG-009). A real Google Play test purchase is still to do (Android TECH-019) |
| Report and deletion, 2026-10-17 | `POST /api/v2/reports` 201 (dish hidden). `DELETE /api/v2/me` 200 `completed`: `user_data` deleted, `quota_counter` deleted, `reports` anonymized (1), `revenuecat_customer` skipped; the app cleared its local data and returned to sign-in |
| Client IP (step 5b), 2026-10-17 | With this PC kept over its limit (249 × 429), check-host.net nodes in Madrid, Mumbai, Warsaw and Moscow got through: each network is its own client (BE-BUG-006 closed). Their GET returned 500, which is now fixed to 405 on `branch-03/pre-production` (BE-BUG-007 note) |
| Startup log (step 3), 2026-10-17 | `The following 1 profile is active: "staging"`; `remote config settings refreshed free_daily=3 recipe_gate=true` (the service account reads the template); `database ready; migrations applied=0 schema_version=null` (a logging bug, BE-BUG-012, fixed on `branch-03`); the only `ERROR` was the step-5b GET (fixed, BE-BUG-007 note) |
| Memory (step 8), 2026-10-17 | About 16–18 % of the 2 GB limit (about 330–360 MB), flat across two deploys and the day's testing |
| Postgres stores, 2026-10-17 | `SMARTBITE_STORES_MODE=postgres` on staging. Health UP, v1 200, v2 401 without a token; a new account's picks count in Postgres (`used 1 of 3`); a recipe 200 (but one step only: BE-BUG-013, fixed on `branch-03`); a report 201. The startup log now names the store (`document store: postgres prefix='stg_'`, after the merge) |
| After the merge (`986088e`), 2026-10-17 | Two recipes on the first try with full methods (6 and 9 steps), the free plan showing 2 steps and the Premium lock (BE-BUG-013 verified) |
| Postgres stores complete, 2026-10-17 | Startup log `document store: postgres prefix='stg_'` and `database ready; migrations applied=0 schema_version=2` (BE-BUG-012 verified). Account deletion on Postgres: 200 `completed`, `quota_counter` deleted, `reports` anonymized (1), `user_data` deleted |
| Gemini fallback, 2026-10-17 | With `GROQ_API_KEY` deliberately wrong, v1 returned 502: Gemini answered 404 (`gemini-2.5-flash-lite` isn't available to this project). Fixed with `GEMINI_MODEL`, default `gemini-3.5-flash-lite` (BE-BUG-014); repeat after the merge. Groq key restored, v1 200 |
| Gemini fallback re-test, 2026-10-17 (`bad6d78`) | Groq key wrong: v1 200 (3.3 s), v2 picks 200 (`degraded: true`), a recipe 502 (`invalid JSON`: Gemini's reasoning used up `max_tokens`). Fixed on `branch-03` with reasoning head-room for Gemini; repeat the recipe after the merge. Groq key restored |
| Gemini fallback complete, 2026-10-17 (`a65a810`) | With the Groq key wrong, a recipe came through Gemini (`attempt=2`, 4.7 s, 12 steps). Groq key restored and answering (`attempt=1`) |
| **Outstanding** | A second device; a recipe and a report; account deletion; the round-1 items |

1. **Create the staging service from the Blueprint** (§4) and enter the secrets.
   - **Expect:** the build succeeds (the first real Docker build) and the deploy turns "Live".
2. **Health:** `curl -s https://<staging>/actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`.
3. **Startup logs:**
   - **Expect:** JSON (ECS) lines, `profiles: staging`, no `ERROR`.
   - **Expect:** `remote config settings refreshed free_daily=3 …` within a few seconds. A
     `remote config template read failed` warning means the service account can't read Remote Config.
4. **Auth:** `curl -s https://<staging>/api/v2/me/quota?timezone=Asia/Karachi` → 401 `UNAUTHENTICATED`.
5. **Client IP (BE-BUG-006).** The v1 per-IP limit is 60 requests a minute and counts even invalid requests, so
   this test costs no AI calls:
   - From one network, send 61 requests:
     `for i in $(seq 1 61); do curl -s -o /dev/null -w "%{http_code}\n" -X POST https://<staging>/api/v1/meal-suggestions -H "Content-Type: application/json" -d '{}'; done | sort | uniq -c`
     - **Expect:** 60 × `400` and 1 × `429`.
   - Within the same minute, from a **different network** (for example the phone on mobile data, or another PC),
     send one request.
     - **Expect:** `400`, not `429`. Getting `429` means all callers are being counted as one IP: stop and adjust
       `TRUSTED_PROXIES` before production.
   - From the first network again, add `-H "X-Forwarded-For: 1.2.3.4"`.
     - **Expect:** still `429`, so spoofing the header doesn't bypass the limit.
6. **The app on staging:**
   1. Set `SMARTBITE_STAGING_API_URL` and enable the Remote Config condition (§7.4).
   2. Install the debug app and sign in with a **test** account.
   3. Check:
      - picks for Cook at home, Near me / Around a place, and Anywhere
      - the server quota line, and the paywall after the free sets
      - a recipe (Premium lock for free users), and a report
   4. Last, delete the test account: the response shows `revenuecat_customer: skipped`.
7. **v1 with real keys:** one valid v1 request (`src/test/resources/golden/v1/request.json`) → 200 with three
   suggestions. This checks the Groq key in particular: v1 falls back to the next model only on HTTP 429, so an
   invalid Groq key fails v1 even when Gemini is fine (BE-BUG-007).
8. **Memory:** in Render's Metrics, after the tests, memory stays well under 2 GB.
9. **Finish:** suspend or delete the staging service.

## 10. Production cutover (owner)

Prerequisite: the staging round passed.
1. **Check:** Billing shows the Pro plan. Note the existing service's region and instance type.
2. **Protect against an early deploy:** on the existing service, turn **Auto-Deploy off**.
3. **Size and timing:** set the instance type to **Standard** and the max shutdown delay to 60 s.
4. **Environment variables:** add the production values (§6). Use "Save only" if offered. Otherwise Render
   redeploys the current code with the new variables, which is harmless: the old code ignores them.
5. **Merge and push:** merge `staging` (the tested code) into `master` and push.
6. **Deploy:** Manual Deploy → "Deploy latest commit". Render builds the image, starts it, and switches traffic
   only when the health check passes. If startup fails, the old version keeps serving.
7. **Health check path:** set it to `/actuator/health`.
8. **Verify:**
   - §9.2 steps 2, 4 and 7 against production
   - an installed Play Store app gets picks (v1)
   - the logs show no `ERROR`
9. **Watch:** monitor the logs and metrics for the first hour (§11).

v2 stays off for users until a later, separate decision: an app release plus Remote Config `api_v2_enabled`.

## 11. Rollback
- **Code:** Render dashboard → the service → Events/Deploys → pick the previous successful deploy → **Rollback**.
  The previous image is redeployed without a rebuild. Then revert the merge on `master` so the next deploy doesn't
  bring it back.
- **Configuration:** fix or remove the variable and redeploy. A missing secret already prevents the new version
  from going live (§1).
- **v2 for users:** set Remote Config `api_v2_enabled` back to `false`. Apps return to v1 on their next fetch.
- **Data:** v2 data is additive (new collections). Rolling back the code leaves it in place; nothing needs restoring.

## 12. Monitoring and operations
- **Render built-ins:**
  - Logs (JSON; search `level":"ERROR`)
  - Metrics (CPU, memory, HTTP)
  - deploy notifications by email or Slack (Workspace → Notifications)
  - optionally, a log stream to an external provider
- **Watch for these log signals:**
  - `ai provider rejected` or `PROVIDER_UNAVAILABLE`: provider keys or quotas
  - `account deletion abandoned`: needs a manual RevenueCat deletion
  - `v1 per-IP rate limit hit` (occasional is normal; frequent means a client-IP problem)
  - `store unavailable`: Firestore
- **External uptime check:** any uptime service on `GET /actuator/health` every 5 minutes. Free tiers exist; this
  isn't a Render resource.
- **Budget alerts:**
  - Google Cloud: covers Firestore, and Places later
  - the AI provider dashboards
- **Later:**
  - A custom domain (e.g. `api.<your-domain>`) pointed at the service, with the app using it. Region moves and
    provider changes then no longer need an app update.
  - The app update itself and a v1 sunset plan (D-021).

## 13. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Deploy fails at startup with "Missing required settings…" | A secret isn't set | Add the variable named in the log |
| "Running on Render without a deployed profile" | `SPRING_PROFILES_ACTIVE` is missing | Set `prod` or `staging` |
| Startup error decoding the service account | `FIREBASE_SERVICE_ACCOUNT_BASE64` isn't single-line base64 | Re-encode it (§7.1) |
| v2 calls return 401 for real users | Wrong `FIREBASE_PROJECT_ID` (token audience) | Match the Firebase project ID |
| v1 returns 502 "AI service error" | An AI key is invalid; v1 doesn't fall back on 401 | Check the Groq and Gemini keys |
| Many 429s on v1 from different users | Client IP resolved wrongly (BE-BUG-006) | Check `TRUSTED_PROXIES`; raise `V1_RATE_LIMIT_PER_MINUTE` temporarily |
| Instance restarts with an exit after `OutOfMemoryError` | Instance too small | Standard or larger (§3) |
| Build fails at `dependency:go-offline` | Maven Central unreachable or a dependency moved | Retry the deploy; check the build log |
| Deploy hangs at "health check" | The app didn't start within Render's window, or the path is wrong | Path `/actuator/health`; read the startup log |

## 14. Deployment checklist (owner)

**Before staging**
- [ ] Billing: Pro plan active; note the existing service's instance type and region.
- [x] Push the feature branches and `staging` to GitHub (`idraakailabs/SmartBite-Backend`); Render builds from the remote.
- [x] Create the secrets (§7.1, §7.2) and generate a `LOG_UID_HASH_KEY` for each environment.
- [x] Check the Firestore rules (§7.3). Replaced 2026-10-17 (the old rules denied all app access).

**Staging round**
- [x] New → Blueprint → `deploy/render-staging.yaml`; enter the six secrets (2026-10-16).
- [x] Run §9.2 steps 1–8, including the client-IP test (step 5). All passed (2026-10-17).
- [x] Debug app: `SMARTBITE_STAGING_API_URL`, the Remote Config condition (§7.4), a test account.
- [x] D-024: `DATABASE_URL` on staging and the debug-app condition for `backend_user_data_enabled` (§15.2 steps 1–4).
- [ ] D-024 checks (§15.2 step 5). Done: history upload, account deletion. Outstanding: a second device.
- [x] Merge `branch-03/pre-production` into `staging` and repeat the affected checks (2026-10-17, all passed): a recipe, Anywhere with one
  cuisine, a v1 request.
- [ ] Suspend or delete the staging service when testing ends.

**Production**
- [ ] Existing service: Auto-Deploy **off**, Standard instance, 60 s shutdown delay.
- [ ] Production variables (§6), marked secret.
- [ ] Merge `staging` → `master`; push.
- [ ] Manual deploy; health check path `/actuator/health`.
- [ ] Verify (§10 step 8): health, 401 without a token, one v1 request, the Play Store app gets picks.
- [ ] Watch the logs and metrics for an hour; know where Rollback is (§11).

**Afterwards (separate decisions)**
- [ ] Turning v2 on for users (app release plus `api_v2_enabled`).
- [ ] A Google Places key and budget (`PLACES_PROVIDER=google`).
- [ ] A custom domain.

## 16. Custom API domain (Cloudflare), after the production deploy (BE-026)

The owner bought a domain at Cloudflare (2026-10-17). Do this **after** §10 is verified, so a problem points at
either the deploy or DNS, not both.

1. Render → the production service → **Settings → Custom Domains** → add `api.<domain>`. Render shows the target
   (the service's `….onrender.com` host).
2. Cloudflare → DNS → add a **CNAME**: name `api`, target that host, **Proxy status: DNS only (grey cloud)**.
3. Remove any **AAAA** record for `api` (Render doesn't support IPv6 yet).
4. Wait for Render to show the certificate as issued (Custom Domains → Verify if needed), then check
   `https://api.<domain>/actuator/health` → `UP`.
5. Optionally the same for staging: `staging-api.<domain>` on smartbite-api-staging.

**Keep the Cloudflare proxy off permanently.** `ClientIpResolver` trusts one proxy hop (Render's,
`TRUSTED_PROXIES=1`, verified in BE-019). Behind Cloudflare's proxy every user would appear as a Cloudflare address
and share one per-IP rate limit. Caching doesn't help this API, and Render already provides HTTPS. Turning the proxy
on would first need `CF-Connecting-IP` support in `ClientIpResolver`.

**Never remove the `onrender.com` address:** released apps call it directly. The v2 app release switches its
production base URL to the new domain (Android TECH-020); older versions keep working.

Source: [Render: Configuring Cloudflare DNS](https://render.com/docs/configure-cloudflare-dns) (checked 2026-10-17).

## 15. User data on our database (D-024): staging and production

**Built on `branch-02/user-history` (backend) and `branch-13/user-history` (Android), 2026-10-16. Deployed to
staging with `smartbite-db` (round 2 in §9.2); not in production.**
Everything is off until you set `DATABASE_URL` and turn on two Remote Config keys, so merging changes nothing for
users by itself.

### 15.1 Costs
| Item | Cost |
|---|---|
| Staging database | `smartbite-db` already exists (Free plan, Singapore). Free: $0, expires 30 days after creation, no backups, test data only |
| Production database | Render Postgres `0.1c-256mb` about **$6/month** plus $0.30/GB storage (1 GB to start), with backups. Same region as the production service. Create it only at launch |
| Web services | Unchanged. The database adds no instance |

### 15.2 Staging (owner)
1. **Merge the feature into staging:** merge `branch-02/user-history` into `staging` and push. The staging
   service redeploys. With no `DATABASE_URL` it behaves exactly as before.
2. **Connect the staging database:**
   1. Render → **smartbite-db** → **Connect** → copy the **Internal Database URL**.
   2. smartbite-api-staging → **Environment** → add **`DATABASE_URL`** = that URL (as a secret) → Save. The
      service redeploys.
   3. The log should show `database ready; migrations applied=2 schema_version=2`.
3. **Optional:** set `SMARTBITE_STORES_MODE=postgres` to also move the server's own documents (quota, caches,
   reports) to the database. Staging Firestore data (`stg_`) isn't copied; it's test data.
4. **Turn it on for the debug app only.** In Firebase Remote Config:
   - add a conditional value `backend_user_data_enabled` = `true` for the condition "App is
     `com.idraak.ai.smartbite.debug`"
   - keep the default `false`
   - `api_v2_enabled` must already be on for the debug app
5. **Install the debug app** from `branch-13/user-history` (with `SMARTBITE_STAGING_API_URL` set) and sign in with
   a **test** account. Check:
   - **Existing history uploads:** log a meal; within a few seconds `meal_history` has a row (see the SQL
     below).
   - **Profile import:** open Profile. The profile comes from the server: the first read imports the
     Firestore document, if any (`source = firestore_import`).
   - **Second phone:** sign in with the same account on a second phone or emulator (debug build). The history
     and favourites appear after the first sync.
   - **Edits:** edit and delete on one phone, and the changes reach the other.
   - **Account deletion** (test account only): `steps.user_data` = `deleted`, and the rows are gone.
6. **Inspect the data (read-only).** In smartbite-db → **Connect** → PSQL command, from a trusted machine:
   ```sql
   select count(*) from meal_history;
   select uid, source, updated_at from user_profile order by updated_at desc limit 5;
   select uid, last_seen_at from user_activity order by last_seen_at desc limit 5;
   ```

### 15.3 Production (owner, later, after the app release that contains this code)
1. **Create the database:** a Render Postgres **0.1c-256mb ($6)**, 1 GB, **same region as the production
   service**. Check **Recovery** (backups and point-in-time recovery).
2. **Connect it:** on the production service, set `DATABASE_URL` to its Internal URL. That's a deploy; the
   migrations run at startup.
3. **Release the app** (a Play update) with this code. Nothing changes for users yet.
4. **Turn it on:** in Remote Config, `backend_user_data_enabled` = `true` (with `api_v2_enabled` already on).
   - Phones upload their local history on their next sync.
   - Profiles are copied from Firestore on each user's first read.
   - Consider a percentage rollout (Remote Config condition: user in random percentile) for the first days.
5. **Update the hosted privacy policy and the Play Data safety form first.** History, favourites, notes and the
   profile become stored on our servers. The repository copy is updated: `legal/privacy-policy.html` in the
   Android repo.

### 15.4 Rollback
- **Switch it off:** set `backend_user_data_enabled` back to `false`. Apps return to Firestore for the profile,
  and history stays on the phones (nothing is deleted).
- **Caveat:** profile edits made while it was on exist only in our database, and Firestore holds the older copy.
  Phones keep their local copy, so only a reinstall would show the older Firestore profile.
- **Keep the database:** turning it back on resumes where it left off.

### 15.5 Not migrated, by design
- **Firestore stays as it is:** no bulk export, and no deletion. Profiles are copied per user on first read, so
  users who never open the new app version keep only their Firestore copy, as today.
- **History older than 24 months** stays on the phone and isn't uploaded.


# Environments and configuration (v2)

> **Deployment guide and checklist: [DEPLOYMENT.md](DEPLOYMENT.md)** (2026-10-16). It supersedes §4–§5 below where they differ.
>
> **Status: plan, 2026-10-11. Nothing here has been executed.** No Render service, setting or secret has been
> created or changed. Every step marked "owner" needs the owner's explicit approval at the time it runs.

## 1. Environments

| | Staging | Production |
|---|---|---|
| Render service | New service, e.g. `smartbite-backend-staging` (to be created) | Existing `smartbite-backend-zsyr.onrender.com` |
| Branch | `branch-01/v2-api` until merged, then `master` | `master` |
| Spring profile | `staging` | `prod` |
| Android build | debug (`.debug` app id) via `BuildConfig` base URL | release via `BuildConfig` base URL |
| Firestore data | same Firebase project, collection prefix `stg_` (unless a separate project exists) | prefix empty |
| Places | `fake` until the owner's key arrives, then `google` with low caps | `google` |
| Auto-deploy | on (from the branch) | **off**; manual deploys with approval |

**Finding:** `src/main/resources/application.yaml` hard-codes `spring.profiles.active: local`. On Render that
profile is active today unless `SPRING_PROFILES_ACTIVE` is set. Harmless now (no `application-local.yaml` is
shipped), but v2 moves the active profile out of the file and requires `SPRING_PROFILES_ACTIVE` to be set.

## 2. Variables

Secret = must be stored as a Render secret value (or secret file), never in Git, images, logs or chat. Values in
the "staging" and "production" columns are defaults or examples, not secrets.

**Final list (2026-10-11):** every `${…}` placeholder in `src/main/resources/application*.yaml` on
`branch-01/v2-api` is listed here.
- **Required** = the service can't serve v2 correctly without it in a deployed environment.
- Durations use Spring's simple format (`5m`, `25s`, `7d`).
- The **Default** column is the value used when the variable is unset.

| Name | Required | Secret | Default | Staging | Production | Purpose |
|---|---|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **yes** | no | *(none: base config only)* | `staging` | `prod` | Profile selection. Unset, the staging/prod overrides (structured logs, Firestore prefix, fake-token ban) don't apply |
| `PORT` | no | no | `8080` | set by Render | set by Render | `server.port` |
| `GEMINI_API_KEY` | **yes** | **yes** | *(none: startup fails)* | staging key | production key | AI provider (v1 and v2). Rotate after the Remote Config keys are removed (TECH-001) |
| `GROQ_API_KEY` | recommended | **yes** | empty (Groq models skipped) | staging key | production key | AI provider (v1 and v2). Rotate as above |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | **yes** | **yes** | empty | base64 of the service-account JSON | same | Admin SDK: ID-token verification, Firestore, Remote Config template read |
| `FIREBASE_PROJECT_ID` | **yes** | no | empty | project id | project id | Expected token audience; checked against the service account |
| `FIRESTORE_COLLECTION_PREFIX` | no | no | `stg_` in `staging`, empty in `prod` | `stg_` | *(empty)* | Keeps staging data apart in the shared Firebase project (D-021) |
| `SMARTBITE_STORES_MODE` | no | no | `firestore` | `firestore` | `firestore` | `postgres` keeps the server's documents in our database (needs `DATABASE_URL`, D-024). `in-memory` is for local runs only; `StartupGuard` refuses it unless in-memory stores are explicitly allowed. Since 2026-10-16 the `staging`/`prod` profiles read this variable instead of hard-coding `firestore` |
| `DATABASE_URL` | for user data | **yes** | empty | the staging database's **Internal** URL | the production database's Internal URL | Our PostgreSQL (D-024). Render's connection string works as-is. Empty: history and profile endpoints answer 503 `FEATURE_UNAVAILABLE` |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | no | password: **yes** | empty | — | — | Only with a `jdbc:` URL; Render's URL already contains them |
| `DATABASE_MAX_POOL_SIZE` | no | no | `5` | `5` | `5` | Connections per instance; keep within the database plan's limit |
| `REVENUECAT_SECRET_API_KEY` | **yes** | **yes** | empty | v2 secret key | v2 secret key | Entitlement reads and customer deletion |
| `REVENUECAT_PROJECT_ID` | **yes** | no | empty | project id | project id | RevenueCat API v2 paths |
| `REVENUECAT_ENTITLEMENT_LOOKUP_KEY` | no | no | `smartbite_pro` | `smartbite_pro` | `smartbite_pro` | Must match the dashboard and the app's `PREMIUM_ENTITLEMENT` |
| `ENTITLEMENT_PROVIDER` | no | no | `revenuecat` | `revenuecat` | `revenuecat` | `revenuecat` or a test fake |
| `ENTITLEMENT_CACHE_TTL_PREMIUM` | no | no | `5m` | `5m` | `5m` | |
| `ENTITLEMENT_CACHE_TTL_FREE` | no | no | `1m` | `1m` | `1m` | |
| `GOOGLE_PLACES_API_KEY` | when `PLACES_PROVIDER=google` | **yes** | empty | key (unset while `fake`) | key | Places API (New), server only |
| `PLACES_PROVIDER` | no | no | `disabled` | `fake`, then `google` | `google` | `google`, `fake` or `disabled` (`disabled` → every eat-out pick is labelled "Popular around") |
| `PLACES_NEARBY_GLOBAL_DAILY_CAP` | no | no | `150` | `20` | `150` | Nearby calls per UTC day (150 × 31 = 4,650, under the 5,000 free) |
| `PLACES_TEXT_GLOBAL_DAILY_CAP` | no | no | `3000` | `200` | `3000` | Text Search (IDs Only) calls per UTC day |
| `PLACES_NEARBY_PER_UID_DAILY` | no | no | `15` | `15` | `15` | Per-user Nearby cache misses per day |
| `PLACES_TEXT_PER_UID_DAILY` | no | no | `60` | `60` | `60` | Per-user Text Search calls per day |
| `PLACES_CACHE_TTL` | no | no | `7d` | `7d` | `7d` | Must stay ≤ 30 days; `StartupGuard` refuses more (Service Specific Terms §14.3) |
| `PLACES_VERIFY_ALTERNATIVES` | no | no | `false` | `false` | `false` | Verify all three picks, not just the hero |
| `FREE_DAILY_SUGGESTIONS` | no | no | `3` | `3` | `3` | Fallback when the Remote Config template can't be read |
| `RECIPE_STEPS_PREMIUM` | no | no | `true` | `true` | `true` | Fallback for the Remote Config key of the same name (D-014) |
| `RECIPE_FREE_PREVIEW_STEPS` | no | no | `2` | `2` | `2` | Steps shown to free users |
| `REMOTE_CONFIG_SYNC_ENABLED` | no | no | `true` | `true` | `true` | Read `free_daily_suggestions` and `recipe_steps_premium` from the template every 5 min |
| `SUGGESTION_DEADLINE` | no | no | `25s` | `25s` | `25s` | Whole-request budget (the app's read timeout is 30 s) |
| `RECIPE_DEADLINE` | no | no | `40s` | `40s` | `40s` | (The app's read timeout is 45 s) |
| `CORS_ALLOWED_ORIGINS` | no | no | `*` | `*` | `*` | Applies to `/api/v1/**` only; `*` keeps v1 unchanged |
| `V1_RATE_LIMIT_PER_MINUTE` | no | no | `60` | `60` | `60` | Per-IP limit on v1 (D-021 item 4) |
| `V1_RATE_LIMIT_PER_HOUR` | no | no | `600` | `600` | `600` | Per-IP limit on v1 |
| `TRUSTED_PROXIES` | no | no | `1` | `1` | `1` | Proxy hops to trust in `X-Forwarded-For` when resolving the client IP (Render adds one) |
| `LOG_UID_HASH_KEY` | **yes** | **yes** | empty | random 32 bytes (base64) | a different random value | HMAC key for logging uids as hashes |
| `JAVA_TOOL_OPTIONS` | no | no | — | *(unset)* | *(unset)* | Extra JVM flags. The image already sets `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` (2026-10-16) |
| `BILLING_DELETION_ENABLED` | no | no | `true`; `false` in the `staging` profile | `false` | *(unset)* | Delete the RevenueCat customer on `DELETE /api/v2/me`. Off on staging because the RevenueCat project is shared (BE-BUG-003) |
| `RENDER` | — | no | set by Render | `true` | `true` | Not set by us. With it present, the app refuses to start without `SPRING_PROFILES_ACTIVE=prod|staging` (`DeployedConfigGuard`) |

Not read by the code yet (reserved): `APP_CHECK_ENFORCE`, for the App Check follow-up (ADR-0003).

Rate limits and timeouts also get properties with the defaults from `docs/api/README.md`; they only need env
overrides if tuning is required.

### Why `FIREBASE_SERVICE_ACCOUNT_BASE64`

- The service-account JSON contains a multi-line private key with `\n` escapes. Pasting raw JSON into an env var is
  error-prone (quoting, newlines) and fails in confusing ways.
- Base64 is a single line, behaves the same locally, in CI and on Render, and is decoded in memory straight into
  `GoogleCredentials`; nothing is written to disk.
- Render secret files are an alternative, but Render documents that Docker services need a different access
  method for them ([Render env vars and secrets](https://render.com/docs/configure-environment-variables), accessed
  2026-10-11), and the env var keeps local runs simple.
- Base64 is encoding, not encryption: the value is exactly as sensitive as the JSON.

## 3. What the owner provides (D-011)

1. **Firebase service account** for the SmartBite project: Firebase console → Project settings → Service accounts →
   generate a key. Store it only as `FIREBASE_SERVICE_ACCOUNT_BASE64` (`base64 -w0 key.json`), then delete the local
   file. Confirm whether staging gets its own service account (recommended, so either key can be revoked alone).
2. **RevenueCat v2 secret key** with permissions `customer_information:customers:read_write` and
   `project_configuration:entitlements:read`, and the project id. (v1 keys don't work with API v2, per the
   RevenueCat API v2 docs.)
3. **Google Cloud**: a billing-enabled project with Places API (New) enabled; an API key restricted to Places API
   (New); a **per-day request quota** on the API and a budget alert. Budget alerts notify but don't stop spend.
   Optionally restrict the key to Render's outbound IPs (Render publishes them per region; not verified here).
4. **Render**: access, the staging service, and the plan choice for staging and production (§5).

## 4. Staging deploy on Render (steps for the owner; not executed)

Classification: every step below **changes remote state or may cost money** → owner approval per step.

1. Pre-checks (local, safe): `./mvnw -B verify` green (unit, MockMvc, WireMock, v1 golden-contract test);
   `docker build -t smartbite-backend:v2-rc .` succeeds on a machine with Docker (Docker isn't installed on the
   current dev machine; a CI build or Render's own Docker build is the alternative). OpenAPI lint clean.
2. Create a new Render **Web Service** from the backend repo, branch `branch-01/v2-api`, runtime **Docker**, same
   region as production (production's region: to be read from the Render dashboard), plan per §5.
3. Health check path: `/actuator/health`. (Production currently has no Actuator; its check probably points at `/`
   served by `HelloController`; read it from the dashboard before changing anything.)
4. Add the env vars from §2 with staging values; secrets as secret values. `PLACES_PROVIDER=fake` until the Places
   key exists.
5. Deploy. Watch the logs for startup: Firebase credentials loaded, RevenueCat entitlement lookup resolved,
   Remote Config template read.
6. Verify (reads remote state only):
   ```bash
   curl -s https://<staging-host>/actuator/health
   # expect 401 with code UNAUTHENTICATED
   curl -s -X POST https://<staging-host>/api/v2/meal-suggestions -H 'Content-Type: application/json' -d '{}'
   # with a real debug-build ID token (never paste tokens into chat or docs)
   curl -s https://<staging-host>/api/v2/me/quota?timezone=Asia/Karachi -H "Authorization: Bearer $ID_TOKEN"
   # v1 unchanged
   curl -s -X POST https://<staging-host>/api/v1/meal-suggestions -H 'Content-Type: application/json' -d @v1-sample.json
   ```
7. Point the Android debug build at the staging host by adding `SMARTBITE_STAGING_API_URL=https://<staging-host>/`
   to the Android repo's `local.properties`, which becomes `BuildConfig.API_BASE_URL` for debug builds. Then run
   the end-to-end checks on the Pixel_7 / Pixel_9 emulators (D-020).
8. Rollback: redeploy the previous Render deploy (staging only; production untouched).

Production follows the same steps later, with auto-deploy off, after staging sign-off and explicit approval.

## 5. Hosting notes for the owner's decision

- Render free web services spin down after 15 minutes without traffic and take about a minute to start again;
  Render says not to use them for production ([Render free](https://render.com/docs/free), accessed 2026-10-11).
  The current production plan isn't known from the repository. If it is free, cold starts explain part of BUG-019.
- Adding the Firebase Admin SDK (gRPC) raises memory use. The current `-Xmx400m` suggests a 512 MB instance. Measure
  heap and RSS on staging before choosing the production instance size. Prices weren't checked for this document;
  check Render's pricing page when deciding.

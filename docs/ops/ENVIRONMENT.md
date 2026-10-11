# Environments and configuration (v2 plan)

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

| Name | Secret | Staging | Production | Purpose |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | no | `staging` | `prod` | Profile selection (required) |
| `PORT` | no | set by Render | set by Render | Already used (`server.port`) |
| `GROQ_API_KEY` | **yes** | staging key | production key | Existing. Rotate both after the Remote Config keys are removed (TECH-001) |
| `GEMINI_API_KEY` | **yes** | staging key | production key | Existing (the app fails to start without it today) |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | **yes** | base64 of the service-account JSON | same or separate SA | Admin SDK: token verification, Firestore, Remote Config template read |
| `FIREBASE_PROJECT_ID` | no | project id | project id | Expected token audience; sanity check against the SA |
| `FIRESTORE_COLLECTION_PREFIX` | no | `stg_` | *(empty)* | Keeps staging data apart |
| `REVENUECAT_SECRET_API_KEY` | **yes** | v2 secret key | v2 secret key | Entitlement read and customer deletion |
| `REVENUECAT_PROJECT_ID` | no | project id | project id | v2 API paths |
| `REVENUECAT_ENTITLEMENT_LOOKUP_KEY` | no | `smartbite_pro` | `smartbite_pro` | Must match the dashboard and the app's `PREMIUM_ENTITLEMENT` |
| `GOOGLE_PLACES_API_KEY` | **yes** | key (or unset with `fake`) | key | Places API (New), server only |
| `PLACES_PROVIDER` | no | `fake` → `google` | `google` | `google`, `fake` or `disabled` |
| `PLACES_NEARBY_GLOBAL_DAILY_CAP` | no | `20` | `150` | Nearby calls per UTC day (150 × 31 = 4,650 < 5,000 free) |
| `PLACES_TEXT_GLOBAL_DAILY_CAP` | no | `200` | `3000` | Text Search (IDs Only) calls per UTC day |
| `PLACES_NEARBY_PER_UID_DAILY` | no | `15` | `15` | Per-user Nearby misses per day |
| `PLACES_CACHE_TTL` | no | `P7D` | `P7D` | Must stay ≤ `P30D` (validated at startup) |
| `PLACES_VERIFY_ALTERNATIVES` | no | `false` | `false` | Verify all three picks, not just the hero |
| `FREE_DAILY_SUGGESTIONS` | no | `3` | `3` | Fallback when the Remote Config template can't be read |
| `RECIPE_STEPS_PREMIUM` | no | `true` | `true` | Fallback for the Remote Config key of the same name |
| `RECIPE_FREE_PREVIEW_STEPS` | no | `2` | `2` | Steps shown to free users |
| `REMOTE_CONFIG_SYNC_ENABLED` | no | `true` | `true` | Read `free_daily_suggestions` and `recipe_steps_premium` default values from the template every 5 min |
| `ENTITLEMENT_CACHE_TTL_PREMIUM` | no | `PT5M` | `PT5M` | |
| `ENTITLEMENT_CACHE_TTL_FREE` | no | `PT1M` | `PT1M` | |
| `SUGGESTION_DEADLINE` | no | `PT25S` | `PT25S` | Whole-request budget |
| `RECIPE_DEADLINE` | no | `PT40S` | `PT40S` | |
| `CORS_ALLOWED_ORIGINS` | no | `*` | `*` | Applies to `/api/v1/**` only; `*` keeps v1 unchanged |
| `LOG_UID_HASH_KEY` | **yes** | random 32 bytes (base64) | different random value | HMAC key for logging uids as hashes |
| `JAVA_TOOL_OPTIONS` | no | e.g. `-XX:MaxRAMPercentage=75` | same | Replaces the hard-coded `-Xmx400m` once the instance size is known |
| `APP_CHECK_ENFORCE` | no | *(not used yet)* | *(not used yet)* | Reserved for the App Check follow-up (ADR-0003) |

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

## 4. Draft staging deploy on Render (not executed)

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
7. Point the Android debug build's `BuildConfig` base URL at the staging host and run the end-to-end checks on the
   Pixel_7 / Pixel_9 emulators (D-020).
8. Rollback: redeploy the previous Render deploy (staging only; production untouched).

Production follows the same steps later, with auto-deploy off, after staging sign-off and explicit approval.

## 5. Hosting notes for the owner's decision

- Render free web services spin down after 15 minutes without traffic and take about a minute to start again;
  Render says not to use them for production ([Render free](https://render.com/docs/free), accessed 2026-10-11).
  The current production plan isn't known from the repository. If it is free, cold starts explain part of BUG-019.
- Adding the Firebase Admin SDK (gRPC) raises memory use. The current `-Xmx400m` suggests a 512 MB instance. Measure
  heap and RSS on staging before choosing the production instance size. Prices weren't checked for this document;
  check Render's pricing page when deciding.

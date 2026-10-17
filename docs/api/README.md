# SmartBite API v2: contract summary

> **Status: agreed by the owner with amendments (D-021, 2026-10-11).** The machine-readable contract
> is [openapi-v2.yaml](openapi-v2.yaml) (OpenAPI 3.1, linted with Redocly CLI: valid, 2 cosmetic warnings).
> Decisions behind it: [ADRs](../adr/). Environment and deploy plan: [ENVIRONMENT.md](../ops/ENVIRONMENT.md).
> Costs: [COSTS.md](../ops/COSTS.md). Product decisions referenced as `D-0xx` live in the Android repository's
> `docs/DECISIONS.md`.

## 0. Implementation status (2026-10-11)

**Implemented on `branch-01/v2-api`.**

| Commit | What |
|---|---|
| `fedc7cc` | v1 golden-contract test (written before the upgrade) |
| `7408e61` | Spring Boot 4.1.1, Java 21 in `pom.xml` and Docker |
| `541cd55` | v1 hardening: AI timeouts, per-IP rate limit, CORS on `/api/v1/**` only |
| `56a53e4` | v2 foundations: Firebase ID-token auth, problem+json errors, config, logging without personal data, Actuator health |
| `1b75444` | Quota (Firestore, atomic, user's day), RevenueCat entitlement, `GET /api/v2/me/quota` |
| `a20d4d0` | `POST /api/v2/meal-suggestions`: taste-first prompt, provider chain, diet filter, evidence-based reasons |
| `65f01d2` | Places (New) area pipeline, caches and spend caps |
| `6b25c42` | `POST /api/v2/recipes` with the Premium step gate |
| `3da6960` | `POST /api/v2/reports` |
| `9736b1f` | `DELETE /api/v2/me` with a server-side retry for the RevenueCat deletion |
| `a818861` | Contract fixtures shared with the Android app; `verification.attribution` on verified picks |
| `7ac27d7` | `DocumentStore` contract tests, in memory and on the Firestore emulator |

**Verified (2026-10-11, development machine, JDK Corretto 21.0.7):**
- `./mvnw -B verify`: **126 tests, 0 failures, 0 errors, 0 skipped. BUILD SUCCESS.** It produces
  `target/smartbite-0.0.1-SNAPSHOT.jar`. The tests are unit, MockMvc, the v1 golden contract, and WireMock or
  in-process fakes for the AI providers, Places, RevenueCat, the Firestore store and Remote Config.
- The Android client (commit `4364739`) matches the contract on:
  - paths and the quota `timezone` query parameter
  - the `Idempotency-Key` and `X-Entitlement-Refresh` headers
  - the `logged` history entry type
  - the daily-limit problem fields (`limit`, `reset_at`)

**Update (2026-10-12):** `./mvnw -B verify` gives 154 tests, 0 failures, 11 skipped. The 11 skipped are the
Firestore emulator store tests, which passed 11/11 in a separate run against the emulator (Firebase CLI 15.33.0).
The Android app and this service now share golden request and response fixtures (`docs/TECHNICAL.md` §6).

**Update (2026-10-16):** deployment prepared ([../ops/DEPLOYMENT.md](../ops/DEPLOYMENT.md)). `DELETE /api/v2/me`
can report `revenuecat_customer: skipped` on staging, where customer deletion is disabled (additive). 162 tests pass.

**Pending (not verified):**
- the Docker image build (Docker needs WSL2, which is off on the development machine; Render or CI will build it)
- real calls to Groq, Gemini, Google Places and RevenueCat
- the Remote Config template read against the real project
- the staging deploy and the end-to-end run with the Android debug build (`docs/ops/ENVIRONMENT.md` §4)

## 1. Endpoints

| Method and path | Purpose | Quota | Rate limit (per uid, default) |
|---|---|---|---|
| `POST /api/v2/meal-suggestions` | One pick set (up to 3) for an intent and area | Counts 1 for free users on a 200 | 6/min, 40/h |
| `POST /api/v2/recipes` | Authentic regional recipe; locked preview for free users | No | 6/min, 30/h |
| `POST /api/v2/reports` | Report a suggestion or recipe | No | 10/h, 30/day |
| `GET /api/v2/me/quota?timezone=` | Read today's allowance without using it | No | 30/min |
| `DELETE /api/v2/me` | Delete server-side data and the RevenueCat customer (account deletion) | No | 3/h |
| `GET`/`PUT /api/v2/me/profile` | The app's profile in our database; the first read imports the legacy Firestore profile (D-024) | No | 30/min |
| `PUT /api/v2/me/device` | This device's FCM token (D-024) | No | 30/min (shared with profile) |
| `GET`/`PUT /api/v2/me/history` | Meal history and favourites sync, 24-month retention (D-024) | No | 30/min |
| `GET /actuator/health` | Platform health check; no auth, no details | n/a | n/a |
| `POST /api/v1/meal-suggestions` | **Unchanged** legacy endpoint for older app versions | n/a | None today; a per-IP limit is proposed and needs approval (§14) |

Rate limits are in memory per instance (one Render instance today). Exceeding one returns `429 RATE_LIMITED`
with `Retry-After`. A per-IP limit (120/min) applies before authentication to blunt token-guessing floods.

## 2. Conventions

- **JSON is snake_case**, like v1. The service sets snake_case globally in Jackson; using camelCase only for v2
  would need a second `ObjectMapper` and would risk changing v1 output.
- Timestamps are UTC ISO-8601. Local dates are `YYYY-MM-DD`. Request bodies are capped at 32 KiB. Unknown request
  fields are ignored; clients must ignore unknown response fields and tolerate new enum values.
- Headers: `Authorization` (required), `X-Request-Id` (optional, echoed), `X-App-Version` (optional, logged),
  `Idempotency-Key` (suggestions and reports), `X-Entitlement-Refresh` (after a purchase or restore).
- **One error body everywhere:** RFC 9457 Problem Details (`application/problem+json`) with a stable `code`
  (§13). Bodies never contain exception messages or provider responses. (Today's v1 handler returns
  `ex.getMessage()`; v2 gets its own handler so v1 output doesn't change.)

## 3. Authentication

- Every v2 request carries `Authorization: Bearer <Firebase ID token>`. The server verifies it with the Firebase
  Admin SDK (signature, issuer, audience = Firebase project id, expiry). Failure → `401 UNAUTHENTICATED`. The app
  refreshes the token once (`getIdToken(true)`) and retries.
- Anonymous Firebase accounts → `403 ACCOUNT_REQUIRED`.
- Revocation is checked only on `DELETE /api/v2/me`, which also requires `auth_time` within 5 minutes
  (the app re-authenticates just before deletion). Otherwise `401 REAUTHENTICATION_REQUIRED`.
- Firebase App Check is a documented follow-up, not required now. See [ADR-0003](../adr/0003-auth-firebase-id-token-app-check-later.md).

## 4. Daily quota (free users)

- **Allowance:** `free_daily_suggestions` successful sets per **local calendar day** (default 3). The server is
  authoritative. It reads the value from the Firebase Remote Config template's default value (refreshed every
  5 minutes, clamped to 0–50) and falls back to the `FREE_DAILY_SUGGESTIONS` env var. Conditional Remote Config
  values are ignored by the server.
- **What counts:** only a `200` from `POST /meal-suggestions` with at least one suggestion. Validation errors,
  `GENERATION_FAILED`, `PROVIDER_UNAVAILABLE`, timeouts and recipes don't count. Each refined set counts as a new
  set (product note: revisit if refine feels punitive with 3 free sets).
- **Atomicity:** a Firestore transaction on `quota/{uid}` *reserves* a slot before the AI call
  (`used + active reservations < limit`, else `429 DAILY_LIMIT_REACHED`). On success a second transaction turns the
  reservation into `used + 1`; on failure it releases the reservation. Reservations expire after 2 minutes, so a
  crash can't leak a slot. Concurrent requests therefore can't exceed the limit.
- **Timezone:** the day is computed from the **server clock** in the request's IANA `timezone` (the device clock is
  never trusted). To stop timezone hopping from resetting the day, the timezone is pinned on the quota document:
  a different timezone is accepted only if the pinned one was set more than 24 hours ago; otherwise the pinned zone
  is used and returned in `quota.timezone`.
- **Response:** every `200` carries `quota` (`is_premium`, `unlimited`, `limit`, `used`, `remaining`, `day`,
  `timezone`, `reset_at` = next local midnight in UTC). `DAILY_LIMIT_REACHED` carries `limit`, `used`, `reset_at`
  and `Retry-After` (seconds to `reset_at`).
- **Premium:** not counted and not capped; the per-uid rate limits still apply.
- **Client-side quota** (`SuggestionQuotaStore`) stays for UX only (showing the paywall early). The app should use
  `quota` from the server for display.

## 5. Entitlement (Premium)

- Source of truth: **RevenueCat REST API v2** `GET /v2/projects/{project_id}/customers/{uid}`; Premium when
  `active_entitlements` contains the entitlement whose `lookup_key` is `smartbite_pro`. The v2 API returns the
  internal entitlement id, so the server resolves `smartbite_pro` → id once at startup through
  `GET /v2/projects/{project_id}/entitlements` and caches it. `404` customer → not Premium.
  (API v1's `GET /subscribers/{id}` is "get **or create**", so it isn't used.)
- **Cache (in memory, per uid):** Premium results 5 minutes; non-Premium results 60 seconds. `X-Entitlement-Refresh:
  true` bypasses the cache (max once per uid per 30 s), so a fresh purchase unlocks immediately.
- **RevenueCat unavailable:** use the last known value up to 24 hours old (stale-if-error); with no known value,
  treat as free. Never trust a client-supplied Premium flag.
- Key: a RevenueCat **v2 secret key** with `customer_information:customers:read_write` and
  `project_configuration:entitlements:read` (verified permission names on the API v2 reference). Rate limit of the
  Customer Information domain: 480 requests/minute (verified, same source).

## 6. Suggestions pipeline

1. Authenticate → rate limit → validate → resolve entitlement → reserve quota (free users).
2. Resolve the meal slot from the server clock in the user's timezone.
3. For `eat_out` with coordinates: load the area cell's nearby candidates (§7, step 2), in parallel with step 4.
4. Build the **taste-first prompt** (no calories, BMR, targets or goal framing; D-005, D-015): diet as a hard rule,
   dislikes as an avoid list, spice, cuisines, proteins, liked dishes, history reactions and entry types, recent
   suggestions, intent, area label/city, refine options. User strings are embedded as JSON data, never as
   instructions. The model returns structured attributes only (name, description, category, origin, diet,
   contains_egg, spice level, primary protein, ingredients, time, difficulty, `similar_to`).
5. **Providers** (same chain as v1): Groq `openai/gpt-oss-120b` → Gemini `gemini-2.5-flash-lite` → Groq
   `openai/gpt-oss-20b`. Fallback on 429, 5xx, timeouts, connection errors **and** unparseable or schema-invalid
   output. 401/403 from a provider also falls back, and logs an error (misconfigured key). JSON output mode is
   requested where the provider supports it (to be verified per provider during implementation).
6. **Strict parsing and validation:** a typed DTO with bean validation; the `category` must be in the enum (else
   `other`); lengths clipped; exactly the fields we asked for.
7. **Diet enforcement:** drop any suggestion whose declared diet conflicts with the request, or whose name or
   ingredients hit the server's deny-lexicon for the diet (vegetarian: meat, poultry, fish, seafood, gelatine;
   vegan: all of those plus eggs, dairy, ghee, honey). Also drop disliked foods, `recent_suggestions` and
   `session_excluded_dishes` matches. If fewer than 3 remain, regenerate once; then return what's left (1–3). Zero →
   `502 GENERATION_FAILED`, no quota used.
   - **Anywhere with chosen cuisines** (BE-BUG-009): the cuisines in `refine.cuisine` (comma-separated) are
     required. Picks of another cuisine are dropped and regenerated like the filters above. Only if none matches
     are the off-cuisine picks returned, with `meta.degraded = true`.
8. Area verification and ranking (§7). 9. Compute reasons (below). 10. Commit quota → respond.

**Deadlines:** the whole request has a 25 s budget. Per AI attempt: connect 3 s, read 15 s, and no new attempt
starts with less than 5 s of budget left. The app's read timeout for this call should be 30 s (today it's 90 s
with 3 retries, BUG-019).

**Diet rules (D-021):** `vegetarian` allows dairy and eggs (the app's onboarding copy says "Dairy and eggs are
fine"). `vegan` excludes eggs, dairy, ghee and honey. `non_vegetarian` has no exclusions. The deny-lexicon is a safety net, not an allergy guarantee.

**Evidence-backed reasons (fixes BUG-021):** the server, not the model, builds every reason by comparing a dish
attribute with request data. Each reason lists its evidence; with no evidence there's no reason.

| Code | Emitted only when |
|---|---|
| `DIET_MATCH` | Diet is vegetarian/vegan (or `vegetarian_today`) and the dish complies |
| `SPICE_MATCH` | Dish spice level equals the tolerance (or the `milder` target) |
| `FAVORITE_CUISINE` | `origin.cuisine` matches a `top_cuisines` entry (normalised, small alias table) |
| `FAVORITE_PROTEIN` | `primary_protein` matches a `favorite_proteins` entry |
| `LIKED_IN_TASTE_TEST` | The dish name matches a `liked_dishes` entry |
| `SIMILAR_TO_LOVED` | The model's `similar_to` names a dish that is in history with reaction `loved` (or in `liked_dishes`) |
| `LOVED_BEFORE` | The same dish is in history with reaction `loved` (entry type `cooked`, `ate_out` or `logged`) |
| `FITS_COOKING_TIME` | Intent is `cook`/`anywhere` and the time estimate ≤ the user's `cooking_time` limit |
| `FITS_SKILL` | Intent is `cook` and difficulty ≤ the user's skill |
| `REFINE_APPLIED` | A refine option the dish satisfies (`quicker` ≤ 30 min, `milder`, `vegetarian_today`) |
| `FRESH_CHOICE` | History has entries and the dish isn't among the last 7 days' dishes or recent suggestions |

History `entry_type` accepts `cooked`, `ate_out`, `suggested` and `logged`. `logged` marks entries saved before
entry types existed (the app never guesses their type): they count as eaten meals for variety, `LOVED_BEFORE` and
`SIMILAR_TO_LOVED`, but never as cooked-vs-ate-out evidence. `suggested` entries are never treated as eaten.
| `FOUND_NEARBY` | Verification status is `verified` |

No budget reason (no price data), no goal, nutrition, health or calorie reason. Maximum 5, strongest first.

## 7. Area pipeline (intent `eat_out` with coordinates)

Uses **Google Places API (New)** from the backend only (D-012). No Places key ever reaches the app.

1. **Validate** `lat`, `lng`, `radius_km` (0.5–10). Coordinates are never logged or stored.
2. **Cell lookup:** cell = geohash of the point (precision 6, about 1.2 × 0.6 km, for radius ≤ 5 km; precision 5,
   about 4.9 × 4.9 km, for 10 km) + radius bucket. On a cache miss, **one Nearby Search (New)** for the cell:
   centre = cell centre, radius = bucket radius + half the cell diagonal (so it covers any point in the cell),
   `includedTypes: ["restaurant"]`, `maxResultCount: 20`, `rankPreference: POPULARITY`, field mask
   **`places.id,places.location`** (Nearby Search has no tier below Pro). Store only place ids and lat/lng.
3. **Generate** suggestions (§6). The area label and city steer the model; Places content isn't put in the prompt
   (only ids and coordinates may be cached, and type data from a fresh call would make results inconsistent between
   cache hits and misses).
4. **Verify the hero:** one **Text Search (New)** with `textQuery` = dish name, `locationBias` circle (the user's
   point, the radius), `pageSize: 20`, field mask **`places.id`** only, which is the *Text Search Essentials
   (IDs Only)* SKU with unlimited free usage. Matches = returned ids ∩ cell candidates whose distance from the user's
   **actual** point ≤ `radius_km`. That is a **lower bound** (at most 20 candidates per cell).
5. **Rank:** if the hero has no match, verify the next suggestion; the first verified suggestion becomes the hero
   (eat_out only). Alternatives aren't verified by default (`PLACES_VERIFY_ALTERNATIVES=false`).
6. **Label:**
   - `verified` → "At N places within X km" with N = `place_count` (the copy should signal a minimum, e.g. "At 4+
     places", because the count is a lower bound), Maps handoff with up to 5 place ids, and
     **Google Maps attribution must be shown** by the app (`attribution.must_display`).
   - `popular_around` → "Popular around <area>", with `unverified_reason` (`no_coordinates`, `not_checked`,
     `no_match_found`, `user_limit_reached`, `global_cap_reached`, `provider_unavailable`, `places_disabled`).
   - `none` → intents `cook` and `anywhere`.
   - "Find nearby" is a Maps URL (`https://www.google.com/maps/search/?api=1&query=<dish> near <area>`); no key.
7. **Limits:** per uid 15 Nearby calls/day (cache misses attributed to them) and 60 Text Searches/day; global
   caps per UTC day stored in Firestore (`PLACES_NEARBY_GLOBAL_DAILY_CAP=150`, which keeps a 30-day month under
   the 5,000 free Nearby Search Pro calls; `PLACES_TEXT_GLOBAL_DAILY_CAP=3000`). Any limit, quota error, timeout
   (connect 2 s, read 4 s, stage budget 5 s) or provider failure → `popular_around` with the matching reason. The
   owner should also set a per-day request quota on the Places API in Google Cloud as a hard backstop; budget
   alerts alone don't stop spend.
8. **No coordinates (city only):** no Places call; `eat_out` suggestions are `popular_around` with
   `no_coordinates`; `area_context.mode = city`.

## 8. Caching and the Google terms it relies on

| Cache | Store | Content | TTL | Basis |
|---|---|---|---|---|
| Nearby cell candidates | Firestore `places_cells` | place ids + lat/lng | 7 days, hard-deleted by day 30 | Place ids may be stored indefinitely; lat/lng may be cached up to 30 consecutive calendar days, then must be deleted |
| Dish text-search matches | Firestore `places_text` | place ids only | 7 days | Place-id exemption (see note) |
| Recipes | Firestore `recipe_cache` | our generated recipe | 30 days, keyed with prompt version | Our own content |
| Entitlement | memory | boolean + expiry | 5 min / 60 s / stale ≤ 24 h | RevenueCat data, not persisted |
| Idempotent replays | memory | last response per (uid, key) | 10 min | Not persisted |
| Remote Config values | memory | 2 numbers/booleans | 5 min | n/a |

- **Nothing else from Places is stored:** no names, addresses, types, ratings or hours. Counts are computed per
  request.
- **Deletion is enforced, not just expiry:** a scheduled job deletes expired cache documents daily. A Firestore TTL
  policy can be added as well if the Firebase project is on the Blaze plan (TTL deletes have no free tier).
- **Attribution:** Places data shown without a Google Map needs the Google Maps logo (or the text "Google Maps"
  where space is limited). Places results shown on a map must be on a Google Map, so the app must not plot place
  results on a non-Google map.
- **Note (needs owner awareness):** storing a dish → place-id association is covered by the place-id exemption as
  we read it; it isn't spelled out. The 7-day TTL keeps exposure small.

Sources (accessed 2026-10-11): Places API policies, "You can therefore store place ID values indefinitely" and
the logo/map rules ([policies](https://developers.google.com/maps/documentation/places/web-service/policies),
last updated 2026-10-08); Google Maps Platform Service Specific Terms §14.3, "Customer may temporarily cache
latitude and longitude values from the Places API for up to 30 consecutive calendar days, after which Customer
must delete the cached latitude and longitude values" ([service terms](https://cloud.google.com/maps-platform/terms/maps-service-terms),
last modified June 10, 2026); Nearby Search and Text Search parameters and SKU field lists
([nearby](https://developers.google.com/maps/documentation/places/web-service/nearby-search),
[text](https://developers.google.com/maps/documentation/places/web-service/text-search), both last updated
2026-10-08); Maps URLs need no key ([Maps URLs](https://developers.google.com/maps/documentation/urls/get-started)).

## 9. Recipes

- `POST /api/v2/recipes` with `dish`, `origin {cuisine, region}`, `servings`, optional `diet` and
  `variant_location`. Generated on the backend only (D-013), never through the direct-AI path.
- **Content:** grouped ingredients (display quantity with local measure where natural, numeric quantity and unit for
  scaling, metric equivalent, substitutions marked), numbered steps with prep/cook/rest times and key quantities
  repeated, tips, the regional origin label, an optional clearly labelled local variant, and a fixed AI safety note.
- **Gate (D-014):** when `recipe_steps_premium` is true (Remote Config default value, env fallback
  `RECIPE_STEPS_PREMIUM=true`) and the caller isn't Premium: full ingredients, the first 2 steps
  (`RECIPE_FREE_PREVIEW_STEPS`), `locked: true`, `total_steps`, empty `tips`, variant without step changes.
  Premium gets everything. A locked recipe is a normal `200`; there is no `RECIPE_LOCKED` error.
- **Cache key:** normalised dish + cuisine + region + servings + diet + variant location + prompt version; TTL 30
  days. Servings scaling in the app works on the numeric quantities, so most users never trigger a new generation.
- **Deadline:** 40 s (AI read timeout 30 s). The app's read timeout for this call should be 45 s.
- **Diet conflict:** `422 DISH_CONFLICTS_WITH_DIET`.

## 10. Reports

- Stored in Firestore `suggestion_reports` through the Admin SDK with **exactly**: `uid`, `suggestion_name`,
  `reason` (`offensive`, `violates_my_diet`, `unsafe_or_wrong`, `other`), `created_at` (server time) and
  `app_version` (D-019). No free text, IP, location or device data.
- `Idempotency-Key` → the document id is derived from (uid, key), so a retry returns the original (`200`).
- Security rules must deny client reads and writes to this collection (the Admin SDK bypasses rules). The rules
  aren't in this repository; the owner should confirm them.
- Privacy policy and Data safety updates: TECH-013 (Android repo).

## 11. Account deletion: what is deleted and what is kept

| Data | Action | Why |
|---|---|---|
| RevenueCat customer | **Deleted** (`DELETE /v2/projects/{p}/customers/{uid}`; RevenueCat queues it asynchronously) | TECH-012; personal purchase history |
| `quota/{uid}` counter | **Deleted** | Personal usage data with no further purpose |
| In-memory entitlement, rate-limit and replay entries | **Evicted** | Same |
| `suggestion_reports` rows of the uid | **Kept without the uid** (field removed) | The rows are evidence for AI-content moderation and prompt fixes; without the uid they hold no personal data (a dish name, a reason, a time and an app version) |
| Places and recipe caches | Kept | Not linked to any user |
| `deletion_requests/{uid}` | Created at start, deleted when every step succeeds | Lets the server retry a failed RevenueCat call (every 15 min, up to 24 h, then logged as an alert) |
| Firebase Auth user, `users/{uid}` | **Not touched by the server** | The app keeps deleting them, as it does today |

Order in the app: warn about an active subscription (deletion doesn't cancel Google Play billing) → Google
re-auth → `DELETE /api/v2/me` → delete `users/{uid}` → delete the Auth user. A `202 pending_retry` still lets the
app continue.

## 12. Idempotency

| Endpoint | Mechanism |
|---|---|
| `POST /meal-suggestions` | `Idempotency-Key` (UUID per user action). The committed key is recorded on the quota document (last 10), so a retry **never consumes a second unit**. The response itself is replayed from memory for 10 minutes (`meta.idempotent_replay: true`); after a restart a retry generates a new set without counting it. |
| `POST /reports` | Key → deterministic document id; duplicates return the original. |
| `DELETE /me` | Naturally idempotent. |
| `POST /recipes`, `GET /me/quota` | Safe to retry (cached / read-only). |

## 13. Error codes

| HTTP | `code` | When | Client action |
|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | Unreadable JSON, wrong types | Bug; don't retry |
| 401 | `UNAUTHENTICATED` | Missing/invalid/expired token | Refresh token once, retry |
| 401 | `REAUTHENTICATION_REQUIRED` | `DELETE /me` with an old or revoked token | Re-authenticate, retry |
| 403 | `ACCOUNT_REQUIRED` | Anonymous account | Sign in |
| 404 / 405 / 415 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` | Wrong route or content type | Bug |
| 413 | `PAYLOAD_TOO_LARGE` | Body > 32 KiB | Trim history |
| 422 | `VALIDATION_FAILED` | Field rules; `errors[]` lists `field`, `code`, `message` | Fix input |
| 422 | `DISH_CONFLICTS_WITH_DIET` | Recipe can't satisfy the diet | Show message |
| 429 | `DAILY_LIMIT_REACHED` | Free allowance used; has `limit`, `used`, `reset_at`, `Retry-After` | Soft paywall / "Back tomorrow" |
| 429 | `RATE_LIMITED` | Burst limit; `Retry-After` | Wait and retry |
| 500 | `INTERNAL_ERROR` | Unexpected | Retry later; quote `request_id` |
| 502 | `GENERATION_FAILED` | Output failed parsing/schema/diet checks on every provider; no quota used | Retry with a new key |
| 503 | `PROVIDER_UNAVAILABLE` | All AI providers failed, or Firestore unavailable; `Retry-After`; no quota used | Retry later |

Reserved for later: `426 CLIENT_UPGRADE_REQUIRED` (minimum supported app version).

## 14. Versioning and `/api/v1` compatibility

- `/api/v1/meal-suggestions` keeps its path, request and response shape, snake_case, calorie prompt (D-015) and
  missing auth. v2 code lives in its own packages (controller, DTOs, services, exception handler) and doesn't
  modify v1 classes.
- The shared AI `RestClient` beans get connect/read timeouts in v2 work. That changes v1 only in that a hung
  provider call now fails (and falls back) instead of hanging. **Needs owner approval** with the other v1
  hardening items (see open questions in the ADRs and ENVIRONMENT.md).
- CORS: today `/**` allows `*`. v2 needs no CORS (mobile only). The mapping is narrowed to `/api/v1/**`, keeping
  `*` there so `index.html` (a browser test page that calls v1 on production) keeps working; v2 and Actuator get
  no CORS. Allowed origins become configurable (`CORS_ALLOWED_ORIGINS`, default `*` to keep v1 unchanged).
- v1 is public, unauthenticated and costs money per call. A per-IP rate limit on v1 (returns `429` only under
  abuse) is recommended and **needs owner approval**, because it changes v1 behaviour for abusive callers.
- Within v2: additive changes only. Removing or renaming a field, tightening validation or changing semantics needs
  `/api/v3`.
- v1 retirement: add `Deprecation` and `Sunset` headers only after analytics show v1 traffic is negligible, and
  only with owner approval. Turning v1 off is a separate approved step.

## 15. Server-side data in Firestore

All collections are written only by the Admin SDK and get a prefix from `FIRESTORE_COLLECTION_PREFIX` (empty in
production, `stg_` in staging) so staging and production never share counters if they share a Firebase project.

| Collection | Document | Fields |
|---|---|---|
| `quota` | `{uid}` | `day`, `timezone`, `timezone_set_at`, `used`, `reservations{key: expires_at}`, `committed_keys[≤10]`, `updated_at` |
| `places_cells` | `{geohash}_{radiusBucket}` | `places[{id, lat, lng}]`, `fetched_at`, `expire_at` |
| `places_text` | `sha256(dish, cell)` | `place_ids[]`, `fetched_at`, `expire_at` |
| `places_usage` | `{yyyy-MM-dd UTC}` | `nearby_calls`, `text_calls` |
| `recipe_cache` | `sha256(key)` | recipe JSON, `prompt_version`, `created_at`, `expire_at` |
| `suggestion_reports` | auto id or `sha256(uid, key)` | `uid`, `suggestion_name`, `reason`, `created_at`, `app_version` |
| `deletion_requests` | `{uid}` | `status`, `created_at`, `attempts`, `last_error_code` |

## 16. Production readiness built into the implementation plan

- Explicit timeouts on every outbound call: AI (3 s connect / 15 s read; recipes 30 s), Places (2 s / 4 s),
  RevenueCat (2 s / 3 s), Firestore (3 s per call, 5 s per transaction), plus per-request deadlines.
- Structured JSON logs (Spring Boot's built-in structured logging) with `request_id`, route, status, latency,
  provider and attempt count, quota outcome, cache hits. **No personal data:** no tokens, no request bodies, no
  coordinates or area labels, no dish lists. The uid is logged only as a keyed hash (`LOG_UID_HASH_KEY`).
- Actuator: `health` only (liveness/readiness groups), details hidden; other endpoints not exposed.
- Bean validation on every DTO, the 32 KiB body limit, rate limits, CORS narrowed, no stack traces in responses.
- Java 21 in `pom.xml` and Docker ([ADR-0004](../adr/0004-java-21-and-spring-boot-baseline.md)).
- Tests: unit (quota maths and timezone pinning, diet filter, reason builder, geohash/cell maths, gating), MockMvc
  (auth, validation, error bodies, v1 golden-contract test), WireMock fakes for Groq, Gemini, Places and RevenueCat
  (429/5xx/timeout/garbage paths), and the Firestore emulator for transactions (`FirestoreDocumentStoreEmulatorTest`, run with the
  Firebase CLI; passed 11/11 on 2026-10-12).
- A verified `docker build` before the staging deploy (Docker isn't installed locally; see ENVIRONMENT.md).

## 18. User data in our own database (D-024, 2026-10-16)

The profile, FCM token, meal history and favourites move from Firestore and the phone to our PostgreSQL. The
endpoints need `DATABASE_URL`; without it they answer `503 FEATURE_UNAVAILABLE` and nothing else changes.

- **Profile** (`/api/v2/me/profile`): the app's own JSON object, stored as-is (`jsonb`), top-level keys merged on
  `PUT`. On a user's first `GET` with no row, the legacy Firestore document `users/{uid}` is read once and copied
  (Firestore's `onboardingComplete` becomes the app's `isOnboardingComplete`; `fcmToken` moves to the device
  column). Firestore is never written, except that account deletion deletes `users/{uid}`.
- **Device** (`/api/v2/me/device`): the FCM token, previously written straight to Firestore.
- **History** (`/api/v2/me/history`): upload is batches of up to 100 entries plus favourite changes; the latest
  client `updated_at` wins per entry id and per favourite name. Download is changes since a cursor, oldest first,
  with tombstones for deletions; rows changed in the last 2 s are left for the next call so a cursor never skips
  a commit. Entries logged over 24 months ago aren't stored; a daily job deletes those that age out and the
  history of accounts that haven't synced for 24 months.
- **Isolation**: every query is keyed by the uid of the verified ID token; no endpoint accepts a uid.
- **Account deletion** (`DELETE /api/v2/me`) also deletes history, favourites, the profile row and the legacy
  Firestore profile (`steps.user_data`).
- Schema, migrations and the optional Postgres document store: [../TECHNICAL.md](../TECHNICAL.md) §9.

## 17. What the Android app needs to do (for the handoff)

- Send the Firebase ID token, `X-App-Version`, `Idempotency-Key` per user action, and the IANA timezone.
- Map stored profile strings to the enums: budget ranges → `budget_tier` by position; "15–30 min" → `up_to_30`
  and so on; proteins and cuisines as stored. Don't send age, gender, weight, height, goal or activity.
- Send `area.label` as a place/area name, never a saved-place nickname.
- Render reasons by `code`; hide unknown codes; show nothing when `reasons` is empty.
- Show "At N+ places within X km" only for `verified`, with the Google Maps attribution; otherwise "Popular around
  <area>". Never plot Places results on a non-Google map.
- Use `quota` from the server for display; handle `DAILY_LIMIT_REACHED` with `reset_at`.
- Send `X-Entitlement-Refresh: true` on the first call after a purchase or restore.
- Read timeouts: 30 s for suggestions, 45 s for recipes; retry only with the same `Idempotency-Key`.
- Base URL from `BuildConfig`: debug → staging, release → production.

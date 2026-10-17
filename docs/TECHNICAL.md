# Backend technical reference

The contract details (endpoints, fields, error codes, quota rules, caching terms) are in
[api/README.md](api/README.md) and [api/openapi-v2.yaml](api/openapi-v2.yaml). Configuration is in
[ops/ENVIRONMENT.md](ops/ENVIRONMENT.md). This document covers how the code is put together.

## 1. Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Actuator, validation), Jackson 3 (`tools.jackson.*`), snake_case JSON
  globally ([ADR-0004](adr/0004-java-21-and-spring-boot-baseline.md))
- Firebase Admin SDK: ID-token verification, Firestore, and reading the Remote Config template
- Outbound HTTP through `RestClient` with explicit timeouts. Providers: Groq and Gemini (OpenAI-compatible
  chat), Google Places API (New), and RevenueCat API v2
- Docker multi-stage build (`Dockerfile`), deployed on Render

## 2. Packages (`com.idraak.smartbite`)

| Package | Responsibility |
|---|---|
| `controller`, `service`, `dto`, `exception`, `config` | **v1** (frozen): `MealSuggestionController`, `AIModelService`, the v1 per-IP limit (`V1RateLimitFilter`), CORS on `/api/v1/**` |
| `common.ratelimit`, `common.web` | Fixed-window `RateLimiter` and `ClientIpResolver` (trusted proxy hops) |
| `v2.auth` | `FirebaseAuthFilter` verifies `Authorization: Bearer` tokens (`FirebaseIdTokenVerifier`; `FakeIdTokenVerifier` in tests) and resolves `AuthenticatedUser` |
| `v2.web` | Request id, access log with a hashed uid (`UidHasher`), 32 KiB body cap, per-uid limits (`V2RateLimits`) |
| `v2.error` | `ErrorCode`, `ApiException`, RFC 9457 problem bodies (`V2ExceptionHandler`) |
| `v2.config` | `V2Properties` (all `smartbite.v2.*` settings), `FirebaseConfig`, `StartupGuard` (refuses unsafe settings outside tests) |
| `v2.store` | `DocumentStore` port with `FirestoreDocumentStore` and `InMemoryDocumentStore` ([ADR-0001](adr/0001-firestore-for-quota-counters-and-caches.md)) |
| `v2.quota` | Free daily allowance per user's local day: reservation, commit and release inside store transactions |
| `v2.entitlement` | Premium from RevenueCat with a short cache; `X-Entitlement-Refresh` bypasses the cache; customer deletion |
| `v2.settings` | `RemoteConfigAppSettings` reads `free_daily_suggestions` and `recipe_steps_premium` every 5 minutes, with env fallbacks |
| `v2.ai` | `ProviderChain` over `OpenAiCompatibleChatModel`s with a whole-request `Deadline` |
| `v2.suggestion` | Prompt, parser, `DietFilter`, `ReasonBuilder` (evidence-based reason codes), `SuggestionService` |
| `v2.area` | Places grounding: cells by geohash, `PlacesCache` (≤ 30 days), `PlacesBudget` (per-uid and global daily caps), verified vs popular labels, Google Maps attribution |
| `v2.recipe` | Recipe prompt, parser, cache, and the free-user locked preview |
| `v2.report`, `v2.account` | Reports (idempotent) and `DELETE /api/v2/me` with a retried RevenueCat deletion |
| `v2.db` | Our PostgreSQL (D-024): `DatabaseConfig` (only with `DATABASE_URL`), `JdbcSettings` (accepts Render's connection string), Flyway migrations at startup |
| `v2.history` | `/api/v2/me/history`: sync of meal history and favourites, 24-month retention, daily cleanup |
| `v2.profile` | `/api/v2/me/profile` and `/device`; one-time import of the legacy Firestore profile (`LegacyProfileSource`) |

## 3. Request flow: `POST /api/v2/meal-suggestions`

1. Filters: request id → body-size cap → Firebase auth → per-uid rate limit → access log.
2. `SuggestionService` checks entitlement. For free users it reserves a quota slot under the `Idempotency-Key`.
   A replayed key returns the stored outcome without spending again.
3. `SuggestionGenerator` builds the taste-first prompt and calls the provider chain within the deadline.
   `SuggestionParser` and `DietFilter` drop anything that breaks the user's diet.
4. For `eat_out` with coordinates, `PlacesAreaGrounding` verifies the hero pick (and the alternatives when
   `PLACES_VERIFY_ALTERNATIVES`): nearby places come from the cell cache or one Nearby call, and Text Search IDs
   are matched within the radius. Verified picks carry `place_count`, `radius_km` and
   `attribution {text: "Google Maps", must_display: true}`. Every failure path degrades to "popular around".
5. `ReasonBuilder` attaches reason codes backed by the profile. The quota is committed on success and released
   on failure.

## 4. Storage

All server data goes through `DocumentStore`: Firestore by default, or our PostgreSQL with
`SMARTBITE_STORES_MODE=postgres` (`JdbcDocumentStore`, table `kv_document`; §9). The collection prefix comes from
`FIRESTORE_COLLECTION_PREFIX`. User data (profile, history, favourites) is always in PostgreSQL (§9). The collections are listed in [api/README.md §15](api/README.md#15-server-side-data-in-firestore).
Counters and quota use store transactions. TTL'd documents (`expire_at`) are swept by `PlacesCacheCleanup` (Places caches) and a scheduled job in
`RecipeConfig` (recipe cache). A
known hot-spot is the global per-day `places_usage` document ([BE-BUG-001](BUGS.md#be-bug-001)).

## 5. Configuration

Every variable, with its default and whether it's secret: [ops/ENVIRONMENT.md §2](ops/ENVIRONMENT.md).
Profiles: `staging` and `prod` (structured logs, Firestore prefix, no fake tokens), and `test`
(`src/test/resources/application-test.yaml`: in-memory stores, fake billing, fake Places, fake tokens).
There's no hard-coded active profile; deployed environments must set `SPRING_PROFILES_ACTIVE`.

## 6. Contract with the Android app

The two repositories share golden fixtures, so a change on either side fails a test:

| Direction | Files | Test |
|---|---|---|
| App → server | `src/test/resources/contract/android/*.json`: request bodies generated by the app's real `SmartBiteV2Repository` | `v2.ContractFixturesTest` posts each one to the real endpoint and expects 2xx |
| Server → app | `src/test/resources/contract/server/*.json`: responses recorded by `ContractFixturesTest` | Android `V2ContractTest` parses them into the domain model |

After a deliberate contract change:
1. Re-record with `-Dcontract.write=true` (or delete the server files).
2. Copy `contract/server/` to the Android repo's `app/src/test/resources/contract/server/`.
3. Copy any regenerated Android request files back here.

Without re-recording, `ContractFixturesTest` compares the JSON *shape* (field paths and types), so changed
values such as dish names don't fail it.

## 7. Tests

| Kind | Where | Notes |
|---|---|---|
| Unit | quota maths and timezone pinning, diet filter, reasons, geohash/cells, parsers, rate limiter | |
| MockMvc | every v2 endpoint: auth, validation, problem bodies, idempotency | `Bearer test:<uid>`; idempotency keys must be UUIDs |
| v1 golden contract | `src/test/resources/golden/` | v1 output must not change |
| Provider fakes | WireMock for Groq/Gemini, Google Places and RevenueCat (429, 5xx, timeout, garbage); `FakePlacesClient` and `FakeBilling` for endpoint tests | |
| App contract | `ContractFixturesTest` (§6) | |
| Store contract | `DocumentStoreContract`: `InMemoryDocumentStoreTest` always runs; `FirestoreDocumentStoreEmulatorTest` runs only when `FIRESTORE_EMULATOR_HOST` is set | Emulator command in the README. The test uses a plaintext gRPC channel ([BE-BUG-002](BUGS.md#be-bug-002)) |

`./mvnw -B verify` on 2026-10-16, after D-024: 207 tests, 0 failures, 0 errors, 11 skipped (the emulator tests;
see §9 for the PostgreSQL suites). Before D-024: 162. The emulator
run on the same day: 11/11 passed. `DeployedConfigTest` binds the real `application*.yaml` files to check the
deployed profiles. A local smoke test of the packaged jar is described in [ops/DEPLOYMENT.md](ops/DEPLOYMENT.md) §9.1.

## 9. Our own database (D-024)

**When:** only when `DATABASE_URL` is set: Render's connection string as-is
(`postgresql://user:password@host:port/db`), or a JDBC URL with `DATABASE_USERNAME`/`DATABASE_PASSWORD`. Without
it the service runs as before, and the user-data endpoints answer `503 FEATURE_UNAVAILABLE`.

**How it's wired:**
- Plain `spring-jdbc`, HikariCP (`DATABASE_MAX_POOL_SIZE`, default 5; `statement_timeout` 5 s per connection)
  and Flyway.
- Spring Boot's JDBC/Flyway auto-configuration modules aren't on the classpath, so nothing database-related
  starts without `DATABASE_URL`.
- `DatabaseConfig` runs the migrations at startup, before traffic. A database that can't be reached, or a
  failed migration, fails startup, and Render keeps the previous deploy.

**Schema** (`src/main/resources/db/migration`):

| Migration | Tables | Notes |
|---|---|---|
| `V1__user_data.sql` | `user_profile` (uid, profile jsonb, fcm_token, source app or firestore_import), `meal_history` (uid+id key, fields, logged_at, client_updated_at, deleted tombstone, server_updated_at), `favorite_dish` (uid+name), `user_activity` (uid, last_seen_at) | Indexes: `(uid, server_updated_at, id)` for sync, `logged_at` for retention, `last_seen_at` for inactivity |
| `V2__documents.sql` | `kv_document` (collection+id key, data jsonb) | Only used with `SMARTBITE_STORES_MODE=postgres` |

**Rules:**
- Migrations are append-only (never edit an applied one; add `V3__…`).
- Every user-data query is keyed by the verified uid.
- Upserts compare `client_updated_at`, so the latest client change wins.
- `server_updated_at` (`clock_timestamp()`) orders the sync cursor.

**Retention:** `HistoryService.cleanup()` runs 5 minutes after startup and then every 24 h. It deletes entries
logged over 24 months ago and the history of accounts whose `user_activity.last_seen_at` is over 24 months old.
Profiles are not removed for inactivity.

**Firestore migration:**
- **Profiles:** copied on demand, at each user's first profile read. Firestore stays the read-only source until
  then, and isn't deleted (except on account deletion).
- **History:** never in Firestore. It is uploaded by each phone on its first sync.
- **The server's own documents** (quota, caches, reports): move by switching `SMARTBITE_STORES_MODE` to
  `postgres`. In production, v2 has never written to Firestore, so nothing needs copying. Staging data
  (`stg_`) is disposable.

**Postgres document store:** `JdbcDocumentStore` passes the same `DocumentStoreContract` as Firestore and the
in-memory store. Instants are stored as `{"$ts": …}`, transactions take a per-document advisory lock, and
`increment` is a single upsert. That removes the transaction hot-spot of BE-BUG-001.

**Tests** use a real PostgreSQL (zonky embedded-postgres 14.x; no Docker): `TestDatabase`. The suites are
`JdbcDocumentStorePostgresTest`, `HistoryEndpointTest`, `ProfileEndpointTest`, `PostgresModeEndpointTest`,
`HistoryWithoutDatabaseTest`, `JdbcSettingsTest` and `UserDataContractFixturesTest`. Render runs PostgreSQL 18;
the SQL uses only features available in both.

## 8. Build and deploy

The deployment guide, costs, environment strategy and checklist are in [ops/DEPLOYMENT.md](ops/DEPLOYMENT.md).
Startup safety: `StartupGuard` (no in-memory stores or fake tokens outside tests) and `DeployedConfigGuard`
(a deployed profile is required on Render, and every required secret must be set).
Shutdown is graceful (`server.shutdown: graceful`, 45 s per phase).

- `./mvnw -B verify` builds `target/smartbite-0.0.1-SNAPSHOT.jar`.
- `Dockerfile`: a Maven build stage (dependencies in their own layer), then a JRE 21 runtime as a non-root user
  with `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. Render builds it on every staging deploy
  ([BE-014](ROADMAP.md#be-014)). It isn't built locally, because Docker needs WSL2, which is off on the
  development machine.
- Deploy plan, rollback and the owner's inputs: [ops/ENVIRONMENT.md §3–4](ops/ENVIRONMENT.md). Nothing has
  been deployed from `branch-01/v2-api`.

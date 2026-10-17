# Backend known issues

Never delete an entry; keep the history. Statuses: `Open`, `In Progress`, `Fixed – unverified`, `Done`,
`Won't fix`.

## <a id="be-bug-001"></a>BE-BUG-001: the global Places usage counter is one contended document

- **Status:** Open (P3, accepted risk at current traffic)
- **Evidence:** `PlacesBudget.acquire` calls `DocumentStore.increment` on `places_usage/{UTC day}` for every
  Nearby and Text Search call. The Firestore implementation runs that increment in a transaction. The
  emulator contract test (2026-10-12) timed out with 20 concurrent increments on one document but passes with 3.
  Production Firestore handles contention better than the emulator; how much better wasn't measured.
- **Impact:** under a burst of simultaneous eat-out requests, some increments can time out. The failure is
  graceful: `PlacesAreaGrounding` catches `StoreUnavailableException` and labels the pick "Popular around"
  (`PROVIDER_UNAVAILABLE`) instead of failing the request. The global caps (150 Nearby, 3,000 Text per day)
  keep the volume low.
- **Fix (when needed):** [BE-017](ROADMAP.md#be-017). Use `FieldValue.increment` (no transaction) and read
  the value afterwards, or shard the counter. With `SMARTBITE_STORES_MODE=postgres` (BE-023) the counter is a
  single upsert, so the issue doesn't apply there.
- **Acceptance criteria:** no store timeouts at the target concurrent eat-out rate on staging.

## <a id="be-bug-003"></a>BE-BUG-003: account deletion on staging would delete real RevenueCat customers

- **Status:** Done
- **Evidence:** staging shares the RevenueCat project with production. `DELETE /api/v2/me` always called the
  RevenueCat customer deletion, so testing account deletion on staging with a real account would delete that
  customer.
- **Resolution:** 2026-10-16. `smartbite.v2.deletion.billing-deletion-enabled` (`BILLING_DELETION_ENABLED`,
  default `true`) is `false` in the `staging` profile; the step then reports `revenuecat_customer: skipped`
  (additive enum value in openapi-v2.yaml).
- **Verification:** `AccountDeletionWithoutBillingTest` (customer not deleted, `skipped`); `DeployedConfigTest`
  (staging binds `false`, prod `true`).

## <a id="be-bug-004"></a>BE-BUG-004: a misconfigured deployed instance started anyway

- **Status:** Done
- **Evidence:** without `SPRING_PROFILES_ACTIVE`, or with `LOG_UID_HASH_KEY` / RevenueCat / Firebase settings
  missing, the service started and failed later (a random log-hash key, entitlement errors at runtime).
- **Resolution:** 2026-10-16. `DeployedConfigGuard`: on Render (`RENDER=true`) a `prod` or `staging` profile is
  required; with those profiles every required secret must be set (names reported, never values). Firebase
  initialisation runs after it.
- **Verification:** `DeployedConfigTest` (7 tests); local jar runs refused to start with the expected messages
  (DEPLOYMENT.md §9.1).

## <a id="be-bug-005"></a>BE-BUG-005: the Docker image fixed the heap at 400 MB and ran as root

- **Status:** Fixed – partly verified. Render built and runs the image (staging, 2026-10-16); the memory check
  (DEPLOYMENT.md §9.2 step 8) is outstanding
- **Evidence:** `ENTRYPOINT java -Xmx400m -Xms200m`, which wastes most of a 2 GB instance; no `USER`.
- **Resolution:** 2026-10-16. `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`, a non-root user, and a
  dependency layer (`dependency:go-offline`). Graceful shutdown set explicitly (45 s).
- **Verification:** the jar ran with the same flags locally; `dependency:go-offline` succeeds. The image build is
  first verified by the staging round.

## <a id="be-bug-006"></a>BE-BUG-006: client IP behind Render's proxy is an unverified assumption

- **Status:** Open (P0 before production; task BE-019)
- **Evidence:** `ClientIpResolver` takes the `X-Forwarded-For` entry `TRUSTED_PROXIES` (1) positions from the
  right. Render's behaviour is documented only in community threads. If the hop count is wrong, every caller
  shares one IP, so the v1 limit (60/min) and the pre-auth v2 limit apply to all users together.
- **Fix:** none needed if the assumption holds; otherwise adjust `TRUSTED_PROXIES`.
- **Acceptance criteria:** DEPLOYMENT.md §9.2 step 5 passes on staging.
- **Progress (2026-10-16, staging):** one machine is counted as one client, and a spoofed header doesn't bypass
  the limit. Still to check: a second network is counted separately (step 5b).

## <a id="be-bug-007"></a>BE-BUG-007: v1 errors include provider text, and v1 falls back only on HTTP 429

- **Status:** Fixed – unverified (2026-10-17, `branch-03/pre-production`; needs a staging run). Built after the
  owner asked for every item to be completed before production (task BE-020).
- **Evidence:** the local smoke test (2026-10-16) with an invalid Groq key returned
  `502 {"message":"AI service error from groq-gpt-oss-120b: 401 Unauthorized: …Invalid API Key…"}`. The raw
  provider error reaches the client, and v1 didn't try Gemini because it falls back only on 429.
- **Impact:** an invalid or revoked Groq key takes v1 down even when Gemini works; provider details leak (no
  secrets).
- **Mitigation now:** DEPLOYMENT.md §9.2 step 7 checks the Groq key with a real v1 request. Passed on staging on
  2026-10-16 (200, 3 suggestions).
- **Also seen on staging (2026-10-16):** a malformed v1 body, such as a bare number instead of an object, returns
  `500 {"error":"Internal server error","message":"JSON parse error: …"}` instead of 400. The parser message
  also reaches the client. Same fix scope (BE-020).
- **Resolution:** 2026-10-17.
  - `AIModelService` moves on to the next provider after any provider HTTP error (not only 429) and after an
    answer that isn't valid JSON. Timeouts still end the request, because each one costs the full read timeout.
  - `GlobalExceptionHandler` returns fixed text: provider and parser messages are logged, never returned. A body
    that isn't valid JSON is `400 {"error":"Invalid request body"}` instead of 500.
  - Status codes and the `error` / `message` keys are unchanged. The app reads only the status code
    (`MealSuggestionRepositoryImpl` maps it to `MealSuggestionError.Server`), and it never sends a malformed body.
- **Verification:** `V1GoldenContractTest`: a 401 followed by an unusable answer still returns the golden
  response; error bodies never include provider text; a bare number is 400. The existing golden and hardening tests
  pass. **Outstanding:** a staging run.

## <a id="be-bug-008"></a>BE-BUG-008: every recipe was rejected on staging (the parser was too strict)

- **Status:** Open: the parser fix is deployed, but recipes still fail on staging (2026-10-17)
- **Evidence:** staging, 2026-10-16. `POST /api/v2/recipes` for "Mutton Biryani" returned
  `502 GENERATION_FAILED` twice. The log said
  `ai output unusable provider=groq-gpt-oss-20b reason=recipe missing ingredients, steps, difficulty or total time`.
  The models answered with valid JSON that didn't match the schema exactly, and the reason didn't say which part
  was missing.
- **Resolution:** `RecipeParser` now accepts:
  - minutes as whole-number decimals or numeric strings
  - a missing total worked out from prep + cook + rest, or a top-level `total_minutes`
  - difficulty synonyms; a missing difficulty defaults to medium
  - steps as plain strings
  - a flat `ingredients` list, and `name` for `item`

  The diet check still applies to every ingredient. The failure reason now names what is missing. The start of
  the raw output is logged at DEBUG (set `LOGGING_LEVEL_COM_IDRAAK_SMARTBITE_V2_RECIPE=DEBUG` on staging to see
  it).
- **Verification:** `RecipeParserTest` (5); recipe endpoint, gate and contract tests; `./mvnw -B verify` 167 pass.
  **Outstanding:** a recipe loads on staging.
- **Staging re-check (2026-10-17, `ff17a40`, with the fix):** not fixed yet. From the Pixel 6a, 2 of 3 recipe
  requests failed with `502 GENERATION_FAILED` after 14–15 s (well inside the 40 s deadline, so providers answered
  but the output was rejected); a retry of "Beef Biryani" returned 200 in 12.7 s. Request IDs `e10d6da2…`,
  `220d3272…`, `85cef68a…`. The log for `85cef68a` (10:28:32Z) says
  `ai output unusable provider=groq-gpt-oss-20b reason=recipe missing steps`: valid JSON with ingredients and a
  time, but no steps where the parser looks (`steps[].instruction`).
- **Second fix (2026-10-17, `branch-03/pre-production`):** steps are also read from `instructions`, `method`,
  `directions` or `preparation` (an array, or an object keyed by number), and a step's text from `text`,
  `description`, `step`, `details`, `content` or `action`. When something is still missing, the WARN reason lists
  the field names the model used (never the recipe text), so the next unknown shape shows up in the log without
  DEBUG. Tests: `RecipeParserTest` (7). **Outstanding:** the merge into `staging`, then a few recipes loading on
  the first try.

## <a id="be-bug-009"></a>BE-BUG-009: "Anywhere · Turkish" returned mostly Pakistani dishes

- **Status:** Fixed – unverified (2026-10-17, `branch-03/pre-production`, task BE-024; needs a staging check)
- **Evidence:** staging, 2026-10-16, debug app, Anywhere with Turkish selected. The top pick was
  "Turkish Chicken Döner Wrap", but the alternatives were "Mutton Haleem" and "Chicken Handi" (Pakistani).
- **Likely cause:** the prompt treats the chosen cuisine as a preference, not a requirement, for alternatives.
  The taste profile (Pakistani) wins.
- **Fix:** make the chosen cuisines a hard constraint for every pick in the `anywhere` intent, and drop
  non-matching picks in the parser (as the diet filter does).
- **Acceptance criteria:** with one cuisine chosen, all three picks have that cuisine (unit test plus a staging
  check).
- **Resolution:** 2026-10-17.
  - For `intent: anywhere`, the cuisines in `refine.cuisine` (the app sends them comma-separated) go into the
    prompt as `required_cuisines`, with a rule that every dish must belong to one of them.
  - `SuggestionGenerator` drops picks whose `origin.cuisine` or region doesn't match (reason `cuisine`) and
    regenerates once, as it does for the diet filter.
  - Only if no pick matches at all are the off-cuisine (but otherwise safe) picks returned, with
    `meta.degraded = true`, instead of failing the set.
  - Other intents are unchanged. Prompt version `suggest-v2.2`.
- **Verification:** `SuggestionEndpointTest` (new: off-cuisine picks are replaced; the fallback is marked
  degraded; Cook at home doesn't require the cuisine). **Outstanding:** a staging check.

## <a id="be-bug-010"></a>BE-BUG-010: the deployed profiles ignored `SMARTBITE_STORES_MODE`

- **Status:** Done (2026-10-16, `branch-02/user-history`)
- **Evidence:** `application-prod.yaml` and `application-staging.yaml` hard-coded `stores.mode: firestore`, so
  the documented variable had no effect on Render. Found by a new `DeployedConfigTest` case.
- **Resolution:** both profiles read `${SMARTBITE_STORES_MODE:firestore}`; the default is unchanged.
- **Verification:** `DeployedConfigTest.postgresModeNeedsADatabaseUrl`.

## <a id="be-bug-011"></a>BE-BUG-011: `RevenueCatClientTest` can time out on a busy machine

- **Status:** Fixed – unverified (2026-10-17, `branch-03/pre-production`; the flake was seen once, so only more
  full runs under load can confirm it)
- **Evidence:** 2026-10-16. One full `./mvnw verify` run, while an Android build used the same CPU, failed
  `notPremiumForOtherEntitlementsOrUnknownCustomer` with "Error while extracting response" after about 2 s. The
  class passed on its own and in the next full run.
- **Likely cause:** the client's short read timeout against WireMock under heavy load.
- **Fix:** a longer read timeout in this test's configuration.
- **Resolution:** 2026-10-17. The test's client uses a 10 s read timeout; the timeout case uses its own client with
  500 ms against a 3 s delay.
- **Verification:** `RevenueCatClientTest` 5/5, and a full `./mvnw -B verify` (213 tests, 0 failures).

## <a id="be-bug-002"></a>BE-BUG-002: the Firestore emulator test connected over TLS

- **Status:** Done (test-only; production code unaffected)
- **Evidence:** `FirestoreOptions.setEmulatorHost` with this client version negotiated TLS. The emulator speaks
  plaintext HTTP/2, so every call failed with `Http2Exception: First received frame was not SETTINGS`
  (`15 03 01`, a TLS alert). A raw HTTP/2 preface sent to the emulator got a valid SETTINGS frame back.
- **Resolution:** `7ac27d7`. `FirestoreDocumentStoreEmulatorTest` builds an explicit plaintext gRPC channel,
  and `@AfterAll` uses `shutdownNow()` because `close()` blocked on channel termination.
- **Verification:** 11/11 emulator tests passed on 2026-10-12 (Firebase CLI 15.33.0, emulator on 127.0.0.1:8085).

# Backend roadmap

Task IDs are stable (`BE-xxx`); never renumber them. Product decisions (`D-0xx`) and the app's own roadmap live
in the Android repository's `docs/`.

## Current position (2026-10-16)

| | |
|---|---|
| Branch | `branch-01/v2-api` (not merged to `master`; not pushed or deployed) |
| Phase | Deployment prepared ([BE-018](#be-018)); the owner deploys (Render Pro workspace bought 2026-10-16) |
| Next task | [BE-010](#be-010) staging round on Render, following [DEPLOYMENT.md](ops/DEPLOYMENT.md) §9.2 (owner) |
| Last verified | 2026-10-16: `./mvnw -B verify` 162 tests, 0 failures, 11 skipped (emulator tests, run separately: 11/11 pass); local jar smoke test (DEPLOYMENT.md §9.1) |

## Phase 2: v2 API (Revision 2)

| ID | Task | Priority | Status | Depends on | Acceptance criteria |
|---|---|---|---|---|---|
| <a id="be-001"></a>BE-001 | v1 golden-contract test before any upgrade | P0 | Done (`fedc7cc`) | | v1 request/response shape pinned by a test |
| <a id="be-002"></a>BE-002 | Spring Boot 4.1.1 and Java 21 (ADR-0004) | P0 | Done (`7408e61`) | BE-001 | Build and v1 golden test green |
| <a id="be-003"></a>BE-003 | v1 hardening: AI timeouts, per-IP rate limit, CORS on `/api/v1/**` (D-021 item 4) | P1 | Done (`541cd55`) | BE-002 | v1 output unchanged; tests for limits and timeouts |
| <a id="be-004"></a>BE-004 | v2 foundations: Firebase ID-token auth, problem+json, config, private logging, health | P0 | Done (`56a53e4`) | BE-002 | 401/403 paths tested; no personal data in logs |
| <a id="be-005"></a>BE-005 | Quota, RevenueCat entitlement, `GET /api/v2/me/quota` | P0 | Done (`1b75444`) | BE-004 | Atomic per user's day; Premium unlimited; tests |
| <a id="be-006"></a>BE-006 | `POST /api/v2/meal-suggestions` | P0 | Done (`a20d4d0`) | BE-005 | Taste-first prompt, provider chain, diet filter, reasons; tests |
| <a id="be-007"></a>BE-007 | Places (New) area pipeline, caches, spend caps (ADR-0002) | P0 | Done (`65f01d2`) | BE-006 | Verified/popular labelling; caps; 30-day cache limit; tests |
| <a id="be-008"></a>BE-008 | Recipes, reports, `DELETE /api/v2/me` | P0 | Done (`6b25c42`, `3da6960`, `9736b1f`) | BE-005 | Premium step gate; idempotent reports; deletion retry; tests |
| <a id="be-009"></a>BE-009 | Contract fixtures shared with the Android app, and Places attribution in the response | P0 | Done (`a818861`) | BE-006..008 | App-generated requests accepted by the real endpoints; recorded responses parse in the app (Android `V2ContractTest`) |
| <a id="be-011"></a>BE-011 | `DocumentStore` contract tests, in memory and on the Firestore emulator | P1 | Done (`7ac27d7`) | | 11 behaviours pass on both stores (emulator run 2026-10-12) |
| <a id="be-010"></a>BE-010 | Staging round on Render ([DEPLOYMENT.md §9.2](ops/DEPLOYMENT.md)) | P0 | Ready for the owner: Blueprint `deploy/render-staging.yaml`; needs secrets | BE-018 | Health 200; v2 401 without a token; quota with a debug ID token; v1 unchanged |
| <a id="be-012"></a>BE-012 | Real-provider smoke test on staging: Groq, Gemini, Places, RevenueCat, Remote Config template | P0 | Not started | BE-010 | Each provider called once successfully; logs clean |
| <a id="be-013"></a>BE-013 | End-to-end run with the Android debug build against staging (`SMARTBITE_STAGING_API_URL`) | P0 | Not started | BE-010 | Picks, recipe, report, quota and deletion work from the app |
| <a id="be-014"></a>BE-014 | Docker image build verified | P1 | Blocked locally (WSL2 off). The staging round's Render build is the first real build; `dependency:go-offline` verified locally | | `docker build` succeeds |
| <a id="be-015"></a>BE-015 | Production deploy and merge to `master` | P0 | Not started; plan in [DEPLOYMENT.md §10](ops/DEPLOYMENT.md) (in-place upgrade of the existing service) | BE-010, BE-012, BE-013 | Owner approval; auto-deploy off; rollback plan |
| <a id="be-016"></a>BE-016 | Firebase App Check (ADR-0003) | P2 | Not started | BE-015 | Enforced after the app sends tokens |
| <a id="be-018"></a>BE-018 | Deployment preparation for Render: config guard, staging safety, Docker sizing, graceful shutdown, guide | P0 | Done (2026-10-16) | | Misconfigured deploys refuse to start; staging can't delete RevenueCat customers; tests and smoke test pass; guide and checklist written |
| <a id="be-019"></a>BE-019 | Verify the client IP behind Render's proxy ([BE-BUG-006](BUGS.md#be-bug-006)) | P0 | Not started (on staging) | BE-010 | DEPLOYMENT.md §9.2 step 5 passes |
| <a id="be-020"></a>BE-020 | v1 error body and fallback ([BE-BUG-007](BUGS.md#be-bug-007)) | P2 | Proposed (v1 is frozen; needs owner approval) | | v1 errors no longer include provider text; non-429 provider errors fall back |
| <a id="be-021"></a>BE-021 | Cloud meal history API (`/api/v2/me/history`) on Render Postgres, 24-month retention (Android D-024 / PROD-020) | P2 | Planned | BE-015 | Sync endpoints with idempotent upserts; daily retention job; deletion on `DELETE /api/v2/me`; Free DB for development, $6 plan before launch, same region as the service |
| <a id="be-017"></a>BE-017 | Shard or batch the global `places_usage` counter if Places traffic grows ([BE-BUG-001](BUGS.md#be-bug-001)) | P3 | Not started | | No transaction contention at the target request rate |

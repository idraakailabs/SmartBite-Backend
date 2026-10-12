# Backend roadmap

Task IDs are stable (`BE-xxx`); never renumber them. Product decisions (`D-0xx`) and the app's own roadmap live
in the Android repository's `docs/`.

## Current position (2026-10-12)

| | |
|---|---|
| Branch | `branch-01/v2-api` (not merged to `master`; not pushed or deployed) |
| Phase | v2 API implemented and tested locally; waiting for the owner's secrets and the staging deploy |
| Next task | [BE-010](#be-010) staging deploy on Render (needs the owner's approval and secrets) |
| Last verified | `./mvnw -B verify`: 154 tests, 0 failures, 11 skipped (the emulator tests, which run separately: 11/11 pass) |

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
| <a id="be-010"></a>BE-010 | Staging deploy on Render ([ENVIRONMENT.md §4](ops/ENVIRONMENT.md)) | P0 | Blocked: needs the owner's approval, Render access and secrets | BE-009 | Health 200; v2 401 without a token; quota with a debug ID token; v1 unchanged |
| <a id="be-012"></a>BE-012 | Real-provider smoke test on staging: Groq, Gemini, Places, RevenueCat, Remote Config template | P0 | Not started | BE-010 | Each provider called once successfully; logs clean |
| <a id="be-013"></a>BE-013 | End-to-end run with the Android debug build against staging (`SMARTBITE_STAGING_API_URL`) | P0 | Not started | BE-010 | Picks, recipe, report, quota and deletion work from the app |
| <a id="be-014"></a>BE-014 | Docker image build verified | P1 | Blocked: Docker unavailable on the dev machine (WSL2 off); Render's build is the fallback | | `docker build` succeeds |
| <a id="be-015"></a>BE-015 | Production deploy and merge to `master` | P0 | Not started | BE-012, BE-013 | Owner approval; auto-deploy off; rollback plan |
| <a id="be-016"></a>BE-016 | Firebase App Check (ADR-0003) | P2 | Not started | BE-015 | Enforced after the app sends tokens |
| <a id="be-017"></a>BE-017 | Shard or batch the global `places_usage` counter if Places traffic grows ([BE-BUG-001](BUGS.md#be-bug-001)) | P3 | Not started | | No transaction contention at the target request rate |

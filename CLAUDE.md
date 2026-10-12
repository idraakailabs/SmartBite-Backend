# CLAUDE.md: SmartBite Backend

The Spring Boot service behind the SmartBite Android app (`com.idraak.ai.smartbite`). It serves:
- the legacy, unauthenticated `POST /api/v1/meal-suggestions`, used by every released app version
- the authenticated v2 API (`/api/v2/**`): suggestions, recipes, reports, quota and account deletion, used by
  the Revision 2 app when Remote Config `api_v2_enabled` is on

Production runs on Render at `smartbite-backend-zsyr.onrender.com`.

## Project documentation: read before changing anything

| Document | Use it for |
|---|---|
| [docs/ROADMAP.md](docs/ROADMAP.md) | **Start here.** Current position, every task ID (`BE-xxx`) with status, dependencies and acceptance criteria |
| [docs/BUGS.md](docs/BUGS.md) | Known defects and risks (`BE-BUG-xxx`): evidence, impact, fix, verification history |
| [docs/TECHNICAL.md](docs/TECHNICAL.md) | Architecture, packages, data flow, storage, configuration, tests, build and run |
| [docs/api/README.md](docs/api/README.md) and [openapi-v2.yaml](docs/api/openapi-v2.yaml) | The v2 API contract (agreed with the owner, D-021) |
| [docs/adr/](docs/adr/) | Architecture decision records |
| [docs/ops/ENVIRONMENT.md](docs/ops/ENVIRONMENT.md) | Every environment variable, the staging deploy plan, what the owner provides |
| [docs/ops/COSTS.md](docs/ops/COSTS.md) | Provider cost model |

Product decisions (`D-0xx`), the product roadmap and the Android side of the contract live in the Android
repository (`D:\Work\Idraak\Android\SmartBite`, `docs/`).

## Working rules

1. **Follow the roadmap.** Work on tasks in priority order and respect "Depends on". Work not on the roadmap
   gets a `BE-xxx` entry (next free ID) before or alongside the change.
2. **v1 is frozen.** `/api/v1/meal-suggestions` keeps its path, request and response shape, snake_case and
   prompt. Released app versions depend on it. v2 code lives in its own packages (`com.idraak.smartbite.v2`).
3. **v2 changes are additive.** Removing or renaming a field, tightening validation or changing semantics needs
   `/api/v3`. A contract change must update `openapi-v2.yaml`, `docs/api/README.md`, and the contract
   fixtures in both repositories (see TECHNICAL §6).
4. **No secrets in Git**, images, logs or test fixtures. Every secret is an environment variable
   ([ENVIRONMENT.md §2](docs/ops/ENVIRONMENT.md)). Logs never contain tokens, request bodies, coordinates or
   raw uids.
5. **Billing is a contract.** `REVENUECAT_ENTITLEMENT_LOOKUP_KEY` defaults to `smartbite_pro`, which must
   match the RevenueCat dashboard and the app's `PREMIUM_ENTITLEMENT`. Never change it on its own.
6. **No remote changes without approval.** Render services, env vars, deploys, Firebase, RevenueCat and Google
   Cloud are changed only with the owner's explicit approval at the time.
7. **Match the existing patterns.** Constructor injection, records for DTOs, `ProblemDetail` errors with a
   stable `code`, `DocumentStore` for persistence, fakes and WireMock in tests. Don't refactor working code
   that no task asks for.

## Required workflow for every task

**Before:** read this file and the relevant docs; identify or create the `BE-xxx` task; set it `In Progress`.

**After:**
1. Run `./mvnw -B verify` (`mvnw.cmd` on Windows; JDK 21). Add or update tests for changed logic. Store
   changes also run the Firestore emulator test (TECHNICAL §7).
2. Verify every acceptance criterion and note how.
3. Update the documentation (below).
4. Report the code changes, verification results, documentation updates and remaining work.

## Mandatory documentation maintenance rule

Every change that implements, modifies, fixes, removes or refactors behaviour updates the affected Markdown in
the same task: `docs/ROADMAP.md` (status, current position), `docs/BUGS.md` (status, **Resolution**,
**Verification**; never delete an entry), `docs/TECHNICAL.md` (architecture, config, storage, tests), and
`docs/api/*` for any contract change. A task isn't done until its docs are updated, or until you've said why
no change is needed. Mark something `Done` only after its acceptance criteria are verified; otherwise keep it
`In Progress` or `Fixed – unverified` and say what is outstanding. Keep facts, plans and assumptions apart,
and keep IDs stable.

## Useful facts

- JDK: Corretto 21 (`C:\Users\Asus\.jdks\corretto-21.0.7` on the development machine). Spring Boot 4.1.1,
  Jackson 3 (`tools.jackson.*`).
- Tests use the `test` profile: in-memory stores, fake billing, fake Places, and the auth header
  `Bearer test:<uid>`. Idempotency keys must be UUIDs.
- Docker isn't usable on the development machine (WSL2/virtualization is off), so the image build is
  verified by Render or CI.

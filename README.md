# SmartBite Backend

Spring Boot 4.1 / Java 21 API for the SmartBite Android app: the legacy `/api/v1/meal-suggestions` endpoint
and the authenticated v2 API (suggestions, recipes, reports, quota, account deletion).

## Run the tests

```bash
./mvnw -B verify
```

On Windows use `mvnw.cmd`, with `JAVA_HOME` pointing at a JDK 21.

The Firestore store test runs only against the emulator, which needs the Firebase CLI and Java:

```bash
firebase emulators:exec --only firestore --project demo-smartbite "./mvnw -B test -Dtest=FirestoreDocumentStoreEmulatorTest"
```

## Run locally

A deployed run needs the variables in [docs/ops/ENVIRONMENT.md](docs/ops/ENVIRONMENT.md) (at least
`GEMINI_API_KEY`, the Firebase service account and `SPRING_PROFILES_ACTIVE`). `StartupGuard` refuses
in-memory stores and fake tokens unless `smartbite.v2.stores.allow-in-memory=true`; only the `test` profile
sets that.

## Documentation

Start with [CLAUDE.md](CLAUDE.md), then [docs/ROADMAP.md](docs/ROADMAP.md),
[docs/TECHNICAL.md](docs/TECHNICAL.md) and the API contract in [docs/api/README.md](docs/api/README.md).

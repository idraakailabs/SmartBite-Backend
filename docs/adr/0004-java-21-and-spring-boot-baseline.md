# ADR-0004: Java 21 everywhere; move to a supported Spring Boot line before v2

- **Status:** Accepted (2026-10-11, by the owner, D-021 item 3): Java 21, and Spring Boot 4.1.x first, in its
  own commit, protected by a v1 golden-contract test.
- **Date:** 2026-10-11
- **Deciders:** owner; backend-devops-expert (recommendation)

## 1. Context (verified 2026-10-11)

- `pom.xml`: Spring Boot parent **3.4.5**, `<java.version>17</java.version>`.
- `Dockerfile`: builds with `maven:3.9-eclipse-temurin-21` and runs on `eclipse-temurin:21-jre`. So the bytecode
  targets 17 and runs on 21. The development machine uses Corretto 21.0.7.
- Spring Boot support ([endoflife.date/spring-boot](https://endoflife.date/spring-boot)):

  | Line | OSS support ends | Latest patch |
  |---|---|---|
  | 3.4 | **2025-12-31 (ended)** | 3.4.13 |
  | 3.5 | **2026-06-30 (ended)** | 3.5.16 |
  | 4.0 | 2026-12-31 | 4.0.8 |
  | 4.1 | 2027-07-31 | 4.1.1 |

  The service runs 3.4.5, which is behind its own line's last patch and out of open-source support, so it gets no
  public security fixes.
- JDK support ([endoflife.date/eclipse-temurin](https://endoflife.date/eclipse-temurin)): Temurin 17 until
  2027-10-31, 21 until 2029-12-31, 25 until 2031-09-30.
- Spring Boot 4.0 moves to Spring Framework 7, Jackson 3 (Jackson 2 support is deprecated), Hibernate Validator 9
  and Tomcat 11 ([4.0 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)).
  That is a real migration for the existing v1 code (Jackson imports in `AIModelService`, the global snake_case
  setting).

## 2. Decision

1. **Java 21**: set `<java.version>21</java.version>`; keep Temurin 21 images (pin the minor version or digest).
   Why 21 rather than 17: it's what already runs in production and on the dev machine, it's supported to 2029, and
   virtual threads (`spring.threads.virtual.enabled=true`) suit this I/O-bound service (each request waits seconds
   on AI calls) on a small instance. Why not 25 yet: no feature we need, and less mileage with the Firebase Admin
   and gRPC stack; revisit in 2027.
2. **Recommended: upgrade to Spring Boot 4.1.x before writing v2**, as its own commit, protected by a v1
   golden-contract test (a recorded v1 request and response JSON, asserted byte-for-byte on field names) written
   first on 3.4.5. Doing it first means v2 code is written once against Jackson 3 instead of being migrated later.

## 3. Alternatives

| Option | Pros | Cons |
|---|---|---|
| **Boot 4.1.x + Java 21 (recommended)** | OSS-supported to 2027-07-31; v2 written once | Jackson 3 migration of v1 code; about half a day to a day of work plus testing (estimate) |
| Boot 3.5.16 + Java 21 | Smallest jump from 3.4 | OSS support already ended 2026-06-30; commercial-only after that |
| Stay on 3.4.5 + Java 21 | No migration | Unsupported since 2025-12-31; not even the last 3.4 patch |
| Java 17 in Docker instead | Matches `pom.xml` | Moves production backwards; loses virtual threads; support ends 2027 |

## 4. Consequences

- The Boot upgrade changes v1's runtime libraries. The golden-contract test plus a staging check of
  `POST /api/v1/meal-suggestions` must pass before production.
- Library choices for v2 (Firebase Admin SDK, Resilience, WireMock, Testcontainers) must be checked for Boot 4 /
  Jackson 3 compatibility at implementation time.
- If the owner prefers to stay on 3.x for now, v2 can still be built on 3.4.13 with Java 21, but that should be a
  conscious, time-boxed exception.

## 5. Revisit when

- Spring Boot 4.1 OSS support nears its end (2027-07-31): plan the next minor upgrade.
- A dependency needs Java 25, or Java 21 pinning issues with virtual threads show up in profiling.

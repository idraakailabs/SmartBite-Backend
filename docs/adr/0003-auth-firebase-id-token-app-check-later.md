# ADR-0003: Authenticate v2 with Firebase ID tokens; Firebase App Check as a follow-up

- **Status:** Accepted (2026-10-11, by the owner, D-021). Implements D-011 and TECH-001 criterion 1.
- **Date:** 2026-10-11
- **Deciders:** owner; backend-devops-expert (recommendation)

## 1. Context

`/api/v1` has no authentication (verified: no security dependency in `pom.xml`, no filters). Server-side quota and
entitlement need a trustworthy user id. Every app user signs in with Google through Firebase Auth, and the
RevenueCat app user id is the Firebase uid (Android `docs/TECHNICAL.md` §3.3).

## 2. Decision

- Every `/api/v2/**` request must carry `Authorization: Bearer <Firebase ID token>`. A servlet filter verifies it
  with `FirebaseAuth.verifyIdToken(token)` (Admin SDK: signature against Google's cached public keys, issuer,
  audience = project id, expiry). Failure → `401 UNAUTHENTICATED` problem body.
- Revocation (`checkRevoked = true`, an extra network call) and `auth_time` ≤ 5 minutes are required only on
  `DELETE /api/v2/me`.
- Anonymous provider tokens → `403 ACCOUNT_REQUIRED`.
- No Spring Security dependency for now: one filter plus a request-scoped principal is enough for a single scheme.
  Revisit if roles or more schemes appear.
- The uid is the only identity used downstream (quota key, RevenueCat customer id, report owner).

## 3. Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| **Firebase ID token via Admin SDK (recommended)** | Already issued to every user; no new IdP; uid matches RevenueCat | Admin SDK dependency and a service account |
| Spring Security OAuth2 resource server with Google's JWKS | Standard Spring; no Admin SDK for auth | Admin SDK is needed anyway for Firestore; more config for the same checks |
| API key in the app | Trivial | Extractable from the APK; no per-user identity; can't enforce quota |

## 4. App Check (follow-up, not required now)

What it adds: proof that a request comes from the genuine SmartBite app on a genuine device (Play Integrity
provider on Android), which blocks scripted use of a stolen or self-minted ID token from outside the app. ID tokens
alone prove *who*, not *which client*.

How it would work here (verified on [Firebase docs](https://firebase.google.com/docs/app-check/custom-resource-backend),
last updated 2026-10-06):
- The app sends the App Check token in the `X-Firebase-AppCheck` header.
- The documented Admin SDK `verifyToken()` examples are Node.js, Python and Go. **Java isn't listed**, so the backend
  would verify the JWT itself against `https://firebaseappcheck.googleapis.com/v1/jwks` (RS256, type, issuer,
  audience, expiry) with a JWT library.
- Replay protection (limited-use tokens, `consume: true`) is beta and Node.js only.

Rollout plan: (1) app sends the header; (2) backend verifies and **logs only** (metric: share of requests with a
valid token, by app version); (3) enforce on v2 once that share is ≥ 99% for current app versions, with a
server environment flag `APP_CHECK_ENFORCE`. Play Integrity has its own quotas, to be checked before step 1.

## 5. Consequences and failure scenarios

| Scenario | Impact | Mitigation |
|---|---|---|
| Google public-key fetch fails | Tokens can't be verified | Admin SDK caches keys; on failure → 503, not 401, so the app doesn't sign the user out |
| Clock skew on the server | False expiry | Rely on platform NTP; Admin SDK tolerance |
| Debug and release builds share one Firebase project | Same uids hit staging and production | Collection prefix per environment (ADR-0001). Assumption to confirm: debug builds use the same RevenueCat project, with sandbox purchases |
| Stolen token | Up to 1 h of use | Short token life; rate limits; App Check follow-up |

## 6. Revisit when

- Abuse is seen (requests per uid far above normal, or many uids from few IPs) → start App Check step 2.
- Roles (admin endpoints) are needed → add Spring Security.

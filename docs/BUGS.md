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
  the value afterwards, or shard the counter.
- **Acceptance criteria:** no store timeouts at the target concurrent eat-out rate on staging.

## <a id="be-bug-002"></a>BE-BUG-002: the Firestore emulator test connected over TLS

- **Status:** Done (test-only; production code unaffected)
- **Evidence:** `FirestoreOptions.setEmulatorHost` with this client version negotiated TLS. The emulator speaks
  plaintext HTTP/2, so every call failed with `Http2Exception: First received frame was not SETTINGS`
  (`15 03 01`, a TLS alert). A raw HTTP/2 preface sent to the emulator got a valid SETTINGS frame back.
- **Resolution:** `7ac27d7`. `FirestoreDocumentStoreEmulatorTest` builds an explicit plaintext gRPC channel,
  and `@AfterAll` uses `shutdownNow()` because `close()` blocked on channel termination.
- **Verification:** 11/11 emulator tests passed on 2026-10-12 (Firebase CLI 15.33.0, emulator on 127.0.0.1:8085).

# ADR-0002: Area grounding with Google Places API (New), called only from the backend

- **Status:** Accepted (2026-10-11, by the owner, D-021). Implements D-012.
- **Deviation recorded (D-021 item 2):** the owner's original implementation brief (§5 step 3) had Places data
  grounding the prompt. This ADR deliberately does **not** put Places content in the prompt (only place ids and
  coordinates may be cached) and verifies the hero after generation. The verified-pick rate is logged and counted
  as a metric (`smartbite.places.verification` by outcome) so the choice can be revisited if it's low.
- **Date:** 2026-10-11
- **Deciders:** owner; backend-devops-expert (recommendation)

## 1. Context

"Eat out" picks must never claim availability without data (D-008 guardrail, PROD-015). The owner chose Google
Places API (New) through the backend, with one Nearby Search per area cell, optional Text Search verification of
the hero, honest labels, Google-compliant caching, a per-uid limit and a global daily cap.

Verified facts (accessed 2026-10-11):
- Nearby Search (New) SKUs are Pro, Enterprise and Enterprise + Atmosphere; there is no Essentials tier. It has no
  free-text keyword; it filters by place types; `maxResultCount` 1–20; `rankPreference` POPULARITY or DISTANCE;
  circle radius ≤ 50,000 m ([Nearby Search](https://developers.google.com/maps/documentation/places/web-service/nearby-search), updated 2026-10-08).
- Text Search (New): field mask `places.id` (and `places.name`, `nextPageToken`, `places.attributions`) triggers
  **Text Search Essentials (IDs Only)**; `locationRestriction` accepts only a rectangle and only for categorical
  queries; `locationBias` accepts a circle; `pageSize` 1–20, max 60 results across pages
  ([Text Search](https://developers.google.com/maps/documentation/places/web-service/text-search), updated 2026-10-08).
- Prices: Nearby Search Pro 5,000 free/month then $32.00 per 1,000; Text Search Essentials (IDs Only) unlimited
  free; Text Search Pro 5,000 free then $32.00 per 1,000; Place Details Essentials 10,000 free then $5.00 per 1,000
  ([pricing](https://developers.google.com/maps/billing-and-pricing/pricing), updated 2026-10-08). Since March 1,
  2025 the $200 monthly credit is replaced by a free monthly usage threshold per Core Services SKU
  ([March 2025 changes](https://developers.google.com/maps/billing-and-pricing/march-2025)).
- Caching: place ids may be stored indefinitely; other Places content must not be pre-fetched, cached or stored
  beyond allowed exceptions ([policies](https://developers.google.com/maps/documentation/places/web-service/policies));
  lat/lng may be cached up to 30 consecutive calendar days and then must be deleted (Service Specific Terms §14.3,
  [terms](https://cloud.google.com/maps-platform/terms/maps-service-terms), last modified June 10, 2026).
- Display: Places data without a Google Map needs the Google logo (or "Google Maps" text when space is limited);
  Places results on a map must be on a Google Map (policies page).

## 2. Decision

- **Nearby Search Pro**, field mask `places.id,places.location`, `includedTypes: ["restaurant"]`, 20 results,
  once per geohash cell + radius bucket, cached 7 days (ids + lat/lng only).
- **Text Search Essentials (IDs Only)**, field mask `places.id`, `textQuery` = dish name, `locationBias` circle,
  for the hero (then the next suggestion if the hero has no match). Matches = Text Search ids ∩ cell candidates
  within the user's radius → `place_count` (a lower bound). Cached 7 days (ids only).
- Labels: `verified` ("At N+ places within X km", with Google Maps attribution), `popular_around` (with a reason),
  `none`.
- Spend controls: per-uid limits, Firestore-backed global daily caps (Nearby default 150/day, inside the 5,000/month
  free tier), plus a Google Cloud per-day quota and budget alert set by the owner. Any limit or failure →
  `popular_around`.
- The Places key exists only as a backend secret, restricted to the Places API (New).

## 3. Alternatives considered

| Option | Pros | Cons | Cost per uncached eat-out set (after free tier) |
|---|---|---|---|
| **Nearby Pro per cell + Text Search IDs-only intersect (recommended)** | Cheapest verified signal; cache-friendly; terms-compliant (ids + lat/lng only) | Count is a lower bound (top 20 per cell); dish matching relies on Google's text relevance | $0.032 per cell miss, $0 per verification |
| Text Search Pro per dish with `places.location`, distance filter | More complete count, no cell step | $0.032 per dish; 3× for all picks; can't cache locations of content beyond 30 days | $0.032–$0.096 |
| Nearby Enterprise (types, rating, hours) to ground the prompt | Richer prompt | Enterprise price; most fields can't be cached, so cache hits lose the grounding | $0.035+ |
| No Places, labels only (Level 1) | $0 | No verification; superseded by D-011/D-012 | $0 |

## 4. Consequences

- **Honesty:** "verified" means "Google returned these places for the dish name near you". It doesn't prove the
  dish is on the menu. The copy should say "found at" rather than "serves".
- **Latency:** cell miss + text search ≈ 2 extra calls (timeouts 2 s connect / 4 s read; 5 s stage budget) in
  parallel with generation where possible. Not measured.
- **Compliance work:** daily cleanup job, no Places content beyond ids and coordinates, attribution in the app, no
  non-Google map with Places results.
- **Cost:** see [COSTS.md](../ops/COSTS.md).

## 5. Failure scenarios

| Scenario | Impact | Mitigation |
|---|---|---|
| Key missing/invalid, quota exceeded, 5xx, timeout | No verification | `popular_around` with `provider_unavailable` / `global_cap_reached`; alert on repeated failures |
| Abuse (one user scanning many cells) | Spend | Per-uid daily limit; global cap; Cloud quota backstop |
| Google changes SKUs or prices | Spend or field-mask change | Field masks in config; costs re-verified before raising caps |
| Text relevance poor for local dish names | False negatives | Shown as `no_match_found` → "Popular around"; measure the verified rate on staging |

## 6. Why this option

It is the only option that gives a real, terms-compliant availability signal at almost no marginal cost: the
verification call is free and the only paid call is cached per area.

## 7. Revisit when

- The verified rate for the hero is below 30% on staging or production samples (signal too weak to show).
- Nearby calls exceed 4,000/month (approaching the free tier), or the owner raises the global cap.
- The design adds a "Places serving this" list (Level 2), which needs names and so Pro/Enterprise fields that can't
  be cached.

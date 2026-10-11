# Cost estimate: Places and AI per pick set (v2)

> **Estimate, 2026-10-11.** Prices are verified from the linked official pages on that date. Token counts, cache
> hit rates and volumes are **assumptions**, not measurements. Re-measure on staging (log tokens per call and the
> cache hit rate) before relying on these numbers.

## 1. Verified prices (accessed 2026-10-11)

| Item | Free usage | Price after free usage | Source |
|---|---|---|---|
| Places Nearby Search Pro | 5,000 calls/month | $32.00 per 1,000 (first tier) | [GMP pricing](https://developers.google.com/maps/billing-and-pricing/pricing), updated 2026-10-08 |
| Places Text Search Essentials (IDs Only) | Unlimited | No charge | same |
| Places Text Search Pro | 5,000/month | $32.00 per 1,000 | same |
| Places Place Details Essentials | 10,000/month | $5.00 per 1,000 | same |
| Groq `openai/gpt-oss-120b` | n/a | $0.15 / 1M input, $0.60 / 1M output tokens | [Groq model page](https://console.groq.com/docs/model/openai/gpt-oss-120b) (no date shown) |
| Groq `openai/gpt-oss-20b` | n/a | $0.075 / 1M input, $0.30 / 1M output | [Groq model page](https://console.groq.com/docs/model/openai/gpt-oss-20b) (no date shown) |
| Gemini `gemini-2.5-flash-lite` (paid tier) | Free tier exists | $0.10 / 1M input, $0.40 / 1M output (incl. thinking) | [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing), updated 2026-10-09 |
| Firestore | 50k reads, 20k writes, 20k deletes per day | $0.03–$0.033 / 100k reads, $0.09–$0.099 / 100k writes (by location) | [Firestore pricing](https://firebase.google.com/docs/firestore/pricing), [Cloud pricing](https://cloud.google.com/firestore/pricing) |

**Privacy note:** the Gemini pricing page lists the free tier as "content used to improve our products" and the paid
tier as "not used". User taste profiles are sent in prompts, so the production Gemini key should be on the paid
tier (and the privacy policy should name the AI providers; TECH-013). Which tier the current keys use isn't known.

## 2. Assumptions

| Assumption | Value |
|---|---|
| Suggestion prompt | 2,500 input tokens (rules, profile, 30-entry history, recent list, schema) |
| Suggestion output | 2,000 tokens (≈1,200 JSON + ≈800 reasoning on gpt-oss) |
| Recipe | 1,500 input, 3,500 output tokens |
| Retries | 10% of sets need a second AI call (parse/diet failure or fallback) |
| Verification | 1 Text Search per eat-out set on average (hero), occasionally 2 |
| Firestore per set | ≈5 reads, ≈3 writes |

## 3. AI cost per call (from the prices and assumptions above)

| Provider | Suggestion set | Recipe (uncached) |
|---|---|---|
| Groq gpt-oss-120b (primary) | 2,500 × $0.15/1M + 2,000 × $0.60/1M = **$0.0016** | 1,500 × $0.15/1M + 3,500 × $0.60/1M = **$0.0023** |
| Gemini 2.5 Flash-Lite (paid) | $0.00025 + $0.0008 = **$0.0011** | $0.00015 + $0.0014 = **$0.0016** |
| Groq gpt-oss-20b | $0.00019 + $0.0006 = **$0.0008** | $0.00011 + $0.00105 = **$0.0012** |

With 10% retries on the primary: ≈ **$0.0018 per suggestion set**.

## 4. Cost per pick set

| Set type | Places calls | Places cost | AI cost | Total |
|---|---|---|---|---|
| `cook` or `anywhere` | none | $0 | $0.0018 | **≈ $0.0018** |
| `eat_out`, city only | none | $0 | $0.0018 | **≈ $0.0018** |
| `eat_out`, cell **cached** | 1 Text Search IDs-only | $0 | $0.0018 | **≈ $0.0018** |
| `eat_out`, cell **not cached**, within the 5,000 free Nearby calls | 1 Nearby Pro + 1 Text IDs-only | $0 | $0.0018 | **≈ $0.0018** |
| `eat_out`, cell **not cached**, beyond the free tier | 1 Nearby Pro + 1 Text IDs-only | $0.032 | $0.0018 | **≈ $0.034** |

Firestore per set: ≈5 reads + 3 writes ≈ $0.000005 beyond the free quota (negligible), and $0 inside it.

Without any cache (every eat-out set calls Nearby), eat-out sets beyond the free tier cost about **19×** a cached
set. The cache and the global cap matter more than the AI model choice.

## 5. Illustrative month (assumed volumes)

30,000 sets/month: 15,000 eat-out with coordinates, 15,000 other; 70% cell hit rate (assumption for dense cities
with a 7-day TTL); 3,000 recipe opens with a 50% recipe-cache hit rate.

| Line | Calculation | Cost |
|---|---|---|
| Nearby Search | 15,000 × 30% = 4,500 calls; under 5,000 free (and under the 150/day cap) | $0 |
| Text Search IDs-only | ≈15,000–20,000 calls | $0 |
| AI suggestions | 30,000 × $0.0018 | ≈ $54 |
| AI recipes | 1,500 × $0.0023 | ≈ $3.50 |
| Firestore | ≈150k reads, 90k writes per month ≈ 5k/3k per day, inside the free daily quota | $0 |
| **Total** | | **≈ $58/month** |

Same month **without** the Places cache and with no cap: 15,000 Nearby calls → 10,000 billable × $0.032 = **$320**
extra. With the default cap of 150/day, Places spend can't exceed the free tier; the cost of the cap is that
some sets fall back to "Popular around" on busy days.

Render hosting, Google Play and RevenueCat fees are outside this estimate.

## 6. What to measure on staging

Tokens per call by provider, fallback rate, cell cache hit rate, Nearby calls per day, verified rate of the hero,
and Firestore operations per set. Update this file with measured values.

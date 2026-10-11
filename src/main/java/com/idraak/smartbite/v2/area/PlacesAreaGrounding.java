package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.v2.ai.Deadline;
import com.idraak.smartbite.v2.model.Enums.EvidenceSource;
import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.ReasonCode;
import com.idraak.smartbite.v2.model.Enums.UnverifiedReason;
import com.idraak.smartbite.v2.store.StoreUnavailableException;
import com.idraak.smartbite.v2.suggestion.ReasonBuilder;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Evidence;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Reason;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Suggestion;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Verification;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Area pipeline for eat-out picks with coordinates (README §7, ADR-0002). Places content never reaches the prompt
 * (D-021 item 2): the hero is verified after generation.
 * <ol>
 *   <li>Cell candidates: cached place ids + coordinates, or one Nearby Search per cell.</li>
 *   <li>Candidates within the user's radius of the user's actual point.</li>
 *   <li>Verify the hero with a Text Search (ids only); if no match, try the next pick; alternatives are verified only
 *       when {@code verifyAlternatives} is on.</li>
 *   <li>The first verified pick becomes the hero. Verified = matches ∩ candidates, a lower bound.</li>
 * </ol>
 * Any cap, limit, timeout or provider failure degrades to "popular around" with the reason. The hero outcome is
 * counted in {@code smartbite.places.verification} so the verified-pick rate can be measured (D-021 item 2).
 */
public class PlacesAreaGrounding implements AreaGrounding {

    private static final Logger log = LoggerFactory.getLogger(PlacesAreaGrounding.class);

    private final PlacesClient client;
    private final PlacesCache cache;
    private final PlacesBudget budget;
    private final List<String> includedTypes;
    private final boolean verifyAlternatives;
    private final Duration stageBudget;
    private final MeterRegistry meters;
    private final Clock clock;
    private final NoPlacesAreaGrounding fallback = new NoPlacesAreaGrounding();

    public PlacesAreaGrounding(PlacesClient client, PlacesCache cache, PlacesBudget budget, List<String> includedTypes,
                               boolean verifyAlternatives, Duration stageBudget, MeterRegistry meters, Clock clock) {
        this.client = client;
        this.cache = cache;
        this.budget = budget;
        this.includedTypes = List.copyOf(includedTypes);
        this.verifyAlternatives = verifyAlternatives;
        this.stageBudget = stageBudget;
        this.meters = meters;
        this.clock = clock;
    }

    private record Check(UnverifiedReason failure, List<String> matches) {
        static Check failed(UnverifiedReason r) {
            return new Check(r, List.of());
        }
    }

    @Override
    public Result ground(String uid, MealSuggestionRequest r, List<Suggestion> suggestions) {
        if (r.intent() != Intent.EAT_OUT || !r.area().hasCoordinates()) {
            return fallback.ground(uid, r, suggestions);
        }
        Deadline deadline = Deadline.in(stageBudget);
        var area = r.area();
        Geo.Cell cell = Geo.cell(area.lat(), area.lng(), area.radiusKm());

        List<PlacesClient.Place> candidates;
        try {
            candidates = candidates(uid, cell);
        } catch (Unavailable e) {
            record(e.reason.name());
            return allPopular(r, suggestions, e.reason, "unavailable");
        }
        Set<String> inRadius = new HashSet<>();
        for (PlacesClient.Place p : candidates) {
            if (Geo.distanceKm(area.lat(), area.lng(), p.lat(), p.lng()) <= area.radiusKm()) inRadius.add(p.id());
        }

        List<Verification> verifications = new ArrayList<>();
        int firstVerified = -1;
        Instant checkedAt = clock.instant();
        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion s = suggestions.get(i);
            boolean shouldCheck = firstVerified < 0 || verifyAlternatives;
            if (!shouldCheck) {
                verifications.add(Labels.popularAround(r, s.name(), UnverifiedReason.NOT_CHECKED));
                continue;
            }
            if (!deadline.hasAtLeast(Duration.ofMillis(500))) {
                verifications.add(Labels.popularAround(r, s.name(), UnverifiedReason.PROVIDER_UNAVAILABLE));
                continue;
            }
            Check check = verify(uid, s.name(), cell, area.lat(), area.lng(), area.radiusKm(), inRadius);
            if (i == 0) record(check.failure() != null ? check.failure().name() : check.matches().isEmpty() ? "NO_MATCH_FOUND" : "VERIFIED");
            if (check.failure() != null) {
                verifications.add(Labels.popularAround(r, s.name(), check.failure()));
            } else if (check.matches().isEmpty()) {
                verifications.add(Labels.popularAround(r, s.name(), UnverifiedReason.NO_MATCH_FOUND));
            } else {
                verifications.add(Labels.verified(r, s.name(), Math.min(20, check.matches().size()), check.matches(), checkedAt));
                if (firstVerified < 0) firstVerified = i;
            }
        }

        List<Suggestion> out = new ArrayList<>();
        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion s = suggestions.get(i);
            Verification v = verifications.get(i);
            List<Reason> reasons = s.reasons();
            if (v.placeCount() != null) {
                List<Reason> withNearby = new ArrayList<>(reasons);
                withNearby.add(new Reason(ReasonCode.FOUND_NEARBY,
                        "Found at " + v.placeCount() + "+ places within " + km(v.radiusKm()),
                        List.of(new Evidence(EvidenceSource.PLACES, "places.place_count", km(v.radiusKm()), String.valueOf(v.placeCount())))));
                reasons = ReasonBuilder.top(withNearby);
            }
            out.add(s.withReasonsAndVerification(reasons, v));
        }
        if (firstVerified > 0) {
            Suggestion hero = out.remove(firstVerified);
            out.add(0, hero);
        }
        log.info("places grounding cell_candidates={} in_radius={} verified_index={}", candidates.size(), inRadius.size(), firstVerified);
        return new Result(List.copyOf(out), Labels.context(r, "used"));
    }

    private List<PlacesClient.Place> candidates(String uid, Geo.Cell cell) {
        try {
            var cached = cache.cell(cell.key());
            if (cached.isPresent()) return cached.get();
            UnverifiedReason blocked = budget.acquire(PlacesBudget.Call.NEARBY, uid);
            if (blocked != null) throw new Unavailable(blocked);
            List<PlacesClient.Place> places = client.nearby(cell.centerLat(), cell.centerLng(), cell.queryRadiusKm(), includedTypes);
            cache.putCell(cell.key(), places);
            return places;
        } catch (PlacesClient.PlacesException e) {
            log.warn("places nearby failed kind={}", e.kind());
            throw new Unavailable(e.kind() == PlacesClient.PlacesException.Kind.QUOTA
                    ? UnverifiedReason.GLOBAL_CAP_REACHED : UnverifiedReason.PROVIDER_UNAVAILABLE);
        } catch (StoreUnavailableException e) {
            throw new Unavailable(UnverifiedReason.PROVIDER_UNAVAILABLE);
        }
    }

    private Check verify(String uid, String dish, Geo.Cell cell, double lat, double lng, double radiusKm, Set<String> inRadius) {
        try {
            List<String> ids = cache.text(dish, cell.key()).orElse(null);
            if (ids == null) {
                UnverifiedReason blocked = budget.acquire(PlacesBudget.Call.TEXT, uid);
                if (blocked != null) return Check.failed(blocked);
                ids = client.textSearchIds(dish, lat, lng, radiusKm);
                cache.putText(dish, cell.key(), ids);
            }
            List<String> matches = ids.stream().filter(inRadius::contains).distinct().toList();
            return new Check(null, matches);
        } catch (PlacesClient.PlacesException e) {
            log.warn("places text search failed kind={}", e.kind());
            return Check.failed(e.kind() == PlacesClient.PlacesException.Kind.QUOTA
                    ? UnverifiedReason.GLOBAL_CAP_REACHED : UnverifiedReason.PROVIDER_UNAVAILABLE);
        } catch (StoreUnavailableException e) {
            return Check.failed(UnverifiedReason.PROVIDER_UNAVAILABLE);
        }
    }

    private Result allPopular(MealSuggestionRequest r, List<Suggestion> suggestions, UnverifiedReason reason, String grounding) {
        return new Result(suggestions.stream()
                .map(s -> s.withReasonsAndVerification(s.reasons(), Labels.popularAround(r, s.name(), reason))).toList(),
                Labels.context(r, grounding));
    }

    private void record(String outcome) {
        meters.counter("smartbite.places.verification", "outcome", outcome.toLowerCase(Locale.ROOT)).increment();
        log.info("places hero verification outcome={}", outcome.toLowerCase(Locale.ROOT));
    }

    private static String km(Double km) {
        if (km == null) return "-";
        return (km == Math.rint(km) ? String.valueOf(km.intValue()) : String.valueOf(km)) + " km";
    }

    private static final class Unavailable extends RuntimeException {
        final UnverifiedReason reason;

        Unavailable(UnverifiedReason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }
}

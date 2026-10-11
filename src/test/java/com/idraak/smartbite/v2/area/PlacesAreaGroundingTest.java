package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.testsupport.MutableClock;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.model.Enums.Category;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.ReasonCode;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.model.Enums.UnverifiedReason;
import com.idraak.smartbite.v2.model.Enums.VerificationStatus;
import com.idraak.smartbite.v2.store.InMemoryDocumentStore;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.Area;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.TasteProfile;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Origin;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Suggestion;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Verification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlacesAreaGroundingTest {

    static final double LAT = 31.4697, LNG = 74.2728;
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
    final InMemoryDocumentStore store = new InMemoryDocumentStore();
    final FakePlacesClient places = new FakePlacesClient();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    PlacesAreaGrounding grounding(int nearbyCap, int perUidNearby, boolean verifyAlternatives) {
        V2Properties.Places cfg = new V2Properties.Places("fake", null, null, nearbyCap, 1000, perUidNearby, 100,
                Duration.ofDays(7), verifyAlternatives, Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5),
                List.of("restaurant"));
        return new PlacesAreaGrounding(places, new PlacesCache(store, cfg.cacheTtl(), clock), new PlacesBudget(store, cfg, clock),
                cfg.includedTypes(), verifyAlternatives, cfg.stageBudget(), meters, clock);
    }

    static MealSuggestionRequest eatOut(double lat, double lng, double radius) {
        return new MealSuggestionRequest(Intent.EAT_OUT, "Asia/Karachi", new Area(lat, lng, radius, "Johar Town, Lahore", "Lahore", null, null),
                new TasteProfile(Diet.NON_VEGETARIAN, null, null, null, null, null, null, null, null, null), null, null, null, null);
    }

    static Suggestion s(String name) {
        return new Suggestion(null, name, "d", Category.CURRY_KARAHI, Origin.of("Pakistani", null), Diet.NON_VEGETARIAN, false,
                SpiceLevel.SPICY, null, List.of("x"), 40, Difficulty.MEDIUM, 2, List.of(), Verification.none());
    }

    void nearbyFixture() {
        places.setNearby(List.of(
                new PlacesClient.Place("A", LAT + 0.005, LNG),      // ~0.6 km
                new PlacesClient.Place("B", LAT, LNG + 0.01),       // ~0.95 km
                new PlacesClient.Place("FAR", LAT + 0.05, LNG)));   // ~5.6 km, outside 3 km
    }

    @Test
    void heroVerifiedOnlyByPlacesWithinTheRadiusWithAttributionAndReason() {
        nearbyFixture();
        places.setTextResults("Mutton Karahi", List.of("A", "FAR", "NOT_A_CANDIDATE"));
        AreaGrounding.Result r = grounding(150, 15, false).ground("u", eatOut(LAT, LNG, 3), List.of(s("Mutton Karahi"), s("Shawarma"), s("Kebab")));

        Suggestion hero = r.suggestions().get(0);
        assertThat(hero.verification().status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(hero.verification().placeCount()).isEqualTo(1);
        assertThat(hero.verification().placeCountIsLowerBound()).isTrue();
        assertThat(hero.verification().maps().placeIds()).containsExactly("A");
        assertThat(hero.verification().attribution().mustDisplay()).isTrue();
        assertThat(hero.reasons()).extracting(x -> x.code()).contains(ReasonCode.FOUND_NEARBY);
        assertThat(r.suggestions().get(1).verification().unverifiedReason()).isEqualTo(UnverifiedReason.NOT_CHECKED);
        assertThat(r.suggestions().get(1).verification().attribution()).isNull();
        assertThat(r.areaContext().placesGrounding()).isEqualTo("used");
        assertThat(meters.counter("smartbite.places.verification", "outcome", "verified").count()).isEqualTo(1.0);
    }

    @Test
    void firstVerifiedPickIsPromotedToHero() {
        nearbyFixture();
        places.setTextResults("Shawarma", List.of("B"));
        AreaGrounding.Result r = grounding(150, 15, false).ground("u", eatOut(LAT, LNG, 3), List.of(s("Mutton Karahi"), s("Shawarma"), s("Kebab")));
        assertThat(r.suggestions()).extracting(Suggestion::name).containsExactly("Shawarma", "Mutton Karahi", "Kebab");
        assertThat(r.suggestions().get(1).verification().unverifiedReason()).isEqualTo(UnverifiedReason.NO_MATCH_FOUND);
        assertThat(r.suggestions().get(2).verification().unverifiedReason()).isEqualTo(UnverifiedReason.NOT_CHECKED);
        assertThat(meters.counter("smartbite.places.verification", "outcome", "no_match_found").count()).isEqualTo(1.0);
    }

    @Test
    void cachedCellsAndTextResultsAvoidRepeatCalls() {
        nearbyFixture();
        places.setTextResults("Mutton Karahi", List.of("A"));
        PlacesAreaGrounding g = grounding(150, 15, false);
        g.ground("u1", eatOut(LAT, LNG, 3), List.of(s("Mutton Karahi")));
        g.ground("u2", eatOut(LAT + 0.0001, LNG, 3), List.of(s("Mutton Karahi")));
        assertThat(places.nearbyCalls()).isEqualTo(1);
        assertThat(places.textCalls()).isEqualTo(1);
        clock.advance(Duration.ofDays(8)); // past the 7-day TTL
        g.ground("u3", eatOut(LAT, LNG, 3), List.of(s("Mutton Karahi")));
        assertThat(places.nearbyCalls()).isEqualTo(2);
    }

    @Test
    void globalCapAndPerUserLimitFallBackToPopularAround() {
        nearbyFixture();
        PlacesAreaGrounding capped = grounding(1, 15, false);
        capped.ground("u", eatOut(LAT, LNG, 3), List.of(s("A dish")));
        AreaGrounding.Result r = capped.ground("u", eatOut(LAT + 1, LNG + 1, 3), List.of(s("A dish")));
        assertThat(r.suggestions().get(0).verification().unverifiedReason()).isEqualTo(UnverifiedReason.GLOBAL_CAP_REACHED);
        assertThat(r.areaContext().placesGrounding()).isEqualTo("unavailable");

        PlacesAreaGrounding perUser = grounding(150, 1, false);
        perUser.ground("v", eatOut(10, 10, 3), List.of(s("A dish")));
        assertThat(perUser.ground("v", eatOut(20, 20, 3), List.of(s("A dish"))).suggestions().get(0).verification().unverifiedReason())
                .isEqualTo(UnverifiedReason.USER_LIMIT_REACHED);
    }

    @Test
    void providerFailureIsPopularAroundNotAnError() {
        places.failWith(new PlacesClient.PlacesException(PlacesClient.PlacesException.Kind.TIMEOUT, "t", null));
        AreaGrounding.Result r = grounding(150, 15, false).ground("u", eatOut(LAT, LNG, 3), List.of(s("A dish")));
        assertThat(r.suggestions().get(0).verification().status()).isEqualTo(VerificationStatus.POPULAR_AROUND);
        assertThat(r.suggestions().get(0).verification().unverifiedReason()).isEqualTo(UnverifiedReason.PROVIDER_UNAVAILABLE);
        assertThat(r.suggestions().get(0).verification().maps().url()).startsWith("https://www.google.com/maps/search/?api=1&query=");
    }

    @Test
    void cookIntentAndCityOnlyNeverCallPlaces() {
        PlacesAreaGrounding g = grounding(150, 15, false);
        MealSuggestionRequest cityOnly = new MealSuggestionRequest(Intent.EAT_OUT, "Asia/Karachi",
                new Area(null, null, null, null, "Lahore", null, null), eatOut(LAT, LNG, 3).profile(), null, null, null, null);
        assertThat(g.ground("u", cityOnly, List.of(s("x"))).suggestions().get(0).verification().unverifiedReason())
                .isEqualTo(UnverifiedReason.NO_COORDINATES);
        assertThat(places.nearbyCalls()).isZero();
    }

    @Test
    void onlyIdsAndCoordinatesAreCachedAndCleanupDeletesExpiredDocuments() {
        nearbyFixture();
        places.setTextResults("Mutton Karahi", List.of("A"));
        grounding(150, 15, false).ground("u", eatOut(LAT, LNG, 3), List.of(s("Mutton Karahi")));
        String cellKey = Geo.cell(LAT, LNG, 3).key();
        var doc = store.get(PlacesCache.CELLS, cellKey).orElseThrow();
        assertThat(doc.keySet()).containsExactlyInAnyOrder("places", "fetched_at", "expire_at");
        for (Object row : (List<?>) doc.get("places")) {
            assertThat(((java.util.Map<?, ?>) row).keySet().stream().map(String::valueOf).toList())
                    .containsExactlyInAnyOrder("id", "lat", "lng");
        }

        PlacesCacheCleanup cleanup = new PlacesCacheCleanup(store, clock);
        assertThat(cleanup.run()).isZero();
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        assertThat(cleanup.run()).isEqualTo(2);
        assertThat(store.count(PlacesCache.CELLS)).isZero();
    }

    @Test
    void ttlOver30DaysIsRefused() {
        assertThatThrownBy(() -> new PlacesCache(store, Duration.ofDays(31), clock)).isInstanceOf(IllegalArgumentException.class);
    }
}

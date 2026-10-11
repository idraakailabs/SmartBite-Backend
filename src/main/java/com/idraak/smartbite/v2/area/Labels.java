package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.UnverifiedReason;
import com.idraak.smartbite.v2.model.Enums.VerificationStatus;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.AreaContext;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Attribution;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.MapsHandoff;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Verification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Honest labels and the Google Maps "Find nearby" link (Maps URLs need no API key). */
public final class Labels {

    private Labels() {
    }

    public static MapsHandoff maps(String dish, String areaLabel, List<String> placeIds) {
        String query = areaLabel == null ? dish : dish + " near " + areaLabel;
        String url = "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        return new MapsHandoff(query, url, List.copyOf(placeIds));
    }

    public static Verification popularAround(MealSuggestionRequest r, String dish, UnverifiedReason reason) {
        var area = r.area();
        return new Verification(VerificationStatus.POPULAR_AROUND, reason, null, true,
                area.hasCoordinates() ? area.radiusKm() : null, area.displayLabel(), null,
                maps(dish, area.displayLabel(), List.of()), null);
    }

    public static Verification verified(MealSuggestionRequest r, String dish, int count, List<String> placeIds, Instant checkedAt) {
        var area = r.area();
        return new Verification(VerificationStatus.VERIFIED, null, count, true, area.radiusKm(), area.displayLabel(),
                checkedAt, maps(dish, area.displayLabel(), placeIds.stream().limit(5).toList()), Attribution.GOOGLE_MAPS);
    }

    public static AreaContext context(MealSuggestionRequest r, String placesGrounding) {
        if (r.intent() == Intent.ANYWHERE) return new AreaContext("none", null, null, "skipped");
        var area = r.area();
        String mode = area.hasCoordinates() ? "coordinates" : "city";
        return new AreaContext(mode, area.displayLabel(), area.hasCoordinates() ? area.radiusKm() : null, placesGrounding);
    }
}

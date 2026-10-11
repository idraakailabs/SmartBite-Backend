package com.idraak.smartbite.v2.suggestion.api;

import com.idraak.smartbite.v2.model.Enums.Category;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.model.Enums.EvidenceSource;
import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.MealSlot;
import com.idraak.smartbite.v2.model.Enums.ReasonCode;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.model.Enums.UnverifiedReason;
import com.idraak.smartbite.v2.model.Enums.VerificationStatus;
import com.idraak.smartbite.v2.quota.QuotaSnapshot;

import java.time.Instant;
import java.util.List;

/** {@code 200} body of {@code POST /api/v2/meal-suggestions} (openapi MealSuggestionResponse). */
public record MealSuggestionResponse(
        String requestId,
        Instant generatedAt,
        MealSlot mealSlot,
        Intent intent,
        AreaContext areaContext,
        List<Suggestion> suggestions,
        QuotaSnapshot quota,
        Meta meta
) {

    public MealSuggestionResponse withQuotaAndMeta(QuotaSnapshot q, Meta m) {
        return new MealSuggestionResponse(requestId, generatedAt, mealSlot, intent, areaContext, suggestions, q, m);
    }

    public record AreaContext(String mode, String label, Double radiusKm, String placesGrounding) {}

    public record Suggestion(
            String id,
            String name,
            String shortDescription,
            Category category,
            Origin origin,
            Diet diet,
            boolean containsEgg,
            SpiceLevel spiceLevel,
            String primaryProtein,
            List<String> keyIngredients,
            int totalTimeMinutes,
            Difficulty difficulty,
            int servings,
            List<Reason> reasons,
            Verification verification
    ) {
        public Suggestion withReasonsAndVerification(List<Reason> r, Verification v) {
            return new Suggestion(id, name, shortDescription, category, origin, diet, containsEgg, spiceLevel,
                    primaryProtein, keyIngredients, totalTimeMinutes, difficulty, servings, r, v);
        }

        public Suggestion withId(String newId) {
            return new Suggestion(newId, name, shortDescription, category, origin, diet, containsEgg, spiceLevel,
                    primaryProtein, keyIngredients, totalTimeMinutes, difficulty, servings, reasons, verification);
        }
    }

    public record Origin(String cuisine, String region, String label) {
        public static Origin of(String cuisine, String region) {
            String label = region == null || region.isBlank() ? cuisine : cuisine + " · " + region + " style";
            return new Origin(cuisine, region == null || region.isBlank() ? null : region, label);
        }
    }

    public record Reason(ReasonCode code, String text, List<Evidence> evidence) {}

    public record Evidence(EvidenceSource source, String field, String userValue, String dishValue) {}

    public record Verification(
            VerificationStatus status,
            UnverifiedReason unverifiedReason,
            Integer placeCount,
            Boolean placeCountIsLowerBound,
            Double radiusKm,
            String areaLabel,
            Instant checkedAt,
            MapsHandoff maps,
            Attribution attribution
    ) {
        public static Verification none() {
            return new Verification(VerificationStatus.NONE, null, null, null, null, null, null, null, null);
        }
    }

    public record MapsHandoff(String query, String url, List<String> placeIds) {}

    public record Attribution(boolean mustDisplay, String text, String logo) {
        public static final Attribution GOOGLE_MAPS = new Attribution(true, "Google Maps", "google_maps");
    }

    public record Meta(String promptVersion, boolean degraded, boolean idempotentReplay) {}
}

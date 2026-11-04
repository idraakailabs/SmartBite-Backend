package com.idraak.smartbite.v2.suggestion.api;

import com.idraak.smartbite.v2.model.Enums.BudgetTier;
import com.idraak.smartbite.v2.model.Enums.CookingSkill;
import com.idraak.smartbite.v2.model.Enums.CookingTime;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.HistoryEntryType;
import com.idraak.smartbite.v2.model.Enums.HistoryMealSlot;
import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.ProfileStatus;
import com.idraak.smartbite.v2.model.Enums.Reaction;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.validation.IanaTimezone;
import com.idraak.smartbite.v2.validation.SafeText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** {@code POST /api/v2/meal-suggestions} body (openapi MealSuggestionRequest). */
public record MealSuggestionRequest(
        @NotNull Intent intent,
        @NotNull @IanaTimezone String timezone,
        @NotNull @Valid Area area,
        @NotNull @Valid TasteProfile profile,
        @Size(max = 30) List<@NotBlank @SafeText @Size(max = 80) String> recentSuggestions,
        @Size(max = 50) List<@NotNull @Valid HistoryEntry> history,
        @Size(max = 20) List<@NotBlank @SafeText @Size(max = 80) String> sessionExcludedDishes,
        @Valid RefineOptions refine,
        // BE-027 (Android TECH-022), all optional. A request with profile_status uses the constraint contract.
        ProfileStatus profileStatus,
        @Size(max = 12) List<@NotBlank @SafeText @Size(max = 40) String> requiredCuisines,
        @Min(1) @Max(1440) Integer maxTotalMinutes
) {

    /** Quicker caps a Cook at home set at this many minutes. */
    public static final int QUICKER_MAX_MINUTES = 30;

    /** A request without the BE-027 fields (as older clients send it). */
    public MealSuggestionRequest(Intent intent, String timezone, Area area, TasteProfile profile, List<String> recentSuggestions,
                                 List<HistoryEntry> history, List<String> sessionExcludedDishes, RefineOptions refine) {
        this(intent, timezone, area, profile, recentSuggestions, history, sessionExcludedDishes, refine, null, null, null);
    }

    /**
     * The client sent the BE-027 constraint contract (it sends {@code profile_status} whenever it does). Only these
     * clients get {@code total_time_minutes: null} on eat-out picks; older clients always get an integer.
     */
    public boolean constraintContract() {
        return profileStatus != null;
    }

    /** True when the user chose the diet; false only when the client said {@code diet_is_explicit: false}. */
    public boolean dietIsExplicit() {
        return !Boolean.FALSE.equals(profile.dietIsExplicit());
    }

    /** Eat-out picks may have no time estimate (it isn't cooking time there), for constraint-contract clients only. */
    public boolean timeMayBeNull() {
        return intent == Intent.EAT_OUT && constraintContract();
    }

    public List<String> recentSuggestionsOrEmpty() {
        return recentSuggestions == null ? List.of() : recentSuggestions;
    }

    public List<HistoryEntry> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    public List<String> sessionExcludedOrEmpty() {
        return sessionExcludedDishes == null ? List.of() : sessionExcludedDishes;
    }

    /**
     * Cuisines every pick must come from: {@code required_cuisines} for any intent (BE-027), plus, for an Anywhere
     * set, the cuisines in {@code refine.cuisine} (comma-separated, BE-BUG-009). Elsewhere {@code refine.cuisine}
     * stays a preference.
     */
    public List<String> effectiveRequiredCuisines() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (requiredCuisines != null) requiredCuisines.forEach(c -> add(out, c));
        if (intent == Intent.ANYWHERE && refine != null && refine.cuisine() != null) {
            java.util.Arrays.stream(refine.cuisine().split(",")).forEach(c -> add(out, c));
        }
        return List.copyOf(out.values());
    }

    private static void add(java.util.Map<String, String> out, String cuisine) {
        String c = cuisine == null ? "" : cuisine.strip();
        if (!c.isEmpty()) out.putIfAbsent(c.toLowerCase(java.util.Locale.ROOT), c);
    }

    /**
     * Required cuisines came from {@code required_cuisines}, which is exclusive: off-cuisine picks are never
     * returned. The legacy {@code refine.cuisine} path keeps its degraded off-cuisine fallback (BE-024).
     */
    public boolean cuisinesStrict() {
        return requiredCuisines != null && requiredCuisines.stream().anyMatch(c -> c != null && !c.isBlank());
    }

    /**
     * The longest a pick may take, or null for no limit (BE-027): {@code max_total_minutes} (ignored for eat-out,
     * where the time isn't cooking time), capped at 30 by {@code refine.quicker} for Cook at home.
     */
    public Integer effectiveMaxMinutes() {
        if (intent == Intent.EAT_OUT) return null;
        Integer limit = maxTotalMinutes;
        if (intent == Intent.COOK && refine != null && refine.isQuicker()) {
            limit = limit == null ? QUICKER_MAX_MINUTES : Math.min(limit, QUICKER_MAX_MINUTES);
        }
        return limit;
    }

    public RefineOptions refineOrEmpty() {
        return refine == null ? new RefineOptions(null, null, null, null, null, null) : refine;
    }

    public record Area(
            @DecimalMin("-90") @DecimalMax("90") Double lat,
            @DecimalMin("-180") @DecimalMax("180") Double lng,
            @DecimalMin("0.5") @DecimalMax("10") Double radiusKm,
            @SafeText @Size(min = 1, max = 80) String label,
            @SafeText @Size(min = 1, max = 60) String city,
            @SafeText @Size(min = 1, max = 60) String country,
            @Pattern(regexp = "^[A-Z]{2}$") String countryCode
    ) {
        public boolean hasCoordinates() {
            return lat != null && lng != null && radiusKm != null;
        }

        @AssertTrue(message = "needs lat, lng, radius_km and label, or city")
        public boolean isCoordinatesOrCity() {
            boolean coords = hasCoordinates() && label != null && !label.isBlank();
            boolean partialCoords = (lat != null || lng != null || radiusKm != null) && !hasCoordinates();
            return !partialCoords && (coords || (city != null && !city.isBlank()));
        }

        /** The label used in copy and prompts: the area label, else the city. */
        public String displayLabel() {
            if (label != null && !label.isBlank()) return label.trim();
            return city == null ? null : city.trim();
        }
    }

    public record TasteProfile(
            @NotNull Diet diet,
            // BE-027: false = diet is the app's default, not the user's choice. Null (older clients) = explicit.
            Boolean dietIsExplicit,
            SpiceLevel spiceTolerance,
            @Size(max = 12) List<@NotBlank @SafeText @Size(max = 40) String> topCuisines,
            @Size(max = 12) List<@NotBlank @SafeText @Size(max = 40) String> favoriteProteins,
            @Size(max = 30) List<@NotBlank @SafeText @Size(max = 60) String> dislikedFoods,
            @Size(max = 30) List<@NotBlank @SafeText @Size(max = 80) String> likedDishes,
            BudgetTier budgetTier,
            CookingTime cookingTime,
            CookingSkill cookingSkill,
            @Min(1) @Max(20) Integer householdSize
    ) {
        /** A profile without {@code diet_is_explicit} (as older clients send it). */
        public TasteProfile(Diet diet, SpiceLevel spiceTolerance, List<String> topCuisines, List<String> favoriteProteins,
                            List<String> dislikedFoods, List<String> likedDishes, BudgetTier budgetTier,
                            CookingTime cookingTime, CookingSkill cookingSkill, Integer householdSize) {
            this(diet, null, spiceTolerance, topCuisines, favoriteProteins, dislikedFoods, likedDishes, budgetTier,
                    cookingTime, cookingSkill, householdSize);
        }

        public List<String> topCuisinesOrEmpty() {
            return topCuisines == null ? List.of() : topCuisines;
        }

        public List<String> favoriteProteinsOrEmpty() {
            return favoriteProteins == null ? List.of() : favoriteProteins;
        }

        public List<String> dislikedFoodsOrEmpty() {
            return dislikedFoods == null ? List.of() : dislikedFoods;
        }

        public List<String> likedDishesOrEmpty() {
            return likedDishes == null ? List.of() : likedDishes;
        }
    }

    public record HistoryEntry(
            @NotBlank @SafeText @Size(max = 80) String dish,
            @NotNull LocalDate date,
            HistoryMealSlot mealSlot,
            @NotNull HistoryEntryType entryType,
            Reaction reaction
    ) {}

    public record RefineOptions(
            Boolean quicker,
            Boolean milder,
            Boolean vegetarianToday,
            Boolean budgetFriendly,
            @Min(1) @Max(20) Integer servings,
            @SafeText @Size(min = 1, max = 40) String cuisine
    ) {
        public boolean isQuicker() {
            return Boolean.TRUE.equals(quicker);
        }

        public boolean isMilder() {
            return Boolean.TRUE.equals(milder);
        }

        public boolean isVegetarianToday() {
            return Boolean.TRUE.equals(vegetarianToday);
        }

        public boolean isBudgetFriendly() {
            return Boolean.TRUE.equals(budgetFriendly);
        }
    }
}

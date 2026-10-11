package com.idraak.smartbite.v2.suggestion.api;

import com.idraak.smartbite.v2.model.Enums.BudgetTier;
import com.idraak.smartbite.v2.model.Enums.CookingSkill;
import com.idraak.smartbite.v2.model.Enums.CookingTime;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.HistoryEntryType;
import com.idraak.smartbite.v2.model.Enums.HistoryMealSlot;
import com.idraak.smartbite.v2.model.Enums.Intent;
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
        @Valid RefineOptions refine
) {

    public List<String> recentSuggestionsOrEmpty() {
        return recentSuggestions == null ? List.of() : recentSuggestions;
    }

    public List<HistoryEntry> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    public List<String> sessionExcludedOrEmpty() {
        return sessionExcludedDishes == null ? List.of() : sessionExcludedDishes;
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

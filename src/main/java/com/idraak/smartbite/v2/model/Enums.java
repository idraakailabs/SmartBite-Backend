package com.idraak.smartbite.v2.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The v2 enums with their exact wire values (openapi-v2.yaml). */
public final class Enums {

    private Enums() {
    }

    public enum Intent {
        @JsonProperty("eat_out") EAT_OUT, @JsonProperty("cook") COOK, @JsonProperty("anywhere") ANYWHERE
    }

    public enum Diet {
        @JsonProperty("non_vegetarian") NON_VEGETARIAN, @JsonProperty("vegetarian") VEGETARIAN, @JsonProperty("vegan") VEGAN;

        /** How strict: vegan (2) > vegetarian (1) > non-vegetarian (0). */
        public int strictness() {
            return ordinal();
        }

        public String wire() {
            return name().toLowerCase();
        }
    }

    public enum SpiceLevel {
        @JsonProperty("mild") MILD, @JsonProperty("medium") MEDIUM, @JsonProperty("spicy") SPICY, @JsonProperty("extra_spicy") EXTRA_SPICY;

        public String wire() {
            return name().toLowerCase();
        }
    }

    public enum CookingSkill {
        @JsonProperty("beginner") BEGINNER, @JsonProperty("intermediate") INTERMEDIATE, @JsonProperty("advanced") ADVANCED
    }

    public enum CookingTime {
        @JsonProperty("up_to_30") UP_TO_30(30), @JsonProperty("up_to_60") UP_TO_60(60),
        @JsonProperty("up_to_90") UP_TO_90(90), @JsonProperty("over_90") OVER_90(null);

        private final Integer maxMinutes;

        CookingTime(Integer maxMinutes) {
            this.maxMinutes = maxMinutes;
        }

        public Integer maxMinutes() {
            return maxMinutes;
        }

        public String wire() {
            return name().toLowerCase();
        }
    }

    public enum BudgetTier {
        @JsonProperty("budget") BUDGET, @JsonProperty("moderate") MODERATE, @JsonProperty("upscale") UPSCALE, @JsonProperty("premium") PREMIUM
    }

    public enum Difficulty {
        @JsonProperty("easy") EASY, @JsonProperty("medium") MEDIUM, @JsonProperty("hard") HARD
    }

    public enum Category {
        @JsonProperty("biryani_rice") BIRYANI_RICE, @JsonProperty("curry_karahi") CURRY_KARAHI,
        @JsonProperty("grill_kebab") GRILL_KEBAB, @JsonProperty("bread_paratha") BREAD_PARATHA,
        @JsonProperty("daal_lentils") DAAL_LENTILS, @JsonProperty("soup") SOUP, @JsonProperty("salad_bowl") SALAD_BOWL,
        @JsonProperty("noodles_pasta") NOODLES_PASTA, @JsonProperty("pizza") PIZZA,
        @JsonProperty("sandwich_burger") SANDWICH_BURGER, @JsonProperty("street_food") STREET_FOOD,
        @JsonProperty("breakfast_eggs") BREAKFAST_EGGS, @JsonProperty("dessert") DESSERT, @JsonProperty("drink") DRINK,
        @JsonProperty("other") OTHER;

        public static Category fromWire(String s) {
            if (s == null) return OTHER;
            try {
                return valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return OTHER;
            }
        }
    }

    /** History entry types accepted in requests; {@code logged} = saved before entry types existed. */
    public enum HistoryEntryType {
        @JsonProperty("cooked") COOKED, @JsonProperty("ate_out") ATE_OUT, @JsonProperty("suggested") SUGGESTED,
        @JsonProperty("logged") LOGGED;

        /** Counts as an eaten meal (variety, LOVED_BEFORE, SIMILAR_TO_LOVED). Never evidence of cook vs eat out. */
        public boolean eaten() {
            return this != SUGGESTED;
        }
    }

    public enum Reaction {
        @JsonProperty("loved") LOVED, @JsonProperty("okay") OKAY, @JsonProperty("not_for_me") NOT_FOR_ME
    }

    public enum MealSlot {
        @JsonProperty("breakfast") BREAKFAST, @JsonProperty("lunch") LUNCH, @JsonProperty("dinner") DINNER,
        @JsonProperty("late_night") LATE_NIGHT;

        public static MealSlot at(int hour) {
            if (hour >= 6 && hour < 12) return BREAKFAST;
            if (hour >= 12 && hour < 18) return LUNCH;
            if (hour >= 18) return DINNER;
            return LATE_NIGHT;
        }
    }

    public enum HistoryMealSlot {
        @JsonProperty("breakfast") BREAKFAST, @JsonProperty("lunch") LUNCH, @JsonProperty("dinner") DINNER,
        @JsonProperty("snack") SNACK, @JsonProperty("late_night") LATE_NIGHT
    }

    public enum VerificationStatus {
        @JsonProperty("verified") VERIFIED, @JsonProperty("popular_around") POPULAR_AROUND, @JsonProperty("none") NONE
    }

    public enum UnverifiedReason {
        @JsonProperty("no_coordinates") NO_COORDINATES, @JsonProperty("not_checked") NOT_CHECKED,
        @JsonProperty("no_match_found") NO_MATCH_FOUND, @JsonProperty("user_limit_reached") USER_LIMIT_REACHED,
        @JsonProperty("global_cap_reached") GLOBAL_CAP_REACHED, @JsonProperty("provider_unavailable") PROVIDER_UNAVAILABLE,
        @JsonProperty("places_disabled") PLACES_DISABLED
    }

    public enum ReasonCode {
        DIET_MATCH, SPICE_MATCH, FAVORITE_CUISINE, FAVORITE_PROTEIN, LIKED_IN_TASTE_TEST, SIMILAR_TO_LOVED,
        LOVED_BEFORE, FITS_COOKING_TIME, FITS_SKILL, REFINE_APPLIED, FRESH_CHOICE, FOUND_NEARBY
    }

    public enum EvidenceSource {
        @JsonProperty("profile") PROFILE, @JsonProperty("history") HISTORY,
        @JsonProperty("recent_suggestions") RECENT_SUGGESTIONS, @JsonProperty("refine") REFINE, @JsonProperty("places") PLACES
    }

    public enum ReportReason {
        @JsonProperty("offensive") OFFENSIVE, @JsonProperty("violates_my_diet") VIOLATES_MY_DIET,
        @JsonProperty("unsafe_or_wrong") UNSAFE_OR_WRONG, @JsonProperty("other") OTHER
    }
}

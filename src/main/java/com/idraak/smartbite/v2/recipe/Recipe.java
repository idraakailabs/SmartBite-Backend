package com.idraak.smartbite.v2.recipe;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Origin;

import java.time.Instant;
import java.util.List;

/** The recipe as returned by {@code POST /api/v2/recipes} (openapi RecipeResponse) and as cached (full, unlocked). */
public record Recipe(
        String recipeId,
        String dish,
        Origin origin,
        int servings,
        Difficulty difficulty,
        Times times,
        List<IngredientGroup> ingredientGroups,
        List<Step> steps,
        int totalSteps,
        boolean locked,
        String lockedReason,
        List<String> tips,
        LocalVariant localVariant,
        String safetyNote,
        boolean isPremium,
        boolean cached,
        Instant generatedAt
) {
    public static final String SAFETY_NOTE = "This recipe was written by AI. Check every ingredient for allergens, "
            + "and cook meat, poultry and eggs until they're cooked through.";

    public record Times(int prepMinutes, int cookMinutes, int restMinutes, int totalMinutes) {}

    public record IngredientGroup(String title, List<Ingredient> items) {}

    public record Ingredient(String item, String displayQuantity, Double quantity, String unit, Metric metric, String notes,
                             boolean optional, boolean isSubstitution, String substitutionFor, List<Substitute> substitutes) {}

    public record Metric(Double quantity, MetricUnit unit) {}

    public enum MetricUnit {
        @JsonProperty("g") G, @JsonProperty("kg") KG, @JsonProperty("ml") ML, @JsonProperty("l") L,
        @JsonProperty("piece") PIECE, @JsonProperty("pinch") PINCH, @JsonProperty("to_taste") TO_TASTE
    }

    public record Substitute(String item, String note) {}

    public record Step(int number, String instruction, List<String> keyQuantities, Integer prepMinutes, Integer cookMinutes,
                       Integer restMinutes, Integer timerSeconds) {}

    public record LocalVariant(String label, List<IngredientChange> ingredientChanges, List<StepChange> stepChanges) {}

    public record IngredientChange(String replace, String with, String note) {}

    public record StepChange(int stepNumber, String instruction) {}

    /** The view for one caller: free users get the preview when the gate is on (D-014). */
    public Recipe forCaller(boolean premium, boolean gateOn, int previewSteps, boolean fromCache) {
        boolean lock = gateOn && !premium && steps.size() > previewSteps;
        if (!lock) {
            return new Recipe(recipeId, dish, origin, servings, difficulty, times, ingredientGroups, steps, steps.size(), false,
                    null, tips, localVariant, SAFETY_NOTE, premium, fromCache, generatedAt);
        }
        LocalVariant variant = localVariant == null ? null
                : new LocalVariant(localVariant.label(), localVariant.ingredientChanges(), List.of());
        return new Recipe(recipeId, dish, origin, servings, difficulty, times, ingredientGroups,
                List.copyOf(steps.subList(0, previewSteps)), steps.size(), true, "PREMIUM_REQUIRED", List.of(), variant,
                SAFETY_NOTE, false, fromCache, generatedAt);
    }
}

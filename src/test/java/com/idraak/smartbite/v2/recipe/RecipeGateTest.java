package com.idraak.smartbite.v2.recipe;

import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Origin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeGateTest {

    static Recipe full(int steps) {
        List<Recipe.Step> s = new java.util.ArrayList<>();
        for (int i = 1; i <= steps; i++) s.add(new Recipe.Step(i, "step " + i, List.of(), null, null, null, null));
        return new Recipe("rcp_x", "Dish", Origin.of("Pakistani", null), 2, Difficulty.EASY, new Recipe.Times(1, 1, 0, 2),
                List.of(new Recipe.IngredientGroup("Ingredients", List.of())), s, steps, false, null, List.of("tip"), null,
                Recipe.SAFETY_NOTE, false, false, Instant.EPOCH);
    }

    @Test
    void gateOffMeansEveryoneGetsTheFullRecipe() {
        Recipe r = full(5).forCaller(false, false, 2, false);
        assertThat(r.locked()).isFalse();
        assertThat(r.steps()).hasSize(5);
        assertThat(r.tips()).containsExactly("tip");
    }

    @Test
    void shortRecipesAreNotLocked() {
        assertThat(full(2).forCaller(false, true, 2, false).locked()).isFalse();
    }

    @Test
    void lockedPreviewKeepsIngredientsAndNameAndCountsAllSteps() {
        Recipe r = full(6).forCaller(false, true, 2, true);
        assertThat(r.locked()).isTrue();
        assertThat(r.steps()).hasSize(2);
        assertThat(r.totalSteps()).isEqualTo(6);
        assertThat(r.dish()).isEqualTo("Dish");
        assertThat(r.ingredientGroups()).hasSize(1);
        assertThat(r.tips()).isEmpty();
        assertThat(r.cached()).isTrue();
    }

    @Test
    void cacheKeyIgnoresCaseAndPunctuationButNotServings() {
        RecipeRequest a = new RecipeRequest("Chicken Karahi", new RecipeRequest.OriginInput("Pakistani", "Lahori"), 4, null, null);
        RecipeRequest b = new RecipeRequest("chicken  karahi!", new RecipeRequest.OriginInput("pakistani", "lahori"), 4, null, null);
        RecipeRequest c = new RecipeRequest("Chicken Karahi", new RecipeRequest.OriginInput("Pakistani", "Lahori"), 2, null, null);
        assertThat(RecipeService.cacheKey(a, "v1")).isEqualTo(RecipeService.cacheKey(b, "v1"))
                .isNotEqualTo(RecipeService.cacheKey(c, "v1")).isNotEqualTo(RecipeService.cacheKey(a, "v2"));
    }
}

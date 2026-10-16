package com.idraak.smartbite.v2.recipe;

import com.idraak.smartbite.v2.ai.ProviderChain.UnusableOutputException;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real models don't always follow the schema exactly. On staging (2026-10-16) every provider's recipe was rejected
 * as "missing ingredients, steps, difficulty or total time"; these are the variants the parser now accepts.
 */
class RecipeParserTest {

    final ObjectMapper mapper = JsonMapper.builder().build();

    RecipeParser.Parsed parse(String json, Diet diet) {
        return RecipeParser.parse(json, diet, "Chicken Karahi", mapper);
    }

    @Test
    void theSchemaAsSpecifiedParses() {
        RecipeParser.Parsed p = parse("""
                {"diet_conflict":false,"difficulty":"medium","times":{"prep_minutes":15,"cook_minutes":35,"rest_minutes":0,"total_minutes":50},
                 "ingredient_groups":[{"title":"For the karahi","items":[{"item":"Chicken","display_quantity":"1 kg","quantity":1,"unit":"kg"}]}],
                 "steps":[{"number":1,"instruction":"Heat the oil.","key_quantities":["3 tbsp oil"],"cook_minutes":2}]}
                """, Diet.NON_VEGETARIAN);

        assertThat(p.difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(p.times().totalMinutes()).isEqualTo(50);
        assertThat(p.groups()).hasSize(1);
        assertThat(p.steps()).extracting(Recipe.Step::instruction).containsExactly("Heat the oil.");
    }

    @Test
    void commonVariantsAreAccepted() {
        RecipeParser.Parsed p = parse("""
                {"difficulty":"Moderate","times":{"prep_minutes":"15","cook_minutes":30.0,"total_minutes":"45 min"},
                 "ingredients":[{"name":"Chicken","display_quantity":"1 kg"},{"item":"Tomatoes","display_quantity":"4"}],
                 "steps":["Heat the oil.","Add the chicken and fry for 8 minutes."]}
                """, Diet.NON_VEGETARIAN);

        assertThat(p.difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(p.times().totalMinutes()).isEqualTo(45);
        assertThat(p.times().prepMinutes()).isEqualTo(15);
        assertThat(p.times().cookMinutes()).isEqualTo(30);
        assertThat(p.groups()).singleElement().satisfies(g -> {
            assertThat(g.title()).isEqualTo("Ingredients");
            assertThat(g.items()).extracting(Recipe.Ingredient::item).containsExactly("Chicken", "Tomatoes");
        });
        assertThat(p.steps()).extracting(Recipe.Step::number).containsExactly(1, 2);
    }

    @Test
    void aMissingTotalIsWorkedOutAndAMissingDifficultyDefaultsToMedium() {
        RecipeParser.Parsed p = parse("""
                {"times":{"prep_minutes":10,"cook_minutes":25,"rest_minutes":5},
                 "ingredient_groups":[{"items":[{"item":"Rice"}]}],
                 "steps":[{"instruction":"Cook the rice."}]}
                """, Diet.NON_VEGETARIAN);

        assertThat(p.times().totalMinutes()).isEqualTo(40);
        assertThat(p.difficulty()).isEqualTo(Difficulty.MEDIUM);
    }

    @Test
    void whatIsStillMissingIsNamed() {
        assertThatThrownBy(() -> parse("""
                {"difficulty":"easy","ingredient_groups":[{"items":[{"item":"Rice"}]}]}
                """, Diet.NON_VEGETARIAN))
                .isInstanceOf(UnusableOutputException.class)
                .hasMessage("recipe missing steps, total time");
    }

    @Test
    void theDietCheckStillAppliesToAFlatIngredientList() {
        assertThatThrownBy(() -> parse("""
                {"difficulty":"easy","times":{"total_minutes":30},
                 "ingredients":[{"item":"Chicken thighs"},{"item":"Onion"}],
                 "steps":["Cook it."]}
                """, Diet.VEGETARIAN))
                .isInstanceOf(UnusableOutputException.class)
                .hasMessage("recipe fails the diet check");
    }
}

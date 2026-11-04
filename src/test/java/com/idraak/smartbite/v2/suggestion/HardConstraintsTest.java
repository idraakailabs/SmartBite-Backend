package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.model.Enums.Category;
import com.idraak.smartbite.v2.model.Enums.CookingTime;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.MealSlot;
import com.idraak.smartbite.v2.model.Enums.ProfileStatus;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.Area;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.RefineOptions;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.TasteProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-027: the request semantics of the constraint contract and the server-side hard-constraint check. */
class HardConstraintsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Area CITY = new Area(null, null, null, null, "Lahore", "Pakistan", null);

    static TasteProfile profile(Diet diet, Boolean explicit, List<String> disliked) {
        return new TasteProfile(diet, explicit, null, null, null, disliked, null, null, CookingTime.UP_TO_60, null, 2);
    }

    static MealSuggestionRequest request(Intent intent, TasteProfile p, RefineOptions refine, ProfileStatus status,
                                         List<String> required, Integer maxMinutes) {
        return new MealSuggestionRequest(intent, "Asia/Karachi", CITY, p, null, null, List.of("Beef Nihari"), refine,
                status, required, maxMinutes);
    }

    static MealSuggestionRequest legacy(Intent intent, RefineOptions refine) {
        return request(intent, profile(Diet.NON_VEGETARIAN, null, null), refine, null, null, null);
    }

    static RefineOptions quicker() {
        return new RefineOptions(true, null, null, null, null, null);
    }

    static Candidate dish(String name, String cuisine, Diet diet, boolean meat, String protein, Integer minutes, String... ingredients) {
        return new Candidate(name, "d", Category.OTHER, cuisine, null, diet, meat, false, false, SpiceLevel.MEDIUM, protein,
                List.of(ingredients), minutes, Difficulty.EASY, null);
    }

    static String rejection(Candidate c, MealSuggestionRequest r) {
        return SuggestionGenerator.rejection(c, r, SuggestionService.effectiveDiet(r), List.of());
    }

    // ---- request semantics ----

    @Test
    void requiredCuisinesApplyToEveryIntentAndMergeWithTheLegacyAnywhereCuisine() {
        var p = profile(Diet.NON_VEGETARIAN, true, null);
        assertThat(request(Intent.EAT_OUT, p, null, ProfileStatus.COMPLETE, List.of("Chinese"), null).effectiveRequiredCuisines())
                .containsExactly("Chinese");
        var anywhere = request(Intent.ANYWHERE, p, new RefineOptions(null, null, null, null, null, "Thai, turkish"),
                ProfileStatus.COMPLETE, List.of("Turkish", " Thai "), null);
        assertThat(anywhere.effectiveRequiredCuisines()).containsExactly("Turkish", "Thai");
        assertThat(anywhere.cuisinesStrict()).isTrue();
        // refine.cuisine alone stays required only for Anywhere, and keeps its legacy (non-strict) fallback
        var legacyAnywhere = legacy(Intent.ANYWHERE, new RefineOptions(null, null, null, null, null, "Turkish"));
        assertThat(legacyAnywhere.effectiveRequiredCuisines()).containsExactly("Turkish");
        assertThat(legacyAnywhere.cuisinesStrict()).isFalse();
        assertThat(legacy(Intent.COOK, new RefineOptions(null, null, null, null, null, "Turkish")).effectiveRequiredCuisines()).isEmpty();
    }

    @Test
    void theTimeLimitIsMaxTotalMinutesCappedByQuickerAndNeverAppliesToEatOut() {
        var p = profile(Diet.NON_VEGETARIAN, true, null);
        assertThat(request(Intent.COOK, p, null, ProfileStatus.COMPLETE, null, 45).effectiveMaxMinutes()).isEqualTo(45);
        assertThat(request(Intent.COOK, p, quicker(), ProfileStatus.COMPLETE, null, 45).effectiveMaxMinutes()).isEqualTo(30);
        assertThat(request(Intent.COOK, p, null, ProfileStatus.COMPLETE, null, null).effectiveMaxMinutes()).isNull();
        assertThat(legacy(Intent.COOK, quicker()).effectiveMaxMinutes()).isEqualTo(30);
        assertThat(legacy(Intent.ANYWHERE, quicker()).effectiveMaxMinutes()).isNull();
        assertThat(request(Intent.ANYWHERE, p, null, ProfileStatus.COMPLETE, null, 40).effectiveMaxMinutes()).isEqualTo(40);
        assertThat(request(Intent.EAT_OUT, p, quicker(), ProfileStatus.COMPLETE, null, 20).effectiveMaxMinutes()).isNull();
    }

    @Test
    void onlyEatOutOnTheConstraintContractMayHaveNoTime() {
        var p = profile(Diet.NON_VEGETARIAN, true, null);
        assertThat(request(Intent.EAT_OUT, p, null, ProfileStatus.PARTIAL, null, null).timeMayBeNull()).isTrue();
        assertThat(legacy(Intent.EAT_OUT, null).timeMayBeNull()).isFalse();
        assertThat(request(Intent.COOK, p, null, ProfileStatus.COMPLETE, null, null).timeMayBeNull()).isFalse();
        assertThat(request(Intent.ANYWHERE, p, null, ProfileStatus.COMPLETE, null, null).timeMayBeNull()).isFalse();
    }

    // ---- hard constraints on each candidate ----

    @Test
    void dietIsCheckedAgainstTheNameIngredientsAndPrimaryProtein() {
        var veg = request(Intent.COOK, profile(Diet.VEGETARIAN, true, null), null, ProfileStatus.COMPLETE, null, null);
        assertThat(rejection(dish("Garden Pulao", "Pakistani", Diet.VEGETARIAN, false, "Chicken", 40, "Rice"), veg)).isEqualTo("diet");
        assertThat(rejection(dish("Garden Pulao", "Pakistani", Diet.VEGETARIAN, false, "Peas", 40, "Rice"), veg)).isNull();
        var vegToday = request(Intent.COOK, profile(Diet.NON_VEGETARIAN, false, null),
                new RefineOptions(null, null, true, null, null, null), ProfileStatus.EMPTY, null, null);
        assertThat(rejection(dish("Chicken Karahi", "Pakistani", Diet.NON_VEGETARIAN, true, "Chicken", 40, "Chicken"), vegToday)).isEqualTo("diet");
    }

    @Test
    void dislikedFoodsAndExcludedDishesAreRejected() {
        var r = request(Intent.COOK, profile(Diet.NON_VEGETARIAN, true, List.of("Mushroom")), null, ProfileStatus.COMPLETE, null, null);
        assertThat(rejection(dish("Creamy Pasta", "Italian", Diet.VEGETARIAN, false, "Mushrooms", 30, "Pasta"), r)).isEqualTo("disliked");
        assertThat(rejection(dish("Beef Nihari", "Pakistani", Diet.NON_VEGETARIAN, true, "Beef", 240, "Beef"), r)).isEqualTo("excluded");
    }

    @Test
    void requiredCuisinesAreExclusiveAndStrictlyMatched() {
        var p = profile(Diet.NON_VEGETARIAN, true, null);
        var eatOut = request(Intent.EAT_OUT, p, null, ProfileStatus.COMPLETE, List.of("Chinese"), null);
        assertThat(rejection(dish("Chicken Karahi", "Pakistani", Diet.NON_VEGETARIAN, true, "Chicken", 40, "Chicken"), eatOut)).isEqualTo("cuisine");
        assertThat(rejection(dish("Chicken Manchurian", "Indo-Chinese", Diet.NON_VEGETARIAN, true, "Chicken", 40, "Chicken"), eatOut)).isNull();
        // strict: a broader cuisine doesn't satisfy a narrower required one (the app would drop it)
        var northernThai = request(Intent.ANYWHERE, p, null, ProfileStatus.COMPLETE, List.of("Northern Thai"), null);
        assertThat(rejection(dish("Pad Thai", "Thai", Diet.NON_VEGETARIAN, true, "Shrimp", 30, "Noodles"), northernThai)).isEqualTo("cuisine");
    }

    @Test
    void cookAndAnywherePicksNeedATimeWithinTheLimit() {
        var p = profile(Diet.NON_VEGETARIAN, true, null);
        var cook = request(Intent.COOK, p, quicker(), ProfileStatus.COMPLETE, null, 45);
        assertThat(rejection(dish("Mutton Pulao", "Pakistani", Diet.NON_VEGETARIAN, true, "Mutton", 50, "Rice"), cook)).isEqualTo("time_over_limit");
        assertThat(rejection(dish("Egg Fried Rice", "Chinese", Diet.VEGETARIAN, false, "Eggs", 30, "Rice"), cook)).isNull();
        assertThat(rejection(dish("Egg Fried Rice", "Chinese", Diet.VEGETARIAN, false, "Eggs", null, "Rice"), cook)).isEqualTo("time_missing");
        var anywhere = request(Intent.ANYWHERE, p, null, ProfileStatus.COMPLETE, null, null);
        assertThat(rejection(dish("Paella", "Spanish", Diet.NON_VEGETARIAN, true, "Shrimp", null, "Rice"), anywhere)).isEqualTo("time_missing");
    }

    @Test
    void eatOutMayHaveNoTimeOnlyOnTheConstraintContract() {
        Candidate noTime = dish("Chicken Karahi", "Pakistani", Diet.NON_VEGETARIAN, true, "Chicken", null, "Chicken");
        var p = profile(Diet.NON_VEGETARIAN, true, null);
        assertThat(rejection(noTime, request(Intent.EAT_OUT, p, null, ProfileStatus.COMPLETE, null, null))).isNull();
        assertThat(rejection(noTime, legacy(Intent.EAT_OUT, null))).isEqualTo("time_missing");
    }

    // ---- the prompt ----

    static JsonNode promptData(MealSuggestionRequest r) {
        String user = SuggestionPrompt.user(r, SuggestionService.effectiveDiet(r), null, MealSlot.DINNER,
                LocalDate.of(2026, 11, 4), 3, List.of(), JSON);
        return JSON.readTree(user.substring(user.indexOf('{')));
    }

    @Test
    void thePromptCarriesTheProfileStatusAndADefaultedDiet() {
        JsonNode data = promptData(request(Intent.EAT_OUT, profile(Diet.NON_VEGETARIAN, false, null), null,
                ProfileStatus.EMPTY, List.of("Chinese"), null));
        assertThat(data.path("profile_status").asString()).isEqualTo("empty");
        assertThat(data.path("diet_is_explicit").asBoolean(true)).isFalse();
        assertThat(data.path("required_cuisines").get(0).asString()).isEqualTo("Chinese");
        assertThat(data.path("total_time_may_be_null").asBoolean()).isTrue();
        assertThat(data.has("max_total_minutes")).isFalse();
    }

    @Test
    void theSystemPromptStaysShort() {
        // BE-BUG-015: a ~6,000-token system prompt (BE-028) made every provider time out or hit its limits in
        // production. Any longer prompt must be timed on staging with the real providers before it ships.
        assertThat(SuggestionPrompt.SYSTEM.length()).as("system prompt characters").isLessThan(4_000);
        assertThat(SuggestionPrompt.SYSTEM).contains("required_cuisines", "Reply in English with ONLY one JSON object");
    }

    @Test
    void aRealDietRuleIsNeverMarkedAsDefaulted() {
        // vegetarian_today makes the diet a real rule even when the profile's diet was a default
        JsonNode data = promptData(request(Intent.COOK, profile(Diet.NON_VEGETARIAN, false, null),
                new RefineOptions(null, null, true, null, null, null), ProfileStatus.PARTIAL, null, 40));
        assertThat(data.path("diet").asString()).isEqualTo("vegetarian");
        assertThat(data.has("diet_is_explicit")).isFalse();
        assertThat(data.path("max_total_minutes").asInt()).isEqualTo(40);
        assertThat(data.has("total_time_may_be_null")).isFalse();
    }

    @Test
    void aLegacyRequestGetsNoneOfTheNewPromptFields() {
        JsonNode data = promptData(legacy(Intent.EAT_OUT, null));
        assertThat(data.has("profile_status")).isFalse();
        assertThat(data.has("diet_is_explicit")).isFalse();
        assertThat(data.has("required_cuisines")).isFalse();
        assertThat(data.has("max_total_minutes")).isFalse();
        assertThat(data.has("total_time_may_be_null")).isFalse();
    }
}

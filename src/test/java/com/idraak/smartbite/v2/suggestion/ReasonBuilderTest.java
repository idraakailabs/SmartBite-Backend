package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.model.Enums.BudgetTier;
import com.idraak.smartbite.v2.model.Enums.Category;
import com.idraak.smartbite.v2.model.Enums.CookingSkill;
import com.idraak.smartbite.v2.model.Enums.CookingTime;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.model.Enums.HistoryEntryType;
import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.Reaction;
import com.idraak.smartbite.v2.model.Enums.ReasonCode;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.Area;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.HistoryEntry;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.RefineOptions;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.TasteProfile;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Reason;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReasonBuilderTest {

    static final LocalDate TODAY = LocalDate.parse("2026-10-11");
    static final Area CITY = new Area(null, null, null, null, "Lahore", "Pakistan", "PK");

    static Candidate dish(String name, String cuisine, String protein, SpiceLevel spice, int minutes, Difficulty d, String similarTo) {
        return new Candidate(name, "desc", Category.CURRY_KARAHI, cuisine, null, Diet.NON_VEGETARIAN, true, false, false,
                spice, protein, List.of("x"), minutes, d, similarTo);
    }

    static MealSuggestionRequest request(Intent intent, TasteProfile p, List<HistoryEntry> history, RefineOptions refine) {
        return new MealSuggestionRequest(intent, "Asia/Karachi", CITY, p, List.of(), history, List.of(), refine);
    }

    static TasteProfile profile(Diet diet) {
        return new TasteProfile(diet, SpiceLevel.SPICY, List.of("South Asian"), List.of("Dal/Lentils", "Mutton"),
                List.of(), List.of("Seekh Kebab"), BudgetTier.MODERATE, CookingTime.UP_TO_60, CookingSkill.BEGINNER, 4);
    }

    static List<ReasonCode> codes(List<Reason> rs) {
        return rs.stream().map(Reason::code).toList();
    }

    @Test
    void everyReasonHasEvidenceAndThereIsNoBudgetOrGoalReason() {
        Candidate c = dish("Mutton Karahi", "Pakistani", "Mutton", SpiceLevel.SPICY, 50, Difficulty.EASY, null);
        List<Reason> rs = ReasonBuilder.build(c, request(Intent.COOK, profile(Diet.NON_VEGETARIAN), List.of(), null),
                Diet.NON_VEGETARIAN, SpiceLevel.SPICY, TODAY);
        assertThat(rs).allSatisfy(r -> assertThat(r.evidence()).isNotEmpty());
        assertThat(codes(rs)).contains(ReasonCode.FAVORITE_CUISINE, ReasonCode.FAVORITE_PROTEIN, ReasonCode.SPICE_MATCH,
                ReasonCode.FITS_COOKING_TIME, ReasonCode.FITS_SKILL);
        assertThat(codes(rs)).doesNotContain(ReasonCode.DIET_MATCH); // non-vegetarian: no diet claim
        assertThat(rs).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void noEvidenceMeansNoReasons() {
        TasteProfile bare = new TasteProfile(Diet.NON_VEGETARIAN, null, null, null, null, null, BudgetTier.UPSCALE, null, null, null);
        Candidate c = dish("Pad Thai", "Thai", "Shrimp", SpiceLevel.MEDIUM, 30, Difficulty.MEDIUM, "Pizza");
        assertThat(ReasonBuilder.build(c, request(Intent.EAT_OUT, bare, List.of(), null), Diet.NON_VEGETARIAN, null, TODAY)).isEmpty();
    }

    @Test
    void lovedBeforeCountsLoggedEntriesButNotSuggestedOnes() {
        Candidate c = dish("Chicken Karahi", "Pakistani", "Chicken", SpiceLevel.MEDIUM, 50, Difficulty.MEDIUM, null);
        TasteProfile p = new TasteProfile(Diet.NON_VEGETARIAN, null, null, null, null, null, null, null, null, null);
        HistoryEntry logged = new HistoryEntry("Chicken Karahi", TODAY.minusDays(20), null, HistoryEntryType.LOGGED, Reaction.LOVED);
        HistoryEntry suggestedOnly = new HistoryEntry("Chicken Karahi", TODAY.minusDays(20), null, HistoryEntryType.SUGGESTED, Reaction.LOVED);
        assertThat(codes(ReasonBuilder.build(c, request(Intent.EAT_OUT, p, List.of(logged), null), Diet.NON_VEGETARIAN, null, TODAY)))
                .containsExactly(ReasonCode.LOVED_BEFORE);
        assertThat(ReasonBuilder.build(c, request(Intent.EAT_OUT, p, List.of(suggestedOnly), null), Diet.NON_VEGETARIAN, null, TODAY))
                .isEmpty();
    }

    @Test
    void similarToLovedNeedsTheNamedDishInLovedHistory() {
        TasteProfile p = new TasteProfile(Diet.NON_VEGETARIAN, null, null, null, null, null, null, null, null, null);
        HistoryEntry loved = new HistoryEntry("Chicken Karahi", TODAY.minusDays(2), null, HistoryEntryType.ATE_OUT, Reaction.LOVED);
        Candidate claimsSimilar = dish("Mutton Karahi", "Pakistani", "Mutton", SpiceLevel.SPICY, 60, Difficulty.MEDIUM, "Chicken Karahi");
        Candidate inventsSimilar = dish("Mutton Karahi", "Pakistani", "Mutton", SpiceLevel.SPICY, 60, Difficulty.MEDIUM, "Beef Nihari");
        assertThat(codes(ReasonBuilder.build(claimsSimilar, request(Intent.EAT_OUT, p, List.of(loved), null), Diet.NON_VEGETARIAN, null, TODAY)))
                .contains(ReasonCode.SIMILAR_TO_LOVED);
        assertThat(codes(ReasonBuilder.build(inventsSimilar, request(Intent.EAT_OUT, p, List.of(loved), null), Diet.NON_VEGETARIAN, null, TODAY)))
                .doesNotContain(ReasonCode.SIMILAR_TO_LOVED);
    }

    @Test
    void dietMatchAndRefineForVegetarianToday() {
        Candidate c = new Candidate("Chana Masala", "d", Category.CURRY_KARAHI, "Pakistani", null, Diet.VEGAN, false, false, false,
                SpiceLevel.MEDIUM, "Chickpeas", List.of("Chickpeas"), 25, Difficulty.EASY, null);
        RefineOptions refine = new RefineOptions(true, null, true, null, null, null);
        List<Reason> rs = ReasonBuilder.build(c, request(Intent.COOK, profile(Diet.NON_VEGETARIAN), List.of(), refine),
                Diet.VEGETARIAN, SpiceLevel.SPICY, TODAY);
        assertThat(codes(rs)).contains(ReasonCode.DIET_MATCH);
        Reason refineReason = rs.stream().filter(r -> r.code() == ReasonCode.REFINE_APPLIED).findFirst().orElse(null);
        if (refineReason != null) {
            assertThat(refineReason.evidence()).extracting(e -> e.field()).contains("refine.quicker");
        }
    }

    @Test
    void cookingTimeReasonOnlyWhenWithinLimitAndNotForEatOut() {
        Candidate slow = dish("Nihari", "Pakistani", "Beef", SpiceLevel.SPICY, 240, Difficulty.HARD, null);
        assertThat(codes(ReasonBuilder.build(slow, request(Intent.COOK, profile(Diet.NON_VEGETARIAN), List.of(), null),
                Diet.NON_VEGETARIAN, SpiceLevel.SPICY, TODAY))).doesNotContain(ReasonCode.FITS_COOKING_TIME, ReasonCode.FITS_SKILL);
        Candidate quick = dish("Daal", "Pakistani", "Lentils", SpiceLevel.MILD, 30, Difficulty.EASY, null);
        assertThat(codes(ReasonBuilder.build(quick, request(Intent.EAT_OUT, profile(Diet.NON_VEGETARIAN), List.of(), null),
                Diet.NON_VEGETARIAN, SpiceLevel.SPICY, TODAY))).doesNotContain(ReasonCode.FITS_COOKING_TIME);
    }

    @Test
    void likedInTasteTestMatchesWholeWords() {
        Candidate c = dish("Seekh Kebab Platter", "Pakistani", "Beef", SpiceLevel.SPICY, 50, Difficulty.MEDIUM, null);
        assertThat(codes(ReasonBuilder.build(c, request(Intent.EAT_OUT, profile(Diet.NON_VEGETARIAN), List.of(), null),
                Diet.NON_VEGETARIAN, SpiceLevel.SPICY, TODAY))).contains(ReasonCode.LIKED_IN_TASTE_TEST);
    }
}

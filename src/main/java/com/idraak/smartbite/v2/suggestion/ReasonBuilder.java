package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.model.Enums.CookingSkill;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.model.Enums.EvidenceSource;
import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.Reaction;
import com.idraak.smartbite.v2.model.Enums.ReasonCode;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest.HistoryEntry;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Evidence;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Reason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds "Why this pick" reasons by comparing dish attributes with request data (fixes BUG-021). Every reason
 * carries the evidence it rests on; with no evidence there is no reason. There are deliberately no budget, goal,
 * nutrition or calorie reasons. The model never writes reason text.
 */
public final class ReasonBuilder {

    static final int MAX_REASONS = 5;

    /** Strongest first. */
    static final List<ReasonCode> PRIORITY = List.of(
            ReasonCode.LOVED_BEFORE, ReasonCode.SIMILAR_TO_LOVED, ReasonCode.LIKED_IN_TASTE_TEST, ReasonCode.FOUND_NEARBY,
            ReasonCode.DIET_MATCH, ReasonCode.FAVORITE_CUISINE, ReasonCode.FAVORITE_PROTEIN, ReasonCode.SPICE_MATCH,
            ReasonCode.REFINE_APPLIED, ReasonCode.FITS_COOKING_TIME, ReasonCode.FITS_SKILL, ReasonCode.FRESH_CHOICE);

    /** Cuisine families: a dish from the right-hand side matches a profile cuisine on the left. */
    static final Map<String, Set<String>> CUISINE_ALIASES = Map.of(
            "south asian", Set.of("pakistani", "indian", "bangladeshi", "sri lankan", "nepali", "afghan"),
            "asian", Set.of("chinese", "japanese", "thai", "korean", "vietnamese", "indonesian", "malaysian", "filipino", "indo chinese"),
            "arabic", Set.of("lebanese", "levantine", "syrian", "emirati", "saudi", "gulf", "egyptian", "middle eastern", "yemeni", "jordanian"),
            "mediterranean", Set.of("greek", "turkish", "lebanese", "spanish", "levantine"),
            "continental", Set.of("french", "british", "german", "european", "american", "italian"),
            "mexican", Set.of("tex mex"));

    private ReasonBuilder() {
    }

    public static List<Reason> build(Candidate dish, MealSuggestionRequest r, Diet effectiveDiet, SpiceLevel spiceTarget, LocalDate today) {
        var p = r.profile();
        var refine = r.refineOrEmpty();
        List<Reason> reasons = new ArrayList<>();

        // LOVED_BEFORE / SIMILAR_TO_LOVED from eaten history (cooked, ate_out or logged; never suggested-only)
        Optional<HistoryEntry> lovedSame = r.historyOrEmpty().stream()
                .filter(h -> h.entryType().eaten() && h.reaction() == Reaction.LOVED && Text.sameDish(h.dish(), dish.name()))
                .findFirst();
        lovedSame.ifPresent(h -> reasons.add(reason(ReasonCode.LOVED_BEFORE, "You loved this before",
                ev(EvidenceSource.HISTORY, "history[" + r.historyOrEmpty().indexOf(h) + "]", h.dish() + " (loved)", dish.name()))));
        if (lovedSame.isEmpty() && dish.similarTo() != null && !Text.sameDish(dish.similarTo(), dish.name())) {
            Optional<HistoryEntry> lovedSimilar = r.historyOrEmpty().stream()
                    .filter(h -> h.entryType().eaten() && h.reaction() == Reaction.LOVED && Text.sameDish(h.dish(), dish.similarTo()))
                    .findFirst();
            if (lovedSimilar.isPresent()) {
                HistoryEntry h = lovedSimilar.get();
                reasons.add(reason(ReasonCode.SIMILAR_TO_LOVED, "Close to " + h.dish() + ", which you loved",
                        ev(EvidenceSource.HISTORY, "history[" + r.historyOrEmpty().indexOf(h) + "]", h.dish() + " (loved)", dish.category().name().toLowerCase())));
            } else {
                p.likedDishesOrEmpty().stream().filter(l -> Text.sameDish(l, dish.similarTo())).findFirst()
                        .filter(l -> !Text.dishMatches(l, dish.name()))
                        .ifPresent(l -> reasons.add(reason(ReasonCode.SIMILAR_TO_LOVED, "Close to " + l + ", which you picked in your taste test",
                                ev(EvidenceSource.PROFILE, "profile.liked_dishes", l, dish.category().name().toLowerCase()))));
            }
        }

        // LIKED_IN_TASTE_TEST
        p.likedDishesOrEmpty().stream().filter(l -> Text.dishMatches(l, dish.name())).findFirst()
                .ifPresent(l -> reasons.add(reason(ReasonCode.LIKED_IN_TASTE_TEST, "You picked " + l + " in your taste test",
                        ev(EvidenceSource.PROFILE, "profile.liked_dishes", l, dish.name()))));

        // DIET_MATCH only when the diet is restrictive
        if (effectiveDiet != Diet.NON_VEGETARIAN && dish.diet() != null && dish.diet().strictness() >= effectiveDiet.strictness()) {
            boolean fromRefine = refine.isVegetarianToday() && p.diet() == Diet.NON_VEGETARIAN;
            reasons.add(reason(ReasonCode.DIET_MATCH, "Fits your " + effectiveDiet.wire() + " diet",
                    ev(fromRefine ? EvidenceSource.REFINE : EvidenceSource.PROFILE,
                            fromRefine ? "refine.vegetarian_today" : "profile.diet", effectiveDiet.wire(), dish.diet().wire())));
        }

        // FAVORITE_CUISINE
        for (String fav : p.topCuisinesOrEmpty()) {
            if (cuisineMatches(fav, dish.cuisine())) {
                reasons.add(reason(ReasonCode.FAVORITE_CUISINE, "From " + dish.cuisine() + " cuisine, one of your favourites",
                        ev(EvidenceSource.PROFILE, "profile.top_cuisines", fav, dish.cuisine())));
                break;
            }
        }

        // FAVORITE_PROTEIN
        if (dish.primaryProtein() != null) {
            for (String fav : p.favoriteProteinsOrEmpty()) {
                if (proteinMatches(fav, dish.primaryProtein())) {
                    reasons.add(reason(ReasonCode.FAVORITE_PROTEIN, dish.primaryProtein() + " is one of your favourites",
                            ev(EvidenceSource.PROFILE, "profile.favorite_proteins", fav, dish.primaryProtein())));
                    break;
                }
            }
        }

        // SPICE_MATCH
        if (spiceTarget != null && dish.spiceLevel() == spiceTarget) {
            boolean milder = refine.isMilder();
            reasons.add(reason(ReasonCode.SPICE_MATCH, milder ? "Milder, as you asked" : spiceText(spiceTarget),
                    ev(milder ? EvidenceSource.REFINE : EvidenceSource.PROFILE, milder ? "refine.milder" : "profile.spice_tolerance",
                            spiceTarget.wire(), dish.spiceLevel().wire())));
        }

        // REFINE_APPLIED (quicker / milder / vegetarian today), one reason with up to 3 pieces of evidence
        List<Evidence> refineEvidence = new ArrayList<>();
        if (refine.isQuicker() && dish.totalTimeMinutes() <= 30) {
            refineEvidence.add(ev(EvidenceSource.REFINE, "refine.quicker", "30 min or less", dish.totalTimeMinutes() + " min"));
        }
        if (refine.isMilder() && spiceTarget != null && dish.spiceLevel().ordinal() <= spiceTarget.ordinal()) {
            refineEvidence.add(ev(EvidenceSource.REFINE, "refine.milder", spiceTarget.wire(), dish.spiceLevel().wire()));
        }
        if (refine.isVegetarianToday() && dish.diet() != null && dish.diet() != Diet.NON_VEGETARIAN) {
            refineEvidence.add(ev(EvidenceSource.REFINE, "refine.vegetarian_today", "vegetarian", dish.diet().wire()));
        }
        if (!refineEvidence.isEmpty()) {
            reasons.add(new Reason(ReasonCode.REFINE_APPLIED, "Matches today's tweaks", List.copyOf(refineEvidence)));
        }

        // FITS_COOKING_TIME (cook / anywhere) and FITS_SKILL (cook)
        if (r.intent() != Intent.EAT_OUT && p.cookingTime() != null && p.cookingTime().maxMinutes() != null
                && dish.totalTimeMinutes() <= p.cookingTime().maxMinutes()) {
            reasons.add(reason(ReasonCode.FITS_COOKING_TIME, "About " + dish.totalTimeMinutes() + " min, within your usual cooking time",
                    ev(EvidenceSource.PROFILE, "profile.cooking_time", p.cookingTime().wire(), dish.totalTimeMinutes() + " min (estimate)")));
        }
        if (r.intent() == Intent.COOK && p.cookingSkill() != null && p.cookingSkill() != CookingSkill.ADVANCED
                && dish.difficulty().ordinal() <= maxDifficulty(p.cookingSkill()).ordinal()) {
            reasons.add(reason(ReasonCode.FITS_SKILL, "Doable at your skill level",
                    ev(EvidenceSource.PROFILE, "profile.cooking_skill", p.cookingSkill().name().toLowerCase(), dish.difficulty().name().toLowerCase())));
        }

        // FRESH_CHOICE: there is history, and the dish isn't among last 7 days' meals or recent suggestions
        List<HistoryEntry> recentEaten = r.historyOrEmpty().stream()
                .filter(h -> h.entryType().eaten() && !h.date().isBefore(today.minusDays(7))).toList();
        if (!recentEaten.isEmpty()
                && recentEaten.stream().noneMatch(h -> Text.sameDish(h.dish(), dish.name()))
                && r.recentSuggestionsOrEmpty().stream().noneMatch(s -> Text.sameDish(s, dish.name()))) {
            reasons.add(reason(ReasonCode.FRESH_CHOICE, "Something different from your recent meals",
                    ev(EvidenceSource.HISTORY, "history", recentEaten.size() + " meals in the last 7 days", dish.name())));
        }

        return top(reasons);
    }

    public static List<Reason> top(List<Reason> reasons) {
        return reasons.stream()
                .sorted(Comparator.comparingInt(x -> PRIORITY.indexOf(x.code())))
                .limit(MAX_REASONS)
                .toList();
    }

    static boolean cuisineMatches(String favourite, String dishCuisine) {
        String f = Text.norm(favourite);
        String d = Text.norm(dishCuisine);
        if (f.isEmpty() || d.isEmpty()) return false;
        if (f.equals(d)) return true;
        Set<String> family = CUISINE_ALIASES.get(f);
        return family != null && family.contains(d);
    }

    static boolean proteinMatches(String favourite, String dishProtein) {
        for (String token : favourite.split("/")) {
            String t = Text.norm(token);
            if (t.length() >= 3 && (Text.containsPhrase(dishProtein, t) || Text.containsPhrase(t, Text.norm(dishProtein)))) return true;
        }
        return false;
    }

    static Difficulty maxDifficulty(CookingSkill skill) {
        return switch (skill) {
            case BEGINNER -> Difficulty.EASY;
            case INTERMEDIATE -> Difficulty.MEDIUM;
            case ADVANCED -> Difficulty.HARD;
        };
    }

    static String spiceText(SpiceLevel s) {
        return switch (s) {
            case MILD -> "Mild, the way you like it";
            case MEDIUM -> "Medium spice, the way you like it";
            case SPICY -> "Spicy, the way you like it";
            case EXTRA_SPICY -> "Extra spicy, the way you like it";
        };
    }

    static Reason reason(ReasonCode code, String text, Evidence evidence) {
        return new Reason(code, text.length() > 120 ? text.substring(0, 117) + "..." : text, List.of(evidence));
    }

    static Evidence ev(EvidenceSource source, String field, String userValue, String dishValue) {
        return new Evidence(source, field, clip(userValue), clip(dishValue));
    }

    private static String clip(String s) {
        return s == null || s.length() <= 80 ? s : s.substring(0, 80);
    }
}

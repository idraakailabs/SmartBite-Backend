package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.ai.Deadline;
import com.idraak.smartbite.v2.ai.ProviderChain;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.MealSlot;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt -> provider chain -> parse -> safety filters (diet, dislikes, recent, excluded). If fewer than 3 remain,
 * regenerates once excluding what was dropped, then returns 1 to 3 safe candidates. Zero -> GENERATION_FAILED.
 * Cuisines chosen for an Anywhere set are required (BE-BUG-009); only if no candidate matches them are
 * off-cuisine but otherwise safe candidates returned, marked degraded, rather than failing the set. Cuisines sent
 * as {@code required_cuisines} (BE-027) are exclusive: no off-cuisine fallback, so a set the app would reject is
 * never returned or counted. Every check runs before quota is committed.
 */
public class SuggestionGenerator {

    private static final Logger log = LoggerFactory.getLogger(SuggestionGenerator.class);
    static final int WANTED = 3;
    static final int MAX_TOKENS = 2500;

    /** {@code compactPrompt}: the set was made with the BE-030 compact prompt ({@code suggest-v3.0}). */
    public record Output(List<Candidate> candidates, boolean degraded, boolean compactPrompt) {}

    private final ProviderChain chain;
    private final SuggestionParser parser;
    private final ObjectMapper mapper;
    private final java.util.function.BooleanSupplier compactPrompt;

    public SuggestionGenerator(ProviderChain chain, ObjectMapper mapper) {
        this(chain, mapper, () -> false);
    }

    /** @param compactPrompt read once per set: use the BE-030 compact prompt, lean data and low reasoning */
    public SuggestionGenerator(ProviderChain chain, ObjectMapper mapper, java.util.function.BooleanSupplier compactPrompt) {
        this.compactPrompt = compactPrompt;
        this.chain = chain;
        this.parser = new SuggestionParser(mapper);
        this.mapper = mapper;
    }

    public Output generate(MealSuggestionRequest r, Diet diet, SpiceLevel spice, MealSlot slot, LocalDate today, Deadline deadline) {
        List<Candidate> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<Candidate> offCuisine = new ArrayList<>();
        boolean degraded = false;
        boolean compact = compactPrompt.getAsBoolean();
        String system = compact ? SuggestionPrompt.SYSTEM_V3 : SuggestionPrompt.SYSTEM;
        for (int round = 0; round < 2 && kept.size() < WANTED; round++) {
            if (round == 1 && !deadline.hasAtLeast(java.time.Duration.ofSeconds(5))) break;
            List<String> exclusions = new ArrayList<>(dropped);
            kept.forEach(c -> exclusions.add(c.name()));
            String user = SuggestionPrompt.user(r, diet, spice, slot, today, WANTED, exclusions, mapper, compact);
            ProviderChain.Generated<List<Candidate>> g;
            try {
                g = chain.generate(system, user, MAX_TOKENS, deadline, parser::parse, compact);
            } catch (ApiException e) {
                if (round == 0 || kept.isEmpty()) throw e;
                break; // keep what the first round produced
            }
            degraded |= g.degraded();
            for (Candidate c : g.value()) {
                if (kept.size() >= WANTED) break;
                String reason = rejection(c, r, diet, kept);
                if (reason == null) {
                    kept.add(c);
                } else {
                    if (reason.equals("cuisine")) offCuisine.add(c);
                    dropped.add(c.name());
                    log.info("suggestion dropped reason={}", reason);
                }
            }
        }
        if (kept.isEmpty() && !offCuisine.isEmpty() && !r.cuisinesStrict()) {
            log.info("suggestion set has no pick in the required cuisines; returning off-cuisine picks");
            List<Candidate> fallback = new ArrayList<>();
            for (Candidate c : offCuisine) {
                if (fallback.size() < WANTED && fallback.stream().noneMatch(k -> Text.sameDish(k.name(), c.name()))) fallback.add(c);
            }
            return new Output(List.copyOf(fallback), true, compact);
        }
        if (kept.isEmpty()) throw new ApiException(ErrorCode.GENERATION_FAILED);
        return new Output(List.copyOf(kept), degraded, compact);
    }

    /**
     * The hard constraints every returned pick must meet (BE-027): diet, disliked foods, recent and excluded dishes,
     * no duplicates, the required cuisines, a time estimate (unless the request allows none) and the time limit.
     *
     * @return why a candidate is rejected, or null if it's acceptable
     */
    static String rejection(Candidate c, MealSuggestionRequest r, Diet diet, List<Candidate> kept) {
        List<String> checked = new ArrayList<>(c.keyIngredients());
        if (c.primaryProtein() != null) checked.add(c.primaryProtein());
        if (!DietFilter.allows(diet, c.declared(), c.name(), checked)) return "diet";
        String text = c.name() + " | " + String.join(" | ", checked);
        for (String disliked : r.profile().dislikedFoodsOrEmpty()) {
            if (Text.containsPhrase(text, disliked)) return "disliked";
        }
        for (String s : r.recentSuggestionsOrEmpty()) if (Text.sameDish(s, c.name())) return "recent";
        for (String s : r.sessionExcludedOrEmpty()) if (Text.sameDish(s, c.name())) return "excluded";
        for (Candidate k : kept) if (Text.sameDish(k.name(), c.name())) return "duplicate";
        if (c.totalTimeMinutes() == null && !r.timeMayBeNull()) return "time_missing";
        Integer limit = r.effectiveMaxMinutes();
        if (limit != null && c.totalTimeMinutes() != null && c.totalTimeMinutes() > limit) return "time_over_limit";
        List<String> required = r.effectiveRequiredCuisines();
        if (!required.isEmpty() && required.stream().noneMatch(want -> matchesCuisine(c, want, r.cuisinesStrict()))) {
            return "cuisine";
        }
        return null;
    }

    /**
     * "Turkish" matches a cuisine of "Turkish", "Turkish (Anatolian)" or "Ottoman Turkish", or a region naming it.
     * Not strict (legacy {@code refine.cuisine}) also accepts a cuisine named inside the wanted one ("Thai" for
     * "Northern Thai"); strict ({@code required_cuisines}) doesn't, matching the app's own check (Android TECH-022).
     */
    static boolean matchesCuisine(Candidate c, String wanted, boolean strict) {
        String origin = c.cuisine() + " | " + (c.region() == null ? "" : c.region());
        return Text.containsPhrase(origin, wanted) || (!strict && Text.containsPhrase(wanted, c.cuisine()));
    }
}

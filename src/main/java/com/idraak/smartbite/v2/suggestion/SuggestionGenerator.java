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
 * off-cuisine but otherwise safe candidates returned, marked degraded, rather than failing the set.
 */
public class SuggestionGenerator {

    private static final Logger log = LoggerFactory.getLogger(SuggestionGenerator.class);
    static final int WANTED = 3;
    static final int MAX_TOKENS = 2500;

    public record Output(List<Candidate> candidates, boolean degraded) {}

    private final ProviderChain chain;
    private final SuggestionParser parser;
    private final ObjectMapper mapper;

    public SuggestionGenerator(ProviderChain chain, ObjectMapper mapper) {
        this.chain = chain;
        this.parser = new SuggestionParser(mapper);
        this.mapper = mapper;
    }

    public Output generate(MealSuggestionRequest r, Diet diet, SpiceLevel spice, MealSlot slot, LocalDate today, Deadline deadline) {
        List<Candidate> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<Candidate> offCuisine = new ArrayList<>();
        boolean degraded = false;
        for (int round = 0; round < 2 && kept.size() < WANTED; round++) {
            if (round == 1 && !deadline.hasAtLeast(java.time.Duration.ofSeconds(5))) break;
            List<String> exclusions = new ArrayList<>(dropped);
            kept.forEach(c -> exclusions.add(c.name()));
            String user = SuggestionPrompt.user(r, diet, spice, slot, today, WANTED, exclusions, mapper);
            ProviderChain.Generated<List<Candidate>> g;
            try {
                g = chain.generate(SuggestionPrompt.SYSTEM, user, MAX_TOKENS, deadline, parser::parse);
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
        if (kept.isEmpty() && !offCuisine.isEmpty()) {
            log.info("suggestion set has no pick in the required cuisines; returning off-cuisine picks");
            List<Candidate> fallback = new ArrayList<>();
            for (Candidate c : offCuisine) {
                if (fallback.size() < WANTED && fallback.stream().noneMatch(k -> Text.sameDish(k.name(), c.name()))) fallback.add(c);
            }
            return new Output(List.copyOf(fallback), true);
        }
        if (kept.isEmpty()) throw new ApiException(ErrorCode.GENERATION_FAILED);
        return new Output(List.copyOf(kept), degraded);
    }

    /** @return why a candidate is rejected, or null if it's acceptable */
    static String rejection(Candidate c, MealSuggestionRequest r, Diet diet, List<Candidate> kept) {
        if (!DietFilter.allows(diet, c.declared(), c.name(), c.keyIngredients())) return "diet";
        String text = c.name() + " | " + String.join(" | ", c.keyIngredients());
        for (String disliked : r.profile().dislikedFoodsOrEmpty()) {
            if (Text.containsPhrase(text, disliked)) return "disliked";
        }
        for (String s : r.recentSuggestionsOrEmpty()) if (Text.sameDish(s, c.name())) return "recent";
        for (String s : r.sessionExcludedOrEmpty()) if (Text.sameDish(s, c.name())) return "excluded";
        for (Candidate k : kept) if (Text.sameDish(k.name(), c.name())) return "duplicate";
        List<String> required = r.requiredCuisines();
        if (!required.isEmpty() && required.stream().noneMatch(want -> matchesCuisine(c, want))) return "cuisine";
        return null;
    }

    /** "Turkish" matches a cuisine of "Turkish", "Turkish (Anatolian)" or "Ottoman Turkish", or a region naming it. */
    static boolean matchesCuisine(Candidate c, String wanted) {
        String origin = c.cuisine() + " | " + (c.region() == null ? "" : c.region());
        return Text.containsPhrase(origin, wanted) || Text.containsPhrase(wanted, c.cuisine());
    }
}

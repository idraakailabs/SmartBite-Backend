package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.v2.model.Enums.Intent;
import com.idraak.smartbite.v2.model.Enums.UnverifiedReason;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Suggestion;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Verification;

import java.util.List;

/** Grounding when Places is disabled: eat-out picks are "Popular around <area>", others have no verification. */
public class NoPlacesAreaGrounding implements AreaGrounding {

    @Override
    public Result ground(String uid, MealSuggestionRequest r, List<Suggestion> suggestions) {
        if (r.intent() != Intent.EAT_OUT) {
            return new Result(suggestions.stream().map(s -> s.withReasonsAndVerification(s.reasons(), Verification.none())).toList(),
                    Labels.context(r, "skipped"));
        }
        UnverifiedReason reason = r.area().hasCoordinates() ? UnverifiedReason.PLACES_DISABLED : UnverifiedReason.NO_COORDINATES;
        return new Result(suggestions.stream()
                .map(s -> s.withReasonsAndVerification(s.reasons(), Labels.popularAround(r, s.name(), reason))).toList(),
                Labels.context(r, r.area().hasCoordinates() ? "unavailable" : "skipped"));
    }
}

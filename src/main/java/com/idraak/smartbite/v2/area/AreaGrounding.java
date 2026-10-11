package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.AreaContext;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Suggestion;

import java.util.List;

/**
 * Sets each suggestion's verification block and, for eat-out sets, may reorder so a verified pick is the hero.
 * Never throws for provider problems: it degrades to honest "popular around" labels.
 */
public interface AreaGrounding {

    record Result(List<Suggestion> suggestions, AreaContext areaContext) {}

    Result ground(String uid, MealSuggestionRequest request, List<Suggestion> suggestions);
}

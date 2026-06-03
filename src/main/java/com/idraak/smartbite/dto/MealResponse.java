package com.idraak.smartbite.dto;

import java.util.List;

public record MealResponse(
        String mealType,
        CalorieSummary calorieSummary,
        List<MealSuggestion> suggestions
) {}

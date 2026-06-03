package com.idraak.smartbite.dto;

import java.util.List;

public record MealSuggestion(
        String name,
        String shortDescription,
        int estimatedCalories,
        List<String> keyIngredients,
        String whyRecommended
) {}

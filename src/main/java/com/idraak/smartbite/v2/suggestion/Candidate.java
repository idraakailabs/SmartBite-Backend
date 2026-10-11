package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.model.Enums.Category;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;

import java.util.List;

/** One parsed and validated model suggestion, before diet filtering, reasons and verification. */
public record Candidate(
        String name,
        String shortDescription,
        Category category,
        String cuisine,
        String region,
        Diet diet,
        Boolean containsMeatOrFish,
        Boolean containsEgg,
        Boolean containsDairy,
        SpiceLevel spiceLevel,
        String primaryProtein,
        List<String> keyIngredients,
        int totalTimeMinutes,
        Difficulty difficulty,
        String similarTo
) {
    public DietFilter.Declared declared() {
        return new DietFilter.Declared(diet, containsMeatOrFish, containsEgg, containsDairy);
    }
}

package com.idraak.smartbite.v2.recipe;

import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.validation.SafeText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v2/recipes} body. */
public record RecipeRequest(
        @NotBlank @SafeText @Size(max = 80) String dish,
        @NotNull @Valid OriginInput origin,
        @NotNull @Min(1) @Max(20) Integer servings,
        Diet diet,
        @SafeText @Size(min = 1, max = 80) String variantLocation
) {
    public record OriginInput(@NotBlank @SafeText @Size(max = 40) String cuisine,
                              @SafeText @Size(min = 1, max = 40) String region) {}
}

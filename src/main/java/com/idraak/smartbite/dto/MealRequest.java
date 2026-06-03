package com.idraak.smartbite.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record MealRequest(
        @NotBlank String country,
        @NotBlank String city,
        @NotBlank String cuisine,
        @NotBlank String gender,
        @Min(1) @Max(120) int age,
        @Positive double weightKg,
        @Positive double heightCm,
        String activityLevel,
        @NotBlank String currentTime,
        @NotBlank String timezone,
        List<EatingHistoryItem> eatingHistory
) {}

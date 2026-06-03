package com.idraak.smartbite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record EatingHistoryItem(
        @NotBlank String name,
        @Positive int calories,
        String mealType,
        String date
) {}

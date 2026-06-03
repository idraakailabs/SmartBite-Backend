package com.idraak.smartbite.dto;

public record CalorieSummary(
        int dailyTarget,
        int weeklyTarget,
        int monthlyTarget,
        int consumedToday,
        int remainingToday
) {}

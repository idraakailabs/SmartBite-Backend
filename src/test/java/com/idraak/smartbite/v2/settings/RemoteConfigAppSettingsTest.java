package com.idraak.smartbite.v2.settings;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteConfigAppSettingsTest {

    @Test
    void usesFallbacksUntilTheTemplateIsRead() {
        RemoteConfigAppSettings s = new RemoteConfigAppSettings(3, true, () -> Map.of("free_daily_suggestions", "5"));
        assertThat(s.freeDailySuggestions()).isEqualTo(3);
        s.refresh();
        assertThat(s.freeDailySuggestions()).isEqualTo(5);
        assertThat(s.recipeStepsPremium()).isTrue();
    }

    @Test
    void clampsAndIgnoresGarbage() {
        AtomicReference<Map<String, String>> values = new AtomicReference<>(Map.of("free_daily_suggestions", "500",
                "recipe_steps_premium", "false"));
        RemoteConfigAppSettings s = new RemoteConfigAppSettings(3, true, values::get);
        s.refresh();
        assertThat(s.freeDailySuggestions()).isEqualTo(50);
        assertThat(s.recipeStepsPremium()).isFalse();
        values.set(Map.of("free_daily_suggestions", "lots", "recipe_steps_premium", "maybe"));
        s.refresh();
        assertThat(s.freeDailySuggestions()).isEqualTo(3);
        assertThat(s.recipeStepsPremium()).isTrue();
    }

    @Test
    void theCompactPromptKillSwitchOnlyTurnsOffOnAnExplicitFalse() {
        // BE-030: missing, garbage or unreadable = allowed (the per-environment env var decides).
        AtomicReference<Map<String, String>> values = new AtomicReference<>(Map.of());
        RemoteConfigAppSettings s = new RemoteConfigAppSettings(3, true, values::get);
        assertThat(s.compactPromptAllowed()).isTrue();
        s.refresh();
        assertThat(s.compactPromptAllowed()).isTrue();
        values.set(Map.of("suggest_prompt_v3_enabled", " FALSE "));
        s.refresh();
        assertThat(s.compactPromptAllowed()).isFalse();
        values.set(Map.of("suggest_prompt_v3_enabled", "maybe"));
        s.refresh();
        assertThat(s.compactPromptAllowed()).isTrue();
        values.set(Map.of("suggest_prompt_v3_enabled", "true"));
        s.refresh();
        assertThat(s.compactPromptAllowed()).isTrue();
    }

    @Test
    void readFailureKeepsCurrentValues() {
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        RemoteConfigAppSettings s = new RemoteConfigAppSettings(3, true, () -> {
            if (fail.get()) throw new IllegalStateException("down");
            return Map.of("free_daily_suggestions", "4");
        });
        s.refresh();
        fail.set(true);
        s.refresh();
        assertThat(s.freeDailySuggestions()).isEqualTo(4);
    }
}

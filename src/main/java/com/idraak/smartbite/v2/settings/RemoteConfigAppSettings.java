package com.idraak.smartbite.v2.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads the <b>default values</b> of {@code free_daily_suggestions} and {@code recipe_steps_premium} from the
 * Firebase Remote Config template (conditional values are ignored), refreshed on a schedule. Until the first
 * successful read, and whenever a read fails, the env-var fallbacks apply.
 */
public class RemoteConfigAppSettings implements AppSettings {

    private static final Logger log = LoggerFactory.getLogger(RemoteConfigAppSettings.class);
    static final String FREE_DAILY_KEY = "free_daily_suggestions";
    static final String RECIPE_GATE_KEY = "recipe_steps_premium";

    private final int fallbackFreeDaily;
    private final boolean fallbackRecipeGate;
    private final Supplier<Map<String, String>> templateDefaults;

    private volatile Integer freeDaily;
    private volatile Boolean recipeGate;

    /** @param templateDefaults returns parameter name -> default value string; null supplier disables syncing */
    public RemoteConfigAppSettings(int fallbackFreeDaily, boolean fallbackRecipeGate, Supplier<Map<String, String>> templateDefaults) {
        this.fallbackFreeDaily = clamp(fallbackFreeDaily);
        this.fallbackRecipeGate = fallbackRecipeGate;
        this.templateDefaults = templateDefaults;
    }

    public void refresh() {
        if (templateDefaults == null) return;
        try {
            Map<String, String> values = templateDefaults.get();
            freeDaily = Optional.ofNullable(values.get(FREE_DAILY_KEY)).map(String::trim).flatMap(RemoteConfigAppSettings::parseInt)
                    .map(RemoteConfigAppSettings::clamp).orElse(null);
            recipeGate = Optional.ofNullable(values.get(RECIPE_GATE_KEY)).map(String::trim)
                    .filter(v -> v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false"))
                    .map(Boolean::parseBoolean).orElse(null);
            log.info("remote config settings refreshed free_daily={} recipe_gate={}", freeDailySuggestions(), recipeStepsPremium());
        } catch (RuntimeException e) {
            log.warn("remote config template read failed; keeping current values ({})", e.getClass().getSimpleName());
        }
    }

    @Override
    public int freeDailySuggestions() {
        Integer v = freeDaily;
        return v != null ? v : fallbackFreeDaily;
    }

    @Override
    public boolean recipeStepsPremium() {
        Boolean v = recipeGate;
        return v != null ? v : fallbackRecipeGate;
    }

    private static Optional<Integer> parseInt(String s) {
        try {
            return Optional.of(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(50, v));
    }
}

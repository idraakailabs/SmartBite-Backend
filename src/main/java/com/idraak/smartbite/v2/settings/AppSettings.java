package com.idraak.smartbite.v2.settings;

/** Server-authoritative product settings that the owner controls through Remote Config (D-014, TECH-001). */
public interface AppSettings {

    /** Free successful sets per local day, clamped to 0..50. */
    int freeDailySuggestions();

    /** When true, free users get only the recipe preview steps. */
    boolean recipeStepsPremium();
}

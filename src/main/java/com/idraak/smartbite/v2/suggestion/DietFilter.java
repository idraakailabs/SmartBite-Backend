package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.model.Enums.Diet;

import java.util.List;

/**
 * Server-side diet safety net (README §6, D-021). A dish is rejected when its declared attributes conflict with the
 * diet, or when its name or ingredients hit the deny-lexicon:
 * <ul>
 *   <li>vegetarian: meat, poultry, fish, seafood, gelatine (dairy and eggs are allowed)</li>
 *   <li>vegan: all of those plus eggs, dairy, ghee and honey</li>
 * </ul>
 * Plant-based phrases ("coconut milk", "peanut butter") are removed before matching. It errs on the side of
 * rejecting, and it is not an allergy guarantee.
 */
public final class DietFilter {

    static final List<String> MEAT_FISH = List.of(
            "chicken", "beef", "mutton", "lamb", "goat", "pork", "bacon", "ham", "sausage", "salami", "pepperoni",
            "turkey", "duck", "veal", "venison", "meat", "meatball", "mince", "keema", "qeema", "kheema", "gosht",
            "murgh", "murg", "nihari", "haleem", "paya", "kaleji", "liver", "brain", "maghaz", "trotters", "bone broth",
            "fish", "machli", "machhli", "salmon", "tuna", "cod", "pomfret", "rohu", "sardine", "anchovy", "anchovies",
            "prawn", "shrimp", "jhinga", "crab", "lobster", "squid", "calamari", "octopus", "oyster", "clam", "mussel",
            "scallop", "seafood", "gelatin", "gelatine", "lard", "fish sauce", "oyster sauce", "shawarma", "tikka boti",
            "seekh kebab", "chapli kebab", "shami kebab", "bihari kebab", "boti", "chargha", "sajji");

    static final List<String> EGG = List.of("egg", "omelette", "omelet", "anda", "mayonnaise", "mayo", "meringue");

    static final List<String> DAIRY_HONEY = List.of(
            "milk", "cream", "butter", "ghee", "paneer", "cheese", "yogurt", "yoghurt", "dahi", "curd", "raita",
            "lassi", "malai", "khoya", "khoa", "kheer", "rabri", "rabdi", "kulfi", "ice cream", "custard", "whey",
            "condensed milk", "honey", "makhan", "mozzarella", "cheddar", "feta", "labneh", "ras malai", "gulab jamun",
            "falooda", "chai");

    static final List<String> PLANT_PHRASES = List.of(
            "coconut milk", "coconut cream", "almond milk", "oat milk", "soy milk", "soya milk", "rice milk",
            "cashew milk", "cashew cream", "peanut butter", "almond butter", "cocoa butter", "vegan cheese",
            "vegan butter", "plant based", "plant-based", "soy chunks", "soya chunks", "jackfruit", "tofu", "vegetarian",
            "veg", "egg free", "eggless", "dairy free");

    private DietFilter() {
    }

    /** What the model declared about a dish. Null booleans mean "not stated" and are treated as unsafe. */
    public record Declared(Diet diet, Boolean containsMeatOrFish, Boolean containsEgg, Boolean containsDairy) {}

    /** @return true if the dish is acceptable for {@code required} */
    public static boolean allows(Diet required, Declared declared, String name, List<String> ingredients) {
        if (required == Diet.NON_VEGETARIAN) return true;
        if (declared.diet() == null || declared.diet().strictness() < required.strictness()) return false;
        if (!Boolean.FALSE.equals(declared.containsMeatOrFish())) return false;
        String text = stripPlantPhrases(name + " | " + String.join(" | ", ingredients));
        if (hitsAny(text, MEAT_FISH)) return false;
        if (required == Diet.VEGAN) {
            if (!Boolean.FALSE.equals(declared.containsEgg()) || !Boolean.FALSE.equals(declared.containsDairy())) return false;
            if (hitsAny(text, EGG) || hitsAny(text, DAIRY_HONEY)) return false;
        }
        return true;
    }

    static boolean hitsAny(String text, List<String> words) {
        for (String w : words) {
            if (Text.containsPhrase(text, w)) return true;
        }
        return false;
    }

    static String stripPlantPhrases(String text) {
        String t = " " + Text.norm(text) + " ";
        for (String p : PLANT_PHRASES) {
            t = t.replace(" " + Text.norm(p) + " ", " ");
        }
        return t.trim();
    }
}

package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.model.Enums.Diet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DietFilterTest {

    static DietFilter.Declared declared(Diet d, Boolean meat, Boolean egg, Boolean dairy) {
        return new DietFilter.Declared(d, meat, egg, dairy);
    }

    @Test
    void vegetarianAllowsEggsAndDairy_D021() {
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(Diet.VEGETARIAN, false, true, true),
                "Anda Paratha", List.of("Eggs", "Ghee", "Wheat flour"))).isTrue();
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(Diet.VEGETARIAN, false, false, true),
                "Paneer Tikka", List.of("Paneer", "Yoghurt"))).isTrue();
    }

    @Test
    void vegetarianRejectsMeatEvenWhenMislabelled() {
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(Diet.VEGETARIAN, false, false, false),
                "Chicken Karahi", List.of("Chicken", "Tomatoes"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(Diet.VEGETARIAN, false, false, false),
                "Aloo Gosht", List.of("Potato"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(Diet.VEGETARIAN, false, false, false),
                "Prawn Biryani", List.of("Rice"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(Diet.NON_VEGETARIAN, true, false, false),
                "Daal", List.of("Lentils"))).isFalse();
    }

    @Test
    void missingDeclarationsAreUnsafe() {
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(Diet.VEGETARIAN, null, false, false),
                "Chana Masala", List.of("Chickpeas"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGETARIAN, declared(null, false, false, false),
                "Chana Masala", List.of("Chickpeas"))).isFalse();
    }

    @Test
    void veganRejectsEggsDairyGheeHoney() {
        DietFilter.Declared ok = declared(Diet.VEGAN, false, false, false);
        assertThat(DietFilter.allows(Diet.VEGAN, ok, "Chana Masala", List.of("Chickpeas", "Onion"))).isTrue();
        assertThat(DietFilter.allows(Diet.VEGAN, ok, "Daal Tadka", List.of("Lentils", "Ghee"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGAN, ok, "Halwa", List.of("Semolina", "Honey"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGAN, ok, "Egg Fried Rice", List.of("Rice"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGAN, ok, "Palak Paneer", List.of("Spinach"))).isFalse();
        assertThat(DietFilter.allows(Diet.VEGAN, declared(Diet.VEGAN, false, true, false), "Toast", List.of("Bread"))).isFalse();
    }

    @Test
    void plantBasedPhrasesDontTriggerDairy() {
        assertThat(DietFilter.allows(Diet.VEGAN, declared(Diet.VEGAN, false, false, false),
                "Thai Green Curry", List.of("Coconut milk", "Tofu", "Peanut butter"))).isTrue();
        assertThat(DietFilter.allows(Diet.VEGAN, declared(Diet.VEGAN, false, false, false),
                "Baingan Bharta", List.of("Eggplant"))).isTrue();
    }

    @Test
    void nonVegetarianHasNoExclusions() {
        assertThat(DietFilter.allows(Diet.NON_VEGETARIAN, declared(null, null, null, null), "Anything", List.of("Beef"))).isTrue();
    }
}

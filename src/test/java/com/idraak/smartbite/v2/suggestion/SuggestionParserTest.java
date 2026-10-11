package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.ai.ProviderChain.UnusableOutputException;
import com.idraak.smartbite.v2.model.Enums.Category;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuggestionParserTest {

    final SuggestionParser parser = new SuggestionParser(JsonMapper.builder().build());

    static final String ITEM = """
            {"name":"Chana Masala","short_description":"Chickpeas in a spiced tomato gravy.","category":"curry_karahi",
             "origin":{"cuisine":"Pakistani","region":"Punjabi"},"diet":"vegan","contains_meat_or_fish":false,
             "contains_egg":false,"contains_dairy":false,"spice_level":"medium","primary_protein":"Chickpeas",
             "key_ingredients":["Chickpeas","Tomato","Onion"],"total_time_minutes":40,"difficulty":"easy","similar_to":null}""";

    @Test
    void parsesFencedJsonWithProse() {
        List<Candidate> c = parser.parse("Here you go:\n```json\n{\"suggestions\":[" + ITEM + "]}\n```");
        assertThat(c).hasSize(1);
        assertThat(c.get(0).name()).isEqualTo("Chana Masala");
        assertThat(c.get(0).category()).isEqualTo(Category.CURRY_KARAHI);
        assertThat(c.get(0).region()).isEqualTo("Punjabi");
    }

    @Test
    void unknownCategoryBecomesOther() {
        List<Candidate> c = parser.parse("{\"suggestions\":[" + ITEM.replace("curry_karahi", "space_food") + "]}");
        assertThat(c.get(0).category()).isEqualTo(Category.OTHER);
    }

    @Test
    void dropsItemsMissingRequiredAttributesInsteadOfInventingThem() {
        String noSpice = ITEM.replace("\"spice_level\":\"medium\",", "");
        String badDiet = ITEM.replace("\"diet\":\"vegan\"", "\"diet\":\"pescatarian\"");
        List<Candidate> c = parser.parse("{\"suggestions\":[" + noSpice + "," + badDiet + "," + ITEM + "]}");
        assertThat(c).hasSize(1);
    }

    @Test
    void clipsLongText() {
        String longName = "A".repeat(200);
        List<Candidate> c = parser.parse("{\"suggestions\":[" + ITEM.replace("Chana Masala", longName) + "]}");
        assertThat(c.get(0).name()).hasSize(80);
    }

    @Test
    void rejectsUnusableOutput() {
        assertThatThrownBy(() -> parser.parse("sorry, I can't")).isInstanceOf(UnusableOutputException.class);
        assertThatThrownBy(() -> parser.parse("{\"suggestions\":\"none\"}")).isInstanceOf(UnusableOutputException.class);
        assertThatThrownBy(() -> parser.parse("{\"suggestions\":[{\"name\":\"x\"}]}")).isInstanceOf(UnusableOutputException.class);
        assertThatThrownBy(() -> parser.parse("{not json}")).isInstanceOf(UnusableOutputException.class);
    }
}

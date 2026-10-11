package com.idraak.smartbite.testsupport;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** Builds fake OpenAI-compatible chat completion bodies for WireMock. */
public final class AiStubs {

    private static final JsonMapper M = JsonMapper.builder().build();

    private AiStubs() {
    }

    public record Dish(String name, String diet, boolean meat, boolean egg, boolean dairy, String spice, String cuisine,
                       String protein, List<String> ingredients, int minutes, String difficulty, String similarTo) {

        public static Dish veg(String name, String... ingredients) {
            return new Dish(name, "vegetarian", false, false, true, "medium", "Pakistani", null, List.of(ingredients), 30, "easy", null);
        }

        public static Dish meat(String name, String protein, String... ingredients) {
            return new Dish(name, "non_vegetarian", true, false, false, "spicy", "Pakistani", protein, List.of(ingredients), 50, "medium", null);
        }

        public static Dish mislabelled(String name, String... ingredients) {
            return new Dish(name, "vegetarian", false, false, false, "medium", "Pakistani", null, List.of(ingredients), 30, "easy", null);
        }
    }

    public static String suggestionsContent(Dish... dishes) {
        ObjectNode root = M.createObjectNode();
        ArrayNode arr = root.putArray("suggestions");
        for (Dish d : dishes) {
            ObjectNode n = arr.addObject();
            n.put("name", d.name());
            n.put("short_description", "A tasty " + d.name() + ".");
            n.put("category", "curry_karahi");
            n.putObject("origin").put("cuisine", d.cuisine()).put("region", "Lahori");
            n.put("diet", d.diet());
            n.put("contains_meat_or_fish", d.meat());
            n.put("contains_egg", d.egg());
            n.put("contains_dairy", d.dairy());
            n.put("spice_level", d.spice());
            if (d.protein() == null) n.putNull("primary_protein");
            else n.put("primary_protein", d.protein());
            ArrayNode ing = n.putArray("key_ingredients");
            d.ingredients().forEach(ing::add);
            n.put("total_time_minutes", d.minutes());
            n.put("difficulty", d.difficulty());
            if (d.similarTo() == null) n.putNull("similar_to");
            else n.put("similar_to", d.similarTo());
        }
        return M.writeValueAsString(root);
    }

    public static String completion(String content) {
        ObjectNode root = M.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", content);
        return M.writeValueAsString(root);
    }
}

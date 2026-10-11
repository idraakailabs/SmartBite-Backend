package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.ai.ProviderChain.UnusableOutputException;
import com.idraak.smartbite.v2.model.Enums.Category;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Safe parsing of the model's JSON. Text around the JSON (markdown fences, prose) is ignored. Items missing a
 * required attribute are dropped rather than filled in with invented values; lengths are clipped; unknown
 * categories become {@code other}. Throws {@link UnusableOutputException} if nothing usable remains.
 */
public class SuggestionParser {

    private final ObjectMapper mapper;

    public SuggestionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<Candidate> parse(String content) {
        JsonNode root = readObject(content, mapper);
        JsonNode items = root.path("suggestions");
        if (!items.isArray()) throw new UnusableOutputException("no suggestions array");
        List<Candidate> out = new ArrayList<>();
        for (JsonNode n : items) {
            Candidate c = toCandidate(n);
            if (c != null) out.add(c);
            if (out.size() == 6) break;
        }
        if (out.isEmpty()) throw new UnusableOutputException("no valid suggestion items");
        return out;
    }

    static JsonNode readObject(String content, ObjectMapper mapper) {
        if (content == null) throw new UnusableOutputException("empty output");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) throw new UnusableOutputException("no JSON object");
        try {
            JsonNode root = mapper.readTree(content.substring(start, end + 1));
            if (!root.isObject()) throw new UnusableOutputException("not an object");
            return root;
        } catch (JacksonException e) {
            throw new UnusableOutputException("invalid JSON");
        }
    }

    private Candidate toCandidate(JsonNode n) {
        String name = text(n, "name", 80);
        String description = text(n, "short_description", 200);
        String cuisine = text(n.path("origin"), "cuisine", 40);
        String region = text(n.path("origin"), "region", 40);
        Diet diet = enumValue(Diet.class, text(n, "diet", 20));
        SpiceLevel spice = enumValue(SpiceLevel.class, text(n, "spice_level", 20));
        Difficulty difficulty = enumValue(Difficulty.class, text(n, "difficulty", 10));
        Integer minutes = n.path("total_time_minutes").isNumber() ? n.path("total_time_minutes").asInt() : null;
        List<String> ingredients = new ArrayList<>();
        for (JsonNode i : n.path("key_ingredients")) {
            String s = clip(i.isString() ? i.asString() : null, 40);
            if (s != null && ingredients.size() < 8) ingredients.add(s);
        }
        if (name == null || description == null || cuisine == null || diet == null || spice == null
                || difficulty == null || minutes == null || minutes < 1 || minutes > 1440 || ingredients.isEmpty()) {
            return null;
        }
        return new Candidate(name, description, Category.fromWire(text(n, "category", 30)), cuisine, region, diet,
                bool(n, "contains_meat_or_fish"), bool(n, "contains_egg"), bool(n, "contains_dairy"),
                spice, text(n, "primary_protein", 40), List.copyOf(ingredients), minutes, difficulty, text(n, "similar_to", 80));
    }

    static String text(JsonNode n, String field, int max) {
        JsonNode v = n.path(field);
        return v.isString() ? clip(v.asString(), max) : null;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", " ").strip();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max).strip();
    }

    static Boolean bool(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isBoolean() ? v.asBoolean() : null;
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, String s) {
        if (s == null) return null;
        try {
            return Enum.valueOf(type, s.trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

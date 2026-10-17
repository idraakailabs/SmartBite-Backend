package com.idraak.smartbite.v2.recipe;

import com.idraak.smartbite.v2.ai.ProviderChain.UnusableOutputException;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.Difficulty;
import com.idraak.smartbite.v2.suggestion.DietFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe parsing of a generated recipe, with the diet safety net applied to every ingredient.
 *
 * <p>Models don't always follow the schema to the letter, so the parser accepts common variants (staging,
 * 2026-10-16: every provider's recipe was rejected): minutes as whole-number decimals or numeric strings, a missing
 * total worked out from prep + cook + rest, difficulty synonyms (a missing difficulty defaults to medium), steps as
 * plain strings, and a flat {@code ingredients} list. What it still can't use is reported by name, and the start of
 * the raw output is logged at DEBUG (recipe text only, no user data).
 */
final class RecipeParser {

    private static final Logger log = LoggerFactory.getLogger(RecipeParser.class);
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d{1,4})(?:\\.\\d+)?\\b");

    /** The model said the dish can't be made for the diet. */
    static final class DietConflict extends RuntimeException {
        DietConflict() {
            super("diet conflict", null, false, false);
        }
    }

    record Parsed(Difficulty difficulty, Recipe.Times times, List<Recipe.IngredientGroup> groups, List<Recipe.Step> steps,
                  List<String> tips, Recipe.LocalVariant variant) {}

    private RecipeParser() {
    }

    static Parsed parse(String content, Diet requiredDiet, String dishName, ObjectMapper mapper) {
        JsonNode root = readObject(content, mapper);
        if (root.path("diet_conflict").asBoolean(false)) throw new DietConflict();

        List<Recipe.IngredientGroup> groups = new ArrayList<>();
        List<String> allIngredients = new ArrayList<>();
        for (JsonNode g : root.path("ingredient_groups")) {
            List<Recipe.Ingredient> items = items(g.path("items"), allIngredients);
            if (!items.isEmpty()) groups.add(new Recipe.IngredientGroup(orDefault(text(g, "title", 60), "Ingredients"), List.copyOf(items)));
            if (groups.size() == 8) break;
        }
        if (groups.isEmpty()) {
            // A flat list instead of groups.
            List<Recipe.Ingredient> items = items(root.path("ingredients"), allIngredients);
            if (!items.isEmpty()) groups.add(new Recipe.IngredientGroup("Ingredients", List.copyOf(items)));
        }

        List<Recipe.Step> steps = new ArrayList<>();
        int unreadableSteps = 0;
        JsonNode stepList = firstContainer(root, STEP_LISTS);
        for (JsonNode s : stepList) {
            // A step is an object, or a plain string when the model simplifies the schema.
            String instruction = s.isString() ? clip(s.asString(), 400) : firstText(s, STEP_TEXTS, 400);
            if (instruction == null) {
                unreadableSteps++;
                continue;
            }
            List<String> keys = new ArrayList<>();
            for (JsonNode k : s.path("key_quantities")) {
                if (k.isString() && keys.size() < 6) keys.add(clip(k.asString(), 40));
            }
            steps.add(new Recipe.Step(steps.size() + 1, instruction, List.copyOf(keys), nonNegative(s, "prep_minutes"),
                    nonNegative(s, "cook_minutes"), nonNegative(s, "rest_minutes"), positive(s, "timer_seconds")));
            if (steps.size() == 30) break;
        }
        Difficulty difficulty = difficulty(text(root, "difficulty", 20));
        JsonNode t = root.path("times");
        Integer total = positive(t, "total_minutes");
        if (total == null) total = positive(root, "total_minutes");
        if (total == null) {
            int sum = orZero(nonNegative(t, "prep_minutes")) + orZero(nonNegative(t, "cook_minutes")) + orZero(nonNegative(t, "rest_minutes"));
            if (sum > 0) total = sum;
        }
        List<String> missing = new ArrayList<>();
        if (groups.isEmpty()) missing.add("ingredients");
        // A partial method is worse than none: it would be cached and shown as the whole recipe (staging,
        // 2026-10-17: a one-step Chicken Tikka Masala).
        if (steps.isEmpty()) missing.add("steps");
        else if (unreadableSteps > 0) missing.add("steps (" + unreadableSteps + " of " + (steps.size() + unreadableSteps) + " unreadable)");
        else if (steps.size() < MIN_STEPS) missing.add("steps (only " + steps.size() + ")");
        if (total == null) missing.add("total time");
        if (!missing.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("unusable recipe output (first 2000 chars): {}", content.length() > 2000 ? content.substring(0, 2000) : content);
            }
            // Field names only (no recipe text), so an unknown shape can be diagnosed from the WARN line.
            String shape = " (fields: " + fieldNames(root) + "; first step: "
                    + (stepList.isEmpty() ? "none" : fieldNames(stepList.iterator().next())) + ")";
            throw new UnusableOutputException("recipe missing " + String.join(", ", missing) + shape);
        }
        if (difficulty == null) difficulty = Difficulty.MEDIUM;
        if (requiredDiet != null && requiredDiet != Diet.NON_VEGETARIAN) {
            Diet declared = dietValue(text(root, "diet", 20));
            DietFilter.Declared d = new DietFilter.Declared(declared, bool(root, "contains_meat_or_fish"),
                    bool(root, "contains_egg"), bool(root, "contains_dairy"));
            if (!DietFilter.allows(requiredDiet, d, dishName, allIngredients)) {
                throw new UnusableOutputException("recipe fails the diet check");
            }
        }
        List<String> tips = new ArrayList<>();
        for (JsonNode tip : root.path("tips")) {
            if (tip.isString() && tips.size() < 8) tips.add(clip(tip.asString(), 240));
        }
        Recipe.LocalVariant variant = null;
        JsonNode v = root.path("local_variant");
        String label = text(v, "label", 100);
        if (label != null) {
            List<Recipe.IngredientChange> changes = new ArrayList<>();
            for (JsonNode c : v.path("ingredient_changes")) {
                String rep = text(c, "replace", 80);
                String with = text(c, "with", 80);
                if (rep != null && with != null && changes.size() < 10) changes.add(new Recipe.IngredientChange(rep, with, text(c, "note", 120)));
            }
            List<Recipe.StepChange> stepChanges = new ArrayList<>();
            for (JsonNode c : v.path("step_changes")) {
                String instr = text(c, "instruction", 400);
                if (instr != null && c.path("step_number").isInt() && stepChanges.size() < 10) {
                    stepChanges.add(new Recipe.StepChange(c.path("step_number").asInt(), instr));
                }
            }
            String fullLabel = label.toLowerCase().startsWith("variant") ? label : clip("Variant · " + label, 100);
            variant = new Recipe.LocalVariant(fullLabel, List.copyOf(changes), List.copyOf(stepChanges));
        }
        Recipe.Times times = new Recipe.Times(orZero(nonNegative(t, "prep_minutes")), orZero(nonNegative(t, "cook_minutes")),
                orZero(nonNegative(t, "rest_minutes")), total);
        return new Parsed(difficulty, times, List.copyOf(groups), List.copyOf(steps), List.copyOf(tips), variant);
    }

    /** Ingredient objects from one list (a group's {@code items} or a flat {@code ingredients}); at most 30. */
    private static List<Recipe.Ingredient> items(JsonNode list, List<String> allIngredients) {
        List<Recipe.Ingredient> items = new ArrayList<>();
        for (JsonNode i : list) {
            String item = text(i, "item", 80);
            if (item == null) item = text(i, "name", 80);
            if (item == null) continue;
            String display = text(i, "display_quantity", 40);
            Double qty = i.path("quantity").isNumber() && i.path("quantity").asDouble() >= 0 ? i.path("quantity").asDouble() : null;
            Recipe.Metric metric = null;
            JsonNode m = i.path("metric");
            Recipe.MetricUnit unit = metricUnit(text(m, "unit", 10));
            if (unit != null) {
                metric = new Recipe.Metric(m.path("quantity").isNumber() ? m.path("quantity").asDouble() : null, unit);
            }
            List<Recipe.Substitute> subs = new ArrayList<>();
            for (JsonNode sub : i.path("substitutes")) {
                String si = text(sub, "item", 80);
                if (si != null && subs.size() < 3) subs.add(new Recipe.Substitute(si, text(sub, "note", 80)));
            }
            items.add(new Recipe.Ingredient(item, display == null ? (qty == null ? "to taste" : String.valueOf(qty)) : display,
                    qty, text(i, "unit", 20), metric, text(i, "notes", 80), i.path("optional").asBoolean(false),
                    i.path("is_substitution").asBoolean(false), text(i, "substitution_for", 80), List.copyOf(subs)));
            allIngredients.add(item);
            if (items.size() == 30) break;
        }
        return items;
    }

    /** easy / medium / hard, plus the synonyms models use; null when absent or unknown. */
    private static Difficulty difficulty(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "easy", "simple", "beginner", "basic" -> Difficulty.EASY;
            case "medium", "moderate", "intermediate", "average" -> Difficulty.MEDIUM;
            case "hard", "difficult", "advanced", "challenging", "expert" -> Difficulty.HARD;
            default -> null;
        };
    }

    /**
     * Whole minutes or seconds: an integer, a whole-number decimal (90.0) or a numeric string ("90", "90 min").
     * Null when absent or not a number.
     */
    private static Integer number(JsonNode n, String f) {
        JsonNode v = n.path(f);
        if (v.isInt()) return v.asInt();
        if (v.isNumber()) {
            double d = v.asDouble();
            return d >= 0 && d <= 100_000 ? (int) Math.round(d) : null;
        }
        if (v.isString()) {
            Matcher m = LEADING_NUMBER.matcher(v.asString());
            return m.find() ? Integer.parseInt(m.group(1)) : null;
        }
        return null;
    }

    /** Where models put the steps, and the text of one step, besides the schema's own names (BE-BUG-008). */
    static final int MIN_STEPS = 2;
    private static final List<String> STEP_LISTS = List.of("steps", "instructions", "method", "directions", "preparation");
    private static final List<String> STEP_TEXTS = List.of("instruction", "text", "description", "step", "details", "content", "action");

    private static JsonNode firstContainer(JsonNode root, List<String> names) {
        for (String name : names) {
            JsonNode n = root.path(name);
            if ((n.isArray() || n.isObject()) && !n.isEmpty()) return n;
        }
        return root.path("steps");
    }

    private static String firstText(JsonNode n, List<String> names, int max) {
        for (String name : names) {
            String s = text(n, name, max);
            if (s != null) return s;
        }
        return null;
    }

    private static String fieldNames(JsonNode n) {
        if (n.isString()) return "string";
        if (!n.isObject()) return n.getNodeType().name().toLowerCase();
        List<String> names = new ArrayList<>(n.propertyNames());
        return names.size() > 12 ? String.join(",", names.subList(0, 12)) + ",…" : String.join(",", names);
    }

    private static JsonNode readObject(String content, ObjectMapper mapper) {
        if (content == null) throw new UnusableOutputException("empty");
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

    private static String text(JsonNode n, String f, int max) {
        JsonNode v = n.path(f);
        return v.isString() ? clip(v.asString(), max) : null;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", " ").strip();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max).strip();
    }

    private static String orDefault(String s, String d) {
        return s == null ? d : s;
    }

    private static Integer nonNegative(JsonNode n, String f) {
        Integer v = number(n, f);
        return v != null && v >= 0 ? v : null;
    }

    private static Integer positive(JsonNode n, String f) {
        Integer v = number(n, f);
        return v != null && v >= 1 ? v : null;
    }

    private static int orZero(Integer i) {
        return i == null ? 0 : i;
    }

    private static Boolean bool(JsonNode n, String f) {
        return n.path(f).isBoolean() ? n.path(f).asBoolean() : null;
    }

    private static Diet dietValue(String s) {
        if (s == null) return null;
        try {
            return Diet.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Recipe.MetricUnit metricUnit(String s) {
        if (s == null) return null;
        try {
            return Recipe.MetricUnit.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

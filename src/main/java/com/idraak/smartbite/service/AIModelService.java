package com.idraak.smartbite.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idraak.smartbite.dto.EatingHistoryItem;
import com.idraak.smartbite.dto.MealRequest;
import com.idraak.smartbite.dto.MealResponse;
import com.idraak.smartbite.exception.AiServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class AIModelService {

    private static final String PROMPT_TEMPLATE = """
            You are a personalized nutrition and meal recommendation assistant. Your job is to suggest meals tailored to a user's profile, calorie targets, and eating history.

            <user_profile>
            - Country: {country}
            - City: {city}
            - Cuisine preference: {cuisine}
            - Gender: {gender}
            - Age: {age}
            - Weight: {weight_kg} kg
            - Height: {height_cm} cm
            - Activity level: {activity_level}
            - Current local time: {current_time} ({timezone})
            </user_profile>

            <calorie_calculation>
            Step 1 — Calculate BMR using Mifflin-St Jeor:
            - Male:   BMR = (10 × weight_kg) + (6.25 × height_cm) − (5 × age) + 5
            - Female: BMR = (10 × weight_kg) + (6.25 × height_cm) − (5 × age) − 161

            Step 2 — Calculate TDEE by multiplying BMR by the activity multiplier:
            - sedentary:   × 1.2
            - light:       × 1.375
            - moderate:    × 1.55  (use this if activity level is unspecified)
            - active:      × 1.725
            - very_active: × 1.9

            Step 3 — Derive targets:
            - daily_target  = round(TDEE)
            - weekly_target = daily_target × 7
            - monthly_target = daily_target × 30

            Step 4 — From the eating history below, sum the calories of all entries where the date matches today ({today}).
            - consumed_today = sum of those calories (0 if history is empty or no entries match today)
            - remaining_today = daily_target − consumed_today
            </calorie_calculation>

            <eating_history>
            {eating_history}
            </eating_history>

            <rules>
            1. Determine the meal type from the current local time:
               - 06:00–11:59 → Breakfast
               - 12:00–17:59 → Lunch
               - 18:00–23:59 → Dinner
               - 00:00–05:59 → Late-night (suggest a light snack or hydration)

            2. Suggest exactly 3 meal options that are:
               - Authentic to the requested cuisine ({cuisine})
               - Realistically available or preparable in {city}, {country}
               - Appropriate for the meal type identified above
               - Each meal's estimated_calories should fit within remaining_today; if remaining_today is below 200, suggest very light options and note the user is near their daily limit

            3. If eating history contains entries from the last 3 days, AVOID repeating those dishes and AVOID the same primary protein/carb base used in the most recent meal.

            4. If eating history is empty, offer variety: one light, one hearty, one traditional.

            5. Do NOT give medical advice. Do NOT comment on the user's weight or appearance.

            6. Always respond entirely in English, regardless of the selected country or cuisine.

            7. In why_recommended, mention how the suggestion fits the user's remaining calorie budget for the day.
            </rules>

            <output_format>
            Return ONLY valid JSON — no markdown, no extra text. Use exactly this shape:

            {"meal_type":"Breakfast|Lunch|Dinner|Late-night","calorie_summary":{"daily_target":number,"weekly_target":number,"monthly_target":number,"consumed_today":number,"remaining_today":number},"suggestions":[{"name":"string","short_description":"string","estimated_calories":number,"key_ingredients":["string"],"why_recommended":"string"}]}
            </output_format>
            """;

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public AIModelService(
            RestClient geminiRestClient,
            @Value("${gemini.model}") String model,
            ObjectMapper objectMapper) {
        this.restClient = geminiRestClient;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public MealResponse getSuggestions(MealRequest request) {
        String prompt = buildPrompt(request);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String content = response.path("choices").get(0).path("message").path("content").asText();
        content = stripMarkdown(content);

        try {
            return objectMapper.readValue(content, MealResponse.class);
        } catch (JsonProcessingException e) {
            throw new AiServiceException("Failed to parse AI response: " + e.getOriginalMessage(), e);
        }
    }

    private String buildPrompt(MealRequest req) {
        String activityLevel = (req.activityLevel() == null || req.activityLevel().isBlank())
                ? "moderate" : req.activityLevel();

        String historyJson = formatHistory(req.eatingHistory());

        String today = java.time.LocalDate.now().toString();

        return PROMPT_TEMPLATE
                .replace("{country}", req.country())
                .replace("{city}", req.city())
                .replace("{cuisine}", req.cuisine())
                .replace("{gender}", req.gender())
                .replace("{age}", String.valueOf(req.age()))
                .replace("{weight_kg}", String.valueOf(req.weightKg()))
                .replace("{height_cm}", String.valueOf(req.heightCm()))
                .replace("{activity_level}", activityLevel)
                .replace("{current_time}", req.currentTime())
                .replace("{timezone}", req.timezone())
                .replace("{today}", today)
                .replace("{eating_history}", historyJson);
    }

    private String formatHistory(List<EatingHistoryItem> history) {
        if (history == null || history.isEmpty()) return "none";
        try {
            return objectMapper.writeValueAsString(history);
        } catch (JsonProcessingException e) {
            return "none";
        }
    }

    private String stripMarkdown(String content) {
        content = content.trim();
        int start = content.indexOf('{');
        int end   = content.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }
}

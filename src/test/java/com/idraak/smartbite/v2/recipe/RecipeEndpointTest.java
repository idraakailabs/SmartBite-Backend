package com.idraak.smartbite.v2.recipe;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idraak.smartbite.testsupport.AiStubs;
import com.idraak.smartbite.v2.entitlement.FakeBilling;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "smartbite.v2.limits.recipes-per-minute=100")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecipeEndpointTest {

    static final WireMockServer groq = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));

    static {
        groq.start();
    }

    @AfterAll
    static void stop() {
        groq.stop();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("groq.base-url", groq::baseUrl);
        registry.add("gemini.base-url", groq::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeBilling billing;

    @BeforeEach
    void reset() {
        groq.resetAll();
        billing.reset();
    }

    static final String KARAHI = """
            {"diet_conflict":false,"diet":"non_vegetarian","contains_meat_or_fish":true,"contains_egg":false,"contains_dairy":true,
             "difficulty":"medium","times":{"prep_minutes":15,"cook_minutes":35,"rest_minutes":0,"total_minutes":50},
             "ingredient_groups":[{"title":"For the karahi","items":[
               {"item":"Chicken, bone-in","display_quantity":"4 pao","quantity":4,"unit":"pao","metric":{"quantity":1000,"unit":"g"},
                "notes":null,"optional":false,"is_substitution":false,"substitution_for":null,"substitutes":[]},
               {"item":"Ghee","display_quantity":"1/2 cup","quantity":0.5,"unit":"cup","metric":{"quantity":120,"unit":"ml"},
                "notes":null,"optional":false,"is_substitution":false,"substitution_for":null,
                "substitutes":[{"item":"Cooking oil","note":"Lighter flavour"}]},
               {"item":"Salt","display_quantity":"to taste","quantity":null,"unit":null,"metric":{"quantity":null,"unit":"to_taste"},
                "optional":false,"is_substitution":false}]}],
             "steps":[
               {"number":1,"instruction":"Heat the 1/2 cup of ghee.","key_quantities":["1/2 cup ghee"],"cook_minutes":2},
               {"number":2,"instruction":"Add the 1 kg chicken and fry.","key_quantities":["1 kg chicken"],"cook_minutes":8,"timer_seconds":480},
               {"number":3,"instruction":"Add tomatoes.","key_quantities":[]},
               {"number":4,"instruction":"Finish with ginger.","key_quantities":[]}],
             "tips":["Use a heavy karahi."],
             "local_variant":{"label":"with ingredients easy to find in Lahore","ingredient_changes":[{"replace":"Desi ghee","with":"Oil","note":"cheaper"}],
               "step_changes":[{"step_number":1,"instruction":"Heat the oil."}]}}""";

    static final String BODY = """
            {"dish":"Chicken Karahi","origin":{"cuisine":"Pakistani","region":"Lahori"},"servings":%d,"variant_location":"Lahore, Pakistan"}""";

    void stub(String content) {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(AiStubs.completion(content))));
    }

    ResultActions call(String uid, String body) throws Exception {
        return mvc.perform(post("/api/v2/recipes").header("Authorization", "Bearer test:" + uid)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void freeUserGetsIngredientsAndTwoStepsLocked() throws Exception {
        stub(KARAHI);
        call("r-free", BODY.formatted(4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dish").value("Chicken Karahi"))
                .andExpect(jsonPath("$.origin.label").value("Pakistani · Lahori style"))
                .andExpect(jsonPath("$.ingredient_groups[0].items", hasSize(3)))
                .andExpect(jsonPath("$.ingredient_groups[0].items[0].display_quantity").value("4 pao"))
                .andExpect(jsonPath("$.ingredient_groups[0].items[0].metric.unit").value("g"))
                .andExpect(jsonPath("$.ingredient_groups[0].items[1].substitutes[0].item").value("Cooking oil"))
                .andExpect(jsonPath("$.ingredient_groups[0].items[2].metric.unit").value("to_taste"))
                .andExpect(jsonPath("$.steps", hasSize(2)))
                .andExpect(jsonPath("$.total_steps").value(4))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.locked_reason").value("PREMIUM_REQUIRED"))
                .andExpect(jsonPath("$.tips", hasSize(0)))
                .andExpect(jsonPath("$.local_variant.label").value("Variant · with ingredients easy to find in Lahore"))
                .andExpect(jsonPath("$.local_variant.step_changes", hasSize(0)))
                .andExpect(jsonPath("$.safety_note").value(org.hamcrest.Matchers.startsWith("This recipe was written by AI")))
                .andExpect(jsonPath("$.is_premium").value(false));
    }

    @Test
    void premiumGetsEverythingAndTheCacheIsSharedAcrossUsers() throws Exception {
        stub(KARAHI);
        call("r-free2", BODY.formatted(6)).andExpect(jsonPath("$.cached").value(false));
        billing.setPremium("r-pro", true);
        call("r-pro", BODY.formatted(6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.steps", hasSize(4)))
                .andExpect(jsonPath("$.steps[1].timer_seconds").value(480))
                .andExpect(jsonPath("$.tips", hasSize(1)))
                .andExpect(jsonPath("$.local_variant.step_changes", hasSize(1)))
                .andExpect(jsonPath("$.is_premium").value(true));
        groq.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void dietConflictIs422() throws Exception {
        stub("{\"diet_conflict\":true}");
        call("r-conf", """
                {"dish":"Chicken Karahi %s","origin":{"cuisine":"Pakistani"},"servings":2,"diet":"vegan"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DISH_CONFLICTS_WITH_DIET"));
    }

    @Test
    void recipeThatBreaksTheRequestedDietIsNeverReturned() throws Exception {
        stub(KARAHI.replace("\"diet\":\"non_vegetarian\",\"contains_meat_or_fish\":true", "\"diet\":\"vegetarian\",\"contains_meat_or_fish\":false"));
        call("r-veg", """
                {"dish":"Veg Karahi %s","origin":{"cuisine":"Pakistani"},"servings":2,"diet":"vegetarian"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GENERATION_FAILED"));
    }

    @Test
    void validation() throws Exception {
        call("r-val", "{\"dish\":\"\",\"origin\":{},\"servings\":0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.hasItems("dish", "origin.cuisine", "servings")));
    }
}

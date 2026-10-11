package com.idraak.smartbite.v2.recipe;

import com.idraak.smartbite.v2.ai.Deadline;
import com.idraak.smartbite.v2.ai.ProviderChain;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.settings.AppSettings;
import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.store.StoreUnavailableException;
import com.idraak.smartbite.v2.suggestion.Text;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Origin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Recipes on the backend only (D-013). Cached for 30 days by normalised dish + cuisine + region + servings + diet +
 * variant location + prompt version; the Premium gate is applied after the cache (D-014). Recipes don't use the
 * daily suggestion quota. A cache outage only skips the cache.
 */
public class RecipeService {

    private static final Logger log = LoggerFactory.getLogger(RecipeService.class);
    static final String COLLECTION = "recipe_cache";
    static final int MAX_TOKENS = 4000;

    private final ProviderChain chain;
    private final DocumentStore store;
    private final EntitlementService entitlements;
    private final AppSettings settings;
    private final V2Properties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RecipeService(ProviderChain chain, DocumentStore store, EntitlementService entitlements, AppSettings settings,
                         V2Properties props, ObjectMapper mapper, Clock clock) {
        this.chain = chain;
        this.store = store;
        this.entitlements = entitlements;
        this.settings = settings;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Recipe recipe(String uid, RecipeRequest r, boolean entitlementRefresh) {
        boolean premium = entitlements.isPremium(uid, entitlementRefresh);
        String key = cacheKey(r, props.ai().recipePromptVersion());
        boolean gate = settings.recipeStepsPremium();
        int preview = Math.max(0, props.recipes().freePreviewSteps());

        Optional<Recipe> cached = readCache(key);
        if (cached.isPresent()) return cached.get().forCaller(premium, gate, preview, true);

        Deadline deadline = Deadline.in(props.ai().recipeDeadline());
        ProviderChain.Generated<RecipeParser.Parsed> g;
        try {
            g = chain.generate(RecipePrompt.SYSTEM, RecipePrompt.user(r, mapper), MAX_TOKENS, deadline,
                    content -> RecipeParser.parse(content, r.diet(), r.dish(), mapper));
        } catch (RecipeParser.DietConflict e) {
            throw new ApiException(ErrorCode.DISH_CONFLICTS_WITH_DIET);
        }
        RecipeParser.Parsed p = g.value();
        Recipe full = new Recipe("rcp_" + key.substring(0, 12), r.dish().strip(), Origin.of(r.origin().cuisine().strip(),
                r.origin().region() == null ? null : r.origin().region().strip()), r.servings(), p.difficulty(), p.times(),
                p.groups(), p.steps(), p.steps().size(), false, null, p.tips(), p.variant(), Recipe.SAFETY_NOTE, false, false,
                Instant.now(clock));
        writeCache(key, full);
        log.info("recipe generated steps={} degraded={}", p.steps().size(), g.degraded());
        return full.forCaller(premium, gate, preview, false);
    }

    private Optional<Recipe> readCache(String key) {
        try {
            return store.get(COLLECTION, key)
                    .filter(d -> d.get("expire_at") instanceof Instant exp && exp.isAfter(clock.instant()))
                    .map(d -> mapper.readValue((String) d.get("recipe_json"), Recipe.class));
        } catch (StoreUnavailableException | JacksonException | ClassCastException e) {
            log.warn("recipe cache read skipped ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void writeCache(String key, Recipe full) {
        try {
            Instant now = clock.instant();
            store.set(COLLECTION, key, Map.of("recipe_json", mapper.writeValueAsString(full), "prompt_version",
                    props.ai().recipePromptVersion(), "created_at", now, "expire_at", now.plus(props.recipes().cacheTtl())));
        } catch (StoreUnavailableException e) {
            log.warn("recipe cache write skipped");
        }
    }

    static String cacheKey(RecipeRequest r, String promptVersion) {
        String raw = String.join("|", Text.norm(r.dish()), Text.norm(r.origin().cuisine()), Text.norm(r.origin().region()),
                String.valueOf(r.servings()), r.diet() == null ? "-" : r.diet().wire(), Text.norm(r.variantLocation()), promptVersion);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

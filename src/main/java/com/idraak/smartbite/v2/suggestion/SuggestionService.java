package com.idraak.smartbite.v2.suggestion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.idraak.smartbite.v2.ai.Deadline;
import com.idraak.smartbite.v2.area.AreaGrounding;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.entitlement.EntitlementService;
import com.idraak.smartbite.v2.model.Enums.Diet;
import com.idraak.smartbite.v2.model.Enums.MealSlot;
import com.idraak.smartbite.v2.model.Enums.SpiceLevel;
import com.idraak.smartbite.v2.quota.QuotaService;
import com.idraak.smartbite.v2.quota.QuotaSnapshot;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Meta;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Origin;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Suggestion;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse.Verification;
import com.idraak.smartbite.v2.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Orchestrates one pick set: entitlement -> quota reservation -> generation -> reasons -> area grounding -> commit. */
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    private final SuggestionGenerator generator;
    private final AreaGrounding grounding;
    private final QuotaService quota;
    private final EntitlementService entitlements;
    private final V2Properties props;
    private final Clock clock;
    private final Cache<String, MealSuggestionResponse> replays;

    public SuggestionService(SuggestionGenerator generator, AreaGrounding grounding, QuotaService quota,
                             EntitlementService entitlements, V2Properties props, Clock clock) {
        this.generator = generator;
        this.grounding = grounding;
        this.quota = quota;
        this.entitlements = entitlements;
        this.props = props;
        this.clock = clock;
        this.replays = Caffeine.newBuilder().expireAfterWrite(props.quota().idempotentReplayTtl()).maximumSize(20_000).build();
    }

    public MealSuggestionResponse suggest(String uid, MealSuggestionRequest r, String idempotencyKey, boolean entitlementRefresh) {
        String replayKey = idempotencyKey == null ? null : uid + "|" + idempotencyKey;
        if (replayKey != null) {
            MealSuggestionResponse cached = replays.getIfPresent(replayKey);
            if (cached != null) {
                return cached.withQuotaAndMeta(cached.quota(), new Meta(cached.meta().promptVersion(), cached.meta().degraded(), true));
            }
        }
        boolean premium = entitlements.isPremium(uid, entitlementRefresh);
        ZoneId zone = ZoneId.of(r.timezone());
        QuotaService.Reservation reservation = premium ? null : quota.reserve(uid, zone, idempotencyKey);
        try {
            Deadline deadline = Deadline.in(props.ai().suggestionDeadline());
            ZonedDateTime local = ZonedDateTime.ofInstant(clock.instant(), zone);
            MealSlot slot = MealSlot.at(local.getHour());
            Diet diet = effectiveDiet(r);
            SpiceLevel spice = spiceTarget(r);
            LocalDate today = local.toLocalDate();

            SuggestionGenerator.Output out = generator.generate(r, diet, spice, slot, today, deadline);
            int servings = servings(r);
            List<Suggestion> suggestions = new ArrayList<>();
            for (Candidate c : out.candidates()) {
                suggestions.add(new Suggestion(null, c.name(), c.shortDescription(), c.category(), Origin.of(c.cuisine(), c.region()),
                        c.diet(), Boolean.TRUE.equals(c.containsEgg()), c.spiceLevel(), c.primaryProtein(), c.keyIngredients(),
                        c.totalTimeMinutes(), c.difficulty(), servings, ReasonBuilder.build(c, r, diet, spice, today), Verification.none()));
            }
            AreaGrounding.Result grounded = grounding.ground(uid, r, suggestions);
            List<Suggestion> finalList = new ArrayList<>();
            for (int i = 0; i < grounded.suggestions().size(); i++) {
                finalList.add(grounded.suggestions().get(i).withId("sug_" + (i + 1)));
            }

            QuotaSnapshot snapshot = premium ? quota.premiumSnapshot(zone) : quota.commit(uid, reservation);
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            MealSuggestionResponse response = new MealSuggestionResponse(requestId, Instant.now(clock), slot, r.intent(),
                    grounded.areaContext(), List.copyOf(finalList), snapshot,
                    new Meta(props.ai().promptVersion(), out.degraded(), false));
            if (replayKey != null) replays.put(replayKey, response);
            log.info("suggestion set ok count={} degraded={} premium={} intent={}", finalList.size(), out.degraded(), premium, r.intent());
            return response;
        } catch (RuntimeException e) {
            if (reservation != null) quota.release(uid, reservation);
            throw e;
        }
    }

    /** {@code vegetarian_today} can only make the diet stricter, never looser. */
    static Diet effectiveDiet(MealSuggestionRequest r) {
        Diet base = r.profile().diet();
        if (r.refineOrEmpty().isVegetarianToday() && base == Diet.NON_VEGETARIAN) return Diet.VEGETARIAN;
        return base;
    }

    /** Profile tolerance, or one level milder (minimum mild) with {@code refine.milder}. */
    static SpiceLevel spiceTarget(MealSuggestionRequest r) {
        SpiceLevel tolerance = r.profile().spiceTolerance();
        if (tolerance == null) return r.refineOrEmpty().isMilder() ? SpiceLevel.MILD : null;
        if (!r.refineOrEmpty().isMilder()) return tolerance;
        return SpiceLevel.values()[Math.max(0, tolerance.ordinal() - 1)];
    }

    static int servings(MealSuggestionRequest r) {
        if (r.refineOrEmpty().servings() != null) return r.refineOrEmpty().servings();
        if (r.profile().householdSize() != null) return r.profile().householdSize();
        return 2;
    }

    public void evict(String uid) {
        replays.asMap().keySet().removeIf(k -> k.startsWith(uid + "|"));
    }
}

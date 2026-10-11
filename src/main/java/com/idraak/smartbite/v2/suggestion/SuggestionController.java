package com.idraak.smartbite.v2.suggestion;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionRequest;
import com.idraak.smartbite.v2.suggestion.api.MealSuggestionResponse;
import com.idraak.smartbite.v2.web.V2RateLimits;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class SuggestionController {

    private final SuggestionService service;
    private final V2RateLimits limits;

    public SuggestionController(SuggestionService service, V2RateLimits limits) {
        this.service = service;
        this.limits = limits;
    }

    @PostMapping(path = "/api/v2/meal-suggestions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MealSuggestionResponse suggest(AuthenticatedUser user,
                                          @Valid @RequestBody MealSuggestionRequest request,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @RequestHeader(value = "X-Entitlement-Refresh", required = false) Boolean refresh) {
        limits.check(V2RateLimits.Route.SUGGESTIONS, user.uid());
        return service.suggest(user.uid(), request, validKey(idempotencyKey), Boolean.TRUE.equals(refresh));
    }

    static String validKey(String key) {
        if (key == null) return null;
        try {
            return UUID.fromString(key).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.",
                    Map.of("errors", List.of(Map.of("field", "Idempotency-Key", "code", "invalid_value", "message", "must be a UUID"))),
                    0, null);
        }
    }
}

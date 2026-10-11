package com.idraak.smartbite.v2.recipe;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import com.idraak.smartbite.v2.web.V2RateLimits;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecipeController {

    private final RecipeService service;
    private final V2RateLimits limits;

    public RecipeController(RecipeService service, V2RateLimits limits) {
        this.service = service;
        this.limits = limits;
    }

    @PostMapping(path = "/api/v2/recipes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Recipe recipe(AuthenticatedUser user, @Valid @RequestBody RecipeRequest request,
                         @RequestHeader(value = "X-Entitlement-Refresh", required = false) Boolean refresh) {
        limits.check(V2RateLimits.Route.RECIPES, user.uid());
        return service.recipe(user.uid(), request, Boolean.TRUE.equals(refresh));
    }
}

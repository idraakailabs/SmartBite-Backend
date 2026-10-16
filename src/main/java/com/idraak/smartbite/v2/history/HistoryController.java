package com.idraak.smartbite.v2.history;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import com.idraak.smartbite.v2.web.V2RateLimits;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v2/me/history} (D-024). The signed-in user's own history only: the uid comes from the verified ID
 * token and is never accepted from the request.
 */
@RestController
@RequestMapping("/api/v2/me/history")
public class HistoryController {

    private final HistoryService history;
    private final V2RateLimits limits;

    public HistoryController(HistoryService history, V2RateLimits limits) {
        this.history = history;
        this.limits = limits;
    }

    /** Changes since {@code cursor} (all history when absent), oldest first, plus the current favourites. */
    @GetMapping
    public HistoryApi.Page changes(AuthenticatedUser user,
                                   @RequestParam(value = "cursor", required = false) String cursor,
                                   @RequestParam(value = "limit", required = false) Integer limit) {
        limits.check(V2RateLimits.Route.HISTORY, user.uid());
        return history.page(user.uid(), cursor, limit);
    }

    /** Uploads new, edited and deleted entries and favourite changes; the latest client change wins. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public HistoryApi.PushResponse push(AuthenticatedUser user, @Valid @RequestBody HistoryApi.PushRequest request) {
        limits.check(V2RateLimits.Route.HISTORY, user.uid());
        return history.push(user.uid(), request);
    }
}

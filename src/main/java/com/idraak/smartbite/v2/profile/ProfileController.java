package com.idraak.smartbite.v2.profile;

import com.idraak.smartbite.v2.auth.AuthenticatedUser;
import com.idraak.smartbite.v2.web.V2RateLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * {@code /api/v2/me/profile} and {@code /api/v2/me/device} (D-024): the app's profile and FCM token, previously
 * written straight to Firestore. Always the signed-in user's own data.
 */
@RestController
@RequestMapping("/api/v2/me")
public class ProfileController {

    /** {@code profile} is the app's own JSON object, stored and returned unchanged (its keys are not snake_case). */
    public record ProfileResponse(JsonNode profile, Instant updatedAt) {}

    public record ProfileRequest(@NotNull JsonNode profile) {}

    public record DeviceRequest(@NotBlank @Size(max = 4096) @Pattern(regexp = "^\\S+$") String fcmToken) {}

    private final ProfileService profiles;
    private final V2RateLimits limits;

    public ProfileController(ProfileService profiles, V2RateLimits limits) {
        this.profiles = profiles;
        this.limits = limits;
    }

    /** 404 NOT_FOUND when the user has no profile in our database or in Firestore. */
    @GetMapping("/profile")
    public ProfileResponse get(AuthenticatedUser user) {
        limits.check(V2RateLimits.Route.PROFILE, user.uid());
        ProfileRepository.Stored s = profiles.get(user.uid());
        return new ProfileResponse(s.profile(), s.updatedAt());
    }

    @PutMapping(path = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProfileResponse put(AuthenticatedUser user, @Valid @RequestBody ProfileRequest request) {
        limits.check(V2RateLimits.Route.PROFILE, user.uid());
        ProfileRepository.Stored s = profiles.put(user.uid(), request.profile());
        return new ProfileResponse(s.profile(), s.updatedAt());
    }

    @PutMapping(path = "/device", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> device(AuthenticatedUser user, @Valid @RequestBody DeviceRequest request) {
        limits.check(V2RateLimits.Route.PROFILE, user.uid());
        profiles.setDeviceToken(user.uid(), request.fcmToken());
        return ResponseEntity.noContent().build();
    }
}

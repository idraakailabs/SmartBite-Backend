package com.idraak.smartbite.v2.error;

/** Stable machine codes of the v2 error body (openapi-v2.yaml ErrorCode). */
public enum ErrorCode {
    MALFORMED_REQUEST(400, "malformed-request", "Malformed request"),
    UNAUTHENTICATED(401, "unauthenticated", "Sign-in required"),
    REAUTHENTICATION_REQUIRED(401, "reauthentication-required", "Please sign in again"),
    ACCOUNT_REQUIRED(403, "account-required", "A signed-in account is required"),
    NOT_FOUND(404, "not-found", "Not found"),
    METHOD_NOT_ALLOWED(405, "method-not-allowed", "Method not allowed"),
    PAYLOAD_TOO_LARGE(413, "payload-too-large", "Request too large"),
    UNSUPPORTED_MEDIA_TYPE(415, "unsupported-media-type", "Unsupported content type"),
    VALIDATION_FAILED(422, "validation-failed", "Validation failed"),
    DISH_CONFLICTS_WITH_DIET(422, "dish-conflicts-with-diet", "This dish does not fit the selected diet"),
    DAILY_LIMIT_REACHED(429, "daily-limit-reached", "Today's free picks are used up"),
    RATE_LIMITED(429, "rate-limited", "Too many requests"),
    INTERNAL_ERROR(500, "internal-error", "Something went wrong"),
    GENERATION_FAILED(502, "generation-failed", "We couldn't put together safe picks"),
    PROVIDER_UNAVAILABLE(503, "provider-unavailable", "Our food brain is busy");

    private final int status;
    private final String slug;
    private final String title;

    ErrorCode(int status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String type() {
        return "urn:smartbite:problem:" + slug;
    }

    public String title() {
        return title;
    }
}

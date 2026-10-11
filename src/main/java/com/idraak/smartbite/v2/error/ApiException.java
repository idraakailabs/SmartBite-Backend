package com.idraak.smartbite.v2.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A v2 failure with a stable code. {@code detail} must be user-safe; {@code extensions} are extra problem members
 * (snake_case keys such as {@code reset_at}). {@code retryAfterSeconds} sets the Retry-After header when > 0.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String detail;
    private final Map<String, Object> extensions;
    private final long retryAfterSeconds;

    public ApiException(ErrorCode code) {
        this(code, null, Map.of(), 0, null);
    }

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of(), 0, null);
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> extensions, long retryAfterSeconds, Throwable cause) {
        super(code.name() + (detail == null ? "" : ": " + detail), cause, false, false);
        this.code = code;
        this.detail = detail;
        this.extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(ErrorCode.RATE_LIMITED, null,
                Map.of("retry_after_seconds", retryAfterSeconds), retryAfterSeconds, null);
    }

    public static ApiException providerUnavailable(long retryAfterSeconds, Throwable cause) {
        return new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, null,
                Map.of("retry_after_seconds", retryAfterSeconds), retryAfterSeconds, cause);
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

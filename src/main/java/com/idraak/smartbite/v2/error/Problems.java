package com.idraak.smartbite.v2.error;

import com.idraak.smartbite.v2.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the one v2 error body: RFC 9457 members plus {@code code}, {@code request_id} and extensions. Built as an
 * ordered map so the global snake_case naming strategy can't rename anything.
 */
public final class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private Problems() {
    }

    public static Map<String, Object> body(ErrorCode code, String detail, Map<String, Object> extensions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.type());
        body.put("title", code.title());
        body.put("status", code.status());
        if (detail != null) body.put("detail", detail);
        body.put("code", code.name());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) body.put("request_id", requestId);
        body.putAll(extensions);
        return body;
    }

    public static ResponseEntity<Object> response(ApiException e) {
        return response(e.code(), e.detail(), e.extensions(), e.retryAfterSeconds());
    }

    public static ResponseEntity<Object> response(ErrorCode code, String detail, Map<String, Object> extensions, long retryAfter) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status()).contentType(PROBLEM_JSON);
        if (retryAfter > 0) builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        if (code.status() == 401) builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return builder.body(body(code, detail, extensions));
    }

    public static ResponseEntity<Object> validation(List<Map<String, String>> errors) {
        return response(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", Map.of("errors", errors), 0);
    }

    /** For filters, which run outside Spring MVC's exception handling. */
    public static void write(HttpServletResponse response, ObjectMapper mapper, ApiException e) throws IOException {
        response.setStatus(e.code().status());
        response.setContentType(PROBLEM_JSON.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (e.retryAfterSeconds() > 0) response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        if (e.code().status() == 401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(mapper.writeValueAsString(body(e.code(), e.detail(), e.extensions())));
    }

    /** {@code area.radiusKm} -> {@code area.radius_km}, keeping indexes such as {@code history[2].dish}. */
    public static String snakeCasePath(String javaPath) {
        return javaPath.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}

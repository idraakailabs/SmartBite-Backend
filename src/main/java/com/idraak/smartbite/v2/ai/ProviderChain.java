package com.idraak.smartbite.v2.ai;

import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Groq gpt-oss-120b -> Gemini 2.5 Flash-Lite -> Groq gpt-oss-20b. Falls back on 429, 5xx, timeouts, connection
 * errors, other 4xx (logged as an error: likely a key problem) and on output the parser rejects. No attempt starts
 * with less than {@code minRemaining} of the deadline left.
 */
public class ProviderChain {

    private static final Logger log = LoggerFactory.getLogger(ProviderChain.class);

    /** Parsed output and which provider produced it (index 0 = primary). */
    public record Generated<T>(T value, int providerIndex, String providerName) {
        public boolean degraded() {
            return providerIndex > 0;
        }
    }

    /** Thrown by parsers when the model output is unusable. */
    public static class UnusableOutputException extends RuntimeException {
        public UnusableOutputException(String message) {
            super(message, null, false, false);
        }
    }

    private final List<ChatModel> models;
    private final Duration perAttemptTimeout;
    private final Duration minRemaining;

    public ProviderChain(List<ChatModel> models, Duration perAttemptTimeout, Duration minRemaining) {
        this.models = List.copyOf(models);
        this.perAttemptTimeout = perAttemptTimeout;
        this.minRemaining = minRemaining;
    }

    /**
     * @throws ApiException GENERATION_FAILED (502) if a provider answered but no output was usable, otherwise
     *                      PROVIDER_UNAVAILABLE (503)
     */
    public <T> Generated<T> generate(String system, String user, int maxTokens, Deadline deadline, Function<String, T> parser) {
        boolean anyAnswered = false;
        for (int i = 0; i < models.size(); i++) {
            ChatModel m = models.get(i);
            if (!deadline.hasAtLeast(minRemaining)) {
                log.warn("ai deadline nearly spent; skipping {} and later providers", m.name());
                break;
            }
            Duration timeout = min(perAttemptTimeout, deadline.remaining());
            long start = System.nanoTime();
            try {
                String content = m.complete(system, user, timeout, maxTokens);
                anyAnswered = true;
                T value = parser.apply(content);
                log.info("ai attempt ok provider={} attempt={} latency_ms={}", m.name(), i + 1, (System.nanoTime() - start) / 1_000_000);
                return new Generated<>(value, i, m.name());
            } catch (ProviderException e) {
                if (e.kind() == ProviderException.Kind.CLIENT_ERROR) {
                    log.error("ai provider rejected the request provider={} ({}); check its key and model", m.name(), e.getMessage());
                } else {
                    log.warn("ai attempt failed provider={} kind={}", m.name(), e.kind());
                }
            } catch (UnusableOutputException e) {
                log.warn("ai output unusable provider={} reason={}", m.name(), e.getMessage());
            }
        }
        if (anyAnswered) throw new ApiException(ErrorCode.GENERATION_FAILED);
        throw ApiException.providerUnavailable(20, null);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}

package com.idraak.smartbite.v2.ai;

import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderChainTest {

    static ChatModel model(String name, List<String> calls, Function<Duration, String> behaviour) {
        return new ChatModel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String complete(String s, String u, Duration timeout, int maxTokens) {
                calls.add(name);
                return behaviour.apply(timeout);
            }
        };
    }

    static Function<Duration, String> fail(ProviderException.Kind kind) {
        return t -> {
            throw new ProviderException(kind, "x", null);
        };
    }

    static final Function<String, String> PARSER = s -> {
        if (!s.startsWith("ok")) throw new ProviderChain.UnusableOutputException("bad");
        return s;
    };

    @Test
    void fallsBackOn429TimeoutAndGarbage() {
        List<String> calls = new ArrayList<>();
        ProviderChain chain = new ProviderChain(List.of(
                model("a", calls, fail(ProviderException.Kind.RATE_LIMITED)),
                model("b", calls, fail(ProviderException.Kind.TIMEOUT)),
                model("c", calls, t -> "garbage"),
                model("d", calls, t -> "ok!")), Duration.ofSeconds(5), Duration.ZERO);
        ProviderChain.Generated<String> g = chain.generate("s", "u", 10, Deadline.in(Duration.ofSeconds(10)), PARSER);
        assertThat(g.value()).isEqualTo("ok!");
        assertThat(g.degraded()).isTrue();
        assertThat(calls).containsExactly("a", "b", "c", "d");
    }

    @Test
    void fallsBackOn5xxAndClientErrors() {
        List<String> calls = new ArrayList<>();
        ProviderChain chain = new ProviderChain(List.of(
                model("a", calls, fail(ProviderException.Kind.SERVER_ERROR)),
                model("b", calls, fail(ProviderException.Kind.CLIENT_ERROR)),
                model("c", calls, t -> "ok")), Duration.ofSeconds(5), Duration.ZERO);
        assertThat(chain.generate("s", "u", 10, Deadline.in(Duration.ofSeconds(10)), PARSER).providerName()).isEqualTo("c");
    }

    @Test
    void allDownIsProviderUnavailable() {
        List<String> calls = new ArrayList<>();
        ProviderChain chain = new ProviderChain(List.of(
                model("a", calls, fail(ProviderException.Kind.CONNECTION)),
                model("b", calls, fail(ProviderException.Kind.RATE_LIMITED))), Duration.ofSeconds(5), Duration.ZERO);
        assertThatThrownBy(() -> chain.generate("s", "u", 10, Deadline.in(Duration.ofSeconds(10)), PARSER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
                    assertThat(e.retryAfterSeconds()).isPositive();
                });
    }

    @Test
    void answeredButUnusableIsGenerationFailed() {
        List<String> calls = new ArrayList<>();
        ProviderChain chain = new ProviderChain(List.of(
                model("a", calls, t -> "nope"),
                model("b", calls, fail(ProviderException.Kind.SERVER_ERROR))), Duration.ofSeconds(5), Duration.ZERO);
        assertThatThrownBy(() -> chain.generate("s", "u", 10, Deadline.in(Duration.ofSeconds(10)), PARSER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.GENERATION_FAILED));
    }

    @Test
    void perAttemptTimeoutIsCappedByTheDeadlineAndNoAttemptStartsTooLate() {
        List<String> calls = new ArrayList<>();
        List<Duration> timeouts = new ArrayList<>();
        ProviderChain chain = new ProviderChain(List.of(
                model("a", calls, t -> {
                    timeouts.add(t);
                    throw new ProviderException(ProviderException.Kind.TIMEOUT, "x", null);
                }),
                model("b", calls, t -> "ok")), Duration.ofSeconds(15), Duration.ofSeconds(5));
        assertThatThrownBy(() -> chain.generate("s", "u", 10, Deadline.in(Duration.ofSeconds(4)), PARSER))
                .isInstanceOf(ApiException.class);
        assertThat(calls).isEmpty();

        chain.generate("s", "u", 10, Deadline.in(Duration.ofSeconds(8)), PARSER);
        assertThat(timeouts.get(0)).isLessThanOrEqualTo(Duration.ofSeconds(8));
    }
}

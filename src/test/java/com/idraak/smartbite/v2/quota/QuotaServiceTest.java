package com.idraak.smartbite.v2.quota;

import com.idraak.smartbite.testsupport.MutableClock;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.settings.RemoteConfigAppSettings;
import com.idraak.smartbite.v2.store.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaServiceTest {

    static final ZoneId KARACHI = ZoneId.of("Asia/Karachi"); // UTC+5, no DST
    static final ZoneId LONDON = ZoneId.of("Europe/London");

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z")); // 15:00 in Karachi
    final InMemoryDocumentStore store = new InMemoryDocumentStore();
    final V2Properties.Quota cfg = new V2Properties.Quota(3, Duration.ofMinutes(2), Duration.ofHours(24), false,
            Duration.ofMinutes(5), Duration.ofMinutes(10));
    final QuotaService quota = new QuotaService(store, new RemoteConfigAppSettings(3, true, null), cfg, clock);

    private QuotaSnapshot useOne(String uid, ZoneId zone) {
        return quota.commit(uid, quota.reserve(uid, zone, null));
    }

    @Test
    void allowsLimitSuccessfulSetsThenRejectsWithResetAt() {
        assertThat(useOne("u", KARACHI).remaining()).isEqualTo(2);
        useOne("u", KARACHI);
        QuotaSnapshot last = useOne("u", KARACHI);
        assertThat(last.used()).isEqualTo(3);
        assertThat(last.remaining()).isZero();
        assertThat(last.resetAt()).isEqualTo(Instant.parse("2026-10-11T19:00:00Z"));

        assertThatThrownBy(() -> quota.reserve("u", KARACHI, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.DAILY_LIMIT_REACHED);
                    assertThat(e.extensions()).containsEntry("limit", 3).containsEntry("used", 3)
                            .containsEntry("reset_at", "2026-10-11T19:00:00Z");
                    assertThat(e.retryAfterSeconds()).isEqualTo(Duration.ofHours(9).toSeconds());
                });
    }

    @Test
    void failedRequestsReleaseTheirSlotAndAreNotCounted() {
        QuotaService.Reservation r = quota.reserve("u", KARACHI, null);
        quota.release("u", r);
        assertThat(quota.peek("u", KARACHI).used()).isZero();
        useOne("u", KARACHI);
        useOne("u", KARACHI);
        useOne("u", KARACHI);
        assertThat(quota.peek("u", KARACHI).used()).isEqualTo(3);
    }

    @Test
    void inFlightReservationsCountTowardsTheLimit() {
        quota.reserve("u", KARACHI, null);
        quota.reserve("u", KARACHI, null);
        quota.reserve("u", KARACHI, null);
        assertThatThrownBy(() -> quota.reserve("u", KARACHI, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void abandonedReservationsExpire() {
        quota.reserve("u", KARACHI, null);
        quota.reserve("u", KARACHI, null);
        quota.reserve("u", KARACHI, null);
        clock.advance(Duration.ofMinutes(3));
        assertThat(quota.reserve("u", KARACHI, null).alreadyCommitted()).isFalse();
    }

    @Test
    void sameIdempotencyKeyIsNeverCountedTwice() {
        QuotaService.Reservation first = quota.reserve("u", KARACHI, "key-1");
        quota.commit("u", first);
        QuotaService.Reservation retry = quota.reserve("u", KARACHI, "key-1");
        assertThat(retry.alreadyCommitted()).isTrue();
        assertThat(quota.commit("u", retry).used()).isEqualTo(1);
        // A retry that raced the original and committed again still counts once.
        quota.commit("u", new QuotaService.Reservation("key-1", first.day(), first.timezone(), false));
        assertThat(quota.peek("u", KARACHI).used()).isEqualTo(1);
    }

    @Test
    void dayRollsOverAtLocalMidnight() {
        useOne("u", KARACHI);
        useOne("u", KARACHI);
        useOne("u", KARACHI);
        clock.set(Instant.parse("2026-10-11T18:59:59Z"));
        assertThatThrownBy(() -> quota.reserve("u", KARACHI, null)).isInstanceOf(ApiException.class);
        clock.set(Instant.parse("2026-10-11T19:00:00Z")); // midnight in Karachi
        QuotaSnapshot s = useOne("u", KARACHI);
        assertThat(s.day()).hasToString("2026-10-12");
        assertThat(s.used()).isEqualTo(1);
    }

    @Test
    void timezoneHoppingWithin24hUsesThePinnedZone() {
        useOne("u", KARACHI);
        useOne("u", KARACHI);
        useOne("u", KARACHI);
        clock.set(Instant.parse("2026-10-11T19:30:00Z")); // Karachi: next day; London: still 2026-10-11
        // Switch to London before 24 h: the pin holds, so the day is Karachi's new day.
        QuotaSnapshot s = useOne("u", LONDON);
        assertThat(s.timezone()).isEqualTo("Asia/Karachi");
        assertThat(s.day()).hasToString("2026-10-12");

        clock.advance(Duration.ofHours(25));
        QuotaSnapshot later = useOne("u", LONDON);
        assertThat(later.timezone()).isEqualTo("Europe/London");
    }

    @Test
    void hoppingCannotResetTheDay() {
        clock.set(Instant.parse("2026-10-11T20:00:00Z")); // Karachi 01:00 on 12th, London 21:00 on 11th
        useOne("u", LONDON);
        useOne("u", LONDON);
        useOne("u", LONDON);
        assertThatThrownBy(() -> quota.reserve("u", KARACHI, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentRequestsNeverExceedTheLimit() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    useOne("u", KARACHI);
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get(10, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertThat(ok).isEqualTo(3);
        assertThat(quota.peek("u", KARACHI).used()).isEqualTo(3);
    }

    @Test
    void storeOutageIsProviderUnavailableNotFreeUsage() {
        store.setUnavailable(true);
        assertThatThrownBy(() -> quota.reserve("u", KARACHI, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
    }

    @Test
    void peekDoesNotChangeAnything() {
        assertThat(quota.peek("u", KARACHI).used()).isZero();
        assertThat(store.count("quota")).isZero();
    }

    @Test
    void deleteRemovesTheCounter() {
        useOne("u", KARACHI);
        assertThat(quota.delete("u")).isTrue();
        assertThat(quota.delete("u")).isFalse();
        assertThat(quota.peek("u", KARACHI).used()).isZero();
    }
}

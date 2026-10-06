package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.SessionRateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie les invariants du token bucket : burst initial, refill linéaire,
 * coût surdimensionné de {@code force=true}, calcul du Retry-After et
 * éviction des buckets idles. Horloge injectée pour rendre tout déterministe.
 */
class IpSessionRateLimiterTest {

    /** Horloge contrôlée — avance via {@link #advance(Duration)}. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;

        TestClock(Instant start) { this.now = new AtomicReference<>(start); }
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private IpSessionRateLimiter limiter(SessionRateLimitProperties props, Clock clock) {
        return new IpSessionRateLimiter(props, meterRegistry, clock);
    }

    @Test
    void disabled_alwaysAccepts() {
        var props = new SessionRateLimitProperties(false, 1, 1, 5, Duration.ofMinutes(10));
        var l = limiter(props, new TestClock(Instant.EPOCH));
        // 100 appels même IP : tous acceptés car enabled=false.
        for (int i = 0; i < 100; i++) {
            assertThat(l.tryAcquire("10.0.0.1", false).accepted()).isTrue();
        }
    }

    @Test
    void burstUpToCapacity_thenRejects() {
        var props = new SessionRateLimitProperties(true, 3, 60, 1, Duration.ofMinutes(10));
        var l = limiter(props, new TestClock(Instant.EPOCH));

        // 3 acceptés (capacity), 4e rejeté.
        assertThat(l.tryAcquire("ip", false).accepted()).isTrue();
        assertThat(l.tryAcquire("ip", false).accepted()).isTrue();
        assertThat(l.tryAcquire("ip", false).accepted()).isTrue();

        var rejected = l.tryAcquire("ip", false);
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void refills_overTime() {
        var props = new SessionRateLimitProperties(true, 2, 60, 1, Duration.ofMinutes(10));
        var clock = new TestClock(Instant.EPOCH);
        var l = limiter(props, clock);

        // Épuise le bucket.
        l.tryAcquire("ip", false);
        l.tryAcquire("ip", false);
        assertThat(l.tryAcquire("ip", false).accepted()).isFalse();

        // 60/min = 1/sec. Avance 1 s → 1 jeton dispo.
        clock.advance(Duration.ofSeconds(1));
        assertThat(l.tryAcquire("ip", false).accepted()).isTrue();
        assertThat(l.tryAcquire("ip", false).accepted()).isFalse();
    }

    @Test
    void perIpIsolation() {
        var props = new SessionRateLimitProperties(true, 1, 1, 1, Duration.ofMinutes(10));
        var l = limiter(props, new TestClock(Instant.EPOCH));

        assertThat(l.tryAcquire("10.0.0.1", false).accepted()).isTrue();
        assertThat(l.tryAcquire("10.0.0.2", false).accepted()).isTrue();
        // Les deux IPs ont chacune leur bucket : l'épuisement de l'une
        // ne pollue pas l'autre.
        assertThat(l.tryAcquire("10.0.0.1", false).accepted()).isFalse();
        assertThat(l.tryAcquire("10.0.0.2", false).accepted()).isFalse();
    }

    @Test
    void forceCostsMore() {
        var props = new SessionRateLimitProperties(true, 5, 1, 5, Duration.ofMinutes(10));
        var l = limiter(props, new TestClock(Instant.EPOCH));

        // Une seule force épuise le bucket entier.
        assertThat(l.tryAcquire("ip", true).accepted()).isTrue();
        var second = l.tryAcquire("ip", true);
        assertThat(second.accepted()).isFalse();
    }

    @Test
    void mixedNormalAndForce_followCost() {
        var props = new SessionRateLimitProperties(true, 6, 1, 5, Duration.ofMinutes(10));
        var l = limiter(props, new TestClock(Instant.EPOCH));

        // force=5 + normal=1 = 6 → bucket vide après.
        assertThat(l.tryAcquire("ip", true).accepted()).isTrue();   // -5
        assertThat(l.tryAcquire("ip", false).accepted()).isTrue();  // -1
        assertThat(l.tryAcquire("ip", false).accepted()).isFalse(); // vide
    }

    @Test
    void retryAfter_atLeastOneSecond_whenBucketEmpty() {
        var props = new SessionRateLimitProperties(true, 1, 60, 1, Duration.ofMinutes(10));
        var l = limiter(props, new TestClock(Instant.EPOCH));

        l.tryAcquire("ip", false);
        var rejected = l.tryAcquire("ip", false);
        assertThat(rejected.accepted()).isFalse();
        // 1/s refill, bucket vide → ~1 s d'attente, plancher RFC à 1 s.
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    void costExceedingCapacity_failsOpen() {
        // forceCost (10) > capacity (5) : config aberrante, on accepte plutôt
        // que de fermer définitivement l'endpoint pour force=true.
        var props = new SessionRateLimitProperties(true, 5, 1, 10, Duration.ofMinutes(10));
        var l = limiter(props, new TestClock(Instant.EPOCH));

        assertThat(l.tryAcquire("ip", true).accepted()).isTrue();
        assertThat(l.tryAcquire("ip", true).accepted()).isTrue();
    }

    @Test
    void evictIdleBuckets_removesFullAndStale() {
        var props = new SessionRateLimitProperties(true, 1, 60, 1, Duration.ofMinutes(10));
        var clock = new TestClock(Instant.EPOCH);
        var l = limiter(props, clock);

        // Crée un bucket, le laisse se refill jusqu'au plein.
        l.tryAcquire("ip", false);
        clock.advance(Duration.ofSeconds(2)); // refill > capacity → plein
        assertThat(l.bucketCountForTest()).isEqualTo(1);

        // Avance au-delà de idle-eviction (10 min) sans toucher au bucket.
        clock.advance(Duration.ofMinutes(11));
        l.evictIdleBuckets();
        assertThat(l.bucketCountForTest()).isZero();
    }

    @Test
    void evictIdleBuckets_keepsActiveBucket() {
        var props = new SessionRateLimitProperties(true, 5, 60, 1, Duration.ofSeconds(30));
        var clock = new TestClock(Instant.EPOCH);
        var l = limiter(props, clock);

        l.tryAcquire("ip", false);
        // Touch récent : pas d'éviction même si le bucket est plein.
        clock.advance(Duration.ofSeconds(10));
        l.evictIdleBuckets();
        assertThat(l.bucketCountForTest()).isEqualTo(1);
    }
}

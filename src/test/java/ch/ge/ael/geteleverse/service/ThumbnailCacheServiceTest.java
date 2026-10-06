package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.ThumbnailCacheProperties;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThumbnailCacheServiceTest {

    @Mock HazelcastInstance hazelcast;
    @Mock IMap<String, byte[]> thumbnailsMap;

    private SimpleMeterRegistry meterRegistry;

    private static final String KEY = "tok/page-001-thumb.jpg";
    private static final String BACKUP_KEY = "tok/page-001-prev-thumb.jpg";
    private static final byte[] THUMB = "fake-jpeg".getBytes();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    /** Service actif, map branchée. */
    private ThumbnailCacheService enabled() {
        when(hazelcast.<String, byte[]>getMap(ThumbnailCacheService.MAP_NAME)).thenReturn(thumbnailsMap);
        var service = new ThumbnailCacheService(hazelcast,
                new ThumbnailCacheProperties(true, Duration.ofMinutes(30)), meterRegistry);
        service.init();
        return service;
    }

    private double counter(String result) {
        var c = meterRegistry.find("televerse.thumbnail.cache").tag("result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    // ===== put =====

    @Test
    void put_writesWithConfiguredTtl() {
        var service = enabled();
        when(thumbnailsMap.setAsync(eq(KEY), eq(THUMB), eq(1800L), eq(TimeUnit.SECONDS)))
                .thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(service.put(KEY, THUMB)).verifyComplete();

        verify(thumbnailsMap).setAsync(KEY, THUMB, 1800L, TimeUnit.SECONDS);
    }

    @Test
    void put_absorbsHazelcastFailure() {
        // Une vignette non écrite se régénère à la lecture : faire remonter
        // l'erreur ferait échouer un upload par ailleurs réussi.
        var service = enabled();
        when(thumbnailsMap.setAsync(any(), any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new HazelcastException("cluster down")));

        StepVerifier.create(service.put(KEY, THUMB)).verifyComplete();

        assertThat(counter("error")).isEqualTo(1.0);
    }

    @Test
    void put_ignoresEmptyPayload() {
        var service = enabled();

        StepVerifier.create(service.put(KEY, new byte[0])).verifyComplete();

        verify(thumbnailsMap, never()).setAsync(any(), any(), anyLong(), any());
    }

    // ===== get =====

    @Test
    void get_returnsCachedBytesAndCountsHit() {
        var service = enabled();
        when(thumbnailsMap.getAsync(KEY)).thenReturn(CompletableFuture.completedFuture(THUMB));

        StepVerifier.create(service.get(KEY))
                .assertNext(bytes -> assertThat(bytes).isEqualTo(THUMB))
                .verifyComplete();

        assertThat(counter("hit")).isEqualTo(1.0);
        assertThat(counter("miss")).isZero();
    }

    @Test
    void get_returnsEmptyAndCountsMiss_whenAbsent() {
        var service = enabled();
        when(thumbnailsMap.getAsync(KEY)).thenReturn(CompletableFuture.completedFuture(null));

        StepVerifier.create(service.get(KEY)).verifyComplete();

        assertThat(counter("miss")).isEqualTo(1.0);
        assertThat(counter("hit")).isZero();
    }

    @Test
    void get_returnsEmptyAndCountsError_whenHazelcastFails() {
        // Cluster injoignable : indistinguable d'un défaut côté appelant (les
        // deux régénèrent), mais compté séparément pour l'alerting.
        var service = enabled();
        when(thumbnailsMap.getAsync(KEY))
                .thenReturn(CompletableFuture.failedFuture(new HazelcastException("cluster down")));

        StepVerifier.create(service.get(KEY)).verifyComplete();

        assertThat(counter("error")).isEqualTo(1.0);
        assertThat(counter("miss")).isZero();
    }

    // ===== deleteAll =====

    @Test
    void deleteAll_removesEveryKeyAndSkipsNulls() {
        var service = enabled();
        when(thumbnailsMap.deleteAsync(any())).thenReturn(CompletableFuture.completedFuture(true));

        var keys = new java.util.ArrayList<String>(List.of(KEY, BACKUP_KEY));
        keys.add(null);

        StepVerifier.create(service.deleteAll(keys)).verifyComplete();

        verify(thumbnailsMap).deleteAsync(KEY);
        verify(thumbnailsMap).deleteAsync(BACKUP_KEY);
        verify(thumbnailsMap, times(2)).deleteAsync(any());
    }

    @Test
    void deleteAll_boundsConcurrentInvocations_onLargeBatch() throws Exception {
        // Régression : deleteAll laissait flatMap prendre sa concurrence par défaut (256),
        // donc un lot de N clés émettait N deleteAsync en vol simultanément. Cumulé au
        // bulkhead de finalisation, le pic dépassait
        // hazelcast.client.max.concurrent.invocations → HazelcastOverloadException.
        var service = enabled();
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var gate = new CompletableFuture<Boolean>();
        var futures = new java.util.ArrayList<CompletableFuture<Boolean>>();

        when(thumbnailsMap.deleteAsync(any())).thenAnswer(inv -> {
            // Les futures ne se complètent qu'après ouverture du portillon : tout ce qui
            // a été émis reste « en vol », ce qui rend le pic observable.
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            var f = gate.thenApply(ignored -> {
                inFlight.decrementAndGet();
                return true;
            });
            futures.add(f);
            return f;
        });

        var keys = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> "tok/page-" + i + "-thumb.jpg")
                .toList();

        StepVerifier.create(service.deleteAll(keys))
                .then(() -> gate.complete(true))
                .verifyComplete();

        verify(thumbnailsMap, times(60)).deleteAsync(any());
        assertThat(peak.get())
                .as("invocations Hazelcast en vol pendant la purge")
                .isLessThanOrEqualTo(ThumbnailCacheService.PURGE_CONCURRENCY);
    }

    @Test
    void deleteAll_isNoOp_onEmptyCollection() {
        var service = enabled();

        StepVerifier.create(service.deleteAll(List.of())).verifyComplete();

        verifyNoInteractions(thumbnailsMap);
    }

    // ===== coupe-circuit =====

    @Test
    void disabled_neverTouchesHazelcast() {
        // app.thumbnail-cache.enabled=false : tout le trafic vignette repart en
        // régénération à la volée, sans redéploiement.
        var service = new ThumbnailCacheService(hazelcast,
                new ThumbnailCacheProperties(false, Duration.ofMinutes(30)), meterRegistry);
        service.init();

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.cachedThumbnailCount()).isEqualTo(-1);

        StepVerifier.create(service.put(KEY, THUMB)).verifyComplete();
        StepVerifier.create(service.get(KEY)).verifyComplete();
        StepVerifier.create(service.delete(KEY)).verifyComplete();
        StepVerifier.create(service.deleteAll(List.of(KEY))).verifyComplete();

        verifyNoInteractions(hazelcast);
        verifyNoInteractions(thumbnailsMap);
    }

    @Test
    void cachedThumbnailCount_returnsSentinel_whenClusterUnreachable() {
        // Même sémantique que la jauge des sessions : -1 distingue « cache vide »
        // de « cluster injoignable » côté Grafana.
        var service = enabled();
        when(thumbnailsMap.size()).thenThrow(new HazelcastException("cluster down"));

        assertThat(service.cachedThumbnailCount()).isEqualTo(-1);
    }
}

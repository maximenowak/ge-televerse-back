package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.SessionRateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket par IP d'origine sur la création de sessions.
 *
 * <p>Chaque appel à {@link #tryAcquire(String, boolean)} consomme
 * {@code costFor(force)} jetons du bucket associé à l'IP. Refill linéaire à
 * {@code refillTokensPerMinute / 60 s}. Bucket par-pod (mémoire locale) : voir
 * {@link SessionRateLimitProperties} pour la discussion sur le scaling
 * horizontal.</p>
 *
 * <p>Observabilité :
 * <ul>
 *   <li>{@code televerse.sessions.rate_limited} (counter, tag {@code mode})
 *       — rejets, séparés normal vs force pour corréler avec
 *       {@code televerse.originator.force}.</li>
 *   <li>{@code televerse.sessions.rate_limit.buckets} (gauge) — taille de la
 *       map, signal d'attaque distribuée (scan d'IPs).</li>
 * </ul>
 */
@Service
@Slf4j
public class IpSessionRateLimiter {

    private final SessionRateLimitProperties props;
    private final Clock clock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Counter rejectionsNormal;
    private final Counter rejectionsForce;

    @Autowired
    public IpSessionRateLimiter(SessionRateLimitProperties props, MeterRegistry meterRegistry) {
        this(props, meterRegistry, Clock.systemUTC());
    }

    /** Constructeur exposé pour les tests : permet d'injecter une horloge déterministe. */
    IpSessionRateLimiter(SessionRateLimitProperties props, MeterRegistry meterRegistry, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.rejectionsNormal = Counter.builder("televerse.sessions.rate_limited")
                .description("Créations de session rejetées par le rate-limiter IP.")
                .tag("mode", "normal")
                .register(meterRegistry);
        this.rejectionsForce = Counter.builder("televerse.sessions.rate_limited")
                .description("Créations de session rejetées par le rate-limiter IP.")
                .tag("mode", "force")
                .register(meterRegistry);
        Gauge.builder("televerse.sessions.rate_limit.buckets", buckets, ConcurrentHashMap::size)
                .description("Nombre de buckets actifs (signal d'attaque distribuée par scan d'IPs).")
                .register(meterRegistry);

        log.info("IpSessionRateLimiter ready enabled={} capacity={} refillPerMin={} forceCost={}",
                props.enabled(), props.capacity(), props.refillTokensPerMinute(), props.forceCost());
    }

    /**
     * Tente de consommer {@code costFor(force)} jetons du bucket de l'IP.
     *
     * @return décision avec, en cas de rejet, le délai estimé (secondes ≥ 1)
     *         avant que le bucket ait accumulé assez de jetons pour la requête.
     */
    public Decision tryAcquire(String ip, boolean force) {
        if (!props.enabled()) {
            return Decision.ACCEPTED;
        }
        int cost = props.costFor(force);
        if (cost > props.capacity()) {
            // Config incohérente : un coût > capacité rendrait l'endpoint
            // inatteignable. On fail-open avec un log loud — on préfère un trou
            // de sécurité observable à un endpoint mort silencieux.
            log.error("Rate limit misconfigured cost={} > capacity={} — admitting request",
                    cost, props.capacity());
            return Decision.ACCEPTED;
        }
        var bucket = buckets.computeIfAbsent(ip, k -> new TokenBucket(props.capacity(), nowNanos()));
        long retryAfterMs = bucket.tryConsume(cost, refillPerNano(), props.capacity(), nowNanos());
        if (retryAfterMs <= 0) {
            return Decision.ACCEPTED;
        }
        (force ? rejectionsForce : rejectionsNormal).increment();
        long retryAfterSeconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
        return new Decision(false, retryAfterSeconds);
    }

    /**
     * Éviction périodique des buckets pleins et inactifs depuis
     * {@code idleEviction}. {@code @Scheduled} fixedDelay évite que les
     * cleanups se chevauchent si une éviction longue ralentit.
     */
    @Scheduled(fixedDelayString = "${app.rate-limit.session-create.idle-eviction:PT10M}")
    public void evictIdleBuckets() {
        if (!props.enabled() || buckets.isEmpty()) return;
        long now = nowNanos();
        long idleNanos = props.idleEviction().toNanos();
        int removed = 0;
        for (var entry : buckets.entrySet()) {
            var bucket = entry.getValue();
            if (bucket.isFullAndIdle(idleNanos, refillPerNano(), props.capacity(), now)) {
                // remove(k, v) : ne supprime que si la valeur n'a pas changé
                // depuis la lecture — un appel concurrent à tryConsume sur la
                // même IP entre-temps invalide l'éviction. Pas grave : on
                // ressayera au prochain tick.
                if (buckets.remove(entry.getKey(), bucket)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("Rate-limit bucket eviction removed={} remaining={}", removed, buckets.size());
        }
    }

    /** Refill en jetons-par-nanoseconde. Calculé hors hot-path. */
    private double refillPerNano() {
        return props.refillTokensPerMinute() / 60_000_000_000.0;
    }

    private long nowNanos() {
        // System.nanoTime() suffirait, mais Clock.instant() est testable et
        // millis-precise — la précision sub-ms ne nous apporte rien ici.
        return clock.instant().toEpochMilli() * 1_000_000L;
    }

    /** Résultat d'une tentative d'acquisition. */
    public record Decision(boolean accepted, long retryAfterSeconds) {
        public static final Decision ACCEPTED = new Decision(true, 0L);
    }

    /**
     * Bucket de jetons à refill linéaire. {@code synchronized} sur les chemins
     * mutants — la pression est par-IP, jamais hot-path global.
     */
    static final class TokenBucket {
        /** En jetons fractionnaires pour ne pas perdre de précision sur des refills sub-jeton. */
        private double tokens;
        private long lastRefillNanos;
        private long lastTouchNanos;

        TokenBucket(int initialCapacity, long nowNanos) {
            this.tokens = initialCapacity;
            this.lastRefillNanos = nowNanos;
            this.lastTouchNanos = nowNanos;
        }

        /**
         * @return 0 si accepté ; sinon délai estimé en millisecondes avant que
         *         le bucket atteigne {@code cost} jetons (toujours ≥ 1 ms quand
         *         positif pour qu'un caller convertisse proprement en
         *         {@code Retry-After} seconds).
         */
        synchronized long tryConsume(int cost, double refillPerNano, int capacity, long nowNanos) {
            refill(refillPerNano, capacity, nowNanos);
            lastTouchNanos = nowNanos;
            if (tokens >= cost) {
                tokens -= cost;
                return 0L;
            }
            double missing = cost - tokens;
            // Évite la division par zéro si refill=0 (rate-limit verrouillé) :
            // dans ce cas pathologique on renvoie un délai d'une minute.
            if (refillPerNano <= 0.0) return 60_000L;
            double waitNanos = missing / refillPerNano;
            long waitMs = (long) Math.ceil(waitNanos / 1_000_000.0);
            return Math.max(1L, waitMs);
        }

        synchronized boolean isFullAndIdle(long idleNanos, double refillPerNano, int capacity, long nowNanos) {
            refill(refillPerNano, capacity, nowNanos);
            return tokens >= capacity && (nowNanos - lastTouchNanos) >= idleNanos;
        }

        private void refill(double refillPerNano, int capacity, long nowNanos) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) return;
            tokens = Math.min((double) capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = nowNanos;
        }

        // Hooks de test : lecture instantanée de l'état interne, sans muter.
        synchronized double tokensForTest() { return tokens; }
    }

    /** Exposé pour le test d'éviction et l'observabilité ad-hoc. */
    int bucketCountForTest() { return buckets.size(); }
}

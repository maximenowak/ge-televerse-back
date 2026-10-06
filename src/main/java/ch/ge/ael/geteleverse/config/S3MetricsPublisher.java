package ch.ge.ael.geteleverse.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.metrics.MetricRecord;
import software.amazon.awssdk.metrics.SdkMetric;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Publie en jauges Micrometer les compteurs du pool de connexions HTTP du
 * SDK AWS Netty ({@code NettyNioAsyncHttpClient}). Le SDK émet une
 * {@link MetricCollection} à la fin de chaque appel ; on en extrait les
 * compteurs qui diagnostiquent l'exhaustion du pool et on les expose sous
 * le préfixe {@code aws.sdk.http.*} via {@code /actuator/prometheus}.
 *
 * <p>Les valeurs sont stockées dans des {@link AtomicLong} mis à jour à
 * chaque {@link #publish(MetricCollection)} — les jauges Micrometer lisent
 * la dernière valeur connue, ce qui suffit pour un signal d'observation.</p>
 */
public class S3MetricsPublisher implements MetricPublisher {

    private final AtomicLong maxConcurrency = new AtomicLong();
    private final AtomicLong availableConcurrency = new AtomicLong();
    private final AtomicLong leasedConcurrency = new AtomicLong();
    private final AtomicLong pendingAcquires = new AtomicLong();

    public S3MetricsPublisher(MeterRegistry registry) {
        Gauge.builder("aws.sdk.http.max.concurrency", maxConcurrency, AtomicLong::doubleValue)
                .description("Plafond du pool de connexions configuré sur le client HTTP Netty du SDK AWS.")
                .register(registry);
        Gauge.builder("aws.sdk.http.available.concurrency", availableConcurrency, AtomicLong::doubleValue)
                .description("Connexions idle disponibles dans le pool.")
                .register(registry);
        Gauge.builder("aws.sdk.http.leased.concurrency", leasedConcurrency, AtomicLong::doubleValue)
                .description("Connexions actuellement louées (en cours d'utilisation).")
                .register(registry);
        Gauge.builder("aws.sdk.http.pending.acquires", pendingAcquires, AtomicLong::doubleValue)
                .description("Requêtes en attente d'une connexion libre dans le pool.")
                .register(registry);
    }

    @Override
    public void publish(MetricCollection collection) {
        readLatest(collection, HttpMetric.MAX_CONCURRENCY, maxConcurrency);
        readLatest(collection, HttpMetric.AVAILABLE_CONCURRENCY, availableConcurrency);
        readLatest(collection, HttpMetric.LEASED_CONCURRENCY, leasedConcurrency);
        readLatest(collection, HttpMetric.PENDING_CONCURRENCY_ACQUIRES, pendingAcquires);
        collection.children().forEach(this::publish);
    }

    private static void readLatest(MetricCollection collection, SdkMetric<Integer> metric, AtomicLong target) {
        for (MetricRecord<?> record : collection) {
            if (record.metric().equals(metric) && record.value() instanceof Integer value) {
                target.set(value);
                return;
            }
        }
    }

    @Override
    public void close() {
        // Pas de ressource à libérer : les jauges Micrometer sont gérées par le registre.
    }
}

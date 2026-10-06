package ch.ge.ael.geteleverse.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Crée les Bulkheads d'admission control de l'application.
 *
 * <p>Le Bulkhead {@code upload} est appliqué au chemin
 * {@code POST /api/upload/{token}} pour borner le nombre d'uploads en vol par
 * pod. Le Bulkhead {@code edit} borne de son côté les endpoints d'édition
 * ({@code rotate / crop / deskew / white-balance} de {@code SessionController}),
 * dont le fan-out S3 + render PDFBox pèse autant sur le pool de connexions et
 * la mémoire du pod. Au-delà de la capacité, les requêtes patientent
 * {@code maxWait} avant d'être rejetées :
 * {@link io.github.resilience4j.bulkhead.BulkheadFullException} est levée et
 * traduite en 429 + {@code Retry-After} par
 * {@link ch.ge.ael.geteleverse.controller.GlobalExceptionHandler}
 * ({@code UPLOAD_BUSY} / {@code EDIT_BUSY} / {@code MERGE_BUSY} / {@code FINALIZE_BUSY}
 * selon le chemin).</p>
 *
 * <p>Le Bulkhead {@code merge} borne les aperçus ({@code preview.pdf}, rendu de
 * vignette à la volée), et le Bulkhead {@code finalize} la finalisation
 * ({@code mergeAndFinalize}) — séparés car la finalisation retient un permis bien
 * plus longtemps qu'un aperçu (jusqu'à {@code app.finalize-timeout}) et
 * affamerait sinon les aperçus interactifs. Tous deux téléchargent + assemblent
 * des pages : leur fan-out S3 (GET par page) + merge PDFBox pèse sur le pool et la RAM.</p>
 *
 * <p>Métriques Micrometer auto-enregistrées via {@link TaggedBulkheadMetrics},
 * taggées {@code name="upload"} / {@code name="edit"} / {@code name="merge"} /
 * {@code name="finalize"} :</p>
 * <ul>
 *   <li>{@code resilience4j_bulkhead_available_concurrent_calls} (gauge) —
 *       permis libres, utilisé comme metric de scaling</li>
 *   <li>{@code resilience4j_bulkhead_max_allowed_concurrent_calls} (gauge) —
 *       capacité, pour calculer le taux de saturation</li>
 * </ul>
 */
@Configuration
@Slf4j
public class AdmissionConfig {

    public static final String UPLOAD_BULKHEAD = "upload";
    public static final String EDIT_BULKHEAD = "edit";
    public static final String MERGE_BULKHEAD = "merge";
    public static final String FINALIZE_BULKHEAD = "finalize";

    @Bean(UPLOAD_BULKHEAD)
    public Bulkhead uploadBulkhead(UploadAdmissionProperties props, MeterRegistry meterRegistry) {
        var config = BulkheadConfig.custom()
                .maxConcurrentCalls(props.maxConcurrent())
                .maxWaitDuration(props.maxWait())
                .build();
        var registry = BulkheadRegistry.of(config);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);

        log.info("UploadAdmissionControl ready maxConcurrent={} maxWait={} retryAfter={}",
                props.maxConcurrent(), props.maxWait(), props.retryAfter());
        return registry.bulkhead(UPLOAD_BULKHEAD);
    }

    @Bean(EDIT_BULKHEAD)
    public Bulkhead editBulkhead(EditAdmissionProperties props, MeterRegistry meterRegistry) {
        var config = BulkheadConfig.custom()
                .maxConcurrentCalls(props.maxConcurrent())
                .maxWaitDuration(props.maxWait())
                .build();
        var registry = BulkheadRegistry.of(config);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);

        log.info("EditAdmissionControl ready maxConcurrent={} maxWait={} retryAfter={}",
                props.maxConcurrent(), props.maxWait(), props.retryAfter());
        return registry.bulkhead(EDIT_BULKHEAD);
    }

    @Bean(MERGE_BULKHEAD)
    public Bulkhead mergeBulkhead(MergeAdmissionProperties props, MeterRegistry meterRegistry) {
        var config = BulkheadConfig.custom()
                .maxConcurrentCalls(props.maxConcurrent())
                .maxWaitDuration(props.maxWait())
                .build();
        var registry = BulkheadRegistry.of(config);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);

        log.info("MergeAdmissionControl ready maxConcurrent={} maxWait={} retryAfter={}",
                props.maxConcurrent(), props.maxWait(), props.retryAfter());
        return registry.bulkhead(MERGE_BULKHEAD);
    }

    @Bean(FINALIZE_BULKHEAD)
    public Bulkhead finalizeBulkhead(FinalizeAdmissionProperties props, MeterRegistry meterRegistry) {
        var config = BulkheadConfig.custom()
                .maxConcurrentCalls(props.maxConcurrent())
                .maxWaitDuration(props.maxWait())
                .build();
        var registry = BulkheadRegistry.of(config);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);

        log.info("FinalizeAdmissionControl ready maxConcurrent={} maxWait={} retryAfter={}",
                props.maxConcurrent(), props.maxWait(), props.retryAfter());
        return registry.bulkhead(FINALIZE_BULKHEAD);
    }
}

package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration de l'admission control sur l'endpoint d'upload.
 *
 * <p>Borne le nombre de requêtes d'upload concurrentes par pod via un Bulkhead
 * Resilience4j (sémaphore). Au-delà de la capacité, les requêtes attendent au
 * plus {@code maxWait} avant d'être rejetées avec un 429 + {@code Retry-After}.
 * L'objectif est de protéger la mémoire du pod (multipart jusqu'à 100 MB +
 * conversion PDFBox) le temps que l'autoscaler démarre de nouveaux pods.</p>
 *
 * @param maxConcurrent capacité dure : nombre maximal d'uploads en vol par pod.
 *                      Règle empirique : {@code vCPU × 2} (laisse de la marge
 *                      CPU pendant les attentes I/O sur S3).
 * @param maxWait       durée maximale d'attente d'un permis avant fast-fail 429.
 *                      Volontairement courte : préfère renvoyer 429 plutôt que
 *                      laisser le client attendre — la file d'attente n'absorbe
 *                      pas un pic soutenu, elle ne fait que le différer.
 * @param retryAfter    valeur du header {@code Retry-After} renvoyé au client
 *                      lors d'un rejet. Indique au client mobile dans combien
 *                      de temps retenter l'upload.
 */
@ConfigurationProperties(prefix = "app.admission.upload")
public record UploadAdmissionProperties(
        int maxConcurrent,
        Duration maxWait,
        Duration retryAfter
) {
    public UploadAdmissionProperties {
        if (maxConcurrent <= 0) maxConcurrent = 8;
        if (maxWait == null) maxWait = Duration.ofMillis(200);
        if (retryAfter == null) retryAfter = Duration.ofSeconds(2);
    }
}

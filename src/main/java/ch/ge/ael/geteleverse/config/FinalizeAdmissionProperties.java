package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration de l'admission control sur la finalisation de session
 * ({@code POST .../finalize} → {@code mergeAndFinalize} : téléchargement de
 * toutes les pages, merge PDFBox, upload du composite, purge des pages).
 *
 * <p>Bulkhead <b>séparé</b> du bulkhead {@code merge} des aperçus, pour une
 * raison de durée de rétention : une finalisation retient son permis bien plus
 * longtemps qu'un aperçu (jusqu'à {@code app.finalize-timeout}, défaut 2 min),
 * car elle fusionne <em>toutes</em> les pages puis écrit + purge sur S3. Les
 * regrouper affamerait les aperçus interactifs (que le front sollicite en
 * continu) dès qu'une poignée de finalisations lentes monopolisent les permis.
 * En les isolant, une rafale de finalisations ne peut plus provoquer de
 * {@code MERGE_BUSY} sur les aperçus, et inversement.</p>
 *
 * <p>Au-delà de la capacité : fast-fail 429 + {@code Retry-After}
 * ({@code FINALIZE_BUSY}). {@code upload + edit + merge + finalize} partagent le
 * budget CPU/mémoire et la capacité de l'endpoint S3 on-prem du pod : régler les
 * quatre plafonds ensemble. La rétention plus longue d'un permis de finalisation
 * peut justifier un {@code maxConcurrent} plus élevé que les autres bulkheads.</p>
 *
 * @param maxConcurrent capacité dure : nombre maximal de finalisations en vol par pod.
 * @param maxWait       durée maximale d'attente d'un permis avant fast-fail 429.
 * @param retryAfter    valeur du header {@code Retry-After} renvoyé lors d'un rejet.
 */
@ConfigurationProperties(prefix = "app.admission.finalize")
public record FinalizeAdmissionProperties(
        int maxConcurrent,
        Duration maxWait,
        Duration retryAfter
) {
    public FinalizeAdmissionProperties {
        if (maxConcurrent <= 0) maxConcurrent = 8;
        if (maxWait == null) maxWait = Duration.ofMillis(200);
        if (retryAfter == null) retryAfter = Duration.ofSeconds(2);
    }
}

package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration de l'admission control sur les chemins d'assemblage / rendu
 * multi-pages de {@code SessionController} :
 * <ul>
 *   <li>{@code GET .../preview.pdf} — fusion en mémoire des pages courantes
 *       ({@code mergePagesPreview}) : N téléchargements S3 + merge PDFBox ;</li>
 *   <li>{@code GET .../pages/{id}/thumbnail?preview=true} — rendu JPEG large à
 *       la volée depuis le PDF de page ({@code generatePreview}) ;</li>
 *   <li>{@code POST .../finalize} — fusion finale + upload + purge
 *       ({@code mergeAndFinalize}).</li>
 * </ul>
 *
 * <p>Ces opérations téléchargent et assemblent des artefacts stockés : leur
 * fan-out S3 (GET par page) et leur empreinte mémoire (bitmaps / PDF fusionné)
 * pèsent sur le pool de connexions Netty du SDK S3 et la RAM du pod, au même
 * titre que les uploads et les éditions. Ce Bulkhead borne leur nombre en vol
 * <b>par pod</b>, indépendamment des bulkheads {@code upload} et {@code edit} :
 * au-delà de la capacité, fast-fail 429 + {@code Retry-After} ({@code MERGE_BUSY}).</p>
 *
 * <p><b>Attention à la durée de rétention du permis :</b> {@code finalize} peut
 * conserver un permis plus longtemps que les aperçus (jusqu'à
 * {@code app.finalize-timeout}). Dimensionner {@code maxConcurrent} en
 * conséquence, et garder à l'esprit que
 * {@code upload + edit + merge} partagent le budget CPU/mémoire et la capacité
 * de l'endpoint S3 on-prem du pod. Si les aperçus sont affamés par les
 * finalisations sous charge, scinder {@code finalize} dans son propre bulkhead.</p>
 *
 * @param maxConcurrent capacité dure : nombre maximal d'assemblages en vol par pod.
 * @param maxWait       durée maximale d'attente d'un permis avant fast-fail 429.
 * @param retryAfter    valeur du header {@code Retry-After} renvoyé lors d'un rejet.
 */
@ConfigurationProperties(prefix = "app.admission.merge")
public record MergeAdmissionProperties(
        int maxConcurrent,
        Duration maxWait,
        Duration retryAfter
) {
    public MergeAdmissionProperties {
        if (maxConcurrent <= 0) maxConcurrent = 8;
        if (maxWait == null) maxWait = Duration.ofMillis(200);
        if (retryAfter == null) retryAfter = Duration.ofSeconds(2);
    }
}

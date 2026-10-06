package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration de l'admission control sur les endpoints d'édition de page
 * (rotate / crop / deskew / white-balance de {@code SessionController}).
 *
 * <p>Pendant qu'un upload attache une page, une édition la retravaille :
 * {@code fetch} du PDF, re-render / re-embed de l'image (PDFBox), régénération
 * de la vignette, puis écriture de la nouvelle version sur S3 — des aller-retours S3
 * et une passe CPU/mémoire par édition (cf. {@code StorageService.cropPage}).
 * Sous forte charge concurrente (pic d'éditions simultanées observé en load
 * test), ce fan-out sature le pool de connexions Netty du SDK S3 et la mémoire
 * du pod. Ce Bulkhead borne le nombre d'éditions en vol <b>par pod</b>,
 * indépendamment du bulkhead {@code upload} : au-delà de la capacité, les
 * requêtes attendent au plus {@code maxWait} avant un fast-fail 429 +
 * {@code Retry-After} ({@code EDIT_BUSY}).</p>
 *
 * <p>Bulkhead distinct de l'upload (et non partagé) pour deux raisons : profils
 * de charge différents (pas d'ingress multipart 100 MB côté édition) et
 * métriques de saturation séparées ({@code name="edit"}) exploitables par
 * / Grafana. Régler {@code maxConcurrent} de sorte que
 * {@code upload.max-concurrent + edit.max-concurrent} reste dans le budget
 * CPU/mémoire et la capacité de l'endpoint S3 on-prem du pod.</p>
 *
 * @param maxConcurrent capacité dure : nombre maximal d'éditions en vol par pod.
 * @param maxWait       durée maximale d'attente d'un permis avant fast-fail 429.
 * @param retryAfter    valeur du header {@code Retry-After} renvoyé au client
 *                      lors d'un rejet.
 */
@ConfigurationProperties(prefix = "app.admission.edit")
public record EditAdmissionProperties(
        int maxConcurrent,
        Duration maxWait,
        Duration retryAfter
) {
    public EditAdmissionProperties {
        if (maxConcurrent <= 0) maxConcurrent = 8;
        if (maxWait == null) maxWait = Duration.ofMillis(200);
        if (retryAfter == null) retryAfter = Duration.ofSeconds(2);
    }
}

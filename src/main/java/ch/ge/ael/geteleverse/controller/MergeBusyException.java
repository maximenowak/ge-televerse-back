package ch.ge.ael.geteleverse.controller;

/**
 * Levée par {@code SessionController} quand le bulkhead d'admission des
 * assemblages ({@code app.admission.merge}) est saturé : tous les permis sont
 * pris et l'attente {@code maxWait} a expiré. Couvre les aperçus
 * ({@code preview.pdf}, rendu de vignette à la volée) et la finalisation.
 * Traduite en {@code 429 MERGE_BUSY} + {@code Retry-After} par
 * {@link GlobalExceptionHandler}.
 *
 * <p>Distincte de {@link EditBusyException} / {@code UPLOAD_BUSY} pour que le
 * client discrimine la nature de la saturation. Le {@code Retry-After} est porté
 * par l'exception (calculé au point de rejet depuis
 * {@code MergeAdmissionProperties#retryAfter()}).</p>
 */
public class MergeBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public MergeBusyException(long retryAfterSeconds) {
        super("Merge admission full, retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

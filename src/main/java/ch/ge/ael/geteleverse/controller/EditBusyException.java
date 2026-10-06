package ch.ge.ael.geteleverse.controller;

/**
 * Levée par {@code SessionController} quand le bulkhead d'admission des
 * éditions ({@code app.admission.edit}) est saturé : tous les permis sont pris
 * et l'attente {@code maxWait} a expiré. Traduite en {@code 429 EDIT_BUSY} +
 * {@code Retry-After} par {@link GlobalExceptionHandler}.
 *
 * <p>Distincte de {@link io.github.resilience4j.bulkhead.BulkheadFullException}
 * (qui, elle, reste mappée sur {@code UPLOAD_BUSY} pour le chemin d'upload) afin
 * que le client puisse discriminer une saturation d'édition d'une saturation
 * d'upload. Le {@code Retry-After} est porté par l'exception — calculé au point
 * de rejet à partir de {@code EditAdmissionProperties#retryAfter()} — pour éviter
 * d'injecter la config dans le handler global.</p>
 */
public class EditBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public EditBusyException(long retryAfterSeconds) {
        super("Edit admission full, retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

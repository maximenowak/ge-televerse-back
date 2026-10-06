package ch.ge.ael.geteleverse.controller;

/**
 * Levée par {@code SessionController.finalize} quand le bulkhead d'admission des
 * finalisations ({@code app.admission.finalize}) est saturé : tous les permis
 * sont pris et l'attente {@code maxWait} a expiré. Traduite en
 * {@code 429 FINALIZE_BUSY} + {@code Retry-After} par {@link GlobalExceptionHandler}.
 *
 * <p>Distincte de {@link MergeBusyException} (aperçus) / {@link EditBusyException}
 * / {@code UPLOAD_BUSY} : la finalisation a son propre bulkhead car elle retient
 * un permis bien plus longtemps qu'un aperçu. Le {@code Retry-After} est porté par
 * l'exception (depuis {@code FinalizeAdmissionProperties#retryAfter()}).</p>
 */
public class FinalizeBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public FinalizeBusyException(long retryAfterSeconds) {
        super("Finalize admission full, retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

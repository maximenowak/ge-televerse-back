package ch.ge.ael.geteleverse.controller;

/**
 * Levée sur le chemin de lecture S3 des aperçus / vignettes ({@code getThumbnail})
 * quand une lecture échoue pour une cause <em>transitoire</em> — timeout
 * d'acquisition de connexion (pool Netty saturé sous forte charge), 5xx /
 * throttling du store on-prem, reset TLS — par opposition à un objet réellement
 * absent ({@code NoSuchKey} / 404 côté store, qui reste un 404 légitime).
 *
 * <p>Traduite en {@code 503 STORAGE_UNAVAILABLE} + {@code Retry-After} par
 * {@link GlobalExceptionHandler}. Le front la traite comme un échec temporaire
 * retryable (même contrat que {@link MergeBusyException} / {@code MERGE_BUSY} et
 * {@link AntivirusUnavailableException} / {@code ANTIVIRUS_UNAVAILABLE}). Le
 * {@code Retry-After} est porté par l'exception (calculé au point de rejet
 * depuis {@code MergeAdmissionProperties#retryAfter()}, le domaine « aperçu »).</p>
 *
 * <p>Motivation : historiquement ces défaillances étaient masquées en
 * {@code 404 notFound} silencieux ({@code onErrorReturn}). Sous forte charge une
 * saturation S3 se présentait alors comme une vague de 404 sans aucune trace de
 * log, indiscernable d'une page réellement supprimée.</p>
 */
public class StorageUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public StorageUnavailableException(String op, long retryAfterSeconds, Throwable cause) {
        super("Storage unavailable during " + op + ": "
                + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()), cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

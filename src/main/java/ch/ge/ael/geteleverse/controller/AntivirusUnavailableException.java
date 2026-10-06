package ch.ge.ael.geteleverse.controller;

/**
 * Levée par {@code AntivirusService.scan} quand clamd est injoignable, a
 * timeout, ou a renvoyé une erreur protocole — uniquement si
 * {@code app.antivirus.fail-mode=closed} (défaut prod). Traduite par
 * {@code GlobalExceptionHandler} en {@code 503 ANTIVIRUS_UNAVAILABLE} avec
 * un {@code Retry-After} indicatif. Le front la traite comme un échec
 * temporaire retryable (équivalent à {@code UPLOAD_BUSY}).
 */
public class AntivirusUnavailableException extends RuntimeException {

    public AntivirusUnavailableException(Throwable cause) {
        super("Antivirus scan unavailable: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()), cause);
    }
}

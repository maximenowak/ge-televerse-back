package ch.ge.ael.geteleverse.controller;

import org.springframework.http.HttpStatus;

/**
 * Mutation de session refusée SOUS le verrou, parce que l'état a changé depuis la
 * lecture de l'appelant : session en cours de finalisation ou finalisée, page
 * supprimée ou modifiée entre-temps, liste de pages divergente, quota dépassé par
 * une édition. Levée par {@code SessionService} avant toute écriture — l'appelant
 * sait donc qu'il peut défaire ce qu'il a écrit sur S3 pour cette opération (cf.
 * {@code SessionService#isNotApplied}).
 *
 * <p>Traduite par {@link GlobalExceptionHandler} en {@link #status()} +
 * {@code {"error": code}}. Non rejouable telle quelle : le client doit relire la
 * session (codes absents des {@code BUSY_CODES} du front).</p>
 */
public class SessionConflictException extends RuntimeException {

    public static final String SESSION_FINALIZING = "SESSION_FINALIZING";
    public static final String SESSION_FINALIZED = "SESSION_FINALIZED";
    public static final String PAGE_GONE = "PAGE_GONE";
    public static final String PAGE_CHANGED = "PAGE_CHANGED";
    public static final String PAGES_CHANGED = "PAGES_CHANGED";
    public static final String SESSION_SIZE_EXCEEDED = "SESSION_SIZE_EXCEEDED";

    private final HttpStatus status;
    private final String code;

    private SessionConflictException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static SessionConflictException sessionFinalizing() {
        return new SessionConflictException(HttpStatus.CONFLICT, SESSION_FINALIZING,
                "La session est en cours de finalisation : elle n'accepte plus de modification.");
    }

    public static SessionConflictException sessionFinalized() {
        return new SessionConflictException(HttpStatus.CONFLICT, SESSION_FINALIZED,
                "La session est finalisée : elle n'accepte plus de modification.");
    }

    public static SessionConflictException pageGone() {
        return new SessionConflictException(HttpStatus.CONFLICT, PAGE_GONE,
                "La page a été supprimée entre-temps.");
    }

    public static SessionConflictException pageChanged() {
        return new SessionConflictException(HttpStatus.CONFLICT, PAGE_CHANGED,
                "La page a été modifiée entre-temps : réessayez sur sa version à jour.");
    }

    public static SessionConflictException pagesChanged() {
        return new SessionConflictException(HttpStatus.CONFLICT, PAGES_CHANGED,
                "La liste des pages a changé entre-temps : réessayez sur la liste à jour.");
    }

    public static SessionConflictException sizeExceeded() {
        return new SessionConflictException(HttpStatus.CONTENT_TOO_LARGE, SESSION_SIZE_EXCEEDED,
                "La modification ferait dépasser la taille maximale de la session.");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

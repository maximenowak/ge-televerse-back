package ch.ge.ael.geteleverse.controller;

/**
 * Levée par {@code SessionController.createSession} quand le token bucket par
 * IP est vide pour l'IP courante (cf.
 * {@link ch.ge.ael.geteleverse.service.IpSessionRateLimiter}). Traduite en
 * {@code 429 SESSION_RATE_LIMITED} + {@code Retry-After} par
 * {@link GlobalExceptionHandler}.
 *
 * <p>{@code force} distingue un rejet sur l'usage de {@code ?force=true} (coût
 * surdimensionné) d'un rejet sur la création nominale — utile pour discriminer
 * en alerting un attaquant qui boucle sur force d'un client mobile qui
 * retente.</p>
 */
public class SessionRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;
    private final boolean force;

    public SessionRateLimitedException(long retryAfterSeconds, boolean force) {
        super("Session creation rate-limited, retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
        this.force = force;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean force() {
        return force;
    }
}

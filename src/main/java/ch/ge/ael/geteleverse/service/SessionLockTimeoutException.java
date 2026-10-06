package ch.ge.ael.geteleverse.service;

import java.time.Duration;

/**
 * Levée par {@code SessionService} quand le verrou Hazelcast d'une session n'a pas
 * pu être acquis dans le délai imparti. Traduite en {@code 429 SESSION_BUSY} +
 * {@code Retry-After} par {@code GlobalExceptionHandler}.
 *
 * <p>Motif : toute mutation de session (attache de page, édition, suppression,
 * réordonnancement) passe par {@code IMap.lock(token)}, qui <b>bloque sans limite
 * de temps</b>. Deux opérations concurrentes sur la même session — cas nominal
 * quand un upload et une édition se croisent — se sérialisent normalement en
 * quelques millisecondes ; mais si le détenteur du verrou traîne (pod throttlé,
 * cluster Hazelcast lent), les autres attendaient indéfiniment sur un thread
 * {@code boundedElastic}, permis de bulkhead en main, sans qu'aucun log ne le
 * signale.</p>
 *
 * <p>C'est de la contention transitoire, pas une erreur métier : le client doit la
 * traiter comme les codes {@code *_BUSY} de l'admission control, c'est-à-dire
 * réessayer après {@code Retry-After}.</p>
 */
public class SessionLockTimeoutException extends RuntimeException {

    private final long retryAfterSeconds;

    public SessionLockTimeoutException(String token, Duration waited, long retryAfterSeconds) {
        super("Session lock not acquired within " + waited + " for token " + token);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

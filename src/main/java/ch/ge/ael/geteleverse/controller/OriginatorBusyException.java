package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.model.OriginatorBinding;

/**
 * Levée par {@code SessionService.createSession} lorsque l'IP d'origine de la
 * requête est déjà liée à une session active dans l'IMap
 * {@code televerse-originators}. Traduite par {@code GlobalExceptionHandler}
 * en {@code 409 ORIGINATOR_HAS_ACTIVE_SESSION} avec l'{@code expiresAt} du
 * binding existant.
 */
public class OriginatorBusyException extends RuntimeException {

    private final OriginatorBinding existing;

    public OriginatorBusyException(OriginatorBinding existing) {
        super("Originator already has an active session: expires " + existing.expiresAt());
        this.existing = existing;
    }

    public OriginatorBinding existing() {
        return existing;
    }
}

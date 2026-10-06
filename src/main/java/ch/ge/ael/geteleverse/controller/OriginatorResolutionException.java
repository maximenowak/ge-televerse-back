package ch.ge.ael.geteleverse.controller;

/**
 * Levée par {@code OriginatorResolver} quand le header {@code X-Forwarded-For}
 * est absent ou contient une valeur impossible à parser comme adresse IP.
 *
 * <p>En production le firewall doit toujours poser {@code X-Forwarded-For} —
 * recevoir cette exception signale une mauvaise config réseau (trafic qui
 * arrive sans passer par l'edge de confiance) plutôt qu'un client mal-formé.
 * Le compteur {@code televerse.originator.invalid} associé sert d'alerte ops.</p>
 */
public class OriginatorResolutionException extends RuntimeException {

    /** Codes d'erreur exposés au front. */
    public static final String MISSING = "MISSING_ORIGINATOR";
    public static final String INVALID = "INVALID_ORIGINATOR";

    private final String errorCode;

    public OriginatorResolutionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

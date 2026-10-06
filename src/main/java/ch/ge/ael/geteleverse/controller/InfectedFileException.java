package ch.ge.ael.geteleverse.controller;

import java.util.Collection;

/**
 * Levée par {@code AntivirusService.scan} quand clamd retourne {@code VirusFound}.
 * Traduite par {@code GlobalExceptionHandler} en {@code 422 INFECTED_FILE} —
 * réponse non-retryable pour le front : l'utilisateur doit re-prendre un autre
 * fichier, pas relancer le même.
 *
 * <p>Le détail des signatures détectées est conservé pour le log côté serveur
 * uniquement (Splunk) ; il n'est pas renvoyé au client pour ne pas livrer
 * d'information exploitable à un attaquant qui sonde nos détections.</p>
 */
public class InfectedFileException extends RuntimeException {

    private final String filename;
    private final Collection<String> signatures;

    public InfectedFileException(String filename, Collection<String> signatures) {
        super("Infected file: " + filename + " signatures=" + signatures);
        this.filename = filename;
        this.signatures = signatures;
    }

    public String filename() {
        return filename;
    }

    public Collection<String> signatures() {
        return signatures;
    }
}

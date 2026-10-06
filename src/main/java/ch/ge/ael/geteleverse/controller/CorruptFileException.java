package ch.ge.ael.geteleverse.controller;

/**
 * Levée par {@code UploadController} quand les octets reçus ne peuvent pas être
 * décodés comme un PDF ou une image valide : PDFBox / ImageIO lèvent
 * {@link java.io.IOException} sur un fichier tronqué, mal formé ou au contenu
 * binaire incohérent (ex. « Missing root object specification in trailer »).
 *
 * <p>Traduite par {@code GlobalExceptionHandler} en {@code 422 CORRUPT_FILE} :
 * la requête est syntaxiquement valide (multipart correct, session valide,
 * extension autorisée) mais le contenu est sémantiquement inexploitable — même
 * sémantique que {@link InfectedFileException}. Non-retryable côté front :
 * ré-uploader le même fichier ne changera rien, l'utilisateur doit en choisir
 * un autre.</p>
 *
 * <p>Le mapping est volontairement restreint aux points de parsing
 * ({@code countPdfPages} pour les PDF, {@code convertToPdf} pour les images) :
 * une {@link java.io.IOException} en aval (rendu de vignette, I/O S3, socket
 * clamd) n'est pas un fichier corrompu et conserve sa propre sémantique
 * d'erreur (500, 503, etc.).</p>
 */
public class CorruptFileException extends RuntimeException {

    private final String filename;

    public CorruptFileException(String filename, Throwable cause) {
        super("Unreadable or corrupt file: " + filename, cause);
        this.filename = filename;
    }

    public String filename() {
        return filename;
    }
}

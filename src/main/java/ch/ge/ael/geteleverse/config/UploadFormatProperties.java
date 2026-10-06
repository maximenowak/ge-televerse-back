package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Allow-list des formats acceptés en upload (vérifiée côté back par extension
 * du nom de fichier, indépendamment du {@code Content-Type} envoyé par le
 * client — qui n'est pas une source de confiance).
 *
 * <p>Le front analyse le media-type pour pré-filtrer côté UX. Le back applique
 * un contrôle distinct via {@link #isAllowed(String)} et rejette en 415
 * {@code INVALID_FILE_FORMAT} les fichiers hors liste. La liste est aussi
 * exposée par {@code GET /api/upload/formats} pour que les deux fronts
 * (webcomponent desktop + app mobile) s'alignent dynamiquement sans dupliquer
 * la valeur côté JS.
 *
 * <p>Normalisation : les valeurs YAML sont trimées, lowercase et le point de
 * tête est enlevé — on accepte indifféremment {@code pdf}, {@code .pdf} ou
 * {@code PDF} dans la config.
 *
 * @param allowedFormats extensions autorisées (sans point). Si vide ou nulle,
 *                       on retombe sur le défaut historique
 *                       {@code pdf, jpg, jpeg, png, tiff}.
 */
@ConfigurationProperties(prefix = "app.upload")
public record UploadFormatProperties(List<String> allowedFormats) {

    private static final List<String> DEFAULT_FORMATS = List.of("pdf", "jpg", "jpeg", "png", "tiff");

    public UploadFormatProperties {
        if (allowedFormats == null || allowedFormats.isEmpty()) {
            allowedFormats = DEFAULT_FORMATS;
        } else {
            allowedFormats = allowedFormats.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(UploadFormatProperties::normalize)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            if (allowedFormats.isEmpty()) {
                allowedFormats = DEFAULT_FORMATS;
            }
        }
    }

    /** Set immuable pour lookup O(1) côté validateur. */
    public Set<String> allowedFormatsSet() {
        return Set.copyOf(allowedFormats);
    }

    /**
     * Extrait l'extension d'un nom de fichier en minuscules, sans le point.
     * Retourne une chaîne vide si le fichier n'a pas d'extension — un tel
     * fichier sera rejeté car aucune valeur vide n'est dans la liste.
     */
    public static String extractExtension(String filename) {
        if (filename == null) return "";
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == filename.length() - 1) return "";
        return filename.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
    }

    public boolean isAllowed(String filename) {
        var ext = extractExtension(filename);
        return !ext.isEmpty() && allowedFormatsSet().contains(ext);
    }

    /** Liste formatée pour l'affichage utilisateur : {@code "pdf, jpg, jpeg, png, tiff"}. */
    public String formattedList() {
        return String.join(", ", allowedFormats);
    }

    private static String normalize(String raw) {
        var trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}

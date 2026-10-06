package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres de traitement des images et documents entrants.
 *
 * @param dpi                résolution cible (en DPI) utilisée pour dimensionner les images
 *                           avant intégration au PDF, et pour rendre les pages PDF existantes
 *                           lors du retraitement. La dimension max en pixels est dérivée de
 *                           la hauteur A4 (297 mm) à ce DPI — 150 DPI ≈ 1754 px.
 * @param jpegQuality        qualité JPEG (0.0 - 1.0) appliquée aux images embarquées dans le PDF.
 *                           0.75 ≈ bon compromis taille / lisibilité pour des documents administratifs.
 * @param opencvThreads      nombre de threads du pool {@code parallel_for} d'OpenCV.
 *                           {@code 0} (défaut) = les vCPU vus par la JVM, donc le quota cgroup du pod.
 *                           Cf. {@link ImageProcessingConfig} pour le motif.
 * @param onnxIntraOpThreads nombre de threads intra-opérateur d'ONNX Runtime, par inférence
 *                           DocAligner. Défaut {@code 1} : le parallélisme utile est déjà fourni
 *                           par les requêtes concurrentes (bulkhead {@code edit}), et le pool ORT
 *                           n'est pas plus conscient du cgroup que celui d'OpenCV.
 */
@ConfigurationProperties(prefix = "app.image")
public record ImageProcessingProperties(
        int dpi,
        float jpegQuality,
        int opencvThreads,
        int onnxIntraOpThreads
) {
    private static final double A4_HEIGHT_MM = 297.0;
    private static final double MM_PER_INCH = 25.4;

    public ImageProcessingProperties {
        if (dpi <= 0) dpi = 150;
        if (jpegQuality <= 0f || jpegQuality > 1f) jpegQuality = 0.75f;
        if (opencvThreads < 0) opencvThreads = 0;
        if (onnxIntraOpThreads <= 0) onnxIntraOpThreads = 1;
    }

    /** Dimension max en pixels (hauteur A4 au DPI configuré). */
    public int maxDimensionPx() {
        return (int) Math.round(A4_HEIGHT_MM / MM_PER_INCH * dpi);
    }

    /**
     * Nombre de threads OpenCV effectivement appliqué : la valeur configurée si
     * elle est explicite, sinon les vCPU vus par la JVM — qui, contrairement à
     * OpenCV, respecte le quota CPU du conteneur.
     */
    public int effectiveOpencvThreads() {
        return opencvThreads > 0 ? opencvThreads : Runtime.getRuntime().availableProcessors();
    }
}

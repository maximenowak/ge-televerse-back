package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Configuration de la recompression PDF via Ghostscript.
 *
 * @param binaryName    nom du binaire à invoquer (résolu via le PATH).
 *                      Défaut {@code gs} (Linux/prod), surchargé en {@code gswin64c} en dev (Windows).
 * @param sizeThreshold seuil en dessous duquel la recompression est ignorée :
 *                      les petits PDF gagnent rarement à passer par Ghostscript.
 * @param workers       nombre maximal de processus {@code gs} concurrents
 *                      (scheduler borné Reactor — règle "vCPU - 1").
 * @param queueSize     profondeur de la file d'attente du scheduler. Au-delà,
 *                      l'opération retombe sur le PDF d'origine non recompressé.
 * @param timeout       délai max pour un appel {@code gs} avant abandon et fallback original.
 * @param pdfSettings   preset Ghostscript de base : {@code /screen}, {@code /ebook}, {@code /printer}, {@code /prepress}.
 *                      {@code /ebook} fournit les défauts (AutoFilter, sous-échantillonnage chroma,
 *                      QFactor 0.76 ≈ la qualité JPEG 0.75 du chemin image). Les deux points faibles du
 *                      preset sont corrigés par le service : la résolution (voir {@code imageResolutionDpi})
 *                      et la conversion colorimétrique (forcée en {@code /LeaveColorUnchanged}, sans quoi
 *                      le preset détruit silencieusement les groupes de transparence — cf. mémoire
 *                      {@code ghostscript-ebook-drops-transparency}).
 * @param imageResolutionDpi résolution cible (DPI) du sous-échantillonnage des images couleur et gris.
 *                      Le preset {@code /ebook} plafonne à 150 DPI, ce qui écrase le détail par rapport au
 *                      chemin image (300 DPI). On aligne donc sur {@code app.image.dpi} (défaut 300) pour
 *                      une qualité homogène entre conversion image→PDF et recompression PDF.
 */
@ConfigurationProperties(prefix = "app.ghostscript")
public record GhostscriptProperties(
        String binaryName,
        DataSize sizeThreshold,
        int workers,
        int queueSize,
        Duration timeout,
        String pdfSettings,
        int imageResolutionDpi
) {
    public GhostscriptProperties {
        if (binaryName == null || binaryName.isBlank()) binaryName = "gs";
        if (sizeThreshold == null) sizeThreshold = DataSize.ofMegabytes(2);
        if (workers <= 0) workers = 3;
        if (queueSize <= 0) queueSize = 50;
        if (timeout == null) timeout = Duration.ofSeconds(15);
        if (pdfSettings == null || pdfSettings.isBlank()) pdfSettings = "/ebook";
        if (imageResolutionDpi <= 0) imageResolutionDpi = 300;
    }
}

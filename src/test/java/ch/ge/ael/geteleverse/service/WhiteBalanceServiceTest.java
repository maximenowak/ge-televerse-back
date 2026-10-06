package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.testutil.NativeProbes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests de la balance des blancs via OpenCV SimpleWB.
 *
 * <p>Nécessite les natives OpenCV (bytedeco) pour la plateforme courante.
 * Classe désactivée par {@link NativeProbes} sur les images sans loader
 * dynamique glibc x86_64 : sur ces systèmes le chargement bytedeco crashe
 * la JVM (SIGABRT) avant tout {@code try/catch}. Cf. également
 * {@code PerspectiveCorrectionServiceTest}.</p>
 */
@EnabledIf(value = "ch.ge.ael.geteleverse.testutil.NativeProbes#nativesAvailable",
        disabledReason = "Binaires natifs indisponibles sur cette image (loader dynamique glibc x86_64 manquant)")
class WhiteBalanceServiceTest {

    private WhiteBalanceService service;

    @BeforeEach
    void setUp() {
        service = new WhiteBalanceService();
        assumeTrue(service.isAvailable(),
                "OpenCV natives indisponibles sur cette plateforme — test ignoré");
    }

    @Test
    void apply_preservesDimensions() {
        var input = createUniformImage(200, 150, new Color(180, 160, 120));

        var result = service.apply(input);

        assertThat(result.getWidth()).isEqualTo(200);
        assertThat(result.getHeight()).isEqualTo(150);
    }

    @Test
    void apply_neutralizesYellowTint() {
        // Simule un document photographié sous lumière jaune :
        // fond beige (R élevé, G moyen, B bas) au lieu de blanc
        var yellowTinted = createUniformImage(100, 100, new Color(240, 220, 160));

        var result = service.apply(yellowTinted);

        // Après correction, les canaux doivent être plus équilibrés (fond plus blanc).
        // On vérifie que la différence max-min entre R, G, B diminue.
        var inputSpread = channelSpread(yellowTinted);
        var outputSpread = channelSpread(result);
        assertThat(outputSpread).isLessThan(inputSpread);
    }

    @Test
    void apply_handlesAlreadyNeutralImage() {
        // Image déjà neutre (gris moyen) — ne doit pas être dégradée
        var neutral = createUniformImage(100, 100, new Color(128, 128, 128));

        var result = service.apply(neutral);

        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(100);
        // Les canaux doivent rester proches les uns des autres
        assertThat(channelSpread(result)).isLessThanOrEqualTo(10);
    }

    @Test
    void apply_handlesTypeIntRgbInput() {
        // BufferedImage de type TYPE_INT_RGB (pas TYPE_3BYTE_BGR)
        var image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(200, 180, 140));
        g.fillRect(0, 0, 80, 60);
        g.dispose();

        var result = service.apply(image);

        assertThat(result.getWidth()).isEqualTo(80);
        assertThat(result.getHeight()).isEqualTo(60);
    }

    @Test
    void apply_correctsBlueTint() {
        // Document photographié sous lumière froide (bleutée)
        var blueTinted = createUniformImage(100, 100, new Color(160, 180, 240));

        var result = service.apply(blueTinted);

        var inputSpread = channelSpread(blueTinted);
        var outputSpread = channelSpread(result);
        assertThat(outputSpread).isLessThan(inputSpread);
    }

    // ===== Helpers =====

    private static BufferedImage createUniformImage(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        var g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    /**
     * Calcule l'écart entre le canal le plus fort et le plus faible au centre de l'image.
     * Un écart faible = couleur neutre, un écart fort = dominante colorée.
     */
    private static int channelSpread(BufferedImage image) {
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int rgb = image.getRGB(cx, cy);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return Math.max(Math.max(r, g), Math.max(g, b)) - Math.min(Math.min(r, g), Math.min(g, b));
    }
}

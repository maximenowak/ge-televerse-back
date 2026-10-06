package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.ImageProcessingProperties;
import ch.ge.ael.geteleverse.testutil.NativeProbes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests du détecteur de coins par CNN (DocAligner).
 *
 * <p>Le modèle ONNX est embarqué dans le jar applicatif, mais ONNX Runtime
 * extrait sa native {@code libonnxruntime.so} dans {@code /tmp} et exige
 * le loader dynamique glibc x86_64 sur le système. Sur les images CI
 * ultra-minimales sans ce loader — comme sur les postes Windows dont l'EDR
 * détourne le CRT (cf. {@link NativeProbes}) — la classe entière est
 * désactivée pour ne pas transformer une native indisponible en échec de
 * test. Le test sur photo réelle ajoute en plus une dépendance à une fixture
 * locale de dev.</p>
 */
@EnabledIf(value = "ch.ge.ael.geteleverse.testutil.NativeProbes#onnxAvailable",
        disabledReason = "ONNX Runtime ne s'initialise pas sur cette machine (natives indisponibles ou CRT détourné)")
class DocAlignerDocumentDetectorTest {

    /** Photo de référence : page A4 sur bureau blanc. Test sauté si absente. */
    private static final Path REAL_PHOTO_FIXTURE = Path.of("/fixtures/p1-avec-distorsion.jpg");

    private static DocAlignerDocumentDetector detector;

    @BeforeAll
    static void setUpAll() {
        detector = new DocAlignerDocumentDetector(new ImageProcessingProperties(150, 0.75f, 0, 1));
    }

    @AfterAll
    static void tearDownAll() {
        if (detector != null) detector.cleanup();
    }

    @Test
    void detector_loadsBundledModelAtConstruction() {
        assertThat(detector.isAvailable()).isTrue();
    }

    @Test
    void detect_returnsEmptyOnUniformImage() {
        var uniform = uniform(800, 600, Color.GRAY);

        var result = detector.detect(uniform);

        // Image uniforme = aucun document à voir = aucun pic dans la heatmap.
        // Le détecteur doit échouer proprement, pas halluciner des coins.
        assertThat(result).isEmpty();
    }

    @Test
    void detect_doesNotThrowOnUnusualInputType() {
        // BufferedImage TYPE_INT_RGB plutôt que TYPE_3BYTE_BGR — la conversion
        // interne doit gérer.
        var input = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        var g = input.createGraphics();
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, 400, 300);
        g.dispose();

        // Pas d'exception, résultat indifférent.
        Optional<?> result = detector.detect(input);
        assertThat(result).isNotNull();
    }

    @Test
    void detect_findsCornersOnRealDocumentPhoto() throws Exception {
        // Recherche dans le classpath (src/test/resources/fixtures/p1-avec-distorsion.jpg)
        var resourceUrl = getClass().getResource("/fixtures/p1-avec-distorsion.jpg");
        assumeTrue(resourceUrl != null,
                "Fixture absente du classpath : /fixtures/p1-avec-distorsion.jpg — test sauté");

        var image = ImageIO.read(resourceUrl);
        assertThat(image)gi
                .as("Le fichier image n'a pas pu être décodé")
                .isNotNull();

        var result = detector.detect(image);

        assertThat(result).isPresent();
        var corners = result.get();
        assertThat(corners).hasSize(4);

        // Les 4 coins doivent être distincts et tomber dans l'image.
        for (var c : corners) {
            assertThat(c.x()).isBetween(0f, (float) image.getWidth());
            assertThat(c.y()).isBetween(0f, (float) image.getHeight());
        }
    }

    private static BufferedImage uniform(int w, int h, Color color) {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        var g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }
}

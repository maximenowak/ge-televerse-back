package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.testutil.NativeProbes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.awt.Color;
import java.awt.Font;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests de la correction de perspective via OpenCV.
 *
 * <p>Nécessite les natives OpenCV (bytedeco) pour la plateforme courante.
 * Classe désactivée par {@link NativeProbes} sur les images sans loader
 * dynamique glibc x86_64 : sur ces systèmes, le chargement de
 * {@code org.bytedeco.opencv.opencv_core.Mat} crashe la JVM (SIGABRT,
 * exit 134) avant même qu'un {@code try/catch} ne puisse intervenir,
 * tuant le fork Surefire et bloquant l'exécution des classes de tests
 * suivantes.</p>
 */
@EnabledIf(value = "ch.ge.ael.geteleverse.testutil.NativeProbes#nativesAvailable",
        disabledReason = "Binaires natifs indisponibles sur cette image (loader dynamique glibc x86_64 manquant)")
class PerspectiveCorrectionServiceTest {

    private PerspectiveCorrectionService service;

    @BeforeEach
    void setUp() {
        // null DNN volontaire — ces tests exercent uniquement la cascade classique
        // (stages 1-3). La couverture du chemin DocAligner se fait dans
        // DocAlignerDocumentDetectorTest, sur des images réelles.
        service = new PerspectiveCorrectionService(null);
        assumeTrue(service.isAvailable(),
                "OpenCV natives indisponibles sur cette plateforme — test ignoré");
    }

    @Test
    void apply_detectsAndCorrectsTrapezoidalDocument() {
        // Image 800x600 avec un document blanc trapézoïdal sur fond sombre
        var input = createTrapezoidDocument(800, 600);

        var result = service.apply(input);

        // Le document doit avoir été détecté et redressé
        assertThat(result.applied()).isTrue();
        // L'image de sortie doit être plus proche d'un rectangle (ratio plus standard)
        assertThat(result.image()).isNotNull();
        assertThat(result.image().getWidth()).isGreaterThan(0);
        assertThat(result.image().getHeight()).isGreaterThan(0);
    }

    @Test
    void apply_preservesColorChannels() {
        var input = createSolidColorDocument(800, 600, Color.RED);

        var result = service.apply(input);

        assertThat(result.applied()).isTrue();
        var img = result.image();
        int rgb = img.getRGB(img.getWidth() / 2, img.getHeight() / 2);
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        assertThat(r).as("canal rouge préservé (pas inversé en bleu)").isGreaterThan(200);
        assertThat(b).as("canal bleu proche de zéro").isLessThan(60);
    }

    @Test
    void apply_returnsOriginalOnUniformImage() {
        // Image uniforme sans contours détectables → pas de correction
        var uniform = createUniformImage(400, 300, Color.GRAY);

        var result = service.apply(uniform);

        assertThat(result.applied()).isFalse();
        assertThat(result.image()).isSameAs(uniform);
    }

    @Test
    void apply_returnsOriginalWhenDocumentTooSmall() {
        // Petit rectangle blanc sur un très grand fond → aire < 20% → rejeté
        var input = createSmallDocumentOnLargeBackground(1000, 800);

        var result = service.apply(input);

        assertThat(result.applied()).isFalse();
    }

    @Test
    void apply_preservesImageWhenNoQuadrilateralFound() {
        // Image avec beaucoup de bruit mais pas de quadrilatère clair
        var noisy = createNoisyImage(500, 400);

        var result = service.apply(noisy);

        assertThat(result.applied()).isFalse();
        assertThat(result.image().getWidth()).isEqualTo(500);
        assertThat(result.image().getHeight()).isEqualTo(400);
    }

    @Test
    void apply_handlesTypeIntRgbInput() {
        // BufferedImage de type TYPE_INT_RGB (pas TYPE_3BYTE_BGR)
        var input = createTrapezoidDocumentAsIntRgb(600, 500);

        var result = service.apply(input);

        // Doit fonctionner sans erreur, quel que soit le résultat de détection
        assertThat(result.image()).isNotNull();
    }

    // ===== crop =====

    @Test
    void crop_detectsAndCropsDocument() {
        var input = createTrapezoidDocument(800, 600);

        var result = service.crop(input);

        assertThat(result.applied()).isTrue();
        // L'image recadrée doit être plus petite que l'originale (marges supprimées)
        assertThat(result.image().getWidth()).isLessThan(800);
        assertThat(result.image().getHeight()).isLessThan(600);
    }

    @Test
    void boundingRectFromQuad_clampsOutOfFrameCornerWithoutWideningTheCrop() {
        // Coins gauches hors cadre (x = -30) : le recadrage s'arrête au bord droit du
        // quadrilatère (ceil 1210,6 = 1211), il ne s'étend pas de 30 px au-delà.
        var quad = new Corner[]{
                new Corner(-30f, 10f), new Corner(1210.6f, 10f),
                new Corner(1210.6f, 1700.2f), new Corner(-30f, 1700.2f)};

        try (var rect = PerspectiveCorrectionService.boundingRectFromQuad(quad, 1316, 1754)) {
            assertThat(rect.x()).isZero();
            assertThat(rect.width()).isEqualTo(1211);
            assertThat(rect.y()).isEqualTo(10);
            assertThat(rect.height()).isEqualTo(1691);
        }
    }

    @Test
    void boundingRectFromQuad_keepsPartiallyCoveredPixels() {
        var quad = new Corner[]{
                new Corner(100.4f, 50.6f), new Corner(1000.2f, 50.6f),
                new Corner(1000.2f, 1019.5f), new Corner(100.4f, 1019.5f)};

        try (var rect = PerspectiveCorrectionService.boundingRectFromQuad(quad, 1316, 1754)) {
            assertThat(rect.x()).isEqualTo(100);
            assertThat(rect.y()).isEqualTo(50);
            assertThat(rect.width()).isEqualTo(901);   // 100 → ceil(1000,2) = 1001
            assertThat(rect.height()).isEqualTo(970);  // 50 → ceil(1019,5) = 1020
        }
    }

    @Test
    void crop_returnsOriginalOnUniformImage() {
        var uniform = createUniformImage(400, 300, Color.GRAY);

        var result = service.crop(uniform);

        assertThat(result.applied()).isFalse();
        assertThat(result.image()).isSameAs(uniform);
    }

    @Test
    void crop_preservesColorChannels() {
        // Document ROUGE sur fond noir : piège l'inversion R/B de la conversion
        // Mat(BGR) -> BufferedImage. Sur du blanc/noir le swap est invisible, d'où
        // une couleur franche. Le crop est purement géométrique : la couleur du
        // centre du document doit rester dominée par le rouge, pas basculer en bleu.
        var input = createSolidColorDocument(800, 600, Color.RED);

        var result = service.crop(input);

        assertThat(result.applied()).isTrue(); // sinon la conversion n'est pas exercée
        var img = result.image();
        int rgb = img.getRGB(img.getWidth() / 2, img.getHeight() / 2);
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        assertThat(r).as("canal rouge préservé (pas inversé en bleu)").isGreaterThan(200);
        assertThat(b).as("canal bleu proche de zéro").isLessThan(60);
    }

    // ===== Helpers =====

    /**
     * Document rectangulaire d'une couleur unie franche sur fond noir, sans texte
     * (le centre reste de la couleur pure — utile pour tester la fidélité couleur).
     */
    private static BufferedImage createSolidColorDocument(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        var g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        int margin = 80;
        g.setColor(color);
        g.fillRect(margin, margin, width - 2 * margin, height - 2 * margin);
        g.dispose();
        return image;
    }

    /**
     * Crée une image avec un document blanc trapézoïdal sur fond sombre,
     * simulant une photo prise de biais.
     */
    private static BufferedImage createTrapezoidDocument(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Fond sombre (table)
        g.setColor(new Color(40, 40, 50));
        g.fillRect(0, 0, width, height);

        // Document blanc en trapèze (perspective simulée)
        int margin = 80;
        int perspectiveShift = 60;
        var trapezoid = new Polygon(
                new int[]{margin + perspectiveShift, width - margin - perspectiveShift,
                        width - margin, margin},
                new int[]{margin, margin,
                        height - margin, height - margin},
                4
        );
        g.setColor(Color.WHITE);
        g.fillPolygon(trapezoid);

        // Texte sur le document pour simuler du contenu
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("Document test", width / 3, height / 2);

        g.dispose();
        return image;
    }

    private static BufferedImage createTrapezoidDocumentAsIntRgb(int width, int height) {
        var source = createTrapezoidDocument(width, height);
        var converted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = converted.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return converted;
    }

    private static BufferedImage createUniformImage(int width, int height, Color color) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        var g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static BufferedImage createSmallDocumentOnLargeBackground(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        var g = image.createGraphics();
        // Grand fond sombre
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, width, height);
        // Petit rectangle blanc (< 20% de l'image)
        int docW = width / 6;
        int docH = height / 6;
        g.setColor(Color.WHITE);
        g.fillRect(width / 2 - docW / 2, height / 2 - docH / 2, docW, docH);
        g.dispose();
        return image;
    }

    private static BufferedImage createNoisyImage(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        var g = image.createGraphics();
        // Gradient qui ne forme pas de quadrilatère net
        for (int y = 0; y < height; y++) {
            float ratio = (float) ((double) y / (double) height);
            g.setColor(new Color(ratio, ratio * 0.8f, ratio * 0.6f));
            g.drawLine(0, y, width, y);
        }
        g.dispose();
        return image;
    }
}

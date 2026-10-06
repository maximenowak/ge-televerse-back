package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.ImageProcessingProperties;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Orientation EXIF et bornes mémoire du pipeline image → PDF, et du rendu de page.
 *
 * <p>Le pipeline décodait la photo à pleine résolution, l'orientait par un
 * {@code AffineTransformOp} bicubique (intermédiaire {@code TYPE_INT_ARGB} pleine
 * taille) puis en faisait une copie orientée, avant seulement de la réduire : ~880 Mo
 * de tas pour une photo de 80 MP en Orientation=6. Il décode désormais sous-échantillonné
 * et oriente l'image déjà réduite — ces tests vérifient que le résultat, lui, ne change
 * pas : dimensions, et coin de référence au bon endroit pour chaque orientation.</p>
 */
class PdfServiceImageMemoryTest {

    /** 150 dpi → plafond de 1754 px (hauteur A4). */
    private final PdfService pdfService = new PdfService(120, new ImageProcessingProperties(150, 0.75f, 0, 1));

    /**
     * Photo 800×600 dont le quart haut-gauche est rouge, le reste bleu. Pour chaque
     * orientation EXIF, l'image embarquée a les bonnes dimensions et le quart rouge se
     * retrouve là où la visionneuse l'aurait affiché.
     */
    @ParameterizedTest(name = "Orientation={0}")
    @CsvSource({
            // orientation, largeur attendue, hauteur attendue, coin rouge (x, y) en fraction
            "1, 800, 600, 0.1, 0.1",
            "3, 800, 600, 0.9, 0.9",
            "6, 600, 800, 0.9, 0.1",
            "8, 600, 800, 0.1, 0.9",
            "2, 800, 600, 0.9, 0.1",
            "4, 800, 600, 0.1, 0.9",
            "5, 600, 800, 0.1, 0.1",
            "7, 600, 800, 0.9, 0.9"
    })
    void appliesEveryExifOrientation(int orientation, int expectedW, int expectedH,
                                     double redX, double redY) throws IOException {
        var jpeg = withExifOrientation(quadrantImage(800, 600), orientation);

        var embedded = pdfService.extractFirstImage(pdfService.convertToPdf(jpeg));

        assertThat(embedded.getWidth()).isEqualTo(expectedW);
        assertThat(embedded.getHeight()).isEqualTo(expectedH);
        assertThat(isRed(embedded, redX, redY)).as("quart rouge en (%s, %s)", redX, redY).isTrue();
        assertThat(isRed(embedded, 1 - redX, 1 - redY)).as("coin opposé bleu").isFalse();
    }

    /**
     * Photo bien plus grande que le plafond, en Orientation=6 : décodée
     * sous-échantillonnée, réduite puis orientée — mêmes dimensions finales et même
     * orientation qu'avec l'ancien pipeline pleine résolution.
     */
    @Test
    void largeRotatedPhotoEndsAtTheCeilingWithTheRightOrientation() throws IOException {
        var jpeg = withExifOrientation(quadrantImage(4000, 3000), 6);

        var embedded = pdfService.extractFirstImage(pdfService.convertToPdf(jpeg));

        assertThat(embedded.getHeight()).isEqualTo(1754);
        assertThat(embedded.getWidth()).isCloseTo(1316, org.assertj.core.api.Assertions.within(1));
        assertThat(isRed(embedded, 0.9, 0.1)).isTrue();
        assertThat(isRed(embedded, 0.1, 0.9)).isFalse();
    }

    @Test
    void largePhotoThumbnailKeepsItsAspectRatio() throws IOException {
        var jpeg = toJpeg(quadrantImage(4000, 3000));

        var thumbnail = ImageIO.read(new java.io.ByteArrayInputStream(pdfService.generateThumbnail(jpeg)));

        assertThat(thumbnail.getWidth()).isEqualTo(120);
        assertThat(thumbnail.getHeight()).isEqualTo(90);
    }

    /**
     * Un PDF de quelques Ko peut déclarer une image de 20000×20000 : le rendu de sa
     * vignette la décodait à pleine résolution (> 1 Go) — une OutOfMemoryError, que
     * rien ne rattrape. Les dimensions déclarées sont vérifiées avant tout rendu.
     */
    @Test
    void refusesToRenderAPageDeclaringAnAbsurdImage() throws IOException {
        byte[] pdf;
        try (var doc = new PDDocument()) {
            var page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var image = LosslessFactory.createFromImage(doc, new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB));
            image.getCOSObject().setInt(COSName.WIDTH, 20_000);
            image.getCOSObject().setInt(COSName.HEIGHT, 20_000);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 36, 36, 100, 100);
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        assertThatThrownBy(() -> pdfService.generateThumbnail(pdf))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("trop volumineuse");
    }

    // ===== Helpers =====

    private static BufferedImage quadrantImage(int w, int h) {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h / 2);
        g.dispose();
        return image;
    }

    private static boolean isRed(BufferedImage image, double fx, double fy) {
        int rgb = image.getRGB((int) (fx * (image.getWidth() - 1)), (int) (fy * (image.getHeight() - 1)));
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        return r > 180 && b < 90;
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /**
     * JPEG portant un segment APP1 EXIF minimal (IFD0 à une entrée : Orientation),
     * inséré juste après le SOI — ce qu'écrit un appareil photo de téléphone.
     */
    private static byte[] withExifOrientation(BufferedImage image, int orientation) throws IOException {
        var jpeg = toJpeg(image);
        byte[] exif = {
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0x2A, 0, 0, 0, 8,         // en-tête TIFF big-endian, IFD0 à l'offset 8
                0, 1,                                  // une entrée
                0x01, 0x12, 0, 3, 0, 0, 0, 1,          // tag Orientation, SHORT, 1 valeur
                0, (byte) orientation, 0, 0,           // valeur (cadrée à gauche)
                0, 0, 0, 0                             // pas d'IFD suivant
        };
        int length = exif.length + 2;
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                         // SOI
        out.write(0xFF);
        out.write(0xE1);                               // APP1
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}

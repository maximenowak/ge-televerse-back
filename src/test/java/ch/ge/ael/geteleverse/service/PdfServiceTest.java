package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.ImageProcessingProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PdfServiceTest {

    // (dpi, jpegQuality, opencvThreads, onnxIntraOpThreads) : 150 dpi → plafond de
    // 1754 px (hauteur A4), que convertToPdf_resizesLargeImage doit franchir.
    private static final ImageProcessingProperties IMAGE_PROPS =
            new ImageProcessingProperties(150, 0.75f, 0, 1);

    private PdfService pdfService;

    @BeforeEach
    void setUp() {
        pdfService = new PdfService(120, IMAGE_PROPS);
    }

    // ===== isPdf (détection par magic bytes) =====

    @Test
    void isPdf_detectsHeaderAtOffsetZero() throws IOException {
        assertThat(PdfService.isPdf("%PDF-1.7\nfake".getBytes())).isTrue();
        assertThat(PdfService.isPdf(createTestPdf(1))).isTrue();
    }

    @Test
    void isPdf_detectsHeaderPrecededByJunkWithinWindow() {
        // La spec (note d'implémentation Adobe) tolère l'en-tête dans les 1024
        // premiers octets ; PDFBox est laxiste de la même façon.
        var bytes = new byte[2048];
        var magic = "%PDF-1.4".getBytes();
        System.arraycopy(magic, 0, bytes, 500, magic.length);
        assertThat(PdfService.isPdf(bytes)).isTrue();
    }

    @Test
    void isPdf_rejectsHeaderBeyondWindow() {
        var bytes = new byte[4096];
        var magic = "%PDF-1.4".getBytes();
        System.arraycopy(magic, 0, bytes, 1024, magic.length);
        assertThat(PdfService.isPdf(bytes)).isFalse();
    }

    @Test
    void isPdf_rejectsImagesAndDegenerateInputs() throws IOException {
        assertThat(PdfService.isPdf(createTestJpeg(80, 60))).isFalse();
        assertThat(PdfService.isPdf(createTestPng(80, 60))).isFalse();
        assertThat(PdfService.isPdf("not-a-pdf".getBytes())).isFalse();
        assertThat(PdfService.isPdf("%PDF".getBytes())).isFalse(); // signature tronquée
        assertThat(PdfService.isPdf(new byte[0])).isFalse();
        assertThat(PdfService.isPdf(null)).isFalse();
    }

    // ===== convertToPdf =====

    @Test
    void convertToPdf_convertsJpegToSinglePagePdf() throws IOException {
        var jpeg = createTestJpeg(800, 600);

        var result = pdfService.convertToPdf(jpeg);

        try (var doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void convertToPdf_convertsPngToSinglePagePdf() throws IOException {
        var png = createTestPng(640, 480);

        var result = pdfService.convertToPdf(png);

        try (var doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void convertToPdf_passesThroughPdf() throws IOException {
        var pdf = createTestPdf(3);

        var result = pdfService.convertToPdf(pdf);

        assertThat(result).isEqualTo(pdf);
    }

    @Test
    void convertToPdf_resizesLargeImage() throws IOException {
        var largeJpeg = createTestJpeg(4000, 3000);

        var result = pdfService.convertToPdf(largeJpeg);

        try (var doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(result.length).isLessThan(5_000_000);
        // Plus grand côté ramené au plafond, ratio 4:3 préservé (3000 × 1754/4000 = 1315,5).
        var embedded = pdfService.extractFirstImage(result);
        assertThat(embedded.getWidth()).isEqualTo(IMAGE_PROPS.maxDimensionPx());
        assertThat(embedded.getHeight()).isCloseTo(1316, within(1));
    }

    @Test
    void convertToPdf_throwsOnCorruptImage() {
        var garbage = "not-an-image".getBytes();

        assertThatThrownBy(() -> pdfService.convertToPdf(garbage))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Format d'image non reconnu");
    }

    // ===== imageToPagePdf =====

    @Test
    void imageToPagePdf_createsLandscapeForWideImage() throws IOException {
        var wideImage = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);

        var result = pdfService.imageToPagePdf(wideImage);

        try (var doc = Loader.loadPDF(result)) {
            var box = doc.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isCloseTo(PDRectangle.A4.getHeight(), within(0.01f));
            assertThat(box.getHeight()).isCloseTo(PDRectangle.A4.getWidth(), within(0.01f));
        }
    }

    @Test
    void imageToPagePdf_createsPortraitForTallImage() throws IOException {
        var tallImage = new BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB);

        var result = pdfService.imageToPagePdf(tallImage);

        try (var doc = Loader.loadPDF(result)) {
            var box = doc.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isCloseTo(PDRectangle.A4.getWidth(), within(0.01f));
            assertThat(box.getHeight()).isCloseTo(PDRectangle.A4.getHeight(), within(0.01f));
        }
    }

    // ===== mergeMultiplePdfs =====

    @Test
    void mergeMultiplePdfs_mergesMultiple() throws IOException {
        var pdf1 = createTestPdf(2);
        var pdf2 = createTestPdf(3);
        var pdf3 = createTestPdf(1);

        var result = pdfService.mergeMultiplePdfs(List.of(pdf1, pdf2, pdf3));

        try (var doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(6);
        }
    }

    @Test
    void mergeMultiplePdfs_returnsSinglePdfAsIs() throws IOException {
        var pdf = createTestPdf(2);

        var result = pdfService.mergeMultiplePdfs(List.of(pdf));

        assertThat(result).isEqualTo(pdf);
    }

    @Test
    void mergeMultiplePdfs_throwsOnEmptyList() {
        assertThatThrownBy(() -> pdfService.mergeMultiplePdfs(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Aucune page à fusionner");
    }

    // ===== generateThumbnail =====

    @Test
    void generateThumbnail_fromJpeg() throws IOException {
        var jpeg = createTestJpeg(800, 600);

        var result = pdfService.generateThumbnail(jpeg);

        assertThat(result).isNotEmpty();
        var thumbnail = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(thumbnail).isNotNull();
        assertThat(thumbnail.getWidth()).isEqualTo(120);
        // Ratio préservé : 600/800 * 120 = 90
        assertThat(thumbnail.getHeight()).isEqualTo(90);
    }

    @Test
    void generateThumbnail_fromPng() throws IOException {
        var png = createTestPng(640, 480);

        var result = pdfService.generateThumbnail(png);

        assertThat(result).isNotEmpty();
        var thumbnail = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(thumbnail).isNotNull();
        assertThat(thumbnail.getWidth()).isEqualTo(120);
    }

    @Test
    void generateThumbnail_fromPdf() throws IOException {
        var pdf = createTestPdf(1);

        var result = pdfService.generateThumbnail(pdf);

        assertThat(result).isNotEmpty();
        var thumbnail = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(thumbnail).isNotNull();
        assertThat(thumbnail.getWidth()).isEqualTo(120);
    }

    @Test
    void generateThumbnail_respectsConfigurableWidth() throws IOException {
        var wideService = new PdfService(200, IMAGE_PROPS);
        var jpeg = createTestJpeg(800, 600);

        var result = wideService.generateThumbnail(jpeg);

        var thumbnail = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(thumbnail.getWidth()).isEqualTo(200);
        // Ratio préservé : 600/800 * 200 = 150
        assertThat(thumbnail.getHeight()).isEqualTo(150);
    }

    @Test
    void generateThumbnail_throwsOnCorruptImage() {
        var garbage = "not-an-image".getBytes();

        assertThatThrownBy(() -> pdfService.generateThumbnail(garbage))
                .isInstanceOf(IOException.class);
    }

    // ===== extractFirstImage & transformFirstImage =====

    /** Page d'un PDF « natif » : ce que dessine {@code draw}, rien d'autre. */
    private static byte[] nativePdf(PdfPageDrawing draw) throws IOException {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                draw.on(doc, cs);
            }
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @FunctionalInterface
    private interface PdfPageDrawing {
        void on(org.apache.pdfbox.pdmodel.PDDocument doc, org.apache.pdfbox.pdmodel.PDPageContentStream cs)
                throws IOException;
    }

    private static void drawPhoto(org.apache.pdfbox.pdmodel.PDDocument doc,
                                  org.apache.pdfbox.pdmodel.PDPageContentStream cs,
                                  float x, float y) throws IOException {
        var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc,
                new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB));
        cs.drawImage(image, x, y, 200, 150);
    }

    @Test
    void imageEdits_areAllowedOnAPagePaintingASingleImage() throws IOException {
        // Page issue d'une photo, et scan passé par Ghostscript (image bornée par un
        // chemin de découpe « re W n », qui ne peint rien).
        var fromPhoto = pdfService.convertToPdf(createTestJpeg(800, 600));
        var clippedScan = nativePdf((doc, cs) -> {
            cs.addRect(0, 0, 595, 842);
            cs.clip();
            drawPhoto(doc, cs, 0, 0);
        });

        assertThat(pdfService.extractFirstImage(fromPhoto)).isNotNull();
        assertThat(pdfService.extractFirstImage(clippedScan)).isNotNull();
        assertThat(pdfService.transformFirstImage(clippedScan, java.util.function.UnaryOperator.identity()))
                .isNotEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"text", "filled-shape", "two-images", "form", "no-image"})
    void imageEdits_areRefusedOnAPagePaintingMoreThanItsImage(String content) throws IOException {
        // Recadrage et balance des blancs reconstruisaient la page à partir de sa
        // PREMIÈRE image : texte, tracés et autres images disparaissaient.
        var pdf = nativePdf((doc, cs) -> {
            switch (content) {
                case "text" -> {
                    drawPhoto(doc, cs, 0, 0);
                    cs.beginText();
                    cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Attestation de domicile");
                    cs.endText();
                }
                case "filled-shape" -> {
                    drawPhoto(doc, cs, 0, 0);
                    cs.addRect(300, 300, 100, 100);
                    cs.fill();
                }
                case "two-images" -> {
                    drawPhoto(doc, cs, 0, 0);
                    drawPhoto(doc, cs, 0, 400);
                }
                case "form" -> {
                    // Un seul Do, mais sur un XObject de formulaire : il peut porter du
                    // texte que l'inspection du flux de la page ne voit pas.
                    var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(doc);
                    form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(595, 842));
                    form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
                    try (var fcs = new org.apache.pdfbox.pdmodel.PDFormContentStream(form)) {
                        fcs.beginText();
                        fcs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                        fcs.newLineAtOffset(50, 700);
                        fcs.showText("Attestation");
                        fcs.endText();
                    }
                    cs.drawForm(form);
                }
                default -> {
                    cs.addRect(10, 10, 20, 20);
                    cs.stroke();
                }
            }
        });

        assertThatThrownBy(() -> pdfService.extractFirstImage(pdf))
                .isInstanceOf(ch.ge.ael.geteleverse.controller.PageNotEditableException.class);
        assertThatThrownBy(() -> pdfService.transformFirstImage(pdf, java.util.function.UnaryOperator.identity()))
                .isInstanceOf(ch.ge.ael.geteleverse.controller.PageNotEditableException.class);
    }

    @Test
    void extractFirstImage_returnsEmbeddedBitmapNotRenderedPage() throws IOException {
        // Embedded image carries the actual content boundaries — used by deskew/crop
        // to avoid feeding white A4 margins into the detector on each pass.
        var pdf = pdfService.convertToPdf(createTestJpeg(800, 600));

        var extracted = pdfService.extractFirstImage(pdf);

        assertThat(extracted).isNotNull();
        assertThat(extracted.getWidth()).isEqualTo(800);
        assertThat(extracted.getHeight()).isEqualTo(600);
    }

    @Test
    void transformFirstImage_preservesPageGeometryAcrossMultiplePasses() throws IOException {
        var pdf = pdfService.convertToPdf(createTestJpeg(800, 600));

        // identity transform repeated twice — the bug we're guarding against is content
        // shrinking each pass because the renderer included A4 white margins
        var first = pdfService.transformFirstImage(pdf, java.util.function.UnaryOperator.identity());
        var second = pdfService.transformFirstImage(first, java.util.function.UnaryOperator.identity());

        try (var doc1 = Loader.loadPDF(first);
             var doc2 = Loader.loadPDF(second)) {
            var box1 = doc1.getPage(0).getMediaBox();
            var box2 = doc2.getPage(0).getMediaBox();
            assertThat(box1.getWidth()).isCloseTo(box2.getWidth(), within(0.01f));
            assertThat(box1.getHeight()).isCloseTo(box2.getHeight(), within(0.01f));
        }
        // La MediaBox est un A4 fixe par construction : c'est l'image embarquée qui
        // rétrécissait à chaque passe, c'est donc elle qui doit rester intacte.
        var embedded = pdfService.extractFirstImage(second);
        assertThat(embedded.getWidth()).isEqualTo(800);
        assertThat(embedded.getHeight()).isEqualTo(600);
    }

    @Test
    void transformFirstImage_passesEmbeddedImageNotRenderedPage() throws IOException {
        // Embedded image must be the source bitmap (post-resize), not a render of the
        // entire A4 page (which would include the white margins and grow each pass).
        var pdf = pdfService.convertToPdf(createTestJpeg(800, 600));

        var captured = new java.util.concurrent.atomic.AtomicReference<BufferedImage>();
        pdfService.transformFirstImage(pdf, img -> {
            captured.set(img);
            return img;
        });

        var seen = captured.get();
        assertThat(seen).isNotNull();
        // Should match the resized source dimensions (≤ MAX_DIMENSION_PX), not A4@72dpi (595×842)
        assertThat(seen.getWidth()).isEqualTo(800);
        assertThat(seen.getHeight()).isEqualTo(600);
    }

    // ===== rotatePdfPage & rotateImage =====

    @Test
    void rotatePdfPage_clockwise_setsRotateMetadata() throws IOException {
        var pdf = pdfService.convertToPdf(createTestJpeg(800, 600));

        var rotated = pdfService.rotatePdfPage(pdf, true);

        try (var doc = Loader.loadPDF(rotated)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(90);
        }
    }

    @Test
    void rotatePdfPage_counterClockwise_wrapsBelowZero() throws IOException {
        var pdf = pdfService.convertToPdf(createTestJpeg(800, 600));

        var rotated = pdfService.rotatePdfPage(pdf, false);

        try (var doc = Loader.loadPDF(rotated)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(270);
        }
    }

    @Test
    void rotatePdfPage_fourCwRotations_returnToZero() throws IOException {
        var pdf = pdfService.convertToPdf(createTestJpeg(800, 600));

        for (int i = 0; i < 4; i++) {
            pdf = pdfService.rotatePdfPage(pdf, true);
        }

        try (var doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(0);
        }
    }

    @Test
    void rotatePdfPage_preservesPageDimensions() throws IOException {
        // 800×600 → A4 paysage (orientation choisie d'après l'image source)
        var pdf = pdfService.convertToPdf(createTestJpeg(800, 600));

        var rotated = pdfService.rotatePdfPage(pdf, true);

        // /Rotate is metadata-only — MediaBox stays A4 landscape, viewers handle the rotation
        try (var doc = Loader.loadPDF(rotated)) {
            var mediaBox = doc.getPage(0).getMediaBox();
            assertThat(mediaBox.getWidth()).isCloseTo(PDRectangle.A4.getHeight(), within(0.01f));
            assertThat(mediaBox.getHeight()).isCloseTo(PDRectangle.A4.getWidth(), within(0.01f));
        }
    }

    @Test
    void rotateImage_clockwise_swapsDimensions() {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);

        var rotated = pdfService.rotateImage(image, true);

        assertThat(rotated.getWidth()).isEqualTo(600);
        assertThat(rotated.getHeight()).isEqualTo(800);
    }

    @Test
    void rotateImage_counterClockwise_swapsDimensions() {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);

        var rotated = pdfService.rotateImage(image, false);

        assertThat(rotated.getWidth()).isEqualTo(600);
        assertThat(rotated.getHeight()).isEqualTo(800);
    }

    // ===== Helpers =====

    private byte[] createTestJpeg(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private byte[] createTestPng(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();

        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] createTestPdf(int pages) throws IOException {
        try (var doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}

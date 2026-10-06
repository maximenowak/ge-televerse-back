package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.GhostscriptProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import reactor.test.StepVerifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests du service de recompression Ghostscript.
 *
 * <p>Les tests qui requièrent un binaire Ghostscript fonctionnel sont conditionnés
 * par {@code assumeTrue(realService != null)} : ils s'exécutent quand
 * {@code gs} (Linux) ou {@code gswin64c} (Windows) est sur le {@code PATH},
 * et sont sautés silencieusement sinon. Les tests du chemin "binaire absent"
 * s'exécutent toujours, indépendamment de la présence réelle de Ghostscript.</p>
 */
class GhostscriptServiceTest {

    /** Service réel partagé par tous les tests qui en ont besoin (instancié si GS dispo). */
    private static GhostscriptService realService;
    private static MeterRegistry realRegistry;
    /** Nom du binaire GS détecté ({@code gswin64c} ou {@code gs}), pour instancier des services ad hoc. */
    private static String realBinaryName;

    /** Service par-test instancié sur un binaire factice (toujours indisponible). */
    private GhostscriptService unavailableService;

    @BeforeAll
    static void detectGhostscript() {
        realRegistry = new SimpleMeterRegistry();
        // Sonde une seule fois pour toute la classe : chaque instanciation fork un process.
        for (var candidate : new String[]{"gswin64c", "gs"}) {
            var probe = new GhostscriptService(propsWithBinary(candidate), realRegistry);
            if (probe.isAvailable()) {
                realService = probe;
                realBinaryName = candidate;
                return;
            }
            probe.shutdown();
        }
    }

    @AfterAll
    static void shutdownReal() {
        if (realService != null) realService.shutdown();
    }

    @AfterEach
    void shutdownPerTest() {
        if (unavailableService != null) unavailableService.shutdown();
    }

    // ===== Toujours exécutés (n'ont pas besoin de Ghostscript) =====

    @Test
    void availabilityProbe_returnsFalse_whenBinaryIsMissing() {
        unavailableService = new GhostscriptService(
                propsWithBinary("definitely-not-a-real-binary-xyz"),
                new SimpleMeterRegistry());

        assertThat(unavailableService.isAvailable()).isFalse();
    }

    @Test
    void recompress_returnsOriginalUnchanged_whenServiceUnavailable() throws Exception {
        unavailableService = new GhostscriptService(
                propsWithBinary("definitely-not-a-real-binary-xyz"),
                new SimpleMeterRegistry());
        var pdfBytes = buildLargePdfWithEmbeddedImage();

        StepVerifier.create(unavailableService.recompressPdfIfNeeded(pdfBytes))
                .assertNext(out -> assertThat(out).isSameAs(pdfBytes))
                .verifyComplete();
    }

    // ===== Skipped si Ghostscript indisponible =====

    @Test
    void recompress_skipsRecompression_whenBelowSizeThreshold() {
        assumeTrue(realService != null, "Ghostscript indisponible — test ignoré");
        var smallPdf = buildSmallPdf();
        assertThat(smallPdf.length).isLessThan((int) DataSize.ofMegabytes(2).toBytes());

        StepVerifier.create(realService.recompressPdfIfNeeded(smallPdf))
                .assertNext(out -> assertThat(out).isSameAs(smallPdf))
                .verifyComplete();
    }

    @Test
    void recompress_shrinksPdfWithLargeEmbeddedImage() throws Exception {
        assumeTrue(realService != null, "Ghostscript indisponible — test ignoré");
        var bigPdf = buildLargePdfWithEmbeddedImage();
        assertThat(bigPdf.length).isGreaterThan((int) DataSize.ofMegabytes(2).toBytes());

        StepVerifier.create(realService.recompressPdfIfNeeded(bigPdf))
                .assertNext(out -> {
                    assertThat(out.length)
                            .as("PDF recompressé doit être plus petit que l'original")
                            .isLessThan(bigPdf.length);
                    assertThat(new String(out, 0, 5)).startsWith("%PDF-");
                })
                .verifyComplete();
    }

    /**
     * Régression : la recompression ne doit PAS effacer les groupes de transparence.
     *
     * <p>Les presets {@code /screen}, {@code /ebook}, {@code /printer} convertissent l'espace
     * colorimétrique de la page par défaut, ce qui aplatit et fait DISPARAÎTRE les illustrations
     * semi-transparentes ; {@code GhostscriptService} force donc
     * {@code -dColorConversionStrategy=/LeaveColorUnchanged} (cf. mémoire
     * {@code ghostscript-ebook-drops-transparency}). La fixture
     * {@code /fixtures/pdf-transparency-cover.pdf} est une page de couverture réelle dont
     * l'illustration s'évapore intégralement sans ce flag (vérifié : sortie tombe à ~14 KB).</p>
     *
     * <p>On mesure la densité de pixels colorés (chroma RGB élevée) dans la bande où se trouve
     * l'illustration : un contenu transparent détruit y laisse du blanc et le compte s'effondre.
     * Le test échouerait bruyamment si quelqu'un « simplifiait » la config vers un preset nu.</p>
     */
    @Test
    void recompress_preservesTransparentArtwork() throws Exception {
        assumeTrue(realService != null, "Ghostscript indisponible — test ignoré");
        var fixture = readClasspathBytes("/fixtures/pdf-transparency-cover.pdf");

        // Seuil de taille abaissé à 1 octet pour forcer le passage de la fixture (275 KB) par gs
        // (le service partagé skip sous 2 MB). Params par ailleurs identiques à la prod (/ebook, 300 DPI).
        var props = new GhostscriptProperties(
                realBinaryName, DataSize.ofBytes(1), 3, 50, Duration.ofSeconds(30), "/ebook", 300);
        var svc = new GhostscriptService(props, new SimpleMeterRegistry());
        try {
            var recompressed = svc.recompressPdfIfNeeded(fixture).block();
            assertThat(recompressed).isNotNull();

            long originalArtwork = countArtworkPixels(fixture);
            long recompressedArtwork = countArtworkPixels(recompressed);

            // Sanité : la fixture contient bien une illustration colorée dans la bande visée.
            assertThat(originalArtwork)
                    .as("la couverture de référence doit contenir des pixels colorés")
                    .isGreaterThan(5_000);
            // Cœur du test : l'illustration survit à la recompression (tolérance JPEG large).
            assertThat(recompressedArtwork)
                    .as("l'illustration semi-transparente ne doit pas être effacée par gs")
                    .isGreaterThan(originalArtwork / 2);
        } finally {
            svc.shutdown();
        }
    }

    /**
     * Régression : le découpage page-à-page via Ghostscript ne doit PAS corrompre le texte.
     *
     * <p>Le {@code Splitter} PDFBox (ancien chemin) casse l'encodage des polices sous-ensembles
     * (lettres accentuées → mauvais glyphes, é→Ø) et détache les flux de colorimétrie ICC (les
     * pages ne s'affichent alors plus dans PDFBox : {@code IOException} au rendu). Le split gs
     * ({@link GhostscriptService#recompressAndSplitToPages}) produit des pages autonomes fidèles.
     * Voir la mémoire {@code pdfbox-splitter-corrupts-subset-fonts}.</p>
     *
     * <p>La fixture {@code /fixtures/pdf-multipage-accents.pdf} est un extrait multi-pages réel
     * (texte riche en accents, plusieurs sous-ensembles de police + colorimétrie ICC). On vérifie
     * que chaque page se rend dans PDFBox et que le texte accentué est intact.</p>
     */
    @Test
    void recompressAndSplitToPages_producesRenderablePagesWithIntactText() throws Exception {
        assumeTrue(realService != null, "Ghostscript indisponible — test ignoré");
        var fixture = readClasspathBytes("/fixtures/pdf-multipage-accents.pdf");
        int expectedPages = 8;

        List<byte[]> pages = realService.recompressAndSplitToPages(fixture, expectedPages).block();

        assertThat(pages).as("gs doit produire une page par page source").hasSize(expectedPages);

        // Chaque page est un PDF autonome que PDFBox rend SANS erreur (le Splitter PDFBox
        // détachait les flux ICC → IOException au rendu). Le rendu ne doit pas lever.
        for (byte[] page : pages) {
            try (var doc = Loader.loadPDF(page)) {
                assertThat(doc.getNumberOfPages()).isEqualTo(1);
                new PDFRenderer(doc).renderImageWithDPI(0, 72);
            }
        }

        // Le texte accentué doit survivre (le Splitter PDFBox le corrompait : é→Ø, è→Ł).
        // La 2ᵉ page de la fixture contient « société », « médias », « accéléré ».
        String page2Text = extractText(pages.get(1));
        assertThat(page2Text)
                .as("accents préservés par le split Ghostscript")
                .contains("société")
                .contains("médias")
                .contains("accéléré");
        assertThat(page2Text)
                .as("aucun glyphe accentué corrompu")
                .doesNotContain("sociØtØ")
                .doesNotContain("mØdias");
    }

    /**
     * Les PDF téléversés passent par gs : les pages d'un scan doivent en sortir
     * éditables (recadrage, balance des blancs), celles d'un PDF de texte non. Garde le
     * filtre d'opérateurs de {@link PdfService#paintsASingleImage} aligné sur ce que
     * pdfwrite émet réellement (découpe, état graphique…).
     */
    @Test
    void recompressAndSplitToPages_keepsScannedPagesEditable_andTextPagesNot() throws Exception {
        assumeTrue(realService != null, "Ghostscript indisponible — test ignoré");
        var scan = buildLargePdfWithEmbeddedImage();

        List<byte[]> scanned = realService.recompressAndSplitToPages(scan, 1).block();
        List<byte[]> text = realService.recompressAndSplitToPages(
                readClasspathBytes("/fixtures/pdf-multipage-accents.pdf"), 8).block();

        assertThat(scanned).hasSize(1);
        try (var doc = Loader.loadPDF(scanned.getFirst())) {
            assertThat(PdfService.paintsASingleImage(doc.getPage(0))).isTrue();
        }
        try (var doc = Loader.loadPDF(text.get(1))) {
            assertThat(PdfService.paintsASingleImage(doc.getPage(0))).isFalse();
        }
    }

    @Test
    void recompress_recordsMetrics_onSuccess() throws Exception {
        assumeTrue(realService != null, "Ghostscript indisponible — test ignoré");
        var bigPdf = buildLargePdfWithEmbeddedImage();
        var successTimer = realRegistry.find("televerse.gs.duration").tag("status", "success").timer();
        var inputSummary = realRegistry.find("televerse.gs.input.bytes").summary();
        var outputSummary = realRegistry.find("televerse.gs.output.bytes").summary();
        long beforeCount = successTimer == null ? 0 : successTimer.count();

        realService.recompressPdfIfNeeded(bigPdf).block();

        // Le compteur de succès a été incrémenté ; les histogrammes ont reçu une valeur.
        assertThat(successTimer).isNotNull();
        assertThat(successTimer.count()).isEqualTo(beforeCount + 1);
        assertThat(inputSummary).isNotNull();
        assertThat(inputSummary.max()).isGreaterThanOrEqualTo(bigPdf.length);
        assertThat(outputSummary).isNotNull();
        assertThat(outputSummary.totalAmount()).isGreaterThan(0);
    }

    @Test
    void recompress_recordsSkippedMetric_whenBelowThreshold() {
        assumeTrue(realService != null, "Ghostscript indisponible — test ignoré");
        var smallPdf = buildSmallPdf();
        var skippedTimer = realRegistry.find("televerse.gs.duration").tag("status", "skipped").timer();
        long beforeCount = skippedTimer == null ? 0 : skippedTimer.count();

        realService.recompressPdfIfNeeded(smallPdf).block();

        assertThat(skippedTimer).isNotNull();
        assertThat(skippedTimer.count()).isEqualTo(beforeCount + 1);
    }

    // ===== Helpers =====

    private static byte[] readClasspathBytes(String resource) throws IOException {
        try (var in = GhostscriptServiceTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s présente sur le classpath", resource).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Extrait la couche texte d'un PDF via PDFBox (chemin d'extraction / copier-coller). */
    private static String extractText(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /**
     * Compte les pixels « colorés » (chroma RGB &gt; 60) dans la bande [10%..45%] de la hauteur
     * de la page 1 — la zone de l'illustration de couverture, au-dessus du bandeau opaque. Un
     * contenu transparent détruit y laisse du blanc (chroma nulle), effondrant le compte.
     */
    private static long countArtworkPixels(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 100);
            int width = img.getWidth();
            int yStart = (int) (img.getHeight() * 0.10);
            int yEnd = (int) (img.getHeight() * 0.45);
            long count = 0;
            for (int y = yStart; y < yEnd; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                    if (chroma > 60) count++;
                }
            }
            return count;
        }
    }

    private static GhostscriptProperties propsWithBinary(String binaryName) {
        return new GhostscriptProperties(
                binaryName,
                DataSize.ofMegabytes(2),
                3,
                50,
                Duration.ofSeconds(15),
                "/ebook",
                300
        );
    }

    /**
     * Construit un PDF d'une page A4 contenant une photo haute résolution synthétique.
     * L'image (gradient + bruit modéré) est suffisamment volumineuse pour dépasser
     * le seuil de 2 MB et bénéficier nettement de la recompression {@code /ebook}.
     */
    private static byte[] buildLargePdfWithEmbeddedImage() throws Exception {
        var image = new BufferedImage(3000, 4000, BufferedImage.TYPE_INT_RGB);
        var rng = new Random(42);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int r = clamp(x * 255 / image.getWidth() + rng.nextInt(40) - 20);
                int g = clamp(y * 255 / image.getHeight() + rng.nextInt(40) - 20);
                int b = clamp(((x + y) * 255 / (image.getWidth() + image.getHeight())) + rng.nextInt(40) - 20);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        try (var doc = new PDDocument()) {
            var page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var pdImage = JPEGFactory.createFromImage(doc, image, 0.95f);
            try (var content = new PDPageContentStream(doc, page)) {
                content.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildSmallPdf() {
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}

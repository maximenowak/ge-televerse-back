package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.AwsProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.CredentialsProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.HttpProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.RegionProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.S3Properties;
import ch.ge.ael.geteleverse.config.FakeS3AsyncClient;
import ch.ge.ael.geteleverse.config.ImageProcessingProperties;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Éditions de page de bout en bout côté stockage : vrai {@link PdfService}, S3 local
 * ({@link FakeS3AsyncClient}), détection mockée (pas de natives OpenCV requises).
 * Contrairement à {@code StorageServiceTest}, qui mocke {@code PdfService}, ces tests
 * voient le PDF réellement écrit.
 */
class StorageServiceEditTest {

    private static final String PAGE_KEY = "tok/page-001.pdf";
    private static final String NEW_KEY = "tok/page-001-3f2a9c1e.pdf";
    private static final String NEW_THUMB = "tok/page-001-3f2a9c1e-thumb.jpg";

    @TempDir
    Path s3Root;

    private final PdfService pdfService = new PdfService(120, new ImageProcessingProperties(150, 0.75f, 0, 1));
    private final PerspectiveCorrectionService perspective = mock(PerspectiveCorrectionService.class);
    private final ThumbnailCacheService thumbnailCache = mock(ThumbnailCacheService.class);
    private StorageService storage;

    @BeforeEach
    void setUp() {
        var props = new AwsProperties(
                new RegionProperties("us-east-1"),
                new CredentialsProperties("key", "secret"),
                new S3Properties("bucket", "https://s3.local"),
                new HttpProperties(100, 200, Duration.ofSeconds(60), Duration.ofSeconds(10),
                        Duration.ofSeconds(120), Duration.ofSeconds(60)));
        when(thumbnailCache.put(any(), any())).thenReturn(Mono.empty());
        when(thumbnailCache.delete(any())).thenReturn(Mono.empty());
        storage = new StorageService(new FakeS3AsyncClient(s3Root), props, pdfService, perspective,
                mock(WhiteBalanceService.class), thumbnailCache);
    }

    /**
     * L'usager tourne une page photographiée de travers, puis la recadre : la page
     * reconstruite depuis l'image embarquée doit garder sa rotation. Avant correctif,
     * imageToPagePdf créait une page neuve sans /Rotate et la page revenait de travers.
     */
    @Test
    void cropKeepsARotationAppliedBefore() throws IOException {
        storeRotatedPage(90);
        var cropped = new BufferedImage(700, 500, BufferedImage.TYPE_INT_RGB);
        when(perspective.crop(any())).thenReturn(new PerspectiveCorrectionService.Result(cropped, true));

        var result = storage.cropPage(PAGE_KEY, NEW_KEY, NEW_THUMB).block();

        assertThat(result.applied()).isTrue();
        assertThat(rotationOf(NEW_KEY)).isEqualTo(90);
    }

    @Test
    void deskewKeepsARotationAppliedBefore() throws IOException {
        storeRotatedPage(270);
        var corrected = new BufferedImage(700, 500, BufferedImage.TYPE_INT_RGB);
        when(perspective.apply(any())).thenReturn(new PerspectiveCorrectionService.Result(corrected, true));

        var result = storage.deskewPage(PAGE_KEY, NEW_KEY, NEW_THUMB).block();

        assertThat(result.applied()).isTrue();
        assertThat(rotationOf(NEW_KEY)).isEqualTo(270);
    }

    @Test
    void cropOfAnUnrotatedPageStaysUnrotated() throws IOException {
        storeRotatedPage(0);
        var cropped = new BufferedImage(700, 500, BufferedImage.TYPE_INT_RGB);
        when(perspective.crop(any())).thenReturn(new PerspectiveCorrectionService.Result(cropped, true));

        storage.cropPage(PAGE_KEY, NEW_KEY, NEW_THUMB).block();

        assertThat(rotationOf(NEW_KEY)).isZero();
    }

    /**
     * Une édition écrit sa nouvelle version sous les clés neuves et ne touche JAMAIS la
     * version courante : c'est ce qui rend une édition refusée (verrou contesté, page
     * modifiée entre-temps) sans effet, et son rejeu sans double application.
     */
    @Test
    void editsNeverOverwriteTheCurrentVersion() throws IOException {
        storeRotatedPage(0);
        var current = storage.fetchFromS3(PAGE_KEY).block();

        storage.rotatePage(PAGE_KEY, true, NEW_KEY, NEW_THUMB).block();

        assertThat(storage.fetchFromS3(PAGE_KEY).block()).isEqualTo(current);
        assertThat(rotationOf(PAGE_KEY)).isZero();
        assertThat(rotationOf(NEW_KEY)).isEqualTo(90);
        verify(thumbnailCache).put(org.mockito.ArgumentMatchers.eq(NEW_THUMB), any());
    }

    @Test
    void nothingIsWritten_whenNoDocumentIsDetected() {
        storeRotatedPageUnchecked();
        when(perspective.crop(any())).thenReturn(new PerspectiveCorrectionService.Result(null, false));

        var result = storage.cropPage(PAGE_KEY, NEW_KEY, NEW_THUMB).block();

        assertThat(result.applied()).isFalse();
        assertThatThrownBy(() -> storage.fetchFromS3(NEW_KEY).block())
                .isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
    }

    @Test
    void aNativePdfPageIsRefused_andNothingIsWritten() throws IOException {
        // Page de PDF portant du texte : la reconstruire depuis son image l'effacerait.
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Facture");
                cs.endText();
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            storage.uploadToS3(PAGE_KEY, out.toByteArray(), "facture.pdf").block();
        }

        assertThatThrownBy(() -> storage.cropPage(PAGE_KEY, NEW_KEY, NEW_THUMB).block())
                .isInstanceOf(ch.ge.ael.geteleverse.controller.PageNotEditableException.class);
        assertThatThrownBy(() -> storage.whiteBalancePage(PAGE_KEY, NEW_KEY, NEW_THUMB).block())
                .isInstanceOf(ch.ge.ael.geteleverse.controller.PageNotEditableException.class);
        assertThatThrownBy(() -> storage.fetchFromS3(NEW_KEY).block())
                .isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
        // La rotation, elle, ne touche qu'à /Rotate : admise sur toute page.
        storage.rotatePage(PAGE_KEY, true, NEW_KEY, NEW_THUMB).block();
        assertThat(rotationOf(NEW_KEY)).isEqualTo(90);
    }

    private void storeRotatedPageUnchecked() {
        try {
            storeRotatedPage(0);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void storeRotatedPage(int degrees) throws IOException {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        var jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", jpeg);
        var pdf = pdfService.convertToPdf(jpeg.toByteArray());
        for (int i = 0; i < degrees / 90; i++) {
            pdf = pdfService.rotatePdfPage(pdf, true);
        }
        storage.uploadToS3(PAGE_KEY, pdf, "photo.jpg").block();
    }

    private int rotationOf(String key) throws IOException {
        var bytes = storage.fetchFromS3(key).block();
        try (var doc = Loader.loadPDF(bytes)) {
            return doc.getPage(0).getRotation();
        }
    }
}

package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.config.LimitsProperties;
import ch.ge.ael.geteleverse.config.UploadFormatProperties;
import ch.ge.ael.geteleverse.model.PageInfo;
import ch.ge.ael.geteleverse.model.SizeProfile;
import ch.ge.ael.geteleverse.model.UploadEvent;
import ch.ge.ael.geteleverse.model.UploadSession;
import ch.ge.ael.geteleverse.service.AntivirusService;
import ch.ge.ael.geteleverse.service.GhostscriptService;
import ch.ge.ael.geteleverse.service.PdfService;
import ch.ge.ael.geteleverse.service.SessionService;
import ch.ge.ael.geteleverse.service.StorageService;
import ch.ge.ael.geteleverse.service.ThumbnailCacheService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.util.unit.DataSize;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock SessionService sessionService;
    @Mock StorageService storageService;
    @Mock PdfService pdfService;
    @Mock GhostscriptService ghostscriptService;
    @Mock AntivirusService antivirusService;
    @Mock ThumbnailCacheService thumbnailCache;
    @Mock FilePart filePart;
    @Captor ArgumentCaptor<UploadEvent> eventCaptor;

    private UploadController controller;
    private LimitsProperties limits;
    private UploadFormatProperties uploadFormats;
    private DistributionSummary uploadRawBytes;
    private Bulkhead uploadBulkhead;
    private SimpleMeterRegistry meterRegistry;

    private static final SizeProfile PROFILE = SizeProfile.S;
    private static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024; // S = 25 MB

    @BeforeEach
    void setUp() {
        var profiles = new java.util.EnumMap<SizeProfile, LimitsProperties.Profile>(SizeProfile.class);
        profiles.put(SizeProfile.S, new LimitsProperties.Profile(DataSize.ofMegabytes(25)));
        profiles.put(SizeProfile.M, new LimitsProperties.Profile(DataSize.ofMegabytes(100)));
        profiles.put(SizeProfile.L, new LimitsProperties.Profile(DataSize.ofMegabytes(250)));
        profiles.put(SizeProfile.XL, new LimitsProperties.Profile(DataSize.ofMegabytes(500)));
        limits = new LimitsProperties(100, DataSize.ofMegabytes(100), profiles);
        uploadFormats = new UploadFormatProperties(List.of("pdf", "jpg", "jpeg", "png", "tiff"));
        meterRegistry = new SimpleMeterRegistry();
        uploadRawBytes = DistributionSummary.builder("test.upload.raw.bytes")
                .register(meterRegistry);
        // Bulkhead permissif pour les tests fonctionnels — capacité largement supérieure
        // à la concurrence d'un test unitaire (1 requête à la fois).
        uploadBulkhead = newBulkhead(8, Duration.ofMillis(50));
        controller = new UploadController(sessionService, storageService, pdfService,
                ghostscriptService, antivirusService, limits, uploadFormats, uploadRawBytes,
                uploadBulkhead, meterRegistry, thumbnailCache);
        // Rollback d'une vignette : dans le cache Hazelcast, où elle vit.
        lenient().when(thumbnailCache.delete(any())).thenReturn(Mono.empty());
        // Par défaut, la recompression Ghostscript renvoie l'entrée inchangée :
        // les tests existants n'ont pas à se soucier de cette étape.
        lenient().when(ghostscriptService.recompressPdfIfNeeded(any()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // Par défaut, le scan AV passe (clean) — les tests dédiés au refus stubent
        // explicitement Mono.error(InfectedFileException) ou AntivirusUnavailableException.
        lenient().when(antivirusService.scan(any(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void upload_imageSuccess() throws IOException {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var pageId = session.nextPageId();
        var thumbnailKey = session.nextThumbnailKey();
        var pageInfo = new PageInfo(pageId, pageKey, "photo.jpg", "image/jpeg", 1024L, Instant.now(), thumbnailKey);
        // attachPage renouvelle l'expiry sous le verrou (cf. SessionService),
        // donc la session post-attach a déjà un expiresAt à jour.
        var sessionWithPage = session.withAddedPage(pageInfo).withRenewedExpiry(Duration.ofMinutes(15));

        var imageBytes = "fake-image".getBytes();
        var pdfBytes = "fake-pdf".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation(pageId, pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes)).thenReturn(pdfBytes);
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), eq(pdfBytes), eq("photo.jpg")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 1024L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), anyBoolean()))
                .thenReturn(Mono.just(new SessionService.AttachResult(true, sessionWithPage)));
        mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    var body = (UploadController.UploadResponse) response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.documentId()).isEqualTo(session.documentId());
                    assertThat(body.message()).isEqualTo("Upload successful");
                    assertThat(body.pageId()).isEqualTo(pageId);
                    assertThat(body.totalPages()).isEqualTo(1);
                    assertThat(body.pagesAdded()).isEqualTo(1);
                    assertThat(body.thumbnailUrl())
                            .startsWith("/api/sessions/tok/pages/%s/thumbnail?v=".formatted(pageId));
                })
                .verifyComplete();

        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("upload-complete");
    }

    @Test
    void upload_singlePagePdf() throws IOException {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var pageId = session.nextPageId();
        var thumbnailKey = session.nextThumbnailKey();
        var sessionWithPage = session.withAddedPage(
                new PageInfo(pageId, pageKey, "doc.pdf", "application/pdf", 2048L, Instant.now(), thumbnailKey))
                .withRenewedExpiry(Duration.ofMinutes(15));

        // Les octets factices portent la signature %PDF- : le routage se fait
        // désormais par magic bytes, plus par le Content-Type déclaré.
        var pdfBytes = "%PDF-1.4 single-page".getBytes();

        // getSession appelé une fois : check initial. Le mapping final lit
        // l'expiresAt depuis le dernier AttachResult (sous verrou côté service)
        // au lieu de re-faire un getSession/renewSession susceptible de
        // clobber des attaches concurrentes.
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation(pageId, pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.splitPdfToPages(pdfBytes)).thenReturn(List.of(pdfBytes));
        when(storageService.storePageFromBytes(anyString(), anyString(), eq(pdfBytes), eq("doc.pdf")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 2048L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenReturn(Mono.just(new SessionService.AttachResult(true, sessionWithPage)));
        mockFilePartHeaders("doc.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    var body = (UploadController.UploadResponse) response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.pagesAdded()).isEqualTo(1);
                    assertThat(body.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void upload_multiPagePdf_splitsIntoIndividualPages() throws IOException {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 multi-page".getBytes();
        var page1Bytes = "page-1".getBytes();
        var page2Bytes = "page-2".getBytes();
        var page3Bytes = "page-3".getBytes();

        // Mock stateful : attachPage accumule les pages dans la référence partagée
        // et renouvelle l'expiry (comme la vraie impl sous verrou). getSession
        // ne sert plus que pour le check initial du contrôleur.
        var sessionState = new java.util.concurrent.atomic.AtomicReference<>(session);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok")).thenReturn(
                Mono.just(new SessionService.Reservation("page-001", "k1", "t1", 1)),
                Mono.just(new SessionService.Reservation("page-002", "k2", "t2", 2)),
                Mono.just(new SessionService.Reservation("page-003", "k3", "t3", 3)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.splitPdfToPages(pdfBytes)).thenReturn(List.of(page1Bytes, page2Bytes, page3Bytes));
        when(storageService.storePageFromBytes(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.just(new StorageService.StorePageResult("key", "thumb", 500L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenAnswer(inv -> {
                    var updated = sessionState.get().withAttachedPage(inv.getArgument(1))
                            .withRenewedExpiry(Duration.ofMinutes(15));
                    sessionState.set(updated);
                    return Mono.just(new SessionService.AttachResult(true, updated));
                });
        mockFilePartHeaders("multi.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    var body = (UploadController.UploadResponse) response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.pagesAdded()).isEqualTo(3);
                })
                .verifyComplete();

        // Chaque page doit déclencher un événement SSE
        verify(sessionService, times(3)).notifyUpload(eq("tok"), any(UploadEvent.class));
    }

    @Test
    void upload_multiPagePdf_usesGhostscriptSplit_whenAvailable() throws IOException {
        // Régression : quand gs est disponible, un PDF multi-pages passe par le split
        // Ghostscript (recompressAndSplitToPages, pages fidèles) et JAMAIS par le Splitter
        // PDFBox — ce dernier corrompt les polices sous-ensembles (accents) et la
        // colorimétrie ICC. Cf. mémoire pdfbox-splitter-corrupts-subset-fonts.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 multi-page".getBytes();
        var page1Bytes = "page-1".getBytes();
        var page2Bytes = "page-2".getBytes();
        var page3Bytes = "page-3".getBytes();

        var sessionState = new java.util.concurrent.atomic.AtomicReference<>(session);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok")).thenReturn(
                Mono.just(new SessionService.Reservation("page-001", "k1", "t1", 1)),
                Mono.just(new SessionService.Reservation("page-002", "k2", "t2", 2)),
                Mono.just(new SessionService.Reservation("page-003", "k3", "t3", 3)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes)).thenReturn(3);
        when(ghostscriptService.isAvailable()).thenReturn(true);
        when(ghostscriptService.recompressAndSplitToPages(eq(pdfBytes), eq(3)))
                .thenReturn(Mono.just(List.of(page1Bytes, page2Bytes, page3Bytes)));
        when(storageService.storePageFromBytes(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.just(new StorageService.StorePageResult("key", "thumb", 500L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenAnswer(inv -> {
                    var updated = sessionState.get().withAttachedPage(inv.getArgument(1))
                            .withRenewedExpiry(Duration.ofMinutes(15));
                    sessionState.set(updated);
                    return Mono.just(new SessionService.AttachResult(true, updated));
                });
        mockFilePartHeaders("multi.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    var body = (UploadController.UploadResponse) response.getBody();
                    assertThat(body).isNotNull();
                    assertThat(body.pagesAdded()).isEqualTo(3);
                })
                .verifyComplete();

        verify(ghostscriptService).recompressAndSplitToPages(pdfBytes, 3);
        verify(pdfService, never()).splitPdfToPages(any());
    }

    @Test
    void upload_returns429_whenMaxPagesReached() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        for (int i = 0; i < 100; i++) {
            session = session.withAddedPage(new PageInfo("page-%03d".formatted(i + 1),
                    "k" + i, "f.pdf", "application/pdf", 1L, Instant.now(), null));
        }
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.message()).contains("Limite de pages");
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_PAGE_LIMIT);
                })
                .verifyComplete();

        verifyNoInteractions(storageService);
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_PAGE_LIMIT)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void upload_pdfExceedingPageBudget_blocksBeforeGhostscript() throws IOException {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 huge".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes)).thenReturn(150);
        mockFilePartHeaders("huge.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_PDF_TOO_MANY_PAGES);
                })
                .verifyComplete();

        // Critique : ni gs ni split ne doivent être invoqués pour un PDF rejeté à l'amont.
        verify(ghostscriptService, never()).recompressPdfIfNeeded(any());
        verify(pdfService, never()).splitPdfToPages(any());
        verify(storageService, never()).storePageFromBytes(any(), any(), any(), any());
    }

    @Test
    void upload_corruptPdf_errorsWithCorruptFileException() throws IOException {
        // PDF mal formé : Loader.loadPDF (countPdfPages) lève IOException. Doit
        // ressortir en CorruptFileException (→ 422 côté GlobalExceptionHandler),
        // pas en IOException brute qui finirait en 500.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-garbage".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes))
                .thenThrow(new IOException("Missing root object specification in trailer."));
        mockFilePartHeaders("broken.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(CorruptFileException.class);
                    assertThat(((CorruptFileException) err).filename()).isEqualTo("broken.pdf");
                })
                .verify();

        // Rejeté au portail de parsing : ni recompression gs, ni stockage S3.
        verify(ghostscriptService, never()).recompressPdfIfNeeded(any());
        verify(storageService, never()).storePageFromBytes(any(), any(), any(), any());
    }

    @Test
    void upload_corruptImage_errorsWithCorruptFileException() throws IOException {
        // Image illisible : convertToPdf (ImageIO.read → null) lève IOException.
        // Même traduction en CorruptFileException que pour un PDF corrompu.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var thumbnailKey = session.nextThumbnailKey();
        var imageBytes = "not-an-image".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation("page-001", pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes))
                .thenThrow(new IOException("Unsupported or corrupt image format"));
        mockFilePartHeaders("broken.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(CorruptFileException.class);
                    assertThat(((CorruptFileException) err).filename()).isEqualTo("broken.jpg");
                })
                .verify();

        // La conversion échoue avant tout stockage S3 ou attache de page.
        verify(storageService, never()).storePageFromBytes(any(), any(), any(), any());
        verify(sessionService, never()).attachPage(any(), any(), anyInt(), anyLong(), anyBoolean());
    }

    @Test
    void upload_returns413AndRollsBack_whenTotalSizeExceeded() throws IOException {
        var existing = new PageInfo("page-001", "k1", "f.jpg", "image/jpeg",
                24L * 1024 * 1024, Instant.now(), null);
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15)).withAddedPage(existing);
        var pageKey = session.nextPageKey();
        var thumbnailKey = session.nextThumbnailKey();
        var oversizedBytes = 2L * 1024 * 1024;

        var imageBytes = "big-image".getBytes();
        var pdfBytes = "big-pdf".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation("page-002", pageKey, thumbnailKey, 2)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes)).thenReturn(pdfBytes);
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), eq(pdfBytes), eq("big.jpg")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, oversizedBytes)));
        // attachPage refuse pour quota dépassé : la session retournée reflète l'état pré-attachement.
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), anyBoolean()))
                .thenReturn(Mono.just(new SessionService.AttachResult(false, session)));
        when(storageService.deleteFromS3(pageKey)).thenReturn(Mono.empty());
        mockFilePartHeaders("big.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    // Le rejet pour quota cumulé renvoie le contrat dédié SESSION_SIZE_EXCEEDED
                    // consommé par le front (cf. PLAN.md « Erreur SESSION_SIZE_EXCEEDED »).
                    var body = (GlobalExceptionHandler.ErrorResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.error()).isEqualTo(UploadController.ERROR_SESSION_SIZE_EXCEEDED);
                    assertThat(body.message()).isEqualTo("Session size limit exceeded");
                    // totalBytes : taille de la session AVANT le fichier rejeté
                    // (la page de 24 MB déjà présente, sans le nouveau de 2 MB).
                    assertThat(body.totalBytes()).isEqualTo(24L * 1024 * 1024);
                    assertThat(body.maxTotalBytes()).isEqualTo(MAX_TOTAL_BYTES);
                })
                .verifyComplete();

        verify(storageService).deleteFromS3(pageKey);
        verify(thumbnailCache).delete(thumbnailKey);
        verify(storageService, never()).deleteFromS3(thumbnailKey);
        // Le rejet alimente le compteur taggé pour la corrélation profil/code d'erreur.
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_SESSION_SIZE_EXCEEDED)
                .tag("sizeProfile", PROFILE.name())
                .counter().count()).isEqualTo(1.0);
    }

    /**
     * Attach refusé en ERREUR sans rien écrire (verrou non obtenu → 429 SESSION_BUSY,
     * que le front rejoue ; session passée en finalisation → 409) : l'objet déjà stocké
     * est défait comme pour un refus. Avant, la page — un document personnel — restait
     * sur S3 jusqu'au cycle de vie du bucket, et chaque rejeu en écrivait une copie de plus.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"lock", "finalizing"})
    void upload_rollsBackTheStoredPage_whenAttachFailsWithoutWriting(String cause) throws IOException {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var thumbnailKey = session.nextThumbnailKey();
        var imageBytes = "an-image".getBytes();
        var pdfBytes = "a-pdf".getBytes();
        RuntimeException failure = "lock".equals(cause)
                ? new ch.ge.ael.geteleverse.service.SessionLockTimeoutException("tok", Duration.ofSeconds(2), 1L)
                : SessionConflictException.sessionFinalizing();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation("page-001", pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes)).thenReturn(pdfBytes);
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), eq(pdfBytes), eq("photo.jpg")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 1024L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), anyBoolean()))
                .thenReturn(Mono.error(failure));
        when(storageService.deleteFromS3(pageKey)).thenReturn(Mono.empty());
        mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .expectErrorMatches(e -> e == failure)
                .verify();

        verify(storageService).deleteFromS3(pageKey);
        verify(thumbnailCache).delete(thumbnailKey);
    }

    @Test
    void upload_keepsTheStoredPage_whenTheAttachOutcomeIsUnknown() throws IOException {
        // Panne Hazelcast PENDANT l'écriture : la page a peut-être été rattachée. La
        // supprimer pourrait vider une page listée — mieux vaut un objet orphelin.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var thumbnailKey = session.nextThumbnailKey();
        var imageBytes = "an-image".getBytes();
        var pdfBytes = "a-pdf".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation("page-001", pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes)).thenReturn(pdfBytes);
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), eq(pdfBytes), eq("photo.jpg")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 1024L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), anyBoolean()))
                .thenReturn(Mono.error(new com.hazelcast.core.HazelcastException("connection lost")));
        mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .expectError(com.hazelcast.core.HazelcastException.class)
                .verify();

        verify(storageService, never()).deleteFromS3(anyString());
    }

    @Test
    void upload_returns429AndRollsBack_whenAttachPageLimitReached() throws IOException {
        // Course multi-sélection : le pré-contrôle d'entrée voit la session sous le
        // cap (1 page < 100), mais un upload concurrent la remplit avant l'attache ;
        // attachPage refuse sous le verrou avec PAGE_LIMIT. La page déjà stockée sur
        // S3 doit être rollback et le client recevoir 429 PAGE_LIMIT_REACHED — et non
        // un 200 muet qui laisse la session au-delà du cap (le bug corrigé).
        var existing = new PageInfo("page-001", "k1", "f.jpg", "image/jpeg",
                1024L, Instant.now(), null);
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15)).withAddedPage(existing);
        var pageKey = session.nextPageKey();
        var thumbnailKey = session.nextThumbnailKey();

        var imageBytes = "an-image".getBytes();
        var pdfBytes = "a-pdf".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation("page-002", pageKey, thumbnailKey, 2)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes)).thenReturn(pdfBytes);
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), eq(pdfBytes), eq("photo.jpg")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 1024L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), anyBoolean()))
                .thenReturn(Mono.just(new SessionService.AttachResult(false, session,
                        SessionService.AttachRejection.PAGE_LIMIT)));
        when(storageService.deleteFromS3(pageKey)).thenReturn(Mono.empty());
        mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_PAGE_LIMIT);
                    assertThat(body.message()).contains("Limite de pages");
                })
                .verifyComplete();

        // La page stockée avant le refus sous verrou doit être nettoyée de S3.
        verify(storageService).deleteFromS3(pageKey);
        verify(thumbnailCache).delete(thumbnailKey);
        verify(storageService, never()).deleteFromS3(thumbnailKey);
        // Le rejet alimente le compteur taggé, comme le pré-contrôle d'entrée.
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_PAGE_LIMIT)
                .tag("sizeProfile", PROFILE.name())
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void upload_pdfFullyRefusedUnderLock_returns429PdfTooManyPages() throws IOException {
        // Course multi-sélection : le pré-contrôle de handlePdfUpload voit assez de
        // slots, mais un upload concurrent remplit la session avant les attaches —
        // toutes refusées PAGE_LIMIT sous le verrou. Le client doit recevoir 429
        // PDF_TOO_MANY_PAGES (même bannière front que le pré-contrôle), et non le
        // 413 SESSION_SIZE_EXCEEDED qui affichait la bannière « limite de taille ».
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 two-page".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok")).thenReturn(
                Mono.just(new SessionService.Reservation("page-001", "k1", "t1", 1)),
                Mono.just(new SessionService.Reservation("page-002", "k2", "t2", 2)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes)).thenReturn(2);
        when(pdfService.splitPdfToPages(pdfBytes)).thenReturn(List.of("p1".getBytes(), "p2".getBytes()));
        when(storageService.storePageFromBytes(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.just(new StorageService.StorePageResult("key", "thumb", 500L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenReturn(Mono.just(new SessionService.AttachResult(false, session,
                        SessionService.AttachRejection.PAGE_LIMIT)));
        when(storageService.deleteFromS3(anyString())).thenReturn(Mono.empty());
        mockFilePartHeaders("doc.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_PDF_TOO_MANY_PAGES);
                    assertThat(body.pagesAdded()).isZero();
                })
                .verifyComplete();

        // Aucune page attachée : pas d'événement SSE, et le rejet est compté une
        // seule fois (pas une fois par page refusée).
        verify(sessionService, never()).notifyUpload(any(), any());
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_PDF_TOO_MANY_PAGES)
                .tag("sizeProfile", PROFILE.name())
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void upload_pdfPartiallyAttached_returns200WithPageLimitErrorCode() throws IOException {
        // Course multi-sélection : la première page du PDF s'attache, la seconde est
        // refusée PAGE_LIMIT sous le verrou. La réponse 200 « Upload partiel » doit
        // porter le motif réel (PDF_TOO_MANY_PAGES) pour que le front affiche la
        // bannière « trop de pages », et non SESSION_SIZE_EXCEEDED qui déclencherait
        // la bannière « limite de taille ».
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 two-page".getBytes();
        var attached = session.withAddedPage(dummyPage(session, 1));

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok")).thenReturn(
                Mono.just(new SessionService.Reservation("page-001", "k1", "t1", 1)),
                Mono.just(new SessionService.Reservation("page-002", "k2", "t2", 2)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes)).thenReturn(2);
        when(pdfService.splitPdfToPages(pdfBytes)).thenReturn(List.of("p1".getBytes(), "p2".getBytes()));
        when(storageService.storePageFromBytes(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.just(new StorageService.StorePageResult("key", "thumb", 500L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenReturn(
                        Mono.just(new SessionService.AttachResult(true, attached)),
                        Mono.just(new SessionService.AttachResult(false, attached,
                                SessionService.AttachRejection.PAGE_LIMIT)));
        when(storageService.deleteFromS3(anyString())).thenReturn(Mono.empty());
        mockFilePartHeaders("doc.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.pagesAdded()).isEqualTo(1);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_PDF_TOO_MANY_PAGES);
                    assertThat(body.message()).contains("limite de pages");
                })
                .verifyComplete();

        // La page attachée a émis son événement SSE ; la refusée a été rollback.
        verify(sessionService, times(1)).notifyUpload(eq("tok"), any(UploadEvent.class));
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_PDF_TOO_MANY_PAGES)
                .tag("sizeProfile", PROFILE.name())
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void upload_pdfStopsAtFirstRefusal_withoutStoringTheRemainingPages() throws IOException {
        // Régression prod : PDF Word de 943 pages sur une session S (5 Mo). Les pages
        // refusées après la première continuaient de passer par vignette + PUT S3 +
        // refus + DELETE : plusieurs minutes de requête pour 25 pages gardées, et un
        // 5xx du reverse-proxy côté usager. On s'arrête au premier refus.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 four-page".getBytes();
        var attached = session.withAddedPage(dummyPage(session, 1));

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok")).thenReturn(
                Mono.just(new SessionService.Reservation("page-001", "k1", "t1", 1)),
                Mono.just(new SessionService.Reservation("page-002", "k2", "t2", 2)),
                Mono.just(new SessionService.Reservation("page-003", "k3", "t3", 3)),
                Mono.just(new SessionService.Reservation("page-004", "k4", "t4", 4)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes)).thenReturn(4);
        when(pdfService.splitPdfToPages(pdfBytes)).thenReturn(List.of(
                "p1".getBytes(), "p2".getBytes(), "p3".getBytes(), "p4".getBytes()));
        when(storageService.storePageFromBytes(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.just(new StorageService.StorePageResult("key", "thumb", 500L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenReturn(
                        Mono.just(new SessionService.AttachResult(true, attached)),
                        Mono.just(new SessionService.AttachResult(false, attached,
                                SessionService.AttachRejection.SIZE_LIMIT)));
        when(storageService.deleteFromS3(anyString())).thenReturn(Mono.empty());
        mockFilePartHeaders("doc.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.pagesAdded()).isEqualTo(1);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_SESSION_SIZE_EXCEEDED);
                })
                .verifyComplete();

        // Page 1 attachée, page 2 refusée puis rollback ; pages 3 et 4 jamais traitées.
        verify(sessionService, times(2)).reservePageId("tok");
        verify(storageService, times(2)).storePageFromBytes(anyString(), anyString(), any(byte[].class), anyString());
        verify(sessionService, times(2)).attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES));
        verify(storageService, times(1)).deleteFromS3(anyString());
    }

    @Test
    void upload_gsSplitTimeout_fallsBackToPdfboxWithoutSecondGsPass() throws IOException {
        // gs a épuisé son budget sur le document entier : le repli ne relance pas une
        // recompression gs du même document, qui expirerait à son tour (+15 s).
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 multi-page".getBytes();

        stubPdfFallbackAfterGsSplitError(session, pdfBytes,
                new java.util.concurrent.TimeoutException("Did not observe any item within 15000ms"));

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();

        verify(ghostscriptService, never()).recompressPdfIfNeeded(any());
        verify(pdfService).splitPdfToPages(pdfBytes);
    }

    @Test
    void upload_gsSplitFailure_fallsBackToRecompressionThenPdfboxSplit() throws IOException {
        // Échec gs autre qu'un timeout (split incomplet) : une recompression du document
        // entier peut encore aboutir, le repli historique est conservé.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 multi-page".getBytes();

        stubPdfFallbackAfterGsSplitError(session, pdfBytes,
                new IOException("split gs incomplet: 1/2 pages produites"));

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();

        verify(ghostscriptService).recompressPdfIfNeeded(pdfBytes);
        verify(pdfService).splitPdfToPages(pdfBytes);
    }

    /** PDF de 2 pages dont le split gs échoue sur {@code gsError} ; le split PDFBox et les attaches passent. */
    private void stubPdfFallbackAfterGsSplitError(UploadSession session, byte[] pdfBytes, Throwable gsError)
            throws IOException {
        var sessionState = new java.util.concurrent.atomic.AtomicReference<>(session);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok")).thenReturn(
                Mono.just(new SessionService.Reservation("page-001", "k1", "t1", 1)),
                Mono.just(new SessionService.Reservation("page-002", "k2", "t2", 2)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes)).thenReturn(2);
        when(ghostscriptService.isAvailable()).thenReturn(true);
        when(ghostscriptService.recompressAndSplitToPages(pdfBytes, 2)).thenReturn(Mono.error(gsError));
        when(pdfService.splitPdfToPages(pdfBytes)).thenReturn(List.of("p1".getBytes(), "p2".getBytes()));
        when(storageService.storePageFromBytes(anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(Mono.just(new StorageService.StorePageResult("key", "thumb", 500L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenAnswer(inv -> {
                    var updated = sessionState.get().withAttachedPage(inv.getArgument(1))
                            .withRenewedExpiry(Duration.ofMinutes(15));
                    sessionState.set(updated);
                    return Mono.just(new SessionService.AttachResult(true, updated));
                });
        mockFilePartHeaders("multi.pdf", MediaType.APPLICATION_PDF);
    }

    @Test
    void upload_invalidSession_returnsBadRequest() {
        when(sessionService.getSessionAsync("expired")).thenReturn(Mono.empty());

        StepVerifier.create(controller.upload("expired", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.message()).contains("expired or invalid");
                })
                .verifyComplete();

        verifyNoInteractions(storageService);
    }

    @Test
    void upload_s3Error_propagates() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.bufferFilePart(filePart))
                .thenReturn(Mono.error(new RuntimeException("S3 down")));
        // Format autorisé : on veut tester la propagation de l'erreur S3 en aval,
        // pas le rejet 415 sur extension inconnue.
        mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .expectErrorMessage("S3 down")
                .verify();

        verify(sessionService, never()).notifyUpload(any(), any());
    }

    @Test
    void upload_propagatesBulkheadFullException_whenSaturated() {
        // Bulkhead à capacité 1, sans attente : un permis pré-acquis suffit à saturer.
        var saturated = newBulkhead(1, Duration.ZERO);
        Assertions.assertTrue(saturated.tryAcquirePermission(),
                "Le permis initial doit être disponible avant saturation");
        try {
            var saturatedController = new UploadController(sessionService, storageService, pdfService,
                    ghostscriptService, antivirusService, limits, uploadFormats, uploadRawBytes,
                    saturated, meterRegistry, thumbnailCache);

            var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
            when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
            // Si le bulkhead rejette correctement, ce stub n'est jamais souscrit.
            when(storageService.bufferFilePart(filePart)).thenReturn(Mono.never());
            // filename() est appelé par le contrôle d'extension en amont du
            // bulkhead — on stub un format autorisé pour atteindre la branche
            // d'admission qu'on veut tester.
            mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

            StepVerifier.create(saturatedController.upload("tok", filePart))
                    .expectError(BulkheadFullException.class)
                    .verify(Duration.ofSeconds(2));

            // Aucune ressource S3/PDF ne doit être consommée si l'admission control rejette.
            verify(sessionService, never()).attachPage(any(), any(), anyInt(), anyLong(), anyBoolean());
            verify(sessionService, never()).notifyUpload(any(), any());
            // Le rejet bulkhead alimente le compteur taggé pour corréler saturation et profil.
            assertThat(meterRegistry.find("televerse.upload.rejections")
                    .tag("errorCode", GlobalExceptionHandler.ERROR_UPLOAD_BUSY)
                    .tag("sizeProfile", PROFILE.name())
                    .counter().count()).isEqualTo(1.0);
        } finally {
            saturated.releasePermission();
        }
    }

    @Test
    void upload_returns415_whenFileFormatNotAllowed() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        // Le contrôle d'extension rejette AVANT toute lecture de headers()
        // ou de bufferFilePart : seul filename() doit être stubbé.
        when(filePart.filename()).thenReturn("evil.exe");

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_INVALID_FILE_FORMAT);
                    assertThat(body.message()).isEqualTo(
                            "evil.exe: Le fichier doit être au format autorisé (pdf, jpg, jpeg, png, tiff)");
                })
                .verifyComplete();

        verifyNoInteractions(storageService);
        verifyNoInteractions(antivirusService);
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_INVALID_FILE_FORMAT)
                .tag("sizeProfile", PROFILE.name())
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void upload_acceptsExtensionCaseInsensitively() throws IOException {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var pageId = session.nextPageId();
        var thumbnailKey = session.nextThumbnailKey();
        var pageInfo = new PageInfo(pageId, pageKey, "PHOTO.JPG", "image/jpeg", 1024L, Instant.now(), thumbnailKey);
        var sessionWithPage = session.withAddedPage(pageInfo).withRenewedExpiry(Duration.ofMinutes(15));

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation(pageId, pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just("img".getBytes()));
        when(pdfService.convertToPdf(any())).thenReturn("pdf".getBytes());
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), any(), eq("PHOTO.JPG")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 1024L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), anyBoolean()))
                .thenReturn(Mono.just(new SessionService.AttachResult(true, sessionWithPage)));
        mockFilePartHeaders("PHOTO.JPG", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }

    @Test
    void upload_pdfContentDeclaredAsImage_routedToPdfBranch() throws IOException {
        // Un vrai PDF envoyé sous un nom .jpg avec Content-Type image/jpeg
        // (en-têtes mobiles souvent faux) doit être routé par magic bytes vers
        // la branche PDF, et ses métadonnées normalisées en application/pdf.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pdfBytes = "%PDF-1.4 vrai-pdf".getBytes();
        var attached = session.withAddedPage(dummyPage(session, 1)).withRenewedExpiry(Duration.ofMinutes(15));

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation("page-001", "k1", "t1", 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        when(pdfService.countPdfPages(pdfBytes)).thenReturn(1);
        when(pdfService.splitPdfToPages(pdfBytes)).thenReturn(List.of(pdfBytes));
        when(storageService.storePageFromBytes(anyString(), anyString(), eq(pdfBytes), eq("scan.jpg")))
                .thenReturn(Mono.just(new StorageService.StorePageResult("k1", "t1", 500L)));
        var pageInfoCaptor = ArgumentCaptor.forClass(PageInfo.class);
        when(sessionService.attachPage(eq("tok"), pageInfoCaptor.capture(), anyInt(), eq(MAX_TOTAL_BYTES)))
                .thenReturn(Mono.just(new SessionService.AttachResult(true, attached)));
        mockFilePartHeaders("scan.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.pagesAdded()).isEqualTo(1);
                })
                .verifyComplete();

        // Le contenu prime sur l'en-tête déclaré : aucune tentative de décodage image.
        verify(pdfService, never()).convertToPdf(any());
        // Métadonnées alignées sur le type détecté, pas sur l'en-tête client.
        assertThat(pageInfoCaptor.getValue().contentType()).isEqualTo("application/pdf");
    }

    @Test
    void upload_imageContentDeclaredAsPdf_routedToImageBranch() throws IOException {
        // Symétrique : une image envoyée sous un nom .pdf avec Content-Type
        // application/pdf part en conversion image→PDF — countPdfPages n'est
        // jamais tenté sur des octets sans signature %PDF-.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var pageId = session.nextPageId();
        var thumbnailKey = session.nextThumbnailKey();
        var attached = session.withAddedPage(dummyPage(session, 1)).withRenewedExpiry(Duration.ofMinutes(15));

        var imageBytes = "jpeg-sans-signature-pdf".getBytes();
        var pdfBytes = "converted-pdf".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation(pageId, pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes)).thenReturn(pdfBytes);
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), eq(pdfBytes), eq("photo.pdf")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 1024L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), anyBoolean()))
                .thenReturn(Mono.just(new SessionService.AttachResult(true, attached)));
        mockFilePartHeaders("photo.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();

        verify(pdfService, never()).countPdfPages(any());
        verify(pdfService).convertToPdf(imageBytes);
    }

    @Test
    void getAllowedFormats_returnsConfiguredList() {
        var response = controller.getAllowedFormats();
        assertThat(response.allowedFormats()).containsExactly("pdf", "jpg", "jpeg", "png", "tiff");
    }

    @Test
    void upload_duplicateImage_rejectedByPreCheck_returns429_beforeAnyWork() {
        // Une page portant l'empreinte SHA-256 des octets uploadés existe déjà :
        // le pré-contrôle anti-spam court-circuite en 429 DUPLICATE_PAGE AVANT le
        // scan AV, la conversion et le stockage S3 (le coût qu'on veut éviter).
        var imageBytes = "octets-identiques".getBytes();
        var existing = new PageInfo("page-001", "k1", "photo.jpg", "image/jpeg",
                1024L, Instant.now(), null).withContentHash(sha256Hex(imageBytes));
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15)).withAddedPage(existing);

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_DUPLICATE_PAGE);
                    assertThat(body.pagesAdded()).isZero();
                    assertThat(body.totalPages()).isEqualTo(1);
                })
                .verifyComplete();

        // Court-circuit total : ni AV, ni réservation, ni stockage, ni attache.
        verifyNoInteractions(antivirusService);
        verify(storageService, never()).storePageFromBytes(any(), any(), any(), any());
        verify(sessionService, never()).reservePageId(any());
        verify(sessionService, never()).attachPage(any(), any(), anyInt(), anyLong(), anyBoolean());
        verify(sessionService, never()).notifyUpload(any(), any());
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_DUPLICATE_PAGE)
                .tag("sizeProfile", PROFILE.name())
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void upload_duplicateImage_rejectedUnderLock_returns429AndRollsBack() throws IOException {
        // Course concurrente : le pré-contrôle voit la session vide (l'upload jumeau
        // n'a pas encore attaché), mais attachPage détecte le doublon sous le verrou
        // (rejectDuplicate=true, chemin image). La page déjà stockée sur S3 doit être
        // rollback et le client recevoir 429 DUPLICATE_PAGE.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var pageKey = session.nextPageKey();
        var thumbnailKey = session.nextThumbnailKey();
        var imageBytes = "an-image".getBytes();
        var pdfBytes = "a-pdf".getBytes();

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reservePageId("tok"))
                .thenReturn(Mono.just(new SessionService.Reservation("page-001", pageKey, thumbnailKey, 1)));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(imageBytes));
        when(pdfService.convertToPdf(imageBytes)).thenReturn(pdfBytes);
        when(storageService.storePageFromBytes(eq(pageKey), eq(thumbnailKey), eq(pdfBytes), eq("photo.jpg")))
                .thenReturn(Mono.just(new StorageService.StorePageResult(pageKey, thumbnailKey, 1024L)));
        when(sessionService.attachPage(eq("tok"), any(PageInfo.class), anyInt(), eq(MAX_TOTAL_BYTES), eq(true)))
                .thenReturn(Mono.just(new SessionService.AttachResult(false, session,
                        SessionService.AttachRejection.DUPLICATE)));
        when(storageService.deleteFromS3(pageKey)).thenReturn(Mono.empty());
        mockFilePartHeaders("photo.jpg", MediaType.IMAGE_JPEG);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_DUPLICATE_PAGE);
                    assertThat(body.pagesAdded()).isZero();
                })
                .verifyComplete();

        // L'objet stocké avant le refus sous verrou doit être nettoyé de S3.
        verify(storageService).deleteFromS3(pageKey);
        verify(thumbnailCache).delete(thumbnailKey);
        verify(storageService, never()).deleteFromS3(thumbnailKey);
        verify(sessionService, never()).notifyUpload(any(), any());
        assertThat(meterRegistry.find("televerse.upload.rejections")
                .tag("errorCode", UploadController.ERROR_DUPLICATE_PAGE)
                .tag("sizeProfile", PROFILE.name())
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void upload_duplicatePdf_rejectedByPreCheck_returns429_beforeParsing() throws IOException {
        // Re-upload d'un PDF entier déjà présent : ses pages partagent l'empreinte
        // du fichier source, donc le pré-contrôle le voit comme doublon et refuse en
        // 429 avant même de compter/découper les pages.
        var pdfBytes = "%PDF-1.4 deja-vu".getBytes();
        var existing = new PageInfo("page-001", "k1", "doc.pdf", "application/pdf",
                2048L, Instant.now(), null).withContentHash(sha256Hex(pdfBytes));
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15)).withAddedPage(existing);

        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.bufferFilePart(filePart)).thenReturn(Mono.just(pdfBytes));
        mockFilePartHeaders("doc.pdf", MediaType.APPLICATION_PDF);

        StepVerifier.create(controller.upload("tok", filePart))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    var body = (UploadController.UploadResponse) response.getBody();
                    Assertions.assertNotNull(body);
                    assertThat(body.errorCode()).isEqualTo(UploadController.ERROR_DUPLICATE_PAGE);
                })
                .verifyComplete();

        verifyNoInteractions(antivirusService);
        verify(pdfService, never()).countPdfPages(any());
        verify(storageService, never()).storePageFromBytes(any(), any(), any(), any());
    }

    // ===== Helpers =====

    private static Bulkhead newBulkhead(int maxConcurrent, Duration maxWait) {
        var registry = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(maxWait)
                .build());
        return registry.bulkhead("test-upload");
    }

    private void mockFilePartHeaders(String filename, MediaType mediaType) {
        when(filePart.filename()).thenReturn(filename);
        var headers = new HttpHeaders();
        if (mediaType != null) {
            headers.setContentType(mediaType);
        }
        when(filePart.headers()).thenReturn(headers);
    }

    private static PageInfo dummyPage(UploadSession session, int index) {
        return new PageInfo("page-%03d".formatted(index), "k" + index, "f.pdf",
                "application/pdf", 500L, Instant.now(), null);
    }

    /** Reproduit l'empreinte du contrôleur pour préparer un doublon dans les tests. */
    private static String sha256Hex(byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

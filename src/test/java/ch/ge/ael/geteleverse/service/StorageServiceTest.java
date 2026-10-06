package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.AwsProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.CredentialsProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.HttpProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.RegionProperties;
import ch.ge.ael.geteleverse.config.AwsProperties.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock S3AsyncClient s3Client;
    @Mock PdfService pdfService;
    @Mock PerspectiveCorrectionService perspectiveCorrectionService;
    @Mock WhiteBalanceService whiteBalanceService;
    @Mock ThumbnailCacheService thumbnailCache;
    @Mock FilePart filePart;
    @Captor ArgumentCaptor<PutObjectRequest> putCaptor;
    @Captor ArgumentCaptor<GetObjectRequest> getCaptor;
    @Captor ArgumentCaptor<DeleteObjectRequest> deleteCaptor;

    private StorageService service;

    private static final String BUCKET = "test-bucket";
    private static final String PAGE_KEY = "user-1/form-1/token-123/page-001.pdf";
    private static final String THUMB_KEY = "user-1/form-1/token-123/page-001-thumb.jpg";
    private static final String DOC_ID = "token-123.pdf";

    @BeforeEach
    void setUp() {
        var props = new AwsProperties(
                new RegionProperties("us-east-1"),
                new CredentialsProperties("key", "secret"),
                new S3Properties(BUCKET, "https://s3.local"),
                new HttpProperties(100, 200, Duration.ofSeconds(60), Duration.ofSeconds(10),
                        Duration.ofSeconds(120), Duration.ofSeconds(60))
        );
        lenient().when(thumbnailCache.put(any(), any())).thenReturn(Mono.empty());
        lenient().when(thumbnailCache.get(any())).thenReturn(Mono.empty());
        lenient().when(thumbnailCache.delete(any())).thenReturn(Mono.empty());

        service = new StorageService(s3Client, props, pdfService, perspectiveCorrectionService,
                whiteBalanceService, thumbnailCache);
    }

    // ===== deleteAllBestEffort =====

    /**
     * Purge d'expiration ou de finalisation : au plus S3_PURGE_CONCURRENCY DELETE en vol
     * quel que soit le nombre de clés, et un échec ne fait ni échouer le lot ni sauter
     * les clés suivantes. Sans borne, flatMap émettait les 60 DELETE d'une session
     * éditée d'un coup, × pods ou × finalisations admises : pool S3 saturé.
     */
    @Test
    void deleteAllBestEffort_boundsConcurrencyAndSurvivesFailures() throws Exception {
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        var pending = new java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<DeleteObjectResponse>>();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            var future = new CompletableFuture<DeleteObjectResponse>();
            pending.add(future);
            return future;
        });
        var keys = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "tok/page-%03d.pdf".formatted(i)).toList();

        var done = service.deleteAllBestEffort(keys).toFuture();
        int completed = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!done.isDone() && System.nanoTime() < deadline) {
            var future = pending.poll();
            if (future == null) {
                Thread.onSpinWait();
                continue;
            }
            inFlight.decrementAndGet();
            if (completed++ % 5 == 0) {
                future.completeExceptionally(S3Exception.builder().message("connection reset").build());
            } else {
                future.complete(DeleteObjectResponse.builder().build());
            }
        }

        assertThat(done).succeedsWithin(Duration.ofSeconds(1));
        assertThat(maxInFlight.get()).isEqualTo(StorageService.S3_PURGE_CONCURRENCY);
        verify(s3Client, times(20)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deleteAllBestEffort_ignoresNullsAndDuplicates() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteObjectResponse.builder().build()));
        var keys = new java.util.ArrayList<String>(List.of("tok/a.pdf", "tok/b.pdf", "tok/a.pdf"));
        keys.add(null);

        StepVerifier.create(service.deleteAllBestEffort(keys)).verifyComplete();

        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }

    // ===== storePage =====

    @Test
    void storePage_convertsToPdfAndUploads() throws IOException {
        var content = "image-bytes".getBytes();
        mockFilePart(content, "photo.jpg");

        var pdfBytes = "fake-pdf".getBytes();
        when(pdfService.convertToPdf(content)).thenReturn(pdfBytes);
        mockS3Put();

        StepVerifier.create(service.storePage(PAGE_KEY, filePart))
                .assertNext(key -> assertThat(key).isEqualTo(PAGE_KEY))
                .verifyComplete();

        verify(s3Client).putObject(putCaptor.capture(), any(AsyncRequestBody.class));
        var request = putCaptor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo(PAGE_KEY);
        assertThat(request.contentType()).isEqualTo("application/pdf");
        assertThat(request.contentLength()).isEqualTo(pdfBytes.length);
    }

    @Test
    void storePage_pdfPassthrough() throws IOException {
        var pdfContent = "pdf-bytes".getBytes();
        mockFilePart(pdfContent, "document.pdf");

        when(pdfService.convertToPdf(pdfContent)).thenReturn(pdfContent);
        mockS3Put();

        StepVerifier.create(service.storePage(PAGE_KEY, filePart))
                .assertNext(key -> assertThat(key).isEqualTo(PAGE_KEY))
                .verifyComplete();

        verify(pdfService).convertToPdf(pdfContent);
    }

    @Test
    void storePage_propagatesConversionError() throws IOException {
        var content = "garbage".getBytes();
        mockFilePartContent(content);

        when(pdfService.convertToPdf(content))
                .thenThrow(new IOException("Unsupported or corrupt image format"));

        StepVerifier.create(service.storePage(PAGE_KEY, filePart))
                .expectErrorMessage("Unsupported or corrupt image format")
                .verify();
    }

    @Test
    void storePage_propagatesS3Error() throws IOException {
        var content = "data".getBytes();
        mockFilePart(content, "f.jpg");

        when(pdfService.convertToPdf(content)).thenReturn("pdf".getBytes());
        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("S3 unavailable")));

        StepVerifier.create(service.storePage(PAGE_KEY, filePart))
                .expectErrorMessage("S3 unavailable")
                .verify();
    }

    @Test
    void storePage_neverReadsDeclaredContentType() throws IOException {
        // Le type source est détecté par magic bytes dans PdfService : l'en-tête
        // Content-Type du FilePart (forgeable) ne doit plus être consulté.
        var content = "data".getBytes();
        mockFilePart(content, "file.bin");

        when(pdfService.convertToPdf(content)).thenReturn("pdf".getBytes());
        mockS3Put();

        StepVerifier.create(service.storePage(PAGE_KEY, filePart))
                .assertNext(key -> assertThat(key).isEqualTo(PAGE_KEY))
                .verifyComplete();

        verify(filePart, never()).headers();
    }

    // ===== storePageWithThumbnail =====

    @Test
    void storePageWithThumbnail_uploadsPdfAndThumbnail() throws IOException {
        var content = "image-bytes".getBytes();
        mockFilePart(content, "photo.jpg");

        var pdfBytes = "fake-pdf".getBytes();
        var thumbBytes = "fake-thumb".getBytes();
        when(pdfService.convertToPdf(content)).thenReturn(pdfBytes);
        when(pdfService.generateThumbnail(content)).thenReturn(thumbBytes);
        mockS3Put();

        StepVerifier.create(service.storePageWithThumbnail(PAGE_KEY, THUMB_KEY, filePart))
                .assertNext(result -> {
                    assertThat(result.pageKey()).isEqualTo(PAGE_KEY);
                    assertThat(result.thumbnailKey()).isEqualTo(THUMB_KEY);
                })
                .verifyComplete();

        // Un SEUL upload S3 : le PDF. La vignette part dans le cache Hazelcast —
        // c'est tout l'objet du changement, une écriture S3 économisée par page.
        verify(s3Client, times(1)).putObject(putCaptor.capture(), any(AsyncRequestBody.class));
        assertThat(putCaptor.getValue().key()).isEqualTo(PAGE_KEY);
        assertThat(putCaptor.getValue().contentType()).isEqualTo("application/pdf");
        verify(thumbnailCache).put(THUMB_KEY, thumbBytes);
    }

    @Test
    void storePageWithThumbnail_keepsThumbnailKey_whenCacheWriteFails() {
        // Hazelcast injoignable : la clé de vignette reste renseignée dans la
        // session, puisque la lecture régénérera la vignette depuis le PDF. La
        // perdre priverait définitivement la page de sa vignette.
        var content = "image-bytes".getBytes();
        mockFilePart(content, "photo.jpg");

        var pdfBytes = "fake-pdf".getBytes();
        var thumbBytes = "fake-thumb".getBytes();
        try {
            when(pdfService.convertToPdf(content)).thenReturn(pdfBytes);
            when(pdfService.generateThumbnail(content)).thenReturn(thumbBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mockS3Put();
        // put() absorbe déjà ses propres erreurs et rend Mono.empty() — on rejoue
        // ce contrat ici plutôt que de laisser remonter une exception.
        when(thumbnailCache.put(THUMB_KEY, thumbBytes)).thenReturn(Mono.empty());

        StepVerifier.create(service.storePageWithThumbnail(PAGE_KEY, THUMB_KEY, filePart))
                .assertNext(result -> {
                    assertThat(result.pageKey()).isEqualTo(PAGE_KEY);
                    assertThat(result.thumbnailKey()).isEqualTo(THUMB_KEY);
                })
                .verifyComplete();
    }

    // ===== regenerateThumbnail =====

    @Test
    void regenerateThumbnail_rendersFromThePagePdfAndRepopulatesTheCache() throws IOException {
        // Défaut de cache (TTL, éviction, cluster injoignable) : on relit le PDF
        // de la page sur S3, on re-rend la vignette, et on la remet en cache pour
        // ne payer ce coût qu'une fois. La lecture du cache, elle, est faite par le
        // contrôleur, hors admission.
        var pdfBytes = "fake-pdf".getBytes();
        var regenerated = "regen-thumb".getBytes();
        mockS3GetReturns(pdfBytes);
        when(pdfService.generateThumbnail(pdfBytes, 120)).thenReturn(regenerated);

        StepVerifier.create(service.regenerateThumbnail(PAGE_KEY, THUMB_KEY, 120))
                .assertNext(bytes -> assertThat(bytes).isEqualTo(regenerated))
                .verifyComplete();

        verify(thumbnailCache).put(THUMB_KEY, regenerated);
        verify(thumbnailCache, never()).get(any());
    }

    @Test
    void storePageWithThumbnail_continuesWhenThumbnailFails() throws IOException {
        var content = "image-bytes".getBytes();
        mockFilePart(content, "photo.jpg");

        var pdfBytes = "fake-pdf".getBytes();
        when(pdfService.convertToPdf(content)).thenReturn(pdfBytes);
        when(pdfService.generateThumbnail(content))
                .thenThrow(new IOException("Échec vignette"));
        mockS3Put();

        StepVerifier.create(service.storePageWithThumbnail(PAGE_KEY, THUMB_KEY, filePart))
                .assertNext(result -> {
                    assertThat(result.pageKey()).isEqualTo(PAGE_KEY);
                    assertThat(result.thumbnailKey()).isNull();
                })
                .verifyComplete();

        // Seul l'upload PDF doit avoir lieu
        verify(s3Client, times(1)).putObject(putCaptor.capture(), any(AsyncRequestBody.class));
        assertThat(putCaptor.getValue().key()).isEqualTo(PAGE_KEY);
    }

    // ===== fetchFromS3 =====

    @Test
    void fetchFromS3_returnsBytes() {
        var content = "pdf-content".getBytes();
        mockS3GetReturns(content);

        StepVerifier.create(service.fetchFromS3(PAGE_KEY))
                .assertNext(bytes -> assertThat(bytes).isEqualTo(content))
                .verifyComplete();
    }

    // ===== deleteFromS3 =====

    @Test
    void deleteFromS3_deletesObject() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteObjectResponse.builder().build()));

        StepVerifier.create(service.deleteFromS3(PAGE_KEY))
                .verifyComplete();

        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(deleteCaptor.getValue().key()).isEqualTo(PAGE_KEY);
    }

    // ===== mergeAndFinalize =====

    @Test
    void mergeAndFinalize_fetchesMergesAndUploads_withoutPurgingThePages() throws IOException {
        var pageKey1 = "u/f/t/page-001.pdf";
        var pageKey2 = "u/f/t/page-002.pdf";
        var merged = "merged".getBytes();

        // Stub fetch pour chaque page
        mockS3GetReturnsForKey(pageKey1, "pdf1".getBytes());
        mockS3GetReturnsForKey(pageKey2, "pdf2".getBytes());

        // Les byte[] récupérées de S3 ne sont pas les mêmes instances → utiliser anyList()
        when(pdfService.mergeMultiplePdfs(anyList())).thenReturn(merged);
        mockS3Put();

        StepVerifier.create(service.mergeAndFinalize(List.of(pageKey1, pageKey2), DOC_ID))
                .assertNext(outcome -> {
                    assertThat(outcome.documentId()).isEqualTo(DOC_ID);
                    assertThat(outcome.bytes()).isEqualTo(merged.length);
                })
                .verifyComplete();

        // Vérifie l'upload du PDF fusionné
        verify(s3Client).putObject(putCaptor.capture(), any(AsyncRequestBody.class));
        assertThat(putCaptor.getValue().key()).isEqualTo(DOC_ID);

        // Les pages restent : leur purge suit la bascule de la session en FINALIZED, sous
        // verrou. Purgées ici, une finalisation qui échouait ensuite laissait une session
        // listant des pages disparues.
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    // ===== Helpers =====

    private void mockFilePart(byte[] content, String filename) {
        mockFilePartContent(content);
        when(filePart.filename()).thenReturn(filename);
    }

    private void mockFilePartContent(byte[] content) {
        var dataBuffer = new DefaultDataBufferFactory().wrap(content);
        when(filePart.content()).thenReturn(Flux.just(dataBuffer));
    }

    @SuppressWarnings("unchecked")
    private void mockS3GetReturns(byte[] content) {
        var responseBytes = ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), content);
        when(s3Client.getObject(any(GetObjectRequest.class), any(AsyncResponseTransformer.class)))
                .thenReturn(CompletableFuture.completedFuture(responseBytes));
    }

    @SuppressWarnings("unchecked")
    private void mockS3GetReturnsForKey(String key, byte[] content) {
        var responseBytes = ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), content);
        when(s3Client.getObject(
                argThat((GetObjectRequest r) -> r != null && key.equals(r.key())),
                any(AsyncResponseTransformer.class)))
                .thenReturn(CompletableFuture.completedFuture(responseBytes));
    }

    private void mockS3Put() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));
    }
}

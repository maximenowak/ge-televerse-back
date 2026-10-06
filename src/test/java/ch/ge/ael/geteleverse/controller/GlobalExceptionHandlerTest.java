package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.config.UploadAdmissionProperties;
import ch.ge.ael.geteleverse.model.OriginatorBinding;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock ServerWebExchange exchange;
    @Mock ServerHttpRequest request;

    private GlobalExceptionHandler handler;
    private UploadAdmissionProperties admissionProperties;

    @BeforeEach
    void setUp() {
        admissionProperties = new UploadAdmissionProperties(8, Duration.ofMillis(200), Duration.ofSeconds(5));
        handler = new GlobalExceptionHandler(admissionProperties, new SimpleMeterRegistry());
        when(exchange.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        when(request.getPath()).thenReturn(RequestPath.parse("/api/upload/tok-123", ""));
    }

    @Test
    void handleBadRequest_returns400_withMessage() {
        var ex = new IllegalArgumentException("Invalid document type");

        var response = handler.handleBadRequest(ex, exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("BAD_REQUEST");
        assertThat(body.message()).isEqualTo("Invalid document type");
    }

    @Test
    void handleGeneric_returns500_withGenericMessage() {
        var ex = new RuntimeException("S3 connection refused");

        var response = handler.handleGeneric(ex, exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
        assertThat(body.message()).doesNotContain("S3");
    }

    @Test
    void handleGeneric_handlesNullPointerException() {
        var response = handler.handleGeneric(new NullPointerException(), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void handleGeneric_handlesExceptionWithNullMessage() {
        var response = handler.handleGeneric(new RuntimeException((String) null), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void handlePageNotEditable_returns422_withAMessageForTheUser() {
        var response = handler.handlePageNotEditable(new PageNotEditableException(), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_PAGE_NOT_EDITABLE);
        assertThat(body.message()).contains("tourner");
    }

    @Test
    void handleCorruptFile_returns422_withCorruptFileCode() {
        var ex = new CorruptFileException("broken.pdf",
                new java.io.IOException("Missing root object specification in trailer."));

        var response = handler.handleCorruptFile(ex, exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_CORRUPT_FILE);
        assertThat(body.message()).isEqualTo("Le fichier est illisible ou endommagé.");
        // La cause technique PDFBox et le nom de fichier ne fuitent pas au client.
        assertThat(body.message()).doesNotContain("trailer");
        assertThat(body.message()).doesNotContain("broken.pdf");
    }

    @Test
    void handleResponseStatus_propagatesNotFound_notFiveHundred() {
        // Chemin inconnu (ex. /api/actuator/* déplacé sur le port de gestion) :
        // le framework lève une NoResourceFoundException (sous-type de
        // ResponseStatusException, 404). Doit ressortir en 404, pas en 500.
        var ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

        var response = handler.handleResponseStatus(ex, exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo("NOT_FOUND");
    }

    @Test
    void handleResponseStatus_propagatesMethodNotAllowed() {
        var ex = new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED);

        var response = handler.handleResponseStatus(ex, exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().error()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void handleBulkheadFull_returns429_withRetryAfterAndBusyCode() {
        var bulkhead = BulkheadRegistry.of(BulkheadConfig.custom().build()).bulkhead("upload");
        var ex = BulkheadFullException.createBulkheadFullException(bulkhead);

        var response = handler.handleBulkheadFull(ex, exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // retry-after configuré à 5s dans setUp — doit apparaître en secondes dans le header.
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_UPLOAD_BUSY);
        assertThat(body.message()).contains("5 s");
    }

    @Test
    void handleEditBusy_returns429_withRetryAfterAndEditBusyCode() {
        // Le Retry-After est porté par l'exception (calculé au point de rejet),
        // pas lu depuis UploadAdmissionProperties comme pour le bulkhead upload.
        var response = handler.handleEditBusy(new EditBusyException(3), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_EDIT_BUSY);
        assertThat(body.message()).contains("3 s");
    }

    @Test
    void handleMergeBusy_returns429_withRetryAfterAndMergeBusyCode() {
        var response = handler.handleMergeBusy(new MergeBusyException(4), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("4");
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_MERGE_BUSY);
        assertThat(body.message()).contains("4 s");
    }

    @Test
    void handleFinalizeBusy_returns429_withRetryAfterAndFinalizeBusyCode() {
        var response = handler.handleFinalizeBusy(new FinalizeBusyException(6), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("6");
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_FINALIZE_BUSY);
        assertThat(body.message()).contains("6 s");
    }

    @Test
    void handleStorageUnavailable_returns503_withRetryAfterAndStorageCode() {
        // Retry-After porté par l'exception (calculé depuis app.admission.merge.retry-after
        // au point de rejet), pas lu depuis UploadAdmissionProperties.
        var ex = new StorageUnavailableException("preview", 4,
                new java.io.IOException("Acquire operation took longer than the configured maximum time"));

        var response = handler.handleStorageUnavailable(ex, exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("4");
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_STORAGE_UNAVAILABLE);
        assertThat(body.message()).contains("4 s");
        // La cause technique (pool S3 saturé) ne fuit pas au client.
        assertThat(body.message()).doesNotContain("Acquire operation");
    }

    @Test
    void handleOriginatorBusy_returns409_withExpiresAt() {
        var registry = new SimpleMeterRegistry();
        var customHandler = new GlobalExceptionHandler(admissionProperties, registry);
        var expiresAt = Instant.parse("2026-05-21T14:32:00Z");
        var binding = new OriginatorBinding("blocking-token", Instant.now(), expiresAt);

        var response = customHandler.handleOriginatorBusy(
                new OriginatorBusyException(binding), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_ORIGINATOR_BUSY);
        // Token JAMAIS exposé : un NAT partagé ne doit pas pouvoir lire le token
        // d'une session voisine via cette réponse.
        assertThat(body.message()).doesNotContain("blocking-token");
        assertThat(body.expiresAt()).isEqualTo(expiresAt.toEpochMilli());

        var counter = registry.find("televerse.originator.rejections")
                .tag("errorCode", GlobalExceptionHandler.ERROR_ORIGINATOR_BUSY)
                .counter();
        Assertions.assertNotNull(counter);
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void handleOriginatorResolution_returns400_withMissingCode() {
        var registry = new SimpleMeterRegistry();
        var customHandler = new GlobalExceptionHandler(admissionProperties, registry);

        var response = customHandler.handleOriginatorResolution(
                new OriginatorResolutionException(OriginatorResolutionException.MISSING,
                        "Missing X-Forwarded-For header"), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().error()).isEqualTo(OriginatorResolutionException.MISSING);
    }

    @Test
    void handleSessionRateLimited_returns429_withRetryAfterAndCode_normalMode() {
        var registry = new SimpleMeterRegistry();
        var customHandler = new GlobalExceptionHandler(admissionProperties, registry);

        var response = customHandler.handleSessionRateLimited(
                new SessionRateLimitedException(7L, false), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7");
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.error()).isEqualTo(GlobalExceptionHandler.ERROR_SESSION_RATE_LIMITED);
        assertThat(body.message()).contains("7 s");
    }

    @Test
    void handleSessionRateLimited_forceMode_alsoReturns429() {
        var registry = new SimpleMeterRegistry();
        var customHandler = new GlobalExceptionHandler(admissionProperties, registry);

        var response = customHandler.handleSessionRateLimited(
                new SessionRateLimitedException(30L, true), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().error()).isEqualTo(GlobalExceptionHandler.ERROR_SESSION_RATE_LIMITED);
    }

    @Test
    void handleBulkheadFull_subSecondRetryAfter_clampedToOneSecond() {
        var subSecondProps = new UploadAdmissionProperties(8, Duration.ofMillis(200), Duration.ofMillis(500));
        var subSecondHandler = new GlobalExceptionHandler(subSecondProps, new SimpleMeterRegistry());
        var bulkhead = BulkheadRegistry.of(BulkheadConfig.custom().build()).bulkhead("upload");

        var response = subSecondHandler.handleBulkheadFull(BulkheadFullException.createBulkheadFullException(bulkhead), exchange);

        // RFC 9110 : Retry-After s'exprime en secondes entières — on plancher à 1.
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }
}
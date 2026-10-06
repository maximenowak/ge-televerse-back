package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.config.EditAdmissionProperties;
import ch.ge.ael.geteleverse.config.FinalizeAdmissionProperties;
import ch.ge.ael.geteleverse.config.GhostscriptProperties;
import ch.ge.ael.geteleverse.config.LimitsProperties;
import ch.ge.ael.geteleverse.config.MergeAdmissionProperties;
import ch.ge.ael.geteleverse.config.OriginatorProperties;
import ch.ge.ael.geteleverse.config.SessionCreateProperties;
import ch.ge.ael.geteleverse.config.SessionRateLimitProperties;
import ch.ge.ael.geteleverse.model.PageInfo;
import ch.ge.ael.geteleverse.model.SessionStatus;
import ch.ge.ael.geteleverse.model.SizeProfile;
import ch.ge.ael.geteleverse.model.UploadEvent;
import ch.ge.ael.geteleverse.model.UploadSession;
import ch.ge.ael.geteleverse.service.EditTrace;
import ch.ge.ael.geteleverse.service.IpSessionRateLimiter;
import ch.ge.ael.geteleverse.service.OriginatorResolver;
import ch.ge.ael.geteleverse.service.SessionLockTimeoutException;
import ch.ge.ael.geteleverse.service.SessionService;
import ch.ge.ael.geteleverse.service.StorageService;
import ch.ge.ael.geteleverse.service.ThumbnailCacheService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock SessionService sessionService;
    @Mock StorageService storageService;
    @Mock ThumbnailCacheService thumbnailCache;
    @Mock OriginatorResolver originatorResolver;
    @Captor ArgumentCaptor<UploadEvent> eventCaptor;

    private SessionController controller;
    private LimitsProperties limits;
    private DistributionSummary sessionPagesCount;
    private SimpleMeterRegistry meterRegistry;
    private GhostscriptProperties ghostscript;
    private OriginatorProperties originatorEnabled;
    private OriginatorProperties originatorDisabled;
    private SessionRateLimitProperties rateLimitEnabled;
    private SessionRateLimitProperties rateLimitDisabled;
    private IpSessionRateLimiter rateLimiterPermissive;
    private SessionCreateProperties sessionCreateProps;
    private Bulkhead editBulkhead;
    private EditAdmissionProperties editAdmission;
    private Bulkhead mergeBulkhead;
    private MergeAdmissionProperties mergeAdmission;
    private Bulkhead finalizeBulkhead;
    private FinalizeAdmissionProperties finalizeAdmission;

    private static final SizeProfile PROFILE = SizeProfile.S;

    private static final String ORIGIN = "https://ge-televerse.app.ge.ch";

    private static ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/sessions")
                .header("Origin", ORIGIN)
                .header("Host", "ge-televerse.app.ge.ch")
                .header("X-Forwarded-For", "10.0.0.1"));
    }

    @BeforeEach
    void setUp() {
        // Le cache de vignettes est best-effort partout : ses appels retournent
        // Mono.empty() par défaut pour que les chaînes réactives se composent
        // sans NPE dans les tests qui ne s'y intéressent pas. Les tests qui
        // vérifient la purge surchargent/inspectent ces stubs explicitement.
        lenient().when(thumbnailCache.put(any(), any())).thenReturn(Mono.empty());
        lenient().when(thumbnailCache.get(any())).thenReturn(Mono.empty());
        lenient().when(thumbnailCache.delete(any())).thenReturn(Mono.empty());
        lenient().when(thumbnailCache.deleteAll(any())).thenReturn(Mono.empty());
        lenient().when(storageService.deleteAllBestEffort(any())).thenReturn(Mono.empty());

        var profiles = new java.util.EnumMap<SizeProfile, LimitsProperties.Profile>(SizeProfile.class);
        profiles.put(SizeProfile.S, new LimitsProperties.Profile(DataSize.ofMegabytes(25)));
        profiles.put(SizeProfile.M, new LimitsProperties.Profile(DataSize.ofMegabytes(100)));
        profiles.put(SizeProfile.L, new LimitsProperties.Profile(DataSize.ofMegabytes(250)));
        profiles.put(SizeProfile.XL, new LimitsProperties.Profile(DataSize.ofMegabytes(500)));
        limits = new LimitsProperties(100, DataSize.ofMegabytes(100), profiles);
        meterRegistry = new SimpleMeterRegistry();
        sessionPagesCount = DistributionSummary.builder("test.session.pages").register(meterRegistry);
        ghostscript = new GhostscriptProperties(null, null, 0, 0, null, null, 0);
        originatorEnabled = new OriginatorProperties(true);
        originatorDisabled = new OriginatorProperties(false);
        // Rate-limit désactivé par défaut dans la majorité des tests pour ne
        // pas dépendre d'une IP résolue. Le rate-limit est testé séparément
        // dans createSession_rateLimited_*.
        rateLimitEnabled = new SessionRateLimitProperties(true, 10, 10, 5, java.time.Duration.ofMinutes(10));
        rateLimitDisabled = new SessionRateLimitProperties(false, 10, 10, 5, java.time.Duration.ofMinutes(10));
        rateLimiterPermissive = new IpSessionRateLimiter(rateLimitDisabled, meterRegistry);
        // Realm cross-origin + whitelist profil. Liste de profils vide → toute
        // origine résout sur S (DEFAULT) : les tests existants qui attendent S
        // restent verts. La dérivation par suffixe est couverte séparément.
        sessionCreateProps = new SessionCreateProperties(
                List.of(".ge.ch", ".etat-ge.ch"), List.of());
        // Bulkhead d'édition volontairement large : les tests d'édition existants
        // ne doivent jamais être rejetés par l'admission control. Le rejet est
        // couvert spécifiquement par editAdmission_rejectsWhenBulkheadFull.
        editAdmission = new EditAdmissionProperties(1000, Duration.ofMillis(200), Duration.ofSeconds(2));
        editBulkhead = Bulkhead.of("edit-test", BulkheadConfig.custom()
                .maxConcurrentCalls(editAdmission.maxConcurrent())
                .maxWaitDuration(editAdmission.maxWait())
                .build());
        // Bulkhead d'assemblage également large : les tests de preview / merge /
        // finalize ne doivent jamais être rejetés, sauf mergeAdmission_rejectsWhenBulkheadFull.
        mergeAdmission = new MergeAdmissionProperties(1000, Duration.ofMillis(200), Duration.ofSeconds(2));
        mergeBulkhead = Bulkhead.of("merge-test", BulkheadConfig.custom()
                .maxConcurrentCalls(mergeAdmission.maxConcurrent())
                .maxWaitDuration(mergeAdmission.maxWait())
                .build());
        // Bulkhead de finalisation également large : seul finalizeAdmission_rejectsWhenBulkheadFull
        // le sature volontairement.
        finalizeAdmission = new FinalizeAdmissionProperties(1000, Duration.ofMillis(200), Duration.ofSeconds(2));
        finalizeBulkhead = Bulkhead.of("finalize-test", BulkheadConfig.custom()
                .maxConcurrentCalls(finalizeAdmission.maxConcurrent())
                .maxWaitDuration(finalizeAdmission.maxWait())
                .build());
        // Par défaut, les tests fonctionnent avec le verrou désactivé : ils ne
        // poussent pas de XFF et veulent valider la logique métier de chaque
        // endpoint sans dépendance sur l'OriginatorResolver. Le verrou est testé
        // séparément dans createSession_originator_*.
        controller = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
    }

    // ===== createSession =====

    @Test
    void createSession_returnsTokenAndUrl() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        // Feature désactivée dans le contrôleur par défaut → null originatorIp.
        when(sessionService.createSessionAsync(PROFILE, null, false)).thenReturn(Mono.just(session));

        var response = controller.createSession(new SessionController.CreateSessionRequest(PROFILE),
                false, exchange()).block();
        Assertions.assertNotNull(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.token()).isEqualTo(session.token());
        assertThat(body.uploadUrl()).isEqualTo(ORIGIN + "/scan/" + session.token());
        assertThat(body.sizeProfile()).isEqualTo(SizeProfile.S);
        // Avec OriginatorProperties.enabled=false, le resolver ne doit pas être appelé.
        verify(originatorResolver, never()).resolve(any());
    }

    @Test
    void createSession_acceptsNullBody() {
        // Corps absent : aucune NPE. Le profil reste dérivé de l'Origin (ici hors
        // whitelist fine → DEFAULT) — le corps n'est jamais consulté pour le profil.
        var session = UploadSession.create(SizeProfile.DEFAULT, Duration.ofMinutes(15));
        when(sessionService.createSessionAsync(SizeProfile.DEFAULT, null, false)).thenReturn(Mono.just(session));

        var response = controller.createSession(null, false, exchange()).block();
        Assertions.assertNotNull(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().sizeProfile()).isEqualTo(SizeProfile.DEFAULT);
    }

    @Test
    void createSession_rejectsCallWithoutOriginHeader() {
        var noOrigin = MockServerWebExchange.from(MockServerHttpRequest.post("/api/sessions"));
        assertThatThrownBy(() ->
                controller.createSession(new SessionController.CreateSessionRequest(PROFILE), false, noOrigin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Origin");
        verifyNoInteractions(sessionService);
    }

    @Test
    void createSession_resolvesAndBindsOriginator_whenEnabled() {
        var enabledController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorEnabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15), "10.0.0.1");
        when(originatorResolver.resolve(any())).thenReturn("10.0.0.1");
        when(sessionService.createSessionAsync(PROFILE, "10.0.0.1", false)).thenReturn(Mono.just(session));

        var response = enabledController.createSession(
                new SessionController.CreateSessionRequest(PROFILE), false, exchange()).block();
        Assertions.assertNotNull(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(originatorResolver).resolve(any());
        verify(sessionService).createSessionAsync(PROFILE, "10.0.0.1", false);
    }

    @Test
    void createSession_propagatesForceFlag_whenEnabled() {
        var enabledController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorEnabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15), "10.0.0.1");
        when(originatorResolver.resolve(any())).thenReturn("10.0.0.1");
        when(sessionService.createSessionAsync(PROFILE, "10.0.0.1", true)).thenReturn(Mono.just(session));

        var response = enabledController.createSession(
                new SessionController.CreateSessionRequest(PROFILE), true, exchange()).block();
        Assertions.assertNotNull(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Compteur force-new incrémenté.
        var forceCounter = meterRegistry.find("televerse.originator.force").counter();
        Assertions.assertNotNull(forceCounter);
        assertThat(forceCounter.count()).isEqualTo(1.0);
    }

    @Test
    void createSession_rateLimited_throwsSessionRateLimitedException() {
        // Bucket à capacité 1, refill très lent : 2e tentative → rejet.
        var tightProps = new SessionRateLimitProperties(true, 1, 1, 1, Duration.ofMinutes(10));
        var tightLimiter = new IpSessionRateLimiter(tightProps, meterRegistry);
        var rateLimitedController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                tightLimiter, tightProps, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(originatorResolver.resolve(any())).thenReturn("10.0.0.42");
        when(sessionService.createSessionAsync(PROFILE, null, false)).thenReturn(Mono.just(session));

        // 1re : consomme le jeton, OK.
        var first = rateLimitedController.createSession(
                new SessionController.CreateSessionRequest(PROFILE), false, exchange()).block();
        Assertions.assertNotNull(first);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2e : bucket vide → exception traduite en 429 par GlobalExceptionHandler.
        assertThatThrownBy(() -> rateLimitedController.createSession(
                new SessionController.CreateSessionRequest(PROFILE), false, exchange()))
                .isInstanceOf(SessionRateLimitedException.class)
                .satisfies(ex -> {
                    var rateEx = (SessionRateLimitedException) ex;
                    assertThat(rateEx.force()).isFalse();
                    assertThat(rateEx.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
                });

        // SessionService n'a été appelé qu'une fois : le rejet précède l'écriture Hazelcast.
        verify(sessionService, times(1)).createSessionAsync(any(), any(), anyBoolean());
        // Compteur taggé mode=normal incrémenté.
        var counter = meterRegistry.find("televerse.sessions.rate_limited")
                .tag("mode", "normal").counter();
        Assertions.assertNotNull(counter);
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void createSession_force_consumesMoreTokens() {
        // capacity=5, forceCost=5 : une force = bucket vide.
        var props = new SessionRateLimitProperties(true, 5, 1, 5, Duration.ofMinutes(10));
        var limiter = new IpSessionRateLimiter(props, meterRegistry);
        var c = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorEnabled,
                limiter, props, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15), "10.0.0.7");
        when(originatorResolver.resolve(any())).thenReturn("10.0.0.7");
        when(sessionService.createSessionAsync(PROFILE, "10.0.0.7", true)).thenReturn(Mono.just(session));

        // 1re force : 5 jetons consommés.
        var first = c.createSession(new SessionController.CreateSessionRequest(PROFILE), true, exchange()).block();
        Assertions.assertNotNull(first);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2e force : bucket vide.
        assertThatThrownBy(() -> c.createSession(
                new SessionController.CreateSessionRequest(PROFILE), true, exchange()))
                .isInstanceOf(SessionRateLimitedException.class)
                .extracting(e -> ((SessionRateLimitedException) e).force())
                .isEqualTo(true);

        // Compteur taggé mode=force.
        var counter = meterRegistry.find("televerse.sessions.rate_limited")
                .tag("mode", "force").counter();
        Assertions.assertNotNull(counter);
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void createSession_derivesProfileFromOrigin_ignoringBodyValue() {
        // SÉCURITÉ : le profil vient de l'Origin (whitelist), jamais du corps.
        // L'Origin (host ge-televerse.app.ge.ch) matche le suffixe .ge.ch → L,
        // alors que le corps réclame XL : la valeur du corps doit être ignorée.
        var props = new SessionCreateProperties(
                List.of(".ge.ch"),
                List.of(new SessionCreateProperties.OriginProfile(".ge.ch", SizeProfile.L)));
        var c = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, props,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
        var session = UploadSession.create(SizeProfile.L, Duration.ofMinutes(15));
        when(sessionService.createSessionAsync(SizeProfile.L, null, false)).thenReturn(Mono.just(session));

        var response = c.createSession(
                new SessionController.CreateSessionRequest(SizeProfile.XL), false, exchange()).block();
        Assertions.assertNotNull(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().sizeProfile()).isEqualTo(SizeProfile.L);
        verify(sessionService).createSessionAsync(SizeProfile.L, null, false);
        verify(sessionService, never()).createSessionAsync(eq(SizeProfile.XL), any(), anyBoolean());
    }

    @Test
    void createSession_originOutsideWhitelist_defaultsToS() {
        // Origine hors whitelist (et hors realm fin) → S, quel que soit le corps.
        var props = new SessionCreateProperties(
                List.of(".ge.ch"),
                List.of(new SessionCreateProperties.OriginProfile(".ge.ch", SizeProfile.L)));
        var c = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, props,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
        var foreign = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/sessions")
                .header("Origin", "https://random.example.com")
                .header("Host", "televerser.app.ge.ch"));
        var session = UploadSession.create(SizeProfile.DEFAULT, Duration.ofMinutes(15));
        when(sessionService.createSessionAsync(SizeProfile.DEFAULT, null, false)).thenReturn(Mono.just(session));

        var response = c.createSession(
                new SessionController.CreateSessionRequest(SizeProfile.XL), false, foreign).block();
        Assertions.assertNotNull(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().sizeProfile()).isEqualTo(SizeProfile.DEFAULT);
    }

    // ===== deleteSession =====

    @Test
    void deleteSession_returns204_whenSessionExists() {
        when(sessionService.deleteSession("tok")).thenReturn(reactor.core.publisher.Mono.just(true));

        StepVerifier.create(controller.deleteSession("tok"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT))
                .verifyComplete();
    }

    @Test
    void deleteSession_returns204_evenWhenSessionAlreadyGone() {
        // Idempotence : navigator.sendBeacon peut être réémis par le navigateur.
        when(sessionService.deleteSession("missing")).thenReturn(reactor.core.publisher.Mono.just(false));

        StepVerifier.create(controller.deleteSession("missing"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT))
                .verifyComplete();
    }

    // ===== getSession =====

    @Test
    void getSession_returnsOk_whenFound() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync(session.token())).thenReturn(Mono.just(session));

        StepVerifier.create(controller.getSession(session.token()))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().session()).isEqualTo(session);
                    assertThat(response.getBody().sizeProfile()).isEqualTo(SizeProfile.S);
                    assertThat(response.getBody().maxTotalBytes()).isEqualTo(25L * 1024 * 1024);
                    assertThat(response.getBody().maxPages()).isEqualTo(100);
                })
                .verifyComplete();
    }

    @Test
    void getSession_returns404_whenNotFound() {
        when(sessionService.getSessionAsync("missing")).thenReturn(Mono.empty());

        StepVerifier.create(controller.getSession("missing"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    // ===== finalize =====

    /** beginFinalize simulé : fige la session en FINALIZING, comme sous verrou. */
    private UploadSession startsFinalizing(UploadSession session) {
        var frozen = session.withStatus(SessionStatus.FINALIZING, Instant.now().plusSeconds(150));
        when(sessionService.beginFinalize(eq(session.token()), any()))
                .thenReturn(Mono.just(new SessionService.FinalizeStart(SessionService.FinalizeStart.State.STARTED, frozen)));
        return frozen;
    }

    private void completesFinalize(UploadSession session) {
        when(sessionService.completeFinalize(session.token()))
                .thenReturn(Mono.just(session.withStatus(SessionStatus.FINALIZED, null).withPages(List.of())));
    }

    @Test
    void finalize_mergesAndSendsSessionCompleteEvent() {
        var page1 = new PageInfo("page-001", "u/f/t/page-001.pdf", "f1.jpg", "image/jpeg", 100, Instant.now(), "u/f/t/page-001-thumb.jpg");
        var page2 = new PageInfo("page-002", "u/f/t/page-002.pdf", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        startsFinalizing(session);
        completesFinalize(session);
        when(storageService.mergeAndFinalize(
                List.of("u/f/t/page-001.pdf", "u/f/t/page-002.pdf"), "tok.pdf"))
                .thenReturn(Mono.just(new StorageService.FinalizeOutcome("tok.pdf", 1024)));

        StepVerifier.create(controller.complete("tok"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().documentId()).isEqualTo("tok.pdf");
                    assertThat(response.getBody().message()).isEqualTo("Session finalized");
                })
                .verifyComplete();

        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        var event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("session-complete");
        assertThat(event.totalPages()).isEqualTo(2);
        verify(sessionService).releaseOriginator(any());
        verify(sessionService, never()).abortFinalize(any());
    }

    /**
     * Les pages ne sont purgées qu'une fois la session passée en FINALIZED : un DELETE
     * coupé ne fait plus échouer une finalisation dont le composite est écrit, et un
     * rejeu ne tombe plus sur des pages déjà supprimées. Toutes les clés partent — PDF
     * vivants et versions d'undo (S3, en un lot borné), vignettes (cache).
     */
    @Test
    void finalize_purgesPagesAndThumbnailsOnlyAfterTheSessionIsFinalized() {
        var prev = new PageInfo.PreviousVersion("u/f/t/page-001-prev.pdf",
                "u/f/t/page-001-prev-thumb.jpg", 100L, Instant.now().minusSeconds(60));
        var initial = new PageInfo.PreviousVersion("u/f/t/page-001-init.pdf",
                "u/f/t/page-001-init-thumb.jpg", 90L, Instant.now().minusSeconds(120));
        var page1 = new PageInfo("page-001", "u/f/t/page-001.pdf", "f1.jpg", "image/jpeg",
                150L, Instant.now().minusSeconds(120), Instant.now(),
                "u/f/t/page-001-thumb.jpg", prev, initial);
        var page2 = new PageInfo("page-002", "u/f/t/page-002.pdf", "f2.jpg", "image/jpeg",
                200, Instant.now(), "u/f/t/page-002-thumb.jpg");
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        startsFinalizing(session);
        completesFinalize(session);
        when(storageService.mergeAndFinalize(
                List.of("u/f/t/page-001.pdf", "u/f/t/page-002.pdf"), "tok.pdf"))
                .thenReturn(Mono.just(new StorageService.FinalizeOutcome("tok.pdf", 1024)));

        StepVerifier.create(controller.complete("tok"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();

        var order = inOrder(sessionService, storageService);
        order.verify(sessionService).completeFinalize("tok");
        order.verify(storageService).deleteAllBestEffort(List.of(
                "u/f/t/page-001.pdf", "u/f/t/page-001-prev.pdf", "u/f/t/page-001-init.pdf",
                "u/f/t/page-002.pdf"));
        verify(thumbnailCache).deleteAll(List.of(
                "u/f/t/page-001-thumb.jpg",
                "u/f/t/page-001-prev-thumb.jpg",
                "u/f/t/page-001-init-thumb.jpg",
                "u/f/t/page-002-thumb.jpg"));
        verify(storageService, never()).deleteFromS3(anyString());
    }

    @Test
    void finalize_replayed_returnsTheSameDocumentId_whenAlreadyFinalized() {
        // Client parti avant la réponse, puis rejeu : le composite existe, même documentId,
        // sans nouvelle fusion.
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(), 1, false)
                .withStatus(SessionStatus.FINALIZED, null);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.beginFinalize(eq("tok"), any())).thenReturn(Mono.just(
                new SessionService.FinalizeStart(SessionService.FinalizeStart.State.ALREADY_FINALIZED, session)));

        StepVerifier.create(controller.complete("tok"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().documentId()).isEqualTo("tok.pdf");
                })
                .verifyComplete();

        verify(storageService, never()).mergeAndFinalize(anyList(), anyString());
    }

    @Test
    void finalize_inProgressElsewhere_isARetryableBusy() {
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.beginFinalize(eq("tok"), any())).thenReturn(Mono.just(
                new SessionService.FinalizeStart(SessionService.FinalizeStart.State.IN_PROGRESS, session)));

        StepVerifier.create(controller.complete("tok"))
                .expectError(FinalizeBusyException.class)
                .verify();

        verify(storageService, never()).mergeAndFinalize(anyList(), anyString());
    }

    @Test
    void finalize_reopensTheSession_whenTheMergeFails() {
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        startsFinalizing(session);
        when(sessionService.abortFinalize("tok")).thenReturn(Mono.just(session));
        when(storageService.mergeAndFinalize(anyList(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("S3 connection reset")));

        StepVerifier.create(controller.complete("tok"))
                .expectErrorMessage("S3 connection reset")
                .verify();

        verify(sessionService).abortFinalize("tok");
        verify(sessionService, never()).completeFinalize(any());
        verify(storageService, never()).deleteAllBestEffort(any());
    }

    @Test
    void finalize_returnsBadRequest_whenSessionExpired() {
        when(sessionService.getSessionAsync("expired")).thenReturn(Mono.empty());

        StepVerifier.create(controller.complete("expired"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).contains("expired or invalid");
                })
                .verifyComplete();

        verifyNoMoreInteractions(storageService);
    }

    @Test
    void finalize_returnsBadRequest_andReopens_whenNoPages() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync(session.token())).thenReturn(Mono.just(session));
        startsFinalizing(session);
        when(sessionService.abortFinalize(session.token())).thenReturn(Mono.just(session));

        StepVerifier.create(controller.complete(session.token()))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).contains("Aucune page");
                })
                .verifyComplete();

        verify(sessionService).abortFinalize(session.token());
        verifyNoMoreInteractions(storageService);
    }

    @Test
    void finalize_returns504_andReopens_whenMergeTimesOut() {
        var shortTimeoutController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMillis(50), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);

        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        startsFinalizing(session);
        when(sessionService.abortFinalize("tok")).thenReturn(Mono.just(session));
        when(storageService.mergeAndFinalize(anyList(), anyString()))
                .thenReturn(Mono.never()); // ne termine jamais → timeout

        StepVerifier.create(shortTimeoutController.complete("tok"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(504);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).contains("délai");
                })
                .verifyComplete();

        verify(sessionService).abortFinalize("tok");
    }

    /**
     * Le chemin d'edition n'avait aucune borne de duree : un maillon lent (GET S3,
     * passe OpenCV/ONNX, verrou Hazelcast) laissait la requete en vol pour toujours.
     * app.edit-timeout la coupe et la qualifie en 504 EDIT_TIMEOUT.
     *
     * L'exception porte le releve d'etapes : la trace posee par le controleur
     * doit atteindre les maillons du pipeline (contexte Reactor), et celui qui
     * etait en vol a la coupure doit y figurer comme `cancelled` -- c'est ce que
     * le WARN du 504 imprime.
     */
    @Test
    void deskew_failsWithEditTimeout_whenEditExceedsBudget() {
        var shortEditController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofMillis(50), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);

        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.deskewPage(any(), any(), any()))
                .thenReturn(EditTrace.stage("detect", Mono.never())); // ne termine jamais -> timeout

        StepVerifier.create(shortEditController.deskewPage("tok", "page-001"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(EditTimeoutException.class);
                    var timeout = (EditTimeoutException) e;
                    assertThat(timeout.budget()).isEqualTo(Duration.ofMillis(50));
                    assertThat(timeout.stages()).matches("detect:\\d+ms:cancelled");
                })
                .verify();
    }

    /**
     * Le chemin d'edition n'avait aucune metrique de duree : impossible de dire
     * depuis Grafana si les editions ralentissaient, ni laquelle.
     * televerse.edit.duration est tagge par operation ET par issue.
     */
    @Test
    void deskew_recordsEditDurationTimer_taggedByOpAndOutcome() {
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.deskewPage(any(), any(), any()))
                .thenReturn(Mono.just(new StorageService.DeskewResult(100L, false)));
        when(sessionService.renewSession("tok")).thenReturn(Mono.just(session));

        StepVerifier.create(controller.deskewPage("tok", "page-001"))
                .expectNextCount(1)
                .verifyComplete();

        var timer = meterRegistry.find("televerse.edit.duration")
                .tag("op", "deskew").tag("outcome", "success").timer();
        Assertions.assertNotNull(timer);
        assertThat(timer.count()).isEqualTo(1);
    }

    /**
     * Un pic de outcome="timeout" sans pic de outcome="busy" dit que le pod n'est
     * pas sature mais lent -- distinction que les compteurs de rejet seuls ne
     * pouvaient pas faire.
     */
    @Test
    void deskew_recordsEditDurationTimer_withTimeoutOutcome() {
        var shortEditController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofMillis(50), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);

        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.deskewPage(any(), any(), any())).thenReturn(Mono.never());

        StepVerifier.create(shortEditController.deskewPage("tok", "page-001"))
                .expectError(EditTimeoutException.class)
                .verify();

        var timer = meterRegistry.find("televerse.edit.duration")
                .tag("op", "deskew").tag("outcome", "timeout").timer();
        Assertions.assertNotNull(timer);
        assertThat(timer.count()).isEqualTo(1);
    }

    /**
     * undo / reset sont restes SANS borne quand les quatre editions ont recu la
     * leur : meme section verrouillee Hazelcast, donc meme mode de blocage. C'est de
     * surcroit le geste de rattrapage, tente juste apres l'echec d'une edition -- sur
     * un pod deja degrade. app.undo-timeout le coupe.
     */
    @Test
    void undo_failsWithEditTimeout_whenUndoExceedsBudget() {
        var shortUndoController = shortUndoController();

        var backup = new PageInfo.PreviousVersion("tok/page-001-prev.pdf",
                "tok/page-001-prev-thumb.jpg", 100L, Instant.now().minusSeconds(60));
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now().minusSeconds(120), Instant.now(), "tok/page-001-thumb.jpg", backup);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.commitPageEdit(eq("tok"), eq("page-001"), any(), any(), anyLong()))
                .thenReturn(Mono.never()); // verrou jamais obtenu -> timeout

        StepVerifier.create(shortUndoController.undoPage("tok", "page-001"))
                .expectError(EditTimeoutException.class)
                .verify();

        var timer = meterRegistry.find("televerse.edit.duration")
                .tag("op", "undo").tag("outcome", "timeout").timer();
        Assertions.assertNotNull(timer);
        assertThat(timer.count()).isEqualTo(1);
    }

    /** Meme borne pour reset (cf. undo). */
    @Test
    void reset_failsWithEditTimeout_whenResetExceedsBudget() {
        var shortUndoController = shortUndoController();

        var initial = new PageInfo.PreviousVersion("tok/page-001-init.pdf",
                "tok/page-001-init-thumb.jpg", 90L, Instant.now().minusSeconds(300));
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now().minusSeconds(120), Instant.now(), "tok/page-001-thumb.jpg",
                null, initial);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.commitPageEdit(eq("tok"), eq("page-001"), any(), any(), anyLong()))
                .thenReturn(Mono.never()); // verrou jamais obtenu -> timeout

        StepVerifier.create(shortUndoController.resetPage("tok", "page-001"))
                .expectError(EditTimeoutException.class)
                .verify();

        var timer = meterRegistry.find("televerse.edit.duration")
                .tag("op", "reset").tag("outcome", "timeout").timer();
        Assertions.assertNotNull(timer);
        assertThat(timer.count()).isEqualTo(1);
    }

    /**
     * La borne enveloppe le chargement de session, contrairement aux editions ou
     * il reste hors du bulkhead : sans permis a proteger, rien ne justifie de
     * laisser hors borne l'appel Hazelcast qui a precisement ouvert l'incident.
     */
    @Test
    void undo_failsWithEditTimeout_whenSessionLoadHangs() {
        var shortUndoController = shortUndoController();
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.never());

        StepVerifier.create(shortUndoController.undoPage("tok", "page-001"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(EditTimeoutException.class);
                    // Le chargement de session est SOUS la borne ici : il figure
                    // dans le releve, et c'est lui qui a mange le budget.
                    assertThat(((EditTimeoutException) e).stages()).matches("session:\\d+ms:cancelled");
                })
                .verify();
    }

    /**
     * undo / reset ne passent PAS par le bulkhead `edit` : les y faire concourir
     * les ferait rejeter a cause de deskews en cours -- sans raison de ressource,
     * un changement de pointeur ne pesant pas comme une passe OpenCV -- et
     * retirerait autant de capacite aux editions. Bulkhead sature : une edition
     * est rejetee, une annulation passe.
     */
    @Test
    void undo_isNotSubjectToEditBulkhead_whenBulkheadIsSaturated() {
        var saturated = Bulkhead.of("edit-full", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
        Assertions.assertTrue(saturated.tryAcquirePermission(), "permis unique pris par le test");

        var saturatedController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                saturated, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);

        var backup = new PageInfo.PreviousVersion("tok/page-001-prev.pdf",
                "tok/page-001-prev-thumb.jpg", 100L, Instant.now().minusSeconds(60));
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now().minusSeconds(120), Instant.now(), "tok/page-001-thumb.jpg", backup);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        commitAppliesEditTo(session);

        // L'edition, elle, est bien refusee par ce meme bulkhead sature.
        when(storageService.deskewPage(any(), any(), any()))
                .thenReturn(Mono.just(new StorageService.DeskewResult(100L, true)));
        StepVerifier.create(saturatedController.deskewPage("tok", "page-001"))
                .expectError(EditBusyException.class)
                .verify();

        StepVerifier.create(saturatedController.undoPage("tok", "page-001"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }

    /** Controleur dont SEULE la borne d'annulation est courte (edit reste a 20 s). */
    private SessionController shortUndoController() {
        return new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofMillis(50), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
    }

    /**
     * commitPageEdit simulé comme le vrai : applique l'édition à la page de la session
     * et rend le commit, mémorisé dans la référence renvoyée.
     */
    private AtomicReference<SessionService.PageCommit> commitAppliesEditTo(UploadSession session) {
        var last = new AtomicReference<SessionService.PageCommit>();
        when(sessionService.commitPageEdit(eq(session.token()), anyString(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    String pageId = inv.getArgument(1);
                    UnaryOperator<PageInfo> edit = inv.getArgument(3);
                    var before = session.findPage(pageId);
                    var after = edit.apply(before);
                    var updated = session.withReplacedPage(after).withRenewedExpiry(Duration.ofMinutes(15));
                    var commit = new SessionService.PageCommit(updated, before, after);
                    last.set(commit);
                    return Mono.just(commit);
                });
        return last;
    }

    // ===== getThumbnail =====

    @Test
    void getThumbnail_returnsJpegBytes() {
        var thumbKey = "u/f/t/page-001-thumb.jpg";
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), thumbKey);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var thumbBytes = "fake-jpeg".getBytes();
        when(thumbnailCache.get(thumbKey)).thenReturn(Mono.just(thumbBytes));

        StepVerifier.create(controller.getThumbnail("tok", "page-001", null, null))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getHeaders().getContentType()).hasToString("image/jpeg");
                    assertThat(response.getHeaders().getCacheControl()).contains("max-age=300");
                    assertThat(response.getBody()).isEqualTo(thumbBytes);
                })
                .verifyComplete();

        verify(storageService, never()).regenerateThumbnail(any(), any(), anyInt());
    }

    /** Contrôleur dont le bulkhead merge n'a plus aucun permis. */
    private SessionController controllerWithSaturatedMergeBulkhead() {
        var fullBulkhead = Bulkhead.of("merge-full", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
        fullBulkhead.acquirePermission();
        return new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                fullBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);
    }

    @Test
    void getThumbnail_cacheHit_needsNoAdmissionPermit() {
        var thumbKey = "u/f/t/page-001-thumb.jpg";
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), thumbKey);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(thumbnailCache.get(thumbKey)).thenReturn(Mono.just("fake-jpeg".getBytes()));

        StepVerifier.create(controllerWithSaturatedMergeBulkhead().getThumbnail("tok", "page-001", null, null))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }

    /**
     * Sur défaut de cache, la régénération (GET S3 du PDF complet + rendu PDFBox)
     * passe par le bulkhead merge : une perte du cache sous charge ne se transforme
     * plus en N rendus simultanés par affichage de liste.
     */
    @Test
    void getThumbnail_cacheMiss_regenerationIsRejected_whenMergeBulkheadIsFull() {
        var thumbKey = "u/f/t/page-001-thumb.jpg";
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), thumbKey);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        // Mono paresseux : l'assembler ne coûte rien, seule sa souscription lance le
        // GET S3 et le rendu — c'est elle que le bulkhead doit empêcher.
        var regenerationStarted = new java.util.concurrent.atomic.AtomicBoolean();
        when(storageService.regenerateThumbnail("u/f/t/page-001.pdf", thumbKey, 120))
                .thenReturn(Mono.fromCallable(() -> {
                    regenerationStarted.set(true);
                    return "regen".getBytes();
                }));

        StepVerifier.create(controllerWithSaturatedMergeBulkhead().getThumbnail("tok", "page-001", null, null))
                .expectError(MergeBusyException.class)
                .verify();

        assertThat(regenerationStarted).as("ni GET S3 ni rendu").isFalse();
    }

    @Test
    void getThumbnail_mapsAPageRenderFailureToCorruptFile() {
        // Rendu PDFBox en échec : défaut permanent du contenu (422 non rejouable), pas
        // une panne de stockage 503 que le client réessaierait indéfiniment.
        var thumbKey = "u/f/t/page-001-thumb.jpg";
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), thumbKey);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.regenerateThumbnail("u/f/t/page-001.pdf", thumbKey, 120))
                .thenReturn(Mono.error(new java.io.IOException("Missing root object specification in trailer.")));

        StepVerifier.create(controller.getThumbnail("tok", "page-001", null, null))
                .expectError(CorruptFileException.class)
                .verify();

        assertThat(previewErrors("thumbnail", "corrupt")).isEqualTo(1.0);
        assertThat(previewErrors("thumbnail", "transient")).isZero();
    }

    @Test
    void getThumbnail_returns404_whenSessionNotFound() {
        when(sessionService.getSessionAsync("missing")).thenReturn(Mono.empty());

        StepVerifier.create(controller.getThumbnail("missing", "page-001", null, null))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void getThumbnail_returns404_whenPageNotFound() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));

        StepVerifier.create(controller.getThumbnail("tok", "page-999", null, null))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void getThumbnail_regenerates_whenThePageHasNoThumbnailKey() {
        // Génération initiale en échec (clé nulle) : la vignette se régénère depuis le
        // PDF de la page, au lieu d'un 404 définitif.
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.regenerateThumbnail("u/f/t/page-001.pdf", null, 120))
                .thenReturn(Mono.just("regen".getBytes()));

        StepVerifier.create(controller.getThumbnail("tok", "page-001", null, null))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }

    @Test
    void getThumbnail_returns404_whenThumbnailObjectMissing() {
        // Objet réellement absent du store (NoSuchKey) → 404 légitime.
        var thumbKey = "u/f/t/page-001-thumb.jpg";
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), thumbKey);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.regenerateThumbnail("u/f/t/page-001.pdf", thumbKey, 120))
                .thenReturn(Mono.error(NoSuchKeyException.builder().message("Objet introuvable").build()));

        StepVerifier.create(controller.getThumbnail("tok", "page-001", null, null))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();

        assertThat(previewErrors("thumbnail", "missing")).isEqualTo(1.0);
    }

    @Test
    void getThumbnail_propagatesStorageUnavailable_whenThumbnailFetchTransientlyFails() {
        // Panne S3 transitoire (pool Netty saturé sous charge) → 503 retryable
        // via StorageUnavailableException, JAMAIS un 404 silencieux comme avant
        // (onErrorReturn). C'est la régression que ce commit corrige.
        var thumbKey = "u/f/t/page-001-thumb.jpg";
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), thumbKey);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.regenerateThumbnail("u/f/t/page-001.pdf", thumbKey, 120))
                .thenReturn(Mono.error(SdkClientException.create(
                        "Acquire operation took longer than the configured maximum time")));

        StepVerifier.create(controller.getThumbnail("tok", "page-001", null, null))
                .verifyErrorSatisfies(err -> assertThat(err)
                        .isInstanceOf(StorageUnavailableException.class)
                        .extracting(e -> ((StorageUnavailableException) e).retryAfterSeconds())
                        .isEqualTo(2L));

        assertThat(previewErrors("thumbnail", "transient")).isEqualTo(1.0);
    }

    @Test
    void getThumbnail_preview_returns404_whenPageObjectMissing() {
        // preview=true : rendu JPEG à la volée depuis le PDF de page. Objet
        // absent (NoSuchKey) → 404 légitime, même à travers le bulkhead merge.
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), "u/f/t/page-001-thumb.jpg");
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.generatePreview("u/f/t/page-001.pdf", 800))
                .thenReturn(Mono.error(NoSuchKeyException.builder().message("Objet introuvable").build()));

        StepVerifier.create(controller.getThumbnail("tok", "page-001", true, null))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();

        assertThat(previewErrors("preview", "missing")).isEqualTo(1.0);
    }

    @Test
    void getThumbnail_preview_propagatesStorageUnavailable_whenTransientFailure() {
        // Panne S3 transitoire sur le rendu d'aperçu → 503 retryable, pas 404.
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), "u/f/t/page-001-thumb.jpg");
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.generatePreview("u/f/t/page-001.pdf", 800))
                .thenReturn(Mono.error(SdkClientException.create("Read timed out")));

        StepVerifier.create(controller.getThumbnail("tok", "page-001", true, null))
                .verifyError(StorageUnavailableException.class);

        assertThat(previewErrors("preview", "transient")).isEqualTo(1.0);
    }

    private double previewErrors(String op, String cause) {
        var counter = meterRegistry.find("televerse.preview.errors")
                .tag("op", op).tag("cause", cause).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ===== deletePage =====

    private void removes(UploadSession session, String pageId) {
        when(sessionService.removePage(session.token(), pageId)).thenReturn(Mono.just(new SessionService.PageRemoval(
                session.withRemovedPage(pageId).withRenewedExpiry(Duration.ofMinutes(15)), session.findPage(pageId))));
    }

    @Test
    void deletePage_removesFromTheSessionThenPurgesThePageObjects() {
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), "u/f/t/page-001-thumb.jpg");
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        removes(session, "page-001");

        StepVerifier.create(controller.deletePage("tok", "page-001"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).contains("supprimée");
                    assertThat(response.getBody().totalPages()).isZero();
                })
                .verifyComplete();

        // Retrait d'abord, purge ensuite : un verrou refusé ne laisse plus une page listée
        // dont les objets auraient déjà disparu. PDF sur S3, vignette dans le cache.
        var order = inOrder(sessionService, storageService);
        order.verify(sessionService).removePage("tok", "page-001");
        order.verify(storageService).deleteAllBestEffort(List.of("u/f/t/page-001.pdf"));
        verify(thumbnailCache).deleteAll(List.of("u/f/t/page-001-thumb.jpg"));
        verify(storageService, never()).deleteFromS3(anyString());

        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("page-deleted");
    }

    @Test
    void deletePage_purgesTheVersionSeenUnderTheLock_notTheCallersSnapshot() {
        // Une édition concurrente a remplacé la version entre la lecture du contrôleur et
        // le verrou : ce sont les clés de la page TELLE QUE le verrou l'a vue qui partent.
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        var edited = page.withNewVersion("u/f/t/page-001-3f2a9c1e.pdf", "u/f/t/page-001-3f2a9c1e-thumb.jpg", 120);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.removePage("tok", "page-001")).thenReturn(Mono.just(new SessionService.PageRemoval(
                session.withRemovedPage("page-001"), edited)));

        StepVerifier.create(controller.deletePage("tok", "page-001"))
                .expectNextCount(1)
                .verifyComplete();

        verify(storageService).deleteAllBestEffort(List.of("u/f/t/page-001-3f2a9c1e.pdf", "u/f/t/page-001.pdf"));
    }

    @Test
    void deletePage_propagatesAConflict_whenTheSessionIsFinalizing() {
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.removePage("tok", "page-001"))
                .thenReturn(Mono.error(SessionConflictException.sessionFinalizing()));

        StepVerifier.create(controller.deletePage("tok", "page-001"))
                .expectError(SessionConflictException.class)
                .verify();

        verify(storageService, never()).deleteAllBestEffort(any());
    }

    @Test
    void deletePage_returns404_whenPageNotFound() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));

        StepVerifier.create(controller.deletePage("tok", "page-999"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void deletePage_returnsBadRequest_whenSessionExpired() {
        when(sessionService.getSessionAsync("expired")).thenReturn(Mono.empty());

        StepVerifier.create(controller.deletePage("expired", "page-001"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    // ===== reorderPages =====

    @Test
    void reorderPages_reordersAndNotifies() {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reorderPages("tok", List.of("page-002", "page-001")))
                .thenReturn(Mono.just(session.withReorderedPages(List.of("page-002", "page-001"))
                        .withRenewedExpiry(Duration.ofMinutes(15))));

        var request = new SessionController.ReorderRequest(List.of("page-002", "page-001"));

        StepVerifier.create(controller.reorderPages("tok", request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().totalPages()).isEqualTo(2);
                })
                .verifyComplete();

        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("pages-reordered");
    }

    @Test
    void reorderPages_returnsBadRequest_whenInvalidPageIds() {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));

        var request = new SessionController.ReorderRequest(List.of("page-001", "page-999"));

        StepVerifier.create(controller.reorderPages("tok", request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).contains("invalide");
                })
                .verifyComplete();
    }

    @Test
    void reorderPages_returnsBadRequest_whenThePageListIsMissing() {
        // Corps {} ou {"pageIds": null} : un 400, pas une NPE en 500.
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));

        StepVerifier.create(controller.reorderPages("tok", new SessionController.ReorderRequest(null)))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();

        verify(sessionService, never()).reorderPages(any(), any());
    }

    @Test
    void reorderPages_propagatesAConflict_whenThePagesChangedUnderTheLock() {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.reorderPages("tok", List.of("page-002", "page-001")))
                .thenReturn(Mono.error(SessionConflictException.pagesChanged()));

        StepVerifier.create(controller.reorderPages("tok",
                        new SessionController.ReorderRequest(List.of("page-002", "page-001"))))
                .expectErrorSatisfies(e -> assertThat(((SessionConflictException) e).code())
                        .isEqualTo(SessionConflictException.PAGES_CHANGED))
                .verify();
    }

    @Test
    void reorderPages_returnsBadRequest_whenSessionExpired() {
        when(sessionService.getSessionAsync("expired")).thenReturn(Mono.empty());

        var request = new SessionController.ReorderRequest(List.of("page-001"));

        StepVerifier.create(controller.reorderPages("expired", request))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    // ===== preview =====

    @Test
    void getPreview_mergesCurrentPagesWithoutFinalizing() {
        var page1 = new PageInfo("page-001", "tok/page-001.pdf", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "tok/page-002.pdf", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var pdfBytes = "merged-pdf".getBytes();
        when(storageService.mergePagesPreview(List.of("tok/page-001.pdf", "tok/page-002.pdf")))
                .thenReturn(Mono.just(pdfBytes));

        StepVerifier.create(controller.getPreview("tok"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getHeaders().getContentType()).hasToString("application/pdf");
                    assertThat(response.getHeaders().getFirst("Content-Disposition"))
                            .contains("inline").contains("preview.pdf");
                    assertThat(response.getHeaders().getCacheControl()).contains("no-cache");
                    assertThat(response.getBody()).isEqualTo(pdfBytes);
                })
                .verifyComplete();

        // Le preview ne doit ni uploader ni supprimer (pas d'appel à mergeAndFinalize)
        verify(storageService, never()).mergeAndFinalize(anyList(), anyString());
        verify(storageService, never()).deleteFromS3(anyString());
    }

    @Test
    void getPreview_returns404_whenSessionExpired() {
        when(sessionService.getSessionAsync("expired")).thenReturn(Mono.empty());

        StepVerifier.create(controller.getPreview("expired"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    @Test
    void getPreview_returns404_whenNoPages() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));

        StepVerifier.create(controller.getPreview("tok"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    // ===== expiresAt propagation =====

    @Test
    void deletePage_includesExpiresAtFromRenewedSession() {
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        // removePage retourne directement la session post-removal avec expiry renouvelée.
        var afterRemove = session.withRemovedPage("page-001").withRenewedExpiry(Duration.ofMinutes(15));
        when(sessionService.removePage("tok", "page-001"))
                .thenReturn(Mono.just(new SessionService.PageRemoval(afterRemove, page)));

        StepVerifier.create(controller.deletePage("tok", "page-001"))
                .assertNext(response -> {
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().expiresAt())
                            .isEqualTo(afterRemove.expiresAt().toEpochMilli());
                })
                .verifyComplete();
    }

    @Test
    void reorderPages_includesExpiresAtFromRenewedSession() {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var reordered = session.withReorderedPages(List.of("page-002", "page-001"))
                .withRenewedExpiry(Duration.ofMinutes(15));
        when(sessionService.reorderPages("tok", List.of("page-002", "page-001")))
                .thenReturn(Mono.just(reordered));

        var request = new SessionController.ReorderRequest(List.of("page-002", "page-001"));

        StepVerifier.create(controller.reorderPages("tok", request))
                .assertNext(response -> {
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().expiresAt())
                            .isEqualTo(reordered.expiresAt().toEpochMilli());
                })
                .verifyComplete();
    }

    // ===== éditions : nouvelle version sous clés neuves, adoptée sous verrou =====

    /** Page jamais éditée, clés d'upload d'origine. */
    private static PageInfo uploadedPage() {
        var uploaded = Instant.now().minusSeconds(60);
        return new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                100L, uploaded, uploaded, "tok/page-001-thumb.jpg", null, null);
    }

    private static UploadSession sessionWith(PageInfo page) {
        return new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
    }

    @Test
    void rotatePage_writesANewVersionAndLetsTheSessionAdoptIt() {
        var page = uploadedPage();
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var newKey = ArgumentCaptor.forClass(String.class);
        var newThumb = ArgumentCaptor.forClass(String.class);
        when(storageService.rotatePage(eq("tok/page-001.pdf"), eq(true), newKey.capture(), newThumb.capture()))
                .thenReturn(Mono.just(150L));
        var commit = commitAppliesEditTo(session);

        StepVerifier.create(controller.rotatePage("tok", "page-001", "cw"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().expiresAt())
                            .isEqualTo(commit.get().session().expiresAt().toEpochMilli());
                    assertThat(response.getBody().undoAvailable()).isTrue();
                })
                .verifyComplete();

        // Clé neuve, jamais la clé vivante : rien d'existant n'est réécrit.
        assertThat(newKey.getValue()).matches("tok/page-001-[0-9a-f]{8}\\.pdf").isNotEqualTo(page.s3Key());
        assertThat(newThumb.getValue()).isEqualTo(newKey.getValue().replace(".pdf", "-thumb.jpg"));
        // Version adoptée : la page pointe sur la nouvelle version, l'upload d'origine
        // devient à la fois version précédente et version initiale — sans copie S3.
        var after = commit.get().after();
        assertThat(after.s3Key()).isEqualTo(newKey.getValue());
        assertThat(after.fileSize()).isEqualTo(150L);
        assertThat(after.previousVersion().s3Key()).isEqualTo("tok/page-001.pdf");
        assertThat(after.initialVersion().s3Key()).isEqualTo("tok/page-001.pdf");
        verify(sessionService).commitPageEdit(eq("tok"), eq("page-001"), eq(page.lastModifiedAt()), any(), anyLong());
        // Rien n'est abandonné à la première édition.
        verify(storageService).deleteAllBestEffort(List.of());

        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("page-edited");
        assertThat(eventCaptor.getValue().pageId()).isEqualTo("page-001");
    }

    /**
     * Régression : le front rejoue SESSION_BUSY. L'édition écrasait la page AVANT de
     * prendre le verrou ; un verrou contesté faisait donc tourner la page une seconde
     * fois au rejeu. Désormais la version refusée est supprimée et la page n'a pas
     * bougé : le rejeu repart de la même version et ne tourne qu'une fois.
     */
    @Test
    void rotatePage_lockContention_discardsTheNewVersionAndLeavesThePageUntouched() {
        var page = uploadedPage();
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var newKey = ArgumentCaptor.forClass(String.class);
        var newThumb = ArgumentCaptor.forClass(String.class);
        when(storageService.rotatePage(eq("tok/page-001.pdf"), eq(true), newKey.capture(), newThumb.capture()))
                .thenReturn(Mono.just(150L));
        when(sessionService.commitPageEdit(eq("tok"), eq("page-001"), any(), any(), anyLong()))
                .thenReturn(Mono.error(new SessionLockTimeoutException("tok", Duration.ofSeconds(2), 1L)));

        StepVerifier.create(controller.rotatePage("tok", "page-001", "cw"))
                .expectError(SessionLockTimeoutException.class)
                .verify();

        verify(storageService).deleteAllBestEffort(List.of(newKey.getValue()));
        verify(thumbnailCache).delete(newThumb.getValue());
        verify(storageService, never()).deleteAllBestEffort(argThat(keys -> keys.contains(page.s3Key())));
        verify(sessionService, never()).notifyUpload(any(), any());
    }

    @Test
    void edit_discardsTheNewVersion_whenThePageChangedMeanwhile() {
        var page = uploadedPage();
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var newKey = ArgumentCaptor.forClass(String.class);
        when(storageService.cropPage(eq("tok/page-001.pdf"), newKey.capture(), any()))
                .thenReturn(Mono.just(new StorageService.DeskewResult(90L, true)));
        when(sessionService.commitPageEdit(eq("tok"), eq("page-001"), any(), any(), anyLong()))
                .thenReturn(Mono.error(SessionConflictException.pageChanged()));

        StepVerifier.create(controller.cropPage("tok", "page-001"))
                .expectErrorSatisfies(e -> assertThat(((SessionConflictException) e).code())
                        .isEqualTo(SessionConflictException.PAGE_CHANGED))
                .verify();

        verify(storageService).deleteAllBestEffort(List.of(newKey.getValue()));
    }

    @Test
    void edit_discardsTheNewVersion_whenTheSessionVanishedBeforeTheCommit() {
        var page = uploadedPage();
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var newKey = ArgumentCaptor.forClass(String.class);
        when(storageService.deskewPage(eq("tok/page-001.pdf"), newKey.capture(), any()))
                .thenReturn(Mono.just(new StorageService.DeskewResult(90L, true)));
        when(sessionService.commitPageEdit(eq("tok"), eq("page-001"), any(), any(), anyLong()))
                .thenReturn(Mono.empty());

        StepVerifier.create(controller.deskewPage("tok", "page-001"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().applied()).isFalse();
                    assertThat(response.getBody().message()).contains("expired or invalid");
                })
                .verifyComplete();

        verify(storageService).deleteAllBestEffort(List.of(newKey.getValue()));
    }

    @Test
    void edit_keepsWhatIsWritten_whenTheOutcomeOfTheCommitIsUnknown() {
        // Panne Hazelcast PENDANT l'écriture : impossible de savoir si la session a
        // adopté la version. La supprimer pourrait vider une page adoptée — on la garde.
        var page = uploadedPage();
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.rotatePage(eq("tok/page-001.pdf"), eq(true), any(), any()))
                .thenReturn(Mono.just(150L));
        when(sessionService.commitPageEdit(eq("tok"), eq("page-001"), any(), any(), anyLong()))
                .thenReturn(Mono.error(new com.hazelcast.core.HazelcastException("connection lost")));

        StepVerifier.create(controller.rotatePage("tok", "page-001", "cw"))
                .expectError(com.hazelcast.core.HazelcastException.class)
                .verify();

        verify(storageService, never()).deleteAllBestEffort(any());
    }

    @Test
    void edit_purgesTheVersionItSupersedes_butKeepsTheInitialOne() {
        // Troisième édition : la version précédente (e1) n'est plus référencée, l'initiale
        // (upload d'origine) l'est toujours.
        var original = new PageInfo.PreviousVersion("tok/page-001.pdf", "tok/page-001-thumb.jpg", 100L,
                Instant.now().minusSeconds(300));
        var e1 = new PageInfo.PreviousVersion("tok/page-001-e1.pdf", "tok/page-001-e1-thumb.jpg", 110L,
                Instant.now().minusSeconds(200));
        var page = new PageInfo("page-001", "tok/page-001-e2.pdf", "f.jpg", "image/jpeg",
                120L, Instant.now().minusSeconds(300), Instant.now().minusSeconds(100),
                "tok/page-001-e2-thumb.jpg", e1, original);
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.rotatePage(eq("tok/page-001-e2.pdf"), eq(true), any(), any()))
                .thenReturn(Mono.just(130L));
        var commit = commitAppliesEditTo(session);

        StepVerifier.create(controller.rotatePage("tok", "page-001", "cw"))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(commit.get().after().previousVersion().s3Key()).isEqualTo("tok/page-001-e2.pdf");
        assertThat(commit.get().after().initialVersion()).isEqualTo(original);
        verify(storageService).deleteAllBestEffort(List.of("tok/page-001-e1.pdf"));
        verify(thumbnailCache).deleteAll(List.of("tok/page-001-e1-thumb.jpg"));
    }

    @Test
    void editAdmission_rejectsWithEditBusy_whenBulkheadFull() {
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        // rotatePage renvoie un Mono paresseux, jamais souscrit puisque le bulkhead
        // refuse le permis avant la souscription. Le stub évite un NPE à l'assemblage
        // de la chaîne (mock non stubé → null) ; le pipeline S3 n'est jamais exécuté.
        when(storageService.rotatePage(eq("tok/page-001.pdf"), eq(true), any(), any()))
                .thenReturn(Mono.just(150L));

        // Bulkhead saturé : capacité 1, permis unique déjà pris, attente nulle → rejet immédiat.
        var fullBulkhead = Bulkhead.of("edit-full", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
        fullBulkhead.acquirePermission();
        var rejectingController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                fullBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);

        StepVerifier.create(rejectingController.rotatePage("tok", "page-001", "cw"))
                .expectError(EditBusyException.class)
                .verify();

        // Le rejet doit court-circuiter tout travail : aucune mutation de session,
        // aucune notification SSE.
        verify(sessionService, never()).commitPageEdit(any(), any(), any(), any(), anyLong());
        verify(sessionService, never()).notifyUpload(any(), any());
    }

    @Test
    void mergeAdmission_rejectsWithMergeBusy_whenBulkheadFull() {
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        // L'aperçu (preview.pdf) passe par le bulkhead merge. mergePagesPreview est
        // invoqué à l'assemblage mais jamais souscrit (permis refusé) : stub → évite
        // le NPE, le fetch/merge S3 n'est jamais exécuté.
        when(storageService.mergePagesPreview(anyList()))
                .thenReturn(Mono.just(new byte[]{1, 2, 3}));

        // Bulkhead merge saturé : capacité 1, permis unique déjà pris, attente nulle.
        var fullBulkhead = Bulkhead.of("merge-full", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
        fullBulkhead.acquirePermission();
        var rejectingController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                fullBulkhead, mergeAdmission,
                finalizeBulkhead, finalizeAdmission);

        StepVerifier.create(rejectingController.getPreview("tok"))
                .expectError(MergeBusyException.class)
                .verify();
    }

    @Test
    void finalizeAdmission_rejectsWithFinalizeBusy_beforeFreezingTheSession() {
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        // beginFinalize est assemblé mais jamais souscrit (permis refusé) : la session
        // n'est pas figée en FINALIZING par une finalisation rejetée à l'admission.
        var frozen = new java.util.concurrent.atomic.AtomicBoolean();
        when(sessionService.beginFinalize(eq("tok"), any())).thenReturn(Mono.fromCallable(() -> {
            frozen.set(true);
            return new SessionService.FinalizeStart(SessionService.FinalizeStart.State.STARTED, session);
        }));

        // Bulkhead finalize saturé — distinct du bulkhead merge (aperçus) : une rafale
        // de finalisations ne doit rejeter QUE les finalisations, pas les aperçus.
        var fullBulkhead = Bulkhead.of("finalize-full", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
        fullBulkhead.acquirePermission();
        var rejectingController = new SessionController(sessionService, storageService, thumbnailCache, limits,
                ghostscript, meterRegistry, originatorResolver, originatorDisabled,
                rateLimiterPermissive, rateLimitDisabled, sessionCreateProps,
                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofSeconds(10), 800, 120, sessionPagesCount,
                editBulkhead, editAdmission,
                mergeBulkhead, mergeAdmission,
                fullBulkhead, finalizeAdmission);

        StepVerifier.create(rejectingController.complete("tok"))
                .expectError(FinalizeBusyException.class)
                .verify();

        assertThat(frozen).isFalse();
        verify(sessionService, never()).notifyUpload(any(), any());
    }

    @Test
    void whiteBalancePage_emitsPageEditedEvent() {
        var page = uploadedPage();
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(storageService.whiteBalancePage(eq("tok/page-001.pdf"), any(), any()))
                .thenReturn(Mono.just(150L));
        var commit = commitAppliesEditTo(session);

        StepVerifier.create(controller.whiteBalancePage("tok", "page-001"))
                .assertNext(response -> {
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().expiresAt())
                            .isEqualTo(commit.get().session().expiresAt().toEpochMilli());
                })
                .verifyComplete();

        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("page-edited");
        assertThat(eventCaptor.getValue().pageId()).isEqualTo("page-001");
    }

    // ===== undoPage =====

    @Test
    void undoPage_pointsBackToThePreviousVersionAndPurgesTheUndoneOne() {
        var backup = new PageInfo.PreviousVersion("tok/page-001-e1.pdf",
                "tok/page-001-e1-thumb.jpg", 100L, Instant.now().minusSeconds(60));
        var initial = new PageInfo.PreviousVersion("tok/page-001.pdf",
                "tok/page-001-thumb.jpg", 90L, Instant.now().minusSeconds(120));
        var page = new PageInfo("page-001", "tok/page-001-e2.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now().minusSeconds(120), Instant.now(), "tok/page-001-e2-thumb.jpg", backup, initial);
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var commit = commitAppliesEditTo(session);

        StepVerifier.create(controller.undoPage("tok", "page-001"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).isEqualTo("Modification annulée");
                    assertThat(response.getBody().undoAvailable()).isFalse();
                    assertThat(response.getBody().expiresAt())
                            .isEqualTo(commit.get().session().expiresAt().toEpochMilli());
                })
                .verifyComplete();

        // Changement de pointeur, sans copie S3 ; l'initiale reste (pas encore revenu à l'upload).
        var restored = commit.get().after();
        assertThat(restored.s3Key()).isEqualTo("tok/page-001-e1.pdf");
        assertThat(restored.thumbnailKey()).isEqualTo("tok/page-001-e1-thumb.jpg");
        assertThat(restored.fileSize()).isEqualTo(100L);
        assertThat(restored.previousVersion()).isNull();
        assertThat(restored.initialVersion()).isEqualTo(initial);
        // Purge APRÈS adoption, de la seule version annulée.
        verify(storageService).deleteAllBestEffort(List.of("tok/page-001-e2.pdf"));
        verify(thumbnailCache).deleteAll(List.of("tok/page-001-e2-thumb.jpg"));

        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("page-edited");
    }

    @Test
    void undoPage_backToTheUploadState_alsoDropsTheInitialCopy_ofALegacyPage() {
        // Page éditée avant le versionnement : sauvegardes en place (-prev / -init). Une
        // seule édition : -prev ET -init portent l'instant d'upload (previousIsInitial) →
        // l'undo ramène à l'état d'origine, et les deux objets devenus inutiles partent.
        var uploadInstant = Instant.now().minusSeconds(120);
        var prev = new PageInfo.PreviousVersion("tok/page-001-prev.pdf",
                "tok/page-001-prev-thumb.jpg", 100L, uploadInstant);
        var initial = new PageInfo.PreviousVersion("tok/page-001-init.pdf",
                "tok/page-001-init-thumb.jpg", 100L, uploadInstant);
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, uploadInstant, Instant.now(), "tok/page-001-thumb.jpg", prev, initial);
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var commit = commitAppliesEditTo(session);

        StepVerifier.create(controller.undoPage("tok", "page-001"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();

        var restored = commit.get().after();
        assertThat(restored.s3Key()).isEqualTo("tok/page-001-prev.pdf");
        assertThat(restored.previousVersion()).isNull();
        assertThat(restored.initialVersion()).isNull();
        verify(storageService).deleteAllBestEffort(List.of("tok/page-001.pdf", "tok/page-001-init.pdf"));
        verify(thumbnailCache).deleteAll(List.of("tok/page-001-thumb.jpg", "tok/page-001-init-thumb.jpg"));
    }

    @Test
    void undoPage_returnsOkWithoutChanges_whenNoBackup() {
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        // Pas de backup → on appelle renewSession (la branche sans commit).
        when(sessionService.renewSession("tok")).thenReturn(Mono.just(session.withRenewedExpiry(Duration.ofMinutes(15))));

        StepVerifier.create(controller.undoPage("tok", "page-001"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).contains("Aucune modification");
                    assertThat(response.getBody().undoAvailable()).isFalse();
                })
                .verifyComplete();

        verify(sessionService, never()).commitPageEdit(any(), any(), any(), any(), anyLong());
        // Même sans sauvegarde à restaurer, l'action a renouvelé le TTL :
        // l'événement page-edited propage ce renouvellement à l'autre appareil.
        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("page-edited");
    }

    @Test
    void undoPage_propagatesAConflict_andPurgesNothing_whenThePageWasDeleted() {
        var backup = new PageInfo.PreviousVersion("tok/page-001.pdf", "tok/page-001-thumb.jpg", 100L,
                Instant.now().minusSeconds(60));
        var page = new PageInfo("page-001", "tok/page-001-e1.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now().minusSeconds(120), Instant.now(), "tok/page-001-e1-thumb.jpg", backup, backup);
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.commitPageEdit(eq("tok"), eq("page-001"), any(), any(), anyLong()))
                .thenReturn(Mono.error(SessionConflictException.pageGone()));

        StepVerifier.create(controller.undoPage("tok", "page-001"))
                .expectError(SessionConflictException.class)
                .verify();

        verify(storageService, never()).deleteAllBestEffort(any());
    }

    @Test
    void undoPage_returnsBadRequest_whenSessionExpired() {
        when(sessionService.getSessionAsync("expired")).thenReturn(Mono.empty());

        StepVerifier.create(controller.undoPage("expired", "page-001"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void undoPage_returns404_whenPageNotFound() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        when(sessionService.getSessionAsync(session.token())).thenReturn(Mono.just(session));

        StepVerifier.create(controller.undoPage(session.token(), "page-999"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();
    }

    // ===== resetPage =====

    @Test
    void resetPage_pointsBackToTheInitialVersionAndPurgesTheOthers() {
        var prev = new PageInfo.PreviousVersion("tok/page-001-prev.pdf",
                "tok/page-001-prev-thumb.jpg", 150L, Instant.now().minusSeconds(30));
        var initial = new PageInfo.PreviousVersion("tok/page-001-init.pdf",
                "tok/page-001-init-thumb.jpg", 100L, Instant.now().minusSeconds(120));
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                200L, Instant.now().minusSeconds(120), Instant.now(), "tok/page-001-thumb.jpg", prev, initial);
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        var commit = commitAppliesEditTo(session);

        StepVerifier.create(controller.resetPage("tok", "page-001"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).isEqualTo("Modifications annulées");
                    assertThat(response.getBody().undoAvailable()).isFalse();
                    assertThat(response.getBody().expiresAt())
                            .isEqualTo(commit.get().session().expiresAt().toEpochMilli());
                })
                .verifyComplete();

        var restored = commit.get().after();
        assertThat(restored.s3Key()).isEqualTo("tok/page-001-init.pdf");
        assertThat(restored.previousVersion()).isNull();
        assertThat(restored.initialVersion()).isNull();
        assertThat(restored.fileSize()).isEqualTo(100L);
        // Version courante et sauvegarde d'undo, désormais sans référence.
        verify(storageService).deleteAllBestEffort(List.of("tok/page-001.pdf", "tok/page-001-prev.pdf"));
        verify(thumbnailCache).deleteAll(List.of("tok/page-001-thumb.jpg", "tok/page-001-prev-thumb.jpg"));
        verify(sessionService).notifyUpload(eq("tok"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("page-edited");
    }

    @Test
    void resetPage_returnsOkWithoutChanges_whenNoInitial() {
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = sessionWith(page);
        when(sessionService.getSessionAsync("tok")).thenReturn(Mono.just(session));
        when(sessionService.renewSession("tok")).thenReturn(Mono.just(session.withRenewedExpiry(Duration.ofMinutes(15))));

        StepVerifier.create(controller.resetPage("tok", "page-001"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    Assertions.assertNotNull(response.getBody());
                    assertThat(response.getBody().message()).contains("Aucune modification");
                })
                .verifyComplete();

        verify(sessionService, never()).commitPageEdit(any(), any(), any(), any(), anyLong());
    }

    @Test
    void resetPage_returnsBadRequest_whenSessionExpired() {
        when(sessionService.getSessionAsync("expired")).thenReturn(Mono.empty());

        StepVerifier.create(controller.resetPage("expired", "page-001"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }
}

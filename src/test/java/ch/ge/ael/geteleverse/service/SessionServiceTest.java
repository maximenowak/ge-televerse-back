package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.controller.OriginatorBusyException;
import ch.ge.ael.geteleverse.model.OriginatorBinding;
import ch.ge.ael.geteleverse.model.PageInfo;
import ch.ge.ael.geteleverse.model.SizeProfile;
import ch.ge.ael.geteleverse.model.UploadEvent;
import ch.ge.ael.geteleverse.model.UploadSession;
import ch.ge.ael.geteleverse.service.SessionService.UploadNotification;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryEventType;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryExpiredListener;
import com.hazelcast.map.listener.MapListener;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.Message;
import com.hazelcast.topic.MessageListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock HazelcastInstance hazelcast;
    @Mock IMap<String, HazelcastJsonValue> sessionsMap;
    @Mock IMap<String, OriginatorBinding> originatorsMap;
    @Mock IMap<String, String> purgeClaims;
    @Mock ITopic<UploadNotification> uploadTopic;
    @Mock StorageService storageService;
    @Mock ThumbnailCacheService thumbnailCache;
    @Captor ArgumentCaptor<MessageListener<UploadNotification>> listenerCaptor;
    @Captor ArgumentCaptor<UploadNotification> notificationCaptor;
    @Captor ArgumentCaptor<HazelcastJsonValue> jsonCaptor;

    private ObjectMapper objectMapper;
    private SessionService service;

    private static final Duration SESSION_TTL = Duration.ofMinutes(15);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(2);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final Duration LOCK_RETRY_AFTER = Duration.ofSeconds(1);
    private static final SizeProfile PROFILE = SizeProfile.S;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();

        when(hazelcast.<String, HazelcastJsonValue>getMap("televerse-sessions")).thenReturn(sessionsMap);
        when(hazelcast.<String, OriginatorBinding>getMap("televerse-originators")).thenReturn(originatorsMap);
        when(hazelcast.<String, String>getMap("televerse-purge-claims")).thenReturn(purgeClaims);
        // Revendication de purge remportée par défaut (pod unique).
        lenient().when(purgeClaims.putIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(null);
        lenient().when(storageService.deleteAllBestEffort(any())).thenReturn(Mono.empty());
        when(hazelcast.<UploadNotification>getTopic("televerse-uploads")).thenReturn(uploadTopic);
        when(uploadTopic.addMessageListener(any())).thenReturn(UUID.randomUUID());

        lenient().when(thumbnailCache.deleteAll(any())).thenReturn(Mono.empty());
        lenient().when(thumbnailCache.delete(any())).thenReturn(Mono.empty());

        // tryLock remplace lock() : sans stub, le mock renvoie false et toute
        // mutation echoue en SessionLockTimeoutException.
        lenient().when(sessionsMap.tryLock(any(), anyLong(), any(), anyLong(), any())).thenReturn(true);

        service = new SessionService(hazelcast, objectMapper, SESSION_TTL,
                LOCK_WAIT, LOCK_LEASE, LOCK_RETRY_AFTER, storageService, thumbnailCache);
        service.init();
    }

    // ===== Helpers =====

    private void stubSession(String token, UploadSession session) {
        try {
            var json = new HazelcastJsonValue(objectMapper.writeValueAsString(session));
            when(sessionsMap.get(token)).thenReturn(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UploadSession captureStoredSession() {
        try {
            verify(sessionsMap, atLeastOnce()).put(anyString(), jsonCaptor.capture(), anyLong(), any());
            return objectMapper.readValue(jsonCaptor.getValue().getValue(), UploadSession.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===== createSession =====

    @Test
    void createSession_storesJsonInMapWithTtl() {
        var session = service.createSession(PROFILE);

        assertThat(session.token()).isNotBlank();
        assertThat(session.sizeProfile()).isEqualTo(PROFILE);
        assertThat(session.isExpired()).isFalse();
        assertThat(session.pages()).isEmpty();
        assertThat(session.pageCounter()).isZero();

        verify(sessionsMap).put(
                eq(session.token()),
                any(HazelcastJsonValue.class),
                eq(SESSION_TTL.toSeconds()),
                eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void createSession_jsonRoundTrips() throws Exception {
        var session = service.createSession(PROFILE);

        verify(sessionsMap).put(eq(session.token()), jsonCaptor.capture(), anyLong(), any());
        var stored = objectMapper.readValue(jsonCaptor.getValue().getValue(), UploadSession.class);

        assertThat(stored.token()).isEqualTo(session.token());
        assertThat(stored.documentId()).isEqualTo(session.documentId());
        assertThat(stored.sizeProfile()).isEqualTo(PROFILE);
        assertThat(stored.pages()).isEmpty();
    }

    @Test
    void createSession_appliesDefaultProfile_whenNull() {
        var session = service.createSession(null);

        assertThat(session.sizeProfile()).isEqualTo(SizeProfile.DEFAULT);
    }

    // ===== getSession =====

    @Test
    void getSession_returnsSession_whenValid() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        var result = service.getSession(session.token());

        assertThat(result).isEqualTo(session);
    }

    @Test
    void getSession_returnsNull_whenNotFound() {
        when(sessionsMap.get("unknown")).thenReturn(null);

        assertThat(service.getSession("unknown")).isNull();
    }

    @Test
    void getSession_returnsNull_andRemoves_whenExpired() {
        var expired = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(), 0, false);
        stubSession("tok", expired);

        assertThat(service.getSession("tok")).isNull();
        verify(sessionsMap).remove("tok");
    }

    @Test
    void getSession_cleansOrphanS3Pages_whenExpired() {
        var backup = new PageInfo.PreviousVersion("tok/page-001-prev.pdf",
                "tok/page-001-prev-thumb.jpg", 100L, Instant.now().minusSeconds(120));
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60),
                "tok/page-001-thumb.jpg", backup);
        var expired = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(page), 1, false);
        stubSession("tok", expired);

        assertThat(service.getSession("tok")).isNull();

        // Page et sauvegarde d'édition : purgées de S3 en UN lot borné — la liste
        // des clés disparaît avec l'entrée Hazelcast, c'est la dernière occasion de
        // nettoyer. Les vignettes, elles, sont rendues au cache Hazelcast.
        verify(storageService).deleteAllBestEffort(List.of("tok/page-001.pdf", "tok/page-001-prev.pdf"));
        verify(storageService, never()).deleteFromS3(anyString());
        verify(thumbnailCache).deleteAll(List.of(
                "tok/page-001-thumb.jpg", "tok/page-001-prev-thumb.jpg"));
        verify(sessionsMap).remove("tok");
    }

    @Test
    void getSession_groupsThumbnailPurgeIntoOneCall_whenExpiredWithManyPages() {
        // Régression : la purge émettait un deleteAll PAR PAGE, chacun fire-and-forget.
        // Les N appels partaient donc simultanément et la borne de concurrence interne
        // de deleteAll ne bornait rien à l'échelle de la session : le pic d'invocations
        // Hazelcast restait proportionnel au nombre de pages, puis au nombre de
        // finalisations concurrentes — d'où HazelcastOverloadException en charge.
        // Un seul appel groupé rend le coût d'une purge constant.
        var pages = new java.util.ArrayList<PageInfo>();
        for (int i = 1; i <= 20; i++) {
            var id = String.format("page-%03d", i);
            var backup = new PageInfo.PreviousVersion("tok/" + id + "-prev.pdf",
                    "tok/" + id + "-prev-thumb.jpg", 100L, Instant.now().minusSeconds(120));
            pages.add(new PageInfo(id, "tok/" + id + ".pdf", "f.jpg", "image/jpeg",
                    150L, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60),
                    "tok/" + id + "-thumb.jpg", backup));
        }
        var expired = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), pages, 20, false);
        stubSession("tok", expired);

        assertThat(service.getSession("tok")).isNull();

        // Exactement un appel, porteur de TOUTES les clés (2 par page : vivante + -prev),
        // et non 20 appels d'une clé chacun.
        var keysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(thumbnailCache, times(1)).deleteAll(keysCaptor.capture());
        assertThat(keysCaptor.getValue())
                .hasSize(40)
                .contains("tok/page-001-thumb.jpg", "tok/page-001-prev-thumb.jpg",
                        "tok/page-020-thumb.jpg", "tok/page-020-prev-thumb.jpg");
    }

    @Test
    void hazelcastEvictionListener_cleansOrphanS3Pages() throws Exception {
        // Capture les listeners enregistrés par init() : expiration TTL puis
        // éviction mémoire — les deux délèguent au même handler.
        var captor = ArgumentCaptor.forClass(MapListener.class);
        verify(sessionsMap, times(2)).addEntryListener(captor.capture(), eq(true));

        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(page), 1, false);
        var json = new HazelcastJsonValue(objectMapper.writeValueAsString(session));

        @SuppressWarnings("unchecked")
        var expiredListener = (EntryExpiredListener<String, HazelcastJsonValue>) captor.getAllValues().get(0);
        expiredListener.entryExpired(new EntryEvent<>("test", null,
                EntryEventType.EXPIRED.getType(), "tok", json, null));

        verify(storageService).deleteAllBestEffort(List.of("tok/page-001.pdf"));
    }

    /**
     * En mode client, CHAQUE pod reçoit l'événement d'expiration : seul celui qui
     * remporte la revendication purge. Avant, tous purgeaient — 6 pods, 6 fois la
     * charge S3 d'une expiration.
     */
    @Test
    void hazelcastEvictionListener_skipsPurge_whenAnotherPodClaimedIt() throws Exception {
        var captor = ArgumentCaptor.forClass(MapListener.class);
        verify(sessionsMap, times(2)).addEntryListener(captor.capture(), eq(true));
        when(purgeClaims.putIfAbsent(eq("tok"), anyString(), anyLong(), any())).thenReturn("other-pod");

        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now(), "tok/page-001-thumb.jpg");
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(page), 1, false);

        @SuppressWarnings("unchecked")
        var expiredListener = (EntryExpiredListener<String, HazelcastJsonValue>) captor.getAllValues().get(0);
        expiredListener.entryExpired(new EntryEvent<>("test", null, EntryEventType.EXPIRED.getType(), "tok",
                new HazelcastJsonValue(objectMapper.writeValueAsString(session)), null));

        verify(storageService, never()).deleteAllBestEffort(any());
        verify(thumbnailCache, never()).deleteAll(any());
    }

    @Test
    void hazelcastEvictionListener_purgesAnyway_whenTheClaimCannotBeRecorded() throws Exception {
        // Cluster injoignable : les DELETE S3 sont idempotents, un doublon vaut mieux
        // qu'un document personnel laissé sur le bucket.
        var captor = ArgumentCaptor.forClass(MapListener.class);
        verify(sessionsMap, times(2)).addEntryListener(captor.capture(), eq(true));
        when(purgeClaims.putIfAbsent(eq("tok"), anyString(), anyLong(), any()))
                .thenThrow(new HazelcastException("cluster down"));

        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg",
                150L, Instant.now(), null);
        var session = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(page), 1, false);

        @SuppressWarnings("unchecked")
        var expiredListener = (EntryExpiredListener<String, HazelcastJsonValue>) captor.getAllValues().get(0);
        expiredListener.entryExpired(new EntryEvent<>("test", null, EntryEventType.EXPIRED.getType(), "tok",
                new HazelcastJsonValue(objectMapper.writeValueAsString(session)), null));

        verify(storageService).deleteAllBestEffort(List.of("tok/page-001.pdf"));
    }

    @Test
    void hazelcastEvictionListener_ignoresEventsWithoutValue() throws Exception {
        var captor = ArgumentCaptor.forClass(MapListener.class);
        verify(sessionsMap, times(2)).addEntryListener(captor.capture(), eq(true));

        @SuppressWarnings("unchecked")
        var expiredListener = (EntryExpiredListener<String, HazelcastJsonValue>) captor.getAllValues().get(0);
        expiredListener.entryExpired(new EntryEvent<>("test", null,
                EntryEventType.EXPIRED.getType(), "tok", null, null));

        verify(storageService, never()).deleteAllBestEffort(any());
    }

    /**
     * IMap.lock attendait SANS LIMITE : une session verrouillee par une operation
     * qui traine bloquait toute autre mutation pour toujours -- requete en vol,
     * permis de bulkhead immobilise, aucune trace. tryLock borne l'attente et
     * qualifie l'echec en SessionLockTimeoutException (429 SESSION_BUSY).
     */
    @Test
    void withSessionLock_failsFast_whenLockNotAcquired() throws Exception {
        when(sessionsMap.tryLock(eq("tok"), anyLong(), any(), anyLong(), any())).thenReturn(false);

        StepVerifier.create(service.renewSession("tok"))
                .expectError(SessionLockTimeoutException.class)
                .verify();

        // Verrou jamais pris => jamais relache : pas d'IllegalMonitorStateException.
        verify(sessionsMap, never()).unlock("tok");
    }

    /**
     * Un cancel Reactor (edit-timeout depasse, navigateur parti) dispose la tache
     * boundedElastic avec cancel(true) : le thread est interrompu en pleine
     * section critique. L'invocation Hazelcast en cours leve, et le finally
     * arrivait sur un thread encore interrompu -- ou l'unlock, invocation
     * bloquante lui aussi, echouait a son tour : verrou retenu jusqu'a la fin du
     * bail (30 s de SESSION_BUSY), pour toujours avant le bail. Le drapeau doit
     * etre efface le temps de rendre le verrou, puis repose.
     */
    @Test
    void withSessionLock_unlocksOnCleanThread_whenInterruptedInCriticalSection() {
        var interruptedAtUnlock = new AtomicReference<Boolean>();
        var interruptedAfter = new AtomicReference<Boolean>();
        when(sessionsMap.get("tok")).thenAnswer(inv -> {
            // Ce que laisse une invocation client interrompue : le drapeau pose
            // par le cancel, et l'appel qui leve.
            Thread.currentThread().interrupt();
            throw new HazelcastException("interrupted");
        });
        doAnswer(inv -> {
            interruptedAtUnlock.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(sessionsMap).unlock("tok");

        StepVerifier.create(service.renewSession("tok")
                        .doOnError(e -> interruptedAfter.set(Thread.currentThread().isInterrupted())))
                .expectError(HazelcastException.class)
                .verify();

        verify(sessionsMap).unlock("tok");
        assertThat(interruptedAtUnlock.get()).as("drapeau efface le temps de l'unlock").isFalse();
        assertThat(interruptedAfter.get()).as("drapeau repose apres l'unlock").isTrue();
    }

    /**
     * Interruption PENDANT l'attente du verrou : tryLock leve InterruptedException.
     * Le membre a pu accorder le verrou juste avant que la reponse ne soit lue,
     * et le client ne le saura jamais. Liberation a l'aveugle --
     * IllegalMonitorStateException si on ne le tenait pas (cas normal, tu),
     * verrou rendu sinon -- puis l'interruption est propagee telle quelle.
     */
    @Test
    void withSessionLock_releasesBlindly_whenTryLockInterrupted() throws Exception {
        when(sessionsMap.tryLock(eq("tok"), anyLong(), any(), anyLong(), any()))
                .thenThrow(new InterruptedException("cancelled"));
        doThrow(new IllegalMonitorStateException("Current thread is not owner of the lock!"))
                .when(sessionsMap).unlock("tok");

        StepVerifier.create(service.renewSession("tok"))
                .expectError(InterruptedException.class)
                .verify();

        verify(sessionsMap).unlock("tok");
        // Section critique jamais atteinte.
        verify(sessionsMap, never()).get("tok");
    }

    /**
     * Les ecritures Hazelcast de la creation (binding originator + session) sont
     * bloquantes et ne doivent plus s'executer sur un thread d'event loop Netty.
     */
    @Test
    void createSessionAsync_emitsOffCallerThread() {
        var callerThread = Thread.currentThread().getName();
        var emittingThread = new AtomicReference<String>();

        StepVerifier.create(service.createSessionAsync(PROFILE, null, false)
                        .doOnNext(s -> emittingThread.set(Thread.currentThread().getName())))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(emittingThread.get()).isNotEqualTo(callerThread);
        assertThat(emittingThread.get()).startsWith("boundedElastic-");
    }

    /**
     * Le refus « une session par IP » etait leve en synchrone ; il doit desormais
     * remonter en signal d'erreur, pour rester traduit en 409 par
     * GlobalExceptionHandler.
     */
    @Test
    void createSessionAsync_propagatesOriginatorBusy_asErrorSignal() {
        var existing = new OriginatorBinding("other-token", Instant.now(), Instant.now().plusSeconds(900));
        when(originatorsMap.putIfAbsent(eq("10.0.0.1"), any(), anyLong(), any())).thenReturn(existing);

        StepVerifier.create(service.createSessionAsync(PROFILE, "10.0.0.1", false))
                .expectError(OriginatorBusyException.class)
                .verify();
    }

    /**
     * Le point de tout l'exercice : le get Hazelcast est BLOQUANT et ne doit plus
     * s'executer sur un thread d'event loop Netty. On verifie que l'emission a
     * bien lieu sur boundedElastic, pas sur le thread appelant.
     */
    @Test
    void getSessionAsync_emitsOffCallerThread() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);
        var callerThread = Thread.currentThread().getName();
        var emittingThread = new AtomicReference<String>();

        StepVerifier.create(service.getSessionAsync(session.token())
                        .doOnNext(s -> emittingThread.set(Thread.currentThread().getName())))
                .expectNext(session)
                .verifyComplete();

        assertThat(emittingThread.get()).isNotEqualTo(callerThread);
        assertThat(emittingThread.get()).startsWith("boundedElastic-");
    }

    /**
     * Session absente ou expiree : getSession renvoie null, la variante reactive
     * doit se traduire par un Mono vide (et non un onNext(null), interdit).
     */
    @Test
    void getSessionAsync_emitsEmpty_whenSessionMissing() {
        when(sessionsMap.get("unknown")).thenReturn(null);

        StepVerifier.create(service.getSessionAsync("unknown"))
                .verifyComplete();
    }

    // ===== renewSession =====

    @Test
    void renewSession_updatesExpiryAndHazelcastTtl() throws Exception {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(service.renewSession(session.token()))
                .assertNext(result -> {
                    assertThat(result.token()).isEqualTo(session.token());
                    assertThat(result.expiresAt()).isAfterOrEqualTo(session.expiresAt());
                })
                .verifyComplete();

        verify(sessionsMap).tryLock(eq(session.token()), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock(session.token());
        verify(sessionsMap).put(eq(session.token()), jsonCaptor.capture(),
                eq(SESSION_TTL.toSeconds()), eq(TimeUnit.SECONDS));

        var stored = objectMapper.readValue(jsonCaptor.getValue().getValue(), UploadSession.class);
        assertThat(stored.token()).isEqualTo(session.token());
        assertThat(stored.expiresAt()).isAfterOrEqualTo(session.expiresAt());
    }

    @Test
    void renewSession_emitsEmpty_whenSessionNotFound() throws Exception {
        when(sessionsMap.get("unknown")).thenReturn(null);

        StepVerifier.create(service.renewSession("unknown"))
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("unknown"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("unknown");
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    // ===== reservePageId / attachPage =====
    //
    // L'atomicité réelle est garantie par Hazelcast (lock/unlock distribué sur
    // la clé) ; ces tests vérifient le câblage du service : lock acquis, état
    // muté de manière cohérente, lock libéré dans tous les cas y compris
    // session absente/expirée. Quota check : intégré dans la section verrouillée
    // d'attachPage (cf. AttachResult.accepted).

    private static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024; // S = 25 MB
    private static final int MAX_PAGES = 100;

    @Test
    void reservePageId_bumpsCounterAndReturnsReservation() throws Exception {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(service.reservePageId(session.token()))
                .assertNext(reservation -> {
                    assertThat(reservation.pageId()).isEqualTo("page-001");
                    assertThat(reservation.pageCounter()).isEqualTo(1);
                })
                .verifyComplete();

        verify(sessionsMap).tryLock(eq(session.token()), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock(session.token());

        var stored = captureStoredSession();
        assertThat(stored.pageCounter()).isEqualTo(1);
        assertThat(stored.pages()).isEmpty();
    }

    @Test
    void reservePageId_emitsEmpty_andReleasesLock_whenSessionGone() throws Exception {
        when(sessionsMap.get("gone")).thenReturn(null);

        StepVerifier.create(service.reservePageId("gone"))
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("gone"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("gone");
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    @Test
    void reservePageId_emitsEmpty_andReleasesLock_whenSessionExpired() throws Exception {
        var expired = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(), 0, false);
        stubSession("tok", expired);

        StepVerifier.create(service.reservePageId("tok"))
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("tok"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("tok");
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    @Test
    void attachPage_appendsPageWithoutTouchingCounter() throws Exception {
        var bumped = UploadSession.create(PROFILE, SESSION_TTL).withBumpedCounter();
        stubSession(bumped.token(), bumped);

        var page = new PageInfo("page-001", "tok/page-001.pdf", "scan.jpg", "image/jpeg", 1024, Instant.now(), null);

        StepVerifier.create(service.attachPage(bumped.token(), page, MAX_PAGES, MAX_TOTAL_BYTES))
                .assertNext(attach -> {
                    assertThat(attach.accepted()).isTrue();
                    assertThat(attach.session().pages()).hasSize(1);
                    assertThat(attach.session().pages().getFirst().pageId()).isEqualTo("page-001");
                    // pageCounter inchangé : la réservation l'avait déjà incrémenté.
                    assertThat(attach.session().pageCounter()).isEqualTo(1);
                })
                .verifyComplete();

        verify(sessionsMap).tryLock(eq(bumped.token()), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock(bumped.token());

        var stored = captureStoredSession();
        assertThat(stored.pages()).hasSize(1);
        assertThat(stored.pageCounter()).isEqualTo(1);
    }

    @Test
    void attachPage_emitsEmpty_andReleasesLock_whenSessionGone() throws Exception {
        when(sessionsMap.get("gone")).thenReturn(null);

        StepVerifier.create(service.attachPage("gone",
                        new PageInfo("p", "k", "f", "t", 0, Instant.now(), null), MAX_PAGES, MAX_TOTAL_BYTES))
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("gone"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("gone");
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    @Test
    void attachPage_refuses_andReleasesLock_whenQuotaExceeded() throws Exception {
        // Session déjà à 24 MB ; nouvelle page de 2 MB ⇒ projection 26 MB > cap 25 MB.
        var existing = new PageInfo("page-001", "k1", "f.jpg", "image/jpeg",
                24L * 1024 * 1024, Instant.now(), null);
        var session = UploadSession.create(PROFILE, SESSION_TTL).withAddedPage(existing);
        stubSession(session.token(), session);

        var newPage = new PageInfo("page-002", "k2", "big.jpg", "image/jpeg",
                2L * 1024 * 1024, Instant.now(), null);

        StepVerifier.create(service.attachPage(session.token(), newPage, MAX_PAGES, MAX_TOTAL_BYTES))
                .assertNext(attach -> {
                    assertThat(attach.accepted()).isFalse();
                    assertThat(attach.rejection()).isEqualTo(SessionService.AttachRejection.SIZE_LIMIT);
                    // La session retournée reflète l'état AVANT l'attachement refusé.
                    assertThat(attach.session().pages()).hasSize(1);
                    assertThat(attach.session().totalBytes()).isEqualTo(24L * 1024 * 1024);
                })
                .verifyComplete();

        verify(sessionsMap).tryLock(eq(session.token()), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock(session.token());
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    @Test
    void attachPage_refuses_andReleasesLock_whenPageLimitReached() throws Exception {
        // Session déjà à 2 pages ; cap = 2 ⇒ la 3e attache est refusée sous le
        // verrou avec PAGE_LIMIT. Ferme la race multi-sélection : le pré-contrôle
        // du contrôleur lit pages().size() hors verrou et laisse passer N requêtes
        // concurrentes ; seul ce check sérialisé borne effectivement la session.
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 1024, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 1024, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        stubSession("tok", session);

        var newPage = new PageInfo("page-003", "k3", "f3.jpg", "image/jpeg", 1024, Instant.now(), null);

        StepVerifier.create(service.attachPage("tok", newPage, 2, MAX_TOTAL_BYTES))
                .assertNext(attach -> {
                    assertThat(attach.accepted()).isFalse();
                    assertThat(attach.rejection()).isEqualTo(SessionService.AttachRejection.PAGE_LIMIT);
                    // État inchangé : la page refusée n'est pas ajoutée.
                    assertThat(attach.session().pages()).hasSize(2);
                })
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("tok"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("tok");
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    @Test
    void attachPage_rejectsDuplicate_whenHashPresent_andRejectDuplicateEnabled() throws Exception {
        // Une page portant l'empreinte "abc" est déjà attachée ; un re-upload à
        // l'identique (même empreinte) est refusé sous le verrou avec DUPLICATE
        // quand rejectDuplicate=true (chemin image). Rien n'est réécrit.
        var existing = new PageInfo("page-001", "k1", "f.jpg", "image/jpeg", 1024, Instant.now(), null)
                .withContentHash("abc");
        var session = UploadSession.create(PROFILE, SESSION_TTL).withAddedPage(existing);
        stubSession(session.token(), session);

        var dup = new PageInfo("page-002", "k2", "f.jpg", "image/jpeg", 1024, Instant.now(), null)
                .withContentHash("abc");

        StepVerifier.create(service.attachPage(session.token(), dup, MAX_PAGES, MAX_TOTAL_BYTES, true))
                .assertNext(attach -> {
                    assertThat(attach.accepted()).isFalse();
                    assertThat(attach.rejection()).isEqualTo(SessionService.AttachRejection.DUPLICATE);
                    // État inchangé : la page en doublon n'est pas ajoutée.
                    assertThat(attach.session().pages()).hasSize(1);
                })
                .verifyComplete();

        verify(sessionsMap).tryLock(eq(session.token()), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock(session.token());
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    @Test
    void attachPage_allowsDuplicateHash_whenRejectDuplicateDisabled() {
        // Chemin PDF (4-arguments = rejectDuplicate=false) : les pages d'un même
        // fichier source partagent l'empreinte et ne doivent PAS se rejeter entre
        // elles sous le verrou (sinon collision intra-lot).
        var existing = new PageInfo("page-001", "k1", "f.pdf", "application/pdf", 512, Instant.now(), null)
                .withContentHash("same");
        var session = UploadSession.create(PROFILE, SESSION_TTL).withAddedPage(existing);
        stubSession(session.token(), session);

        var next = new PageInfo("page-002", "k2", "f.pdf", "application/pdf", 512, Instant.now(), null)
                .withContentHash("same");

        StepVerifier.create(service.attachPage(session.token(), next, MAX_PAGES, MAX_TOTAL_BYTES))
                .assertNext(attach -> {
                    assertThat(attach.accepted()).isTrue();
                    assertThat(attach.session().pages()).hasSize(2);
                })
                .verifyComplete();

        var stored = captureStoredSession();
        assertThat(stored.pages()).hasSize(2);
    }

    @Test
    void attachPage_allowsDistinctHash_whenRejectDuplicateEnabled() {
        // Empreinte différente ⇒ pas un doublon : attache normale même en mode strict.
        var existing = new PageInfo("page-001", "k1", "f.jpg", "image/jpeg", 1024, Instant.now(), null)
                .withContentHash("hash-A");
        var session = UploadSession.create(PROFILE, SESSION_TTL).withAddedPage(existing);
        stubSession(session.token(), session);

        var other = new PageInfo("page-002", "k2", "g.jpg", "image/jpeg", 1024, Instant.now(), null)
                .withContentHash("hash-B");

        StepVerifier.create(service.attachPage(session.token(), other, MAX_PAGES, MAX_TOTAL_BYTES, true))
                .assertNext(attach -> {
                    assertThat(attach.accepted()).isTrue();
                    assertThat(attach.session().pages()).hasSize(2);
                })
                .verifyComplete();
    }

    // ===== removePage =====

    @Test
    void removePage_removesPageFromSession() throws Exception {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        stubSession("tok", session);

        StepVerifier.create(service.removePage("tok", "page-001"))
                .assertNext(result -> {
                    assertThat(result.session().pages()).hasSize(1);
                    assertThat(result.session().pages().getFirst().pageId()).isEqualTo("page-002");
                    // La page telle que le verrou l'a vue : c'est d'elle que l'appelant purge.
                    assertThat(result.removed()).isEqualTo(page1);
                })
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("tok"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("tok");
        var stored = captureStoredSession();
        assertThat(stored.pages()).hasSize(1);
    }

    @Test
    void removePage_emitsEmpty_whenSessionNotFound() throws Exception {
        when(sessionsMap.get("unknown")).thenReturn(null);

        StepVerifier.create(service.removePage("unknown", "page-001"))
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("unknown"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("unknown");
    }

    // ===== reorderPages =====

    @Test
    void reorderPages_reordersPages() throws Exception {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        stubSession("tok", session);

        StepVerifier.create(service.reorderPages("tok", List.of("page-002", "page-001")))
                .assertNext(result -> assertThat(result.pages()).extracting(PageInfo::pageId)
                        .containsExactly("page-002", "page-001"))
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("tok"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("tok");
    }

    @Test
    void reorderPages_emitsEmpty_whenSessionNotFound() throws Exception {
        when(sessionsMap.get("unknown")).thenReturn(null);

        StepVerifier.create(service.reorderPages("unknown", List.of()))
                .verifyComplete();

        verify(sessionsMap).tryLock(eq("unknown"), anyLong(), any(), anyLong(), any());
        verify(sessionsMap).unlock("unknown");
    }

    @Test
    void reorderPages_rejectsAListThatNoLongerMatchesTheSession() {
        // Une page attachée (ou supprimée) entre la lecture du contrôleur et le verrou :
        // la liste reçue ne couvre plus la session. Refus, rien n'est écrit — avant, la
        // page nouvelle disparaissait de la session, ou un null y était enregistré.
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var page3 = new PageInfo("page-003", "k3", "f3.jpg", "image/jpeg", 300, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2, page3), 3, false);
        stubSession("tok", session);

        StepVerifier.create(service.reorderPages("tok", List.of("page-002", "page-001")))
                .expectErrorSatisfies(e -> assertThat(((ch.ge.ael.geteleverse.controller.SessionConflictException) e)
                        .code()).isEqualTo(ch.ge.ael.geteleverse.controller.SessionConflictException.PAGES_CHANGED))
                .verify();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
        verify(sessionsMap).unlock("tok");
    }

    @Test
    void reorderPages_rejectsDuplicatedIds() {
        // Même taille mais ensemble différent, ou même ensemble mais une page répétée :
        // refus dans les deux cas.
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page1, page2), 2, false);
        stubSession("tok", session);

        StepVerifier.create(service.reorderPages("tok", List.of("page-001", "page-001")))
                .expectError(ch.ge.ael.geteleverse.controller.SessionConflictException.class)
                .verify();
        StepVerifier.create(service.reorderPages("tok", List.of("page-001", "page-002", "page-001")))
                .expectError(ch.ge.ael.geteleverse.controller.SessionConflictException.class)
                .verify();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    // ===== cycle de vie : mutations refusées hors session ouverte =====

    private static UploadSession withOnePage(ch.ge.ael.geteleverse.model.SessionStatus status, Instant until) {
        var page = new PageInfo("page-001", "tok/page-001.pdf", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        return new UploadSession("tok", "doc.pdf", PROFILE, Instant.now(), Instant.now().plusSeconds(900),
                List.of(page), 1, false, null, status, until);
    }

    private static String conflictCode(Throwable e) {
        return ((ch.ge.ael.geteleverse.controller.SessionConflictException) e).code();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ch.ge.ael.geteleverse.model.SessionStatus.class,
            names = {"FINALIZING", "FINALIZED"})
    void contentMutations_areRefused_onceFinalizationStarted(ch.ge.ael.geteleverse.model.SessionStatus status) {
        // La fusion lit la liste de pages figée par beginFinalize : une page ajoutée,
        // éditée, retirée ou déplacée après ce point était perdue en silence, ou purgée
        // sous le PDF en cours d'assemblage.
        var session = withOnePage(status, Instant.now().plusSeconds(60));
        stubSession("tok", session);
        var expected = status == ch.ge.ael.geteleverse.model.SessionStatus.FINALIZING
                ? ch.ge.ael.geteleverse.controller.SessionConflictException.SESSION_FINALIZING
                : ch.ge.ael.geteleverse.controller.SessionConflictException.SESSION_FINALIZED;
        var page = session.pages().getFirst();
        var newPage = new PageInfo("page-002", "tok/page-002.pdf", "g.jpg", "image/jpeg", 100, Instant.now(), null);

        StepVerifier.create(service.attachPage("tok", newPage, 10, 1_000_000))
                .expectErrorSatisfies(e -> assertThat(conflictCode(e)).isEqualTo(expected)).verify();
        StepVerifier.create(service.removePage("tok", "page-001"))
                .expectErrorSatisfies(e -> assertThat(conflictCode(e)).isEqualTo(expected)).verify();
        StepVerifier.create(service.reorderPages("tok", List.of("page-001")))
                .expectErrorSatisfies(e -> assertThat(conflictCode(e)).isEqualTo(expected)).verify();
        StepVerifier.create(service.commitPageEdit("tok", "page-001", page.lastModifiedAt(),
                        p -> p.withNewVersion("tok/page-001-aaaa.pdf", null, 100), 1_000_000))
                .expectErrorSatisfies(e -> assertThat(conflictCode(e)).isEqualTo(expected)).verify();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void contentMutations_resume_whenAFinalizationLeaseHasLapsed() {
        // Pod mort en pleine fusion : à l'échéance du bail, la session redevient
        // modifiable plutôt que de rester gelée jusqu'à son expiration.
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZING, Instant.now().minusSeconds(1));
        stubSession("tok", session);

        StepVerifier.create(service.removePage("tok", "page-001"))
                .assertNext(r -> assertThat(r.session().pages()).isEmpty())
                .verifyComplete();
    }

    // ===== commitPageEdit =====

    @Test
    void commitPageEdit_adoptsTheNewVersion_andReturnsBothSides() {
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.OPEN, null);
        var page = session.pages().getFirst();
        stubSession("tok", session);

        StepVerifier.create(service.commitPageEdit("tok", "page-001", page.lastModifiedAt(),
                        p -> p.withNewVersion("tok/page-001-3f2a9c1e.pdf", "tok/page-001-3f2a9c1e-thumb.jpg", 150),
                        1_000_000))
                .assertNext(commit -> {
                    assertThat(commit.before()).isEqualTo(page);
                    assertThat(commit.after().s3Key()).isEqualTo("tok/page-001-3f2a9c1e.pdf");
                    assertThat(commit.after().previousVersion().s3Key()).isEqualTo("tok/page-001.pdf");
                    assertThat(commit.session().findPage("page-001")).isEqualTo(commit.after());
                })
                .verifyComplete();

        var stored = captureStoredSession();
        assertThat(stored.findPage("page-001").s3Key()).isEqualTo("tok/page-001-3f2a9c1e.pdf");
        assertThat(stored.findPage("page-001").fileSize()).isEqualTo(150);
    }

    @Test
    void commitPageEdit_refuses_whenThePageWasDeletedMeanwhile() {
        // Avant : la mise à jour « réussissait » sur une page disparue — 200 « appliqué »,
        // objets S3 orphelins.
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.OPEN, null);
        stubSession("tok", session);

        StepVerifier.create(service.commitPageEdit("tok", "page-999", Instant.now(), p -> p, 1_000_000))
                .expectErrorSatisfies(e -> assertThat(conflictCode(e))
                        .isEqualTo(ch.ge.ael.geteleverse.controller.SessionConflictException.PAGE_GONE))
                .verify();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void commitPageEdit_refuses_whenThePageChangedSinceTheCallerReadIt() {
        // Deux éditions concurrentes de la même page : la seconde partait d'une version
        // déjà supplantée et écrasait la première.
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.OPEN, null);
        var page = session.pages().getFirst();
        stubSession("tok", session);

        StepVerifier.create(service.commitPageEdit("tok", "page-001", page.lastModifiedAt().minusMillis(1),
                        p -> p.withNewVersion("tok/page-001-aaaa.pdf", null, 100), 1_000_000))
                .expectErrorSatisfies(e -> assertThat(conflictCode(e))
                        .isEqualTo(ch.ge.ael.geteleverse.controller.SessionConflictException.PAGE_CHANGED))
                .verify();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void commitPageEdit_refuses_whenTheGrowthExceedsTheQuota() {
        // Session à 100 octets, quota 120 : une version de 150 le crève.
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.OPEN, null);
        var page = session.pages().getFirst();
        stubSession("tok", session);

        StepVerifier.create(service.commitPageEdit("tok", "page-001", page.lastModifiedAt(),
                        p -> p.withNewVersion("tok/page-001-aaaa.pdf", null, 150), 120))
                .expectErrorSatisfies(e -> {
                    assertThat(conflictCode(e))
                            .isEqualTo(ch.ge.ael.geteleverse.controller.SessionConflictException.SESSION_SIZE_EXCEEDED);
                    assertThat(((ch.ge.ael.geteleverse.controller.SessionConflictException) e).status())
                            .isEqualTo(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE);
                })
                .verify();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void commitPageEdit_admitsAShrinkingVersion_evenOverTheQuota() {
        // Une session déjà au-dessus du quota (quota abaissé entre-temps) doit pouvoir
        // réduire une page, pas seulement la supprimer.
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.OPEN, null);
        var page = session.pages().getFirst();
        stubSession("tok", session);

        StepVerifier.create(service.commitPageEdit("tok", "page-001", page.lastModifiedAt(),
                        p -> p.withNewVersion("tok/page-001-aaaa.pdf", null, 80), 50))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void commitPageEdit_admitsAGrowthExactlyUpToTheQuota() {
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.OPEN, null);
        var page = session.pages().getFirst();
        stubSession("tok", session);

        StepVerifier.create(service.commitPageEdit("tok", "page-001", page.lastModifiedAt(),
                        p -> p.withNewVersion("tok/page-001-aaaa.pdf", null, 150), 150))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ===== finalisation =====

    @Test
    void beginFinalize_freezesAnOpenSession_untilTheLeaseExpires() {
        var session = withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.OPEN, null);
        stubSession("tok", session);
        var before = Instant.now();

        StepVerifier.create(service.beginFinalize("tok", Duration.ofMinutes(3)))
                .assertNext(start -> {
                    assertThat(start.state()).isEqualTo(SessionService.FinalizeStart.State.STARTED);
                    assertThat(start.session().pages()).isEqualTo(session.pages());
                })
                .verifyComplete();

        var stored = captureStoredSession();
        assertThat(stored.status()).isEqualTo(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZING);
        assertThat(stored.statusUntil()).isBetween(before.plus(Duration.ofMinutes(3)),
                Instant.now().plus(Duration.ofMinutes(3)));
    }

    @Test
    void beginFinalize_reportsAFinalizationInProgress_withoutWriting() {
        // Double clic, ou second onglet : une seule fusion, pas deux PDF concurrents.
        stubSession("tok", withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZING, Instant.now().plusSeconds(60)));

        StepVerifier.create(service.beginFinalize("tok", Duration.ofMinutes(3)))
                .assertNext(start -> assertThat(start.state()).isEqualTo(SessionService.FinalizeStart.State.IN_PROGRESS))
                .verifyComplete();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void beginFinalize_reportsAnAlreadyFinalizedSession_soThatARetryGetsTheSameDocument() {
        stubSession("tok", withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZED, null));

        StepVerifier.create(service.beginFinalize("tok", Duration.ofMinutes(3)))
                .assertNext(start -> {
                    assertThat(start.state()).isEqualTo(SessionService.FinalizeStart.State.ALREADY_FINALIZED);
                    assertThat(start.session().documentId()).isEqualTo("doc.pdf");
                })
                .verifyComplete();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void beginFinalize_restarts_aFinalizationWhoseLeaseLapsed() {
        stubSession("tok", withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZING, Instant.now().minusSeconds(1)));

        StepVerifier.create(service.beginFinalize("tok", Duration.ofMinutes(3)))
                .assertNext(start -> assertThat(start.state()).isEqualTo(SessionService.FinalizeStart.State.STARTED))
                .verifyComplete();

        assertThat(captureStoredSession().statusUntil()).isAfter(Instant.now());
    }

    @Test
    void completeFinalize_marksTheSessionFinalized_andForgetsItsPages() {
        // Les pages sont fusionnées : une expiration ultérieure n'a plus rien à purger.
        stubSession("tok", withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZING, Instant.now().plusSeconds(60)));

        StepVerifier.create(service.completeFinalize("tok"))
                .assertNext(done -> {
                    assertThat(done.status()).isEqualTo(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZED);
                    assertThat(done.statusUntil()).isNull();
                    assertThat(done.pages()).isEmpty();
                })
                .verifyComplete();

        var stored = captureStoredSession();
        assertThat(stored.status()).isEqualTo(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZED);
        assertThat(stored.pages()).isEmpty();
    }

    @Test
    void abortFinalize_reopensAFinalizingSession() {
        stubSession("tok", withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZING, Instant.now().plusSeconds(60)));

        StepVerifier.create(service.abortFinalize("tok"))
                .assertNext(s -> assertThat(s.status()).isEqualTo(ch.ge.ael.geteleverse.model.SessionStatus.OPEN))
                .verifyComplete();

        var stored = captureStoredSession();
        assertThat(stored.status()).isEqualTo(ch.ge.ael.geteleverse.model.SessionStatus.OPEN);
        assertThat(stored.statusUntil()).isNull();
        assertThat(stored.pages()).hasSize(1);
    }

    @Test
    void abortFinalize_leavesAFinalizedSessionAlone() {
        // Une finalisation tardive déclarée échouée (timeout côté client) alors qu'elle a
        // abouti : le document existe, la session ne doit pas redevenir ouverte.
        stubSession("tok", withOnePage(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZED, null));

        StepVerifier.create(service.abortFinalize("tok"))
                .assertNext(s -> assertThat(s.status()).isEqualTo(ch.ge.ael.geteleverse.model.SessionStatus.FINALIZED))
                .verifyComplete();

        verify(sessionsMap, never()).put(anyString(), any(), anyLong(), any());
    }

    @Test
    void aSessionWrittenBeforeTheLifecycle_readsAsOpen() throws Exception {
        // Déploiement progressif : les sessions en vol n'ont pas de champ status.
        var legacy = """
                {"token":"tok","documentId":"doc.pdf","sizeProfile":"S","createdAt":"2026-09-24T10:00:00Z",
                 "expiresAt":"2026-09-24T10:15:00Z","pages":[],"pageCounter":0,"phoneConnected":false}""";

        var session = objectMapper.readValue(legacy, UploadSession.class);

        assertThat(session.status()).isEqualTo(ch.ge.ael.geteleverse.model.SessionStatus.OPEN);
        assertThat(session.statusUntil()).isNull();
    }

    // ===== subscribe =====

    /** Flux SSE ouvert pour {@code token} (session supposée présente). */
    private reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> stream(String token) {
        return service.subscribe(token).block(Duration.ofSeconds(5));
    }

    @Test
    void subscribe_emitsConnectedEvent_thenWaits() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(stream(session.token()).take(1))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("connected");
                    assertThat(sse.data()).contains("waiting");
                })
                .verifyComplete();
    }

    @Test
    void subscribe_loadsTheSessionOffTheCallingThread() {
        // Le contrôleur appelle subscribe sur une event loop Netty : l'IMap.get
        // bloquant ne doit jamais s'y exécuter.
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        var readThread = new AtomicReference<String>();
        when(sessionsMap.get(session.token())).thenAnswer(inv -> {
            readThread.set(Thread.currentThread().getName());
            return new HazelcastJsonValue(objectMapper.writeValueAsString(session));
        });

        var subscription = service.subscribe(session.token());
        assertThat(readThread.get()).as("rien n'est lu à l'assemblage").isNull();

        assertThat(subscription.block(Duration.ofSeconds(5))).isNotNull();
        assertThat(readThread.get()).startsWith("boundedElastic");
    }

    @Test
    void subscribe_returnsEmpty_whenSessionNotFound() {
        when(sessionsMap.get("missing")).thenReturn(null);

        StepVerifier.create(service.subscribe("missing"))
                .verifyComplete();
    }

    @Test
    void subscribe_returnsEmpty_whenSessionExpired() {
        var expired = new UploadSession("tok", "tok.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(), 0, false);
        stubSession("tok", expired);

        StepVerifier.create(service.subscribe("tok"))
                .verifyComplete();
    }

    @Test
    void subscribe_registersTheSinkOnlyWhenTheStreamIsSubscribed() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        var flux = stream(session.token());
        assertThat(service.localSinkCount()).as("flux jamais souscrit : rien à fuir").isZero();

        var disposable = flux.subscribe();
        assertThat(service.localSinkCount()).isEqualTo(1);

        disposable.dispose();
        assertThat(service.localSinkCount()).isZero();
    }

    @Test
    void reconnect_onTheSamePod_completesTheOldStreamAndKeepsTheNewOneAlive() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);
        var oldEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var oldCompleted = new java.util.concurrent.atomic.AtomicBoolean();
        var newEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var newCompleted = new java.util.concurrent.atomic.AtomicBoolean();

        var oldStream = stream(session.token())
                .subscribe(e -> oldEvents.add(e.event()), e -> {}, () -> oldCompleted.set(true));
        stream(session.token())
                .subscribe(e -> newEvents.add(e.event()), e -> {}, () -> newCompleted.set(true));
        // Annulation tardive de l'ancienne connexion (zombie coupé par un proxy) :
        // elle ne doit pas retirer le sink de la nouvelle.
        oldStream.dispose();
        service.sendHeartbeats();

        assertThat(oldCompleted).as("flux remplacé fermé").isTrue();
        assertThat(newCompleted).as("flux vivant intact").isFalse();
        assertThat(newEvents).containsExactly("connected", "heartbeat");
        assertThat(oldEvents).containsExactly("connected");
        assertThat(service.localSinkCount()).isEqualTo(1);
    }

    // ===== notifyUpload via ITopic =====

    @Test
    void notifyUpload_publishesToTopic() {
        var event = UploadEvent.completed("doc-123", "scan.pdf", "application/pdf", 1024L, "page-001", 0, 1, 1024L, 100_000L);

        service.notifyUpload("tok", event);

        verify(uploadTopic).publish(notificationCaptor.capture());
        var notification = notificationCaptor.getValue();
        assertThat(notification.token()).isEqualTo("tok");
        assertThat(notification.event()).isEqualTo(event);
    }

    // ===== ITopic listener pushes SSE to local sink =====

    @Test
    void topicListener_pushesEventToLocalSink() {
        verify(uploadTopic).addMessageListener(listenerCaptor.capture());
        var listener = listenerCaptor.getValue();

        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        var event = UploadEvent.completed("doc-456", "photo.jpg", "image/jpeg", 2048L, "page-001", 0, 1, 2048L, 100_000L);
        var notification = new UploadNotification(session.token(), event);
        @SuppressWarnings("unchecked")
        Message<UploadNotification> message = mock(Message.class);
        when(message.getMessageObject()).thenReturn(notification);

        StepVerifier.create(stream(session.token()).take(2))
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("connected"))
                .then(() -> listener.onMessage(message))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("upload-complete");
                    assertThat(sse.data()).contains("doc-456");
                })
                .verifyComplete();
    }

    @Test
    void topicListener_ignoresEvent_whenNoLocalSink() {
        verify(uploadTopic).addMessageListener(listenerCaptor.capture());
        var listener = listenerCaptor.getValue();

        var event = UploadEvent.completed("doc-789", "f.pdf", "application/pdf", 100L, "page-001", 0, 1, 100L, 100_000L);
        var notification = new UploadNotification("no-such-token", event);
        @SuppressWarnings("unchecked")
        Message<UploadNotification> message = mock(Message.class);
        when(message.getMessageObject()).thenReturn(notification);

        listener.onMessage(message);

        assertThat(service.localSinkCount()).isZero();
        verify(message).getMessageObject();
    }

    // ===== heartbeat =====

    @Test
    void sendHeartbeats_emitsHeartbeatToSubscribers() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(stream(session.token()).take(2))
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("connected"))
                .then(service::sendHeartbeats)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("heartbeat");
                    assertThat(sse.data()).isEqualTo("{}");
                })
                .verifyComplete();
    }

    @Test
    void sendHeartbeats_isNoOp_whenNoSinks() {
        // Aucun subscribe préalable → sinks vide. Doit rester silencieux.
        service.sendHeartbeats();

        assertThat(service.localSinkCount()).isZero();
    }

    // ===== cleanup =====

    private HazelcastJsonValue json(UploadSession session) {
        try {
            return new HazelcastJsonValue(objectMapper.writeValueAsString(session));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static UploadSession expiredVersionOf(UploadSession session, List<PageInfo> pages) {
        return new UploadSession(session.token(), "doc.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), pages, pages.size(), false);
    }

    @Test
    void cleanupExpiredSinks_completesOrphanedSinks_whenSessionGone() {
        // Session valide pour subscribe, puis supprimée de Hazelcast (TTL expiré)
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(stream(session.token()))
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("connected"))
                .then(() -> {
                    when(sessionsMap.getAll(Set.of(session.token()))).thenReturn(java.util.Map.of());
                    service.cleanupExpiredSinks();
                })
                .verifyComplete();
    }

    @Test
    void cleanupExpiredSinks_deletesOrphanPagesFromS3AndThumbnailsFromCache() {
        var page1 = new PageInfo("page-001", "u/f/t/page-001.pdf", "f1.jpg", "image/jpeg", 100, Instant.now(), "u/f/t/page-001-thumb.jpg");
        var page2 = new PageInfo("page-002", "u/f/t/page-002.pdf", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var valid = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(valid.token(), valid);

        StepVerifier.create(stream(valid.token()))
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("connected"))
                .then(() -> {
                    when(sessionsMap.getAll(Set.of(valid.token())))
                            .thenReturn(java.util.Map.of(valid.token(), json(expiredVersionOf(valid, List.of(page1, page2)))));
                    service.cleanupExpiredSinks();
                })
                .verifyComplete();

        // PDF supprimés de S3 en un lot, vignette rendue au cache — 2 clés S3 seulement :
        // page2 n'a pas de vignette, et celle de page1 ne passe plus par S3.
        verify(storageService).deleteAllBestEffort(List.of("u/f/t/page-001.pdf", "u/f/t/page-002.pdf"));
        verify(storageService, never()).deleteFromS3(anyString());
        verify(thumbnailCache).deleteAll(List.of("u/f/t/page-001-thumb.jpg"));
    }

    @Test
    void cleanupExpiredSinks_skipsCleanup_whenNoPagesInExpiredSession() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(stream(session.token()))
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("connected"))
                .then(() -> {
                    when(sessionsMap.getAll(Set.of(session.token())))
                            .thenReturn(java.util.Map.of(session.token(), json(expiredVersionOf(session, List.of()))));
                    service.cleanupExpiredSinks();
                })
                .verifyComplete();

        verifyNoInteractions(storageService);
    }

    @Test
    void cleanupExpiredSinks_readsAllSinksInOneBatchAndIsolatesAnUnreadableEntry() {
        // Deux flux ouverts : l'une des valeurs est illisible, l'autre expirée. Avant,
        // l'exception de la première interrompait le balayage de tout le pod, et chaque
        // sink coûtait un IMap.get bloquant.
        var broken = UploadSession.create(PROFILE, SESSION_TTL);
        var expiring = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(broken.token(), broken);
        stubSession(expiring.token(), expiring);
        var expiringCompleted = new java.util.concurrent.atomic.AtomicBoolean();
        stream(broken.token()).subscribe();
        stream(expiring.token()).subscribe(e -> {}, e -> {}, () -> expiringCompleted.set(true));

        when(sessionsMap.getAll(Set.of(broken.token(), expiring.token()))).thenReturn(java.util.Map.of(
                broken.token(), new HazelcastJsonValue("{not json"),
                expiring.token(), json(expiredVersionOf(expiring, List.of()))));

        service.cleanupExpiredSinks();

        assertThat(expiringCompleted).isTrue();
        assertThat(service.localSinkCount()).as("le sink illisible reste, pour le prochain passage").isEqualTo(1);
        verify(sessionsMap).getAll(anySet());
    }

    @Test
    void cleanupExpiredSinks_closesNothingWhenTheBatchReadFails() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);
        stream(session.token()).subscribe();
        when(sessionsMap.getAll(anySet())).thenThrow(new HazelcastException("cluster down"));

        service.cleanupExpiredSinks();

        assertThat(service.localSinkCount()).isEqualTo(1);
    }

    // ===== originator binding =====

    @Test
    void createSession_withOriginator_putsBindingThenSession() {
        when(originatorsMap.putIfAbsent(eq("10.0.0.1"), any(OriginatorBinding.class),
                anyLong(), any(TimeUnit.class))).thenReturn(null);

        var session = service.createSession(PROFILE, "10.0.0.1", false);

        assertThat(session.originatorIp()).isEqualTo("10.0.0.1");
        // Binding posé AVANT la session : si on inversait l'ordre une race
        // permettrait deux sessions sur la même IP entre putSession et putBinding.
        var inOrder = inOrder(originatorsMap, sessionsMap);
        inOrder.verify(originatorsMap).putIfAbsent(eq("10.0.0.1"), any(OriginatorBinding.class),
                eq(SESSION_TTL.toSeconds()), eq(TimeUnit.SECONDS));
        inOrder.verify(sessionsMap).put(eq(session.token()), any(HazelcastJsonValue.class),
                anyLong(), any());
    }

    @Test
    void createSession_throwsOriginatorBusy_whenBindingExists() {
        var existing = new OriginatorBinding("other-token", Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)));
        when(originatorsMap.putIfAbsent(eq("10.0.0.1"), any(OriginatorBinding.class),
                anyLong(), any(TimeUnit.class))).thenReturn(existing);

        assertThatThrownBy(() -> service.createSession(PROFILE, "10.0.0.1", false))
                .isInstanceOf(OriginatorBusyException.class)
                .satisfies(e -> assertThat(((OriginatorBusyException) e).existing().sessionToken())
                        .isEqualTo("other-token"));

        // Session JAMAIS écrite : on n'est pas allé jusqu'au put sessionsMap.
        verify(sessionsMap, never()).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());
    }

    @Test
    void createSession_compensatesBinding_whenSessionWriteFails() {
        when(originatorsMap.putIfAbsent(eq("10.0.0.1"), any(OriginatorBinding.class),
                anyLong(), any(TimeUnit.class))).thenReturn(null);
        doThrow(new RuntimeException("Hazelcast down"))
                .when(sessionsMap).put(anyString(), any(HazelcastJsonValue.class), anyLong(), any());

        assertThatThrownBy(() -> service.createSession(PROFILE, "10.0.0.1", false))
                .isInstanceOf(RuntimeException.class);

        // Sans cette compensation, l'IP serait bloquée jusqu'à expiration TTL
        // alors qu'aucune session n'existe.
        verify(originatorsMap).delete("10.0.0.1");
    }

    @Test
    void createSession_force_purgesExistingSessionFirst() {
        var existing = new OriginatorBinding("prior-token", Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)));
        when(originatorsMap.remove("10.0.0.1")).thenReturn(existing);
        // Le internal delete relit sessionsMap.remove pour la session bloquante.
        when(sessionsMap.remove("prior-token")).thenReturn(null);
        when(originatorsMap.putIfAbsent(eq("10.0.0.1"), any(OriginatorBinding.class),
                anyLong(), any(TimeUnit.class))).thenReturn(null);

        var session = service.createSession(PROFILE, "10.0.0.1", true);

        // Force-new : on a explicitement vidé le binding préexistant, puis on
        // a pu repasser un putIfAbsent qui ne rencontre plus de collision.
        var inOrder = inOrder(originatorsMap);
        inOrder.verify(originatorsMap).remove("10.0.0.1");
        inOrder.verify(originatorsMap).putIfAbsent(eq("10.0.0.1"), any(OriginatorBinding.class),
                anyLong(), any(TimeUnit.class));
        assertThat(session.originatorIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void createSession_withoutOriginator_skipsBindingMap() {
        // Feature désactivée par config (originatorIp = null côté contrôleur) :
        // aucune interaction avec l'IMap originator.
        var session = service.createSession(PROFILE, null, false);

        assertThat(session.originatorIp()).isNull();
        verifyNoInteractions(originatorsMap);
    }

    @Test
    void deleteSession_releasesOriginatorBinding() throws Exception {
        var sessionWithIp = new UploadSession("tok", "tok.pdf", PROFILE, Instant.now(),
                Instant.now().plusSeconds(900), List.of(), 0, false, "10.0.0.1");
        var json = new HazelcastJsonValue(objectMapper.writeValueAsString(sessionWithIp));
        when(sessionsMap.remove("tok")).thenReturn(json);

        StepVerifier.create(service.deleteSession("tok"))
                .assertNext(deleted -> assertThat(deleted).isTrue())
                .verifyComplete();

        verify(originatorsMap).delete("10.0.0.1");
    }

    @Test
    void deleteSession_returnsFalse_whenSessionAlreadyGone() {
        // Idempotence pour le navigator.sendBeacon qui peut tirer plusieurs fois.
        when(sessionsMap.remove("missing")).thenReturn(null);

        StepVerifier.create(service.deleteSession("missing"))
                .assertNext(deleted -> assertThat(deleted).isFalse())
                .verifyComplete();

        verifyNoInteractions(originatorsMap);
    }

    @Test
    void releaseOriginator_isNoOp_forSessionWithoutIp() {
        var sessionNoIp = UploadSession.create(PROFILE, SESSION_TTL);

        service.releaseOriginator(sessionNoIp);

        verifyNoInteractions(originatorsMap);
    }

    @Test
    void releaseOriginator_deletesBinding_whenSessionHasIp() {
        var sessionWithIp = UploadSession.create(PROFILE, SESSION_TTL, "10.0.0.1");

        service.releaseOriginator(sessionWithIp);

        verify(originatorsMap).delete("10.0.0.1");
    }

    @Test
    void withSessionLock_refreshesOriginatorTtl_afterMutation() {
        // Vérifie que toute mutation (renewSession ici) aligne le TTL du binding
        // sur celui de la session. Sans cela, une session active dont le binding
        // aurait expiré laisserait passer une 2e session sur la même IP.
        var session = UploadSession.create(PROFILE, SESSION_TTL, "10.0.0.1");
        stubSession(session.token(), session);

        StepVerifier.create(service.renewSession(session.token()))
                .expectNextCount(1)
                .verifyComplete();

        verify(originatorsMap).setTtl("10.0.0.1", SESSION_TTL.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void withSessionLock_skipsTtlRefresh_whenOriginatorIpIsNull() {
        // Session héritée d'avant la feature, ou feature désactivée : pas d'IP
        // attachée → aucune touche à l'IMap originator.
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(service.renewSession(session.token()))
                .expectNextCount(1)
                .verifyComplete();

        verify(originatorsMap, never()).setTtl(anyString(), anyLong(), any(TimeUnit.class));
    }

    // ===== destroy =====

    @Test
    void destroy_removesListenerAndCompletesSinks() {
        var session = UploadSession.create(PROFILE, SESSION_TTL);
        stubSession(session.token(), session);

        StepVerifier.create(stream(session.token()))
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("connected"))
                .then(service::destroy)
                .verifyComplete();

        verify(uploadTopic).removeMessageListener(any(UUID.class));
    }
}

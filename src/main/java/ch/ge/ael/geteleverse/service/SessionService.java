package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.controller.OriginatorBusyException;
import ch.ge.ael.geteleverse.controller.SessionConflictException;
import ch.ge.ael.geteleverse.model.OriginatorBinding;
import ch.ge.ael.geteleverse.model.PageInfo;
import ch.ge.ael.geteleverse.model.SessionStatus;
import ch.ge.ael.geteleverse.model.SizeProfile;
import ch.ge.ael.geteleverse.model.UploadEvent;
import ch.ge.ael.geteleverse.model.UploadSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryExpiredListener;
import com.hazelcast.topic.ITopic;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final HazelcastInstance hazelcast;
    private final ObjectMapper objectMapper;
    private final Duration sessionTtl;
    /** Attente max du verrou de session avant abandon (429 SESSION_BUSY). */
    private final Duration lockWait;
    /** Bail du verrou de session : garantit sa libération même si le détenteur meurt. */
    private final Duration lockLease;
    /** Retry-After (s) annoncé au client quand le verrou n'a pas pu être pris. */
    private final long lockRetryAfterSeconds;
    private final StorageService storageService;
    private final ThumbnailCacheService thumbnailCache;

    private IMap<String, HazelcastJsonValue> sessionsMap;
    private IMap<String, OriginatorBinding> originatorsMap;
    /**
     * Élection du purgeur d'une session : {@code token → identifiant du pod}, TTL par
     * entrée. Types natifs uniquement (aucune classe applicative côté membres), et le
     * client peut poser le TTL entrée par entrée même sans pouvoir déclarer la map.
     */
    private IMap<String, String> purgeClaims;
    private ITopic<UploadNotification> uploadTopic;
    private UUID topicListenerId;
    private UUID expiredListenerId;
    private UUID evictedListenerId;

    private final ConcurrentHashMap<String, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

    /**
     * Latche la transition online → offline pour les jauges Hazelcast : un seul
     * WARN par disparition du cluster, un seul INFO au retour. Sinon chaque scrape
     * Prometheus (toutes les ~15 s) reloggue la stack complète de {@code IMap.size()},
     * en plus d'une seconde stack du client Hazelcast 5.4.0 sur le double-complete
     * de Future post-interrupt.
     */
    private final AtomicBoolean hazelcastOffline = new AtomicBoolean(false);

    /**
     * Événement émis périodiquement sur chaque sink SSE actif pour forcer la
     * détection des connexions zombies (proxies d'entreprise coupent l'idle
     * HTTP sans propager le FIN). Immuable, partageable entre tous les sinks.
     */
    private static final ServerSentEvent<String> HEARTBEAT_EVENT = ServerSentEvent.<String>builder()
            .event("heartbeat")
            .data("{}")
            .build();

    /** Premier événement de tout flux SSE, émis à son ouverture. */
    private static final ServerSentEvent<String> CONNECTED_EVENT = ServerSentEvent.<String>builder()
            .event("connected")
            .data("{\"status\":\"waiting\"}")
            .build();

    /**
     * Réessai d'une émission SSE en concurrence avec une autre (cf. {@link #emit}) :
     * une émission concurrente dure quelques microsecondes, 100 ms couvrent très
     * largement un thread préempté sans immobiliser l'émetteur.
     */
    private static final Sinks.EmitFailureHandler EMIT_RETRY =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));

    /** Taille des lots de lecture du balayage des sinks ({@link #cleanupExpiredSinks}). */
    private static final int SWEEP_BATCH_SIZE = 100;

    /**
     * Durée de vie d'une revendication de purge. Couvre largement la purge elle-même et
     * la fenêtre où les autres pods reçoivent le même événement d'expiration ; au-delà,
     * le token (UUID) n'a plus aucune chance de réapparaître.
     */
    private static final Duration PURGE_CLAIM_TTL = Duration.ofMinutes(10);

    /** Identifiant de ce pod dans les revendications de purge (diagnostic uniquement). */
    private final String podId = UUID.randomUUID().toString();

    public SessionService(
            HazelcastInstance hazelcast,
            ObjectMapper objectMapper,
            @Value("${app.session-ttl:PT15M}") Duration sessionTtl,
            @Value("${app.session-lock.wait:PT2S}") Duration lockWait,
            @Value("${app.session-lock.lease:PT30S}") Duration lockLease,
            @Value("${app.session-lock.retry-after:PT1S}") Duration lockRetryAfter,
            StorageService storageService,
            ThumbnailCacheService thumbnailCache
    ) {
        this.hazelcast = hazelcast;
        this.objectMapper = objectMapper;
        this.sessionTtl = sessionTtl;
        this.lockWait = lockWait;
        this.lockLease = lockLease;
        this.lockRetryAfterSeconds = Math.max(1, lockRetryAfter.toSeconds());
        this.storageService = storageService;
        this.thumbnailCache = thumbnailCache;
    }

    /**
     * Notification record sent through Hazelcast ITopic to broadcast upload events across pods.
     */
    public record UploadNotification(String token, UploadEvent event) implements Serializable {}

    /**
     * Réservation atomique d'un slot de page : {@code pageId}, clés S3
     * (page et vignette), et nouvelle valeur de {@code pageCounter}.
     * Retournée par {@link #reservePageId(String)} ; le caller stocke ensuite
     * l'objet S3 sous {@link #pageKey()} et appelle {@link #attachPage(String, PageInfo, int, long)}.
     */
    public record Reservation(String pageId, String pageKey, String thumbnailKey, int pageCounter) {}

    /** Motif de refus d'un attachement vérifié sous le verrou par {@link #attachPage}. */
    public enum AttachRejection {
        /** Limite du nombre de pages ({@code app.limits.max-pages}) atteinte. */
        PAGE_LIMIT,
        /** Quota cumulé d'octets du profil de taille dépassé. */
        SIZE_LIMIT,
        /**
         * Une page portant la même empreinte de contenu brut est déjà présente
         * dans la session : re-upload octet pour octet du même fichier (anti-spam).
         * Vérifié sous le verrou uniquement quand {@code rejectDuplicate} est
         * demandé (cf. {@link #attachPage(String, PageInfo, int, long, boolean)}).
         */
        DUPLICATE
    }

    /**
     * Résultat d'un attachement de page : succès, ou refus avec son motif
     * ({@link #rejection}, non-null seulement quand {@code accepted == false}).
     * Le champ {@code session} reflète l'état post-attachement en cas de succès,
     * l'état pré-attachement en cas de refus. Quand {@link #reservePageId} et
     * {@link #attachPage} retournent {@link reactor.core.publisher.Mono#empty()},
     * c'est la session qui a disparu (TTL Hazelcast ou expiration logique) —
     * distinct d'un refus quota.
     */
    public record AttachResult(boolean accepted, UploadSession session, AttachRejection rejection) {
        /**
         * Compat : attachement accepté ({@code rejection} null) ou, si
         * {@code accepted == false}, refus quota taille par défaut. Utilisé par
         * le code et les tests qui n'ont pas à distinguer le motif de refus.
         */
        public AttachResult(boolean accepted, UploadSession session) {
            this(accepted, session, accepted ? null : AttachRejection.SIZE_LIMIT);
        }
    }

    /**
     * Édition de page adoptée sous le verrou : la session écrite, la page telle
     * qu'elle était ({@code before}) et telle qu'elle est désormais ({@code after}).
     * Les clés que {@code after} ne référence plus sont à purger par l'appelant
     * ({@link PageInfo#s3KeysDroppedBy}).
     */
    public record PageCommit(UploadSession session, PageInfo before, PageInfo after) {}

    /**
     * Suppression de page faite sous le verrou. {@code removed} est la page telle
     * qu'elle était À CET INSTANT — ses clés courantes, éventuellement changées par
     * une édition depuis la lecture de l'appelant — ou null si elle avait déjà disparu.
     */
    public record PageRemoval(UploadSession session, PageInfo removed) {}

    /**
     * Issue de {@link #beginFinalize} : fusion à lancer sur {@code session} (figée en
     * {@link SessionStatus#FINALIZING}), finalisation déjà en cours ailleurs, ou déjà
     * faite — auquel cas {@code session.documentId()} est la réponse.
     */
    public record FinalizeStart(State state, UploadSession session) {
        public enum State { STARTED, IN_PROGRESS, ALREADY_FINALIZED }
    }

    /**
     * Vrai si {@code error} garantit que la mutation n'a RIEN écrit dans la session :
     * refus sous le verrou ({@link SessionConflictException}, levée avant l'écriture)
     * ou verrou jamais obtenu ({@link SessionLockTimeoutException}). L'appelant peut
     * alors défaire sans risque les objets S3 écrits pour cette opération. Toute autre
     * erreur (cluster en panne pendant l'écriture…) laisse l'issue inconnue : mieux vaut
     * un objet orphelin qu'une page vidée de son contenu.
     */
    public static boolean isNotApplied(Throwable error) {
        return error instanceof SessionConflictException || error instanceof SessionLockTimeoutException;
    }

    /**
     * Refuse une mutation de contenu sur une session qui n'est plus ouverte. À appeler
     * DANS la section verrouillée : c'est la seule lecture qui fasse foi.
     */
    private static void requireOpen(UploadSession session) {
        switch (session.effectiveStatus(Instant.now())) {
            case OPEN -> { /* admis */ }
            case FINALIZING -> throw SessionConflictException.sessionFinalizing();
            case FINALIZED -> throw SessionConflictException.sessionFinalized();
        }
    }

    @PostConstruct
    void init() {
        sessionsMap = hazelcast.getMap("televerse-sessions");
        originatorsMap = hazelcast.getMap("televerse-originators");
        purgeClaims = hazelcast.getMap("televerse-purge-claims");
        uploadTopic = hazelcast.getTopic("televerse-uploads");

        topicListenerId = uploadTopic.addMessageListener(message -> {
            var notification = message.getMessageObject();
            var sink = sinks.get(notification.token());
            if (sink == null) return;

            try {
                var json = objectMapper.writeValueAsString(notification.event());
                var sse = ServerSentEvent.<String>builder()
                        .event(notification.event().type())
                        .data(json)
                        .build();
                emit(notification.token(), sink, sse);
            } catch (JacksonException e) {
                log.error("Event serialization failed token={}", notification.token(), e);
            }
        });

        // Filet pour les sessions qui meurent en silence (onglet tué sans
        // DELETE, plus aucune requête ni sink SSE) : leur entrée est purgée
        // par le TTL Hazelcast et la liste des clés S3 disparaîtrait avec
        // elle — pages orphelines définitives. On intercepte l'expiration TTL
        // (entryExpired) et l'éviction mémoire (entryEvicted, par précaution)
        // avec l'ancienne valeur pour purger les objets S3. En mode client
        // (prod), chaque pod reçoit l'événement : un seul purge, celui qui
        // remporte la revendication (cf. cleanupOrphanPages). Ces événements
        // ne sont pas rares — toute session non supprimée explicitement finit
        // ainsi — et une purge par pod multipliait la charge S3 d'autant. Le
        // binding originator n'est volontairement PAS touché :
        // son TTL aligné l'évince de lui-même, et le supprimer ici pourrait
        // tuer le binding d'une session successeure créée entre-temps sur la
        // même IP. Les suppressions explicites (remove/delete) n'émettent pas
        // ces événements — pas de double purge avec les chemins existants.
        expiredListenerId = sessionsMap.addEntryListener(
                (EntryExpiredListener<String, HazelcastJsonValue>) this::cleanupEvictedEntry, true);
        evictedListenerId = sessionsMap.addEntryListener(
                (EntryEvictedListener<String, HazelcastJsonValue>) this::cleanupEvictedEntry, true);
    }

    /**
     * Purge S3 d'une session évincée par Hazelcast (TTL ou pression mémoire)
     * sans être passée par un chemin de suppression explicite. Best-effort :
     * une valeur illisible est loguée et l'éviction suit son cours (le
     * lifecycle S3 côté ops reste le filet ultime).
     */
    private void cleanupEvictedEntry(EntryEvent<String, HazelcastJsonValue> event) {
        var json = event.getOldValue();
        if (json == null) return;
        try {
            log.info("Session evicted by Hazelcast token={}", event.getKey());
            cleanupOrphanPages(fromJson(json));
        } catch (RuntimeException e) {
            log.warn("Eviction cleanup failed token={} error={}", event.getKey(), e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        if (topicListenerId != null) {
            uploadTopic.removeMessageListener(topicListenerId);
        }
        if (sessionsMap != null) {
            if (expiredListenerId != null) sessionsMap.removeEntryListener(expiredListenerId);
            if (evictedListenerId != null) sessionsMap.removeEntryListener(evictedListenerId);
        }
        sinks.values().forEach(Sinks.Many::tryEmitComplete);
        sinks.clear();
    }

    /**
     * Nombre de sessions actives dans l'IMap Hazelcast (vue cluster-wide).
     * Exposé en gauge Prometheus — chaque pod retourne le même total puisque
     * l'IMap est distribué. Retourne -1 si Hazelcast est injoignable
     * (cf. {@link #safeMapSize}).
     */
    public int activeSessionCount() {
        return safeMapSize(sessionsMap);
    }

    /**
     * Nombre de bindings « une IP d'origine → un token » actifs (vue cluster-wide).
     * Doit suivre la courbe de {@link #activeSessionCount()} à un facteur près
     * lié aux sessions héritées d'avant l'activation de la feature. Retourne -1
     * si Hazelcast est injoignable.
     */
    public int activeOriginatorBindingCount() {
        return safeMapSize(originatorsMap);
    }

    /**
     * Lecture défensive de la taille d'un IMap pour le chemin des jauges
     * Prometheus. Quand le cluster Hazelcast disparaît (downtime planifié en dev,
     * blip réseau en prod), {@code IMap.size()} lève {@link HazelcastException}.
     * Sans ce filet, Micrometer relogue la stack complète à chaque scrape via
     * {@code DefaultGauge.value()}, plus une seconde stack interne au client
     * Hazelcast 5.4.0 (race sur {@code Future.complete} post-interrupt).
     *
     * <p>Valeur sentinelle {@code -1} plutôt que {@code 0} : permet de distinguer
     * « cluster vide » de « cluster injoignable » côté Grafana / alerting. Le
     * passage en offline et le retour en ligne sont chacun logués <b>une seule
     * fois</b> via {@link #hazelcastOffline}.</p>
     *
     * <p>Filet réservé aux jauges : les opérations métier ({@code POST /sessions},
     * upload, etc.) continuent de propager {@link HazelcastException} normalement
     * pour que l'utilisateur reçoive un 503 et que le retry kick in.</p>
     */
    private int safeMapSize(IMap<?, ?> map) {
        if (map == null) return 0;
        try {
            int size = map.size();
            if (hazelcastOffline.compareAndSet(true, false)) {
                log.info("Hazelcast reconnecté — les jauges actives reprennent");
            }
            return size;
        } catch (HazelcastException e) {
            if (hazelcastOffline.compareAndSet(false, true)) {
                log.warn("Hazelcast injoignable — les jauges actives retournent -1 jusqu'à reconnexion (cause: {})",
                        rootMessage(e));
            }
            return -1;
        }
    }

    private static String rootMessage(Throwable t) {
        var cause = t.getCause() != null ? t.getCause() : t;
        var msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }

    /**
     * Nombre de connexions SSE actives locales à ce pod. Complémentaire de
     * {@link #activeSessionCount()} qui est cluster-wide ; utile pour observer
     * la répartition des consommateurs SSE par pod côté load-balancer.
     */
    public int localSinkCount() {
        return sinks.size();
    }

    // ===== Session CRUD =====

    public UploadSession createSession(SizeProfile sizeProfile) {
        return createSession(sizeProfile, null, false);
    }

    /**
     * Crée une session, et lie atomiquement l'IP d'origine si elle est fournie.
     *
     * <p>Quand {@code originatorIp} est non-null, le binding est posé via
     * {@link IMap#putIfAbsent(Object, Object, long, TimeUnit)} : opération
     * atomique cluster-wide (sérialisée par le partition owner Hazelcast). Si
     * un binding existe déjà pour cette IP, on lève
     * {@link OriginatorBusyException} avec le binding actuel — le contrôleur
     * traduit en {@code 409 ORIGINATOR_HAS_ACTIVE_SESSION}.</p>
     *
     * <p>Si l'écriture dans {@code sessionsMap} échoue après le succès du
     * {@code putIfAbsent}, on supprime le binding en compensation pour ne pas
     * laisser l'IP bloquée jusqu'à expiration TTL sans session associée.</p>
     *
     * @param originatorIp IP d'origine (XFF first-hop, déjà normalisée) ou
     *                     {@code null} si la feature est désactivée par
     *                     {@code app.originator.enabled=false}.
     * @param force        si {@code true} et qu'un binding existe pour cette IP,
     *                     la session liée est purgée avant que la nouvelle soit
     *                     créée (utilisé par le front sur action explicite
     *                     « j'ai fermé l'autre fenêtre »).
     */
    public UploadSession createSession(SizeProfile sizeProfile, String originatorIp, boolean force) {
        if (originatorIp == null) {
            var session = UploadSession.create(sizeProfile, sessionTtl, null);
            sessionsMap.put(session.token(), toJson(session), sessionTtl.toSeconds(), TimeUnit.SECONDS);
            log.debug("Session created token={} sizeProfile={} originator=disabled",
                    session.token(), session.sizeProfile());
            return session;
        }

        if (force) {
            var prior = originatorsMap.remove(originatorIp);
            if (prior != null) {
                log.info("Originator force-new : releasing prior session token={} originator={}",
                        prior.sessionToken(), originatorIp);
                deleteSessionInternal(prior.sessionToken());
            }
        }

        var session = UploadSession.create(sizeProfile, sessionTtl, originatorIp);
        var binding = new OriginatorBinding(session.token(), session.createdAt(), session.expiresAt());

        var existing = originatorsMap.putIfAbsent(originatorIp, binding,
                sessionTtl.toSeconds(), TimeUnit.SECONDS);
        if (existing != null) {
            log.info("Originator busy originator={} existingToken={} expiresAt={}",
                    originatorIp, existing.sessionToken(), existing.expiresAt());
            throw new OriginatorBusyException(existing);
        }

        try {
            sessionsMap.put(session.token(), toJson(session), sessionTtl.toSeconds(), TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            // Compensation : sans la session, le binding n'est associé à rien et bloquerait
            // l'IP jusqu'à expiration TTL. Mieux vaut libérer immédiatement.
            originatorsMap.delete(originatorIp);
            throw e;
        }
        log.debug("Session created token={} sizeProfile={} originator={}",
                session.token(), session.sizeProfile(), originatorIp);
        return session;
    }

    public UploadSession getSession(String token) {
        var json = sessionsMap.get(token);
        if (json == null) return null;

        var session = fromJson(json);
        if (session.isExpired()) {
            // Purge complète immédiate : sans cleanupOrphanPages ici, la liste
            // des clés S3 disparaissait avec l'entrée et les objets restaient
            // orphelins pour toujours (le sweeper, ne voyant plus que
            // json == null, ne peut plus rien purger). cleanupSink ferme aussi
            // le flux SSE local sans attendre le prochain passage du sweeper.
            cleanupOrphanPages(session);
            removeSessionAndOriginator(token, session);
            cleanupSink(token);
            return null;
        }
        return session;
    }

    /**
     * Variante non bloquante de {@link #createSession(SizeProfile, String, boolean)},
     * à utiliser depuis les contrôleurs.
     *
     * <p>La création écrit dans deux {@code IMap} Hazelcast — {@code putIfAbsent}
     * du binding originator puis {@code put} de la session — soit deux allers-retours
     * <b>bloquants</b> vers le cluster, et jusqu'à cinq sur le chemin
     * {@code force=true}, qui purge d'abord le binding et la session antérieurs.
     * Exécutés sur un thread d'event loop Netty, ils y gelaient toutes les autres
     * connexions rattachées à cette boucle : les flux SSE, dont les heartbeats,
     * comme les phases de lecture / écriture des requêtes d'upload et d'édition —
     * lesquelles ont beau déporter leur traitement, elles restent tributaires de
     * l'event loop pour leurs octets. Et la création de session arrive typiquement
     * par vagues synchronisées (ouverture d'un guichet), donc plusieurs boucles à
     * la fois.</p>
     *
     * <p>{@link OriginatorBusyException} et les échecs Hazelcast remontent en
     * signal d'erreur, traduits comme avant par {@code GlobalExceptionHandler}.
     * Même contrat que {@link #deleteSession(String)}, déjà sur ce modèle.</p>
     */
    public Mono<UploadSession> createSessionAsync(SizeProfile sizeProfile, String originatorIp, boolean force) {
        return Mono.fromCallable(() -> createSession(sizeProfile, originatorIp, force))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Variante non bloquante de {@link #getSession(String)}, à utiliser depuis
     * tout code appelé sur un thread d'event loop Netty — c'est-à-dire depuis
     * les contrôleurs.
     *
     * <p>{@code getSession} fait un {@code IMap.get} sur le client Hazelcast :
     * un aller-retour réseau <b>bloquant</b> vers le cluster. Appelé directement
     * dans le corps d'une méthode de contrôleur, il s'exécutait sur un thread
     * {@code reactor-http-nio-*}, dont le pod ne compte qu'une poignée (un par
     * vCPU). Un cluster momentanément lent y bloquait donc les event loops
     * elles-mêmes : les requêtes déjà acceptées n'avançaient plus, et les
     * nouvelles n'étaient même plus <i>lues</i> — donc ni servies, ni tracées,
     * pas même par le filtre de log HTTP qui écrit sa ligne d'entrée sur ce même
     * thread. Vu du navigateur : un appel « pending » sans la moindre trace côté
     * pod.</p>
     *
     * <p>Émet {@link Mono#empty()} quand la session est absente ou expirée —
     * l'appelant discrimine ce cas, {@code getSession} renvoyant {@code null}.</p>
     */
    public Mono<UploadSession> getSessionAsync(String token) {
        return Mono.fromCallable(() -> getSession(token))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Renouvelle l'expiry de la session sous verrou (appelé à chaque activité
     * utilisateur explicite — bouton « rester connecté »). Les autres mutations
     * ({@link #attachPage}, {@link #removePage}, etc.) renouvellent déjà
     * l'expiry dans leur propre section verrouillée ; appeler {@code renewSession}
     * après l'une d'elles est inutile (et nuisible avant le passage sous verrou,
     * cf. historique du fix race {@code metro.jpg}).
     *
     * @return un {@link Mono} qui émet la session renouvelée, ou {@link Mono#empty()}
     *         si la session a disparu / expiré.
     */
    public Mono<UploadSession> renewSession(String token) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            var renewed = session.withRenewedExpiry(sessionTtl);
            return new LockedResult<>(renewed, renewed);
        });
    }

    /**
     * Marque le téléphone comme connecté à la session et renouvelle l'expiry,
     * le tout sous verrou Hazelcast pour éviter de clobber un attachement
     * concurrent (cf. {@link #attachPage}).
     */
    public Mono<UploadSession> phoneConnect(String token) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            var updated = session.withPhoneConnected(true).withRenewedExpiry(sessionTtl);
            return new LockedResult<>(updated, updated);
        });
    }

    /**
     * Marque le téléphone comme déconnecté de la session et renouvelle l'expiry
     * sous verrou. Mêmes propriétés que {@link #phoneConnect}.
     */
    public Mono<UploadSession> phoneDisconnect(String token) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            var updated = session.withPhoneConnected(false).withRenewedExpiry(sessionTtl);
            return new LockedResult<>(updated, updated);
        });
    }

    // ===== Gestion des pages =====

    /**
     * Réserve atomiquement le prochain {@code pageId} d'une session : incrémente
     * {@code pageCounter} et retourne les identifiants S3 correspondants.
     * À appeler AVANT le stockage S3 — la {@link PageInfo} finale est ensuite
     * rattachée via {@link #attachPage(String, PageInfo, int, long)}.
     *
     * <p>Atomicité fournie par {@link com.hazelcast.map.IMap#lockAsync(Object)} :
     * Hazelcast sérialise le read-modify-write sur la même clé entre tous les
     * pods, donc deux uploads concurrents obtiennent des {@code pageId} distincts.
     * Préféré aux EntryProcessor parce qu'il ne nécessite pas que les classes
     * applicatives soient présentes sur le classpath des membres du cluster.
     *
     * <p>Toute la séquence {@code lock → get → put → unlock} est non-bloquante :
     * chaîne de {@link reactor.core.publisher.Mono Mono} composés via
     * {@link reactor.core.publisher.Mono#usingWhen}, qui garantit que l'unlock
     * s'exécute aussi sur erreur ou annulation amont (client qui ferme la
     * connexion mid-upload).
     *
     * <p>Sémantique de gap : si le stockage S3 échoue après la réservation, le
     * compteur reste incrémenté et il y a un trou dans la numérotation (ex.
     * {@code page-001} puis {@code page-003}). Acceptable : les {@code pageId}
     * restent uniques et l'ordre lexicographique est préservé.
     *
     * @return un {@link Mono} qui émet la réservation, ou {@link Mono#empty()}
     *         si la session n'existe plus ou a expiré.
     */
    public Mono<Reservation> reservePageId(String token) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            // Pas d'upload dans une session en finalisation : la page serait absente du
            // document final alors que le téléphone recevrait un 200.
            requireOpen(session);
            // nextPageId() lit pageCounter + 1, donc reflète déjà le slot qui va
            // être réservé par withBumpedCounter() ci-dessous.
            var reservation = new Reservation(
                    session.nextPageId(),
                    session.nextPageKey(),
                    session.nextThumbnailKey(),
                    session.pageCounter() + 1
            );
            // Renouvellement de l'expiry intégré au verrou : un appel séparé à
            // renewSession() après coup était un read-modify-write non-atomique
            // qui pouvait rembobiner l'état (clobber d'une page attachée par un
            // upload concurrent). Toute mutation passe désormais par cette
            // section verrouillée.
            var bumped = session.withBumpedCounter().withRenewedExpiry(sessionTtl);
            return new LockedResult<>(bumped, reservation);
        });
    }

    /**
     * Rattache atomiquement une {@link PageInfo} à la session après écriture
     * S3 réussie, en vérifiant le quota cumulé sous le verrou. Ne touche pas à
     * {@code pageCounter} (déjà incrémenté par {@link #reservePageId(String)}).
     *
     * <p>Les deux limites — nombre de pages et quota d'octets — sont vérifiées
     * dans la section verrouillée pour fermer la même race historique : deux (ou
     * N) uploads pouvaient lire un état conforme via {@code getSession()}, puis
     * attacher en parallèle et faire franchir le cap. Le pré-contrôle du
     * contrôleur ({@code pages().size() >= maxPages}) lit un snapshot hors
     * verrou ; une multi-sélection envoie N requêtes qui le franchissent toutes
     * avant que la première n'attache. Seul ce check sérialisé (lecture +
     * comparaison + écriture sous le même verrou) empêche le dépassement.
     *
     * @param maxPages      nombre maximal de pages autorisé pour la session.
     * @param maxTotalBytes cap cumulé du profil de taille de la session.
     * @return un {@link Mono} qui émet :
     *         <ul>
     *           <li>{@code AttachResult(accepted=true, sessionMiseÀJour, null)} si
     *               la page passe les deux limites,</li>
     *           <li>{@code AttachResult(accepted=false, sessionInchangée, motif)}
     *               si une limite aurait été franchie ({@link AttachRejection#PAGE_LIMIT}
     *               prioritaire sur {@link AttachRejection#SIZE_LIMIT}),</li>
     *           <li>{@link Mono#empty()} si la session a expiré entre la
     *               réservation et l'attachement.</li>
     *         </ul>
     */
    public Mono<AttachResult> attachPage(String token, PageInfo page, int maxPages, long maxTotalBytes) {
        return attachPage(token, page, maxPages, maxTotalBytes, false);
    }

    /**
     * Variante d'{@link #attachPage(String, PageInfo, int, long)} qui, quand
     * {@code rejectDuplicate} est vrai, refuse aussi sous le verrou une page dont
     * l'empreinte de contenu brut ({@link PageInfo#contentHash()}) est déjà
     * présente dans la session — re-upload octet pour octet du même fichier
     * (anti-spam). Le contrôle est fait <em>avant</em> les limites de pages/taille :
     * un re-upload sur une session pleine reste un doublon, pas un dépassement.
     *
     * <p>Réservé au chemin image (un upload = une page) : la vérification y est
     * exempte de collision intra-lot. Le split PDF multi-pages attache plusieurs
     * pages partageant la même empreinte source et passe donc par la variante
     * 4-arguments ({@code rejectDuplicate=false}) ; ses doublons sont interceptés
     * en amont par le pré-contrôle hors verrou du contrôleur.
     *
     * @param rejectDuplicate active le refus {@link AttachRejection#DUPLICATE}.
     */
    public Mono<AttachResult> attachPage(String token, PageInfo page, int maxPages, long maxTotalBytes,
                                         boolean rejectDuplicate) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            // Même garde qu'à la réservation : la finalisation a pu démarrer entre les
            // deux (l'appelant défait alors l'objet S3, cf. isNotApplied).
            requireOpen(session);
            // Doublon d'abord : un re-upload à l'identique est un doublon même si
            // la session est par ailleurs pleine. On n'écrit rien (sessionToPut =
            // null) ; le contrôleur construit la 429 DUPLICATE_PAGE et rollback
            // l'objet S3 déjà stocké par cet upload concurrent.
            if (rejectDuplicate && page.contentHash() != null
                    && session.pages().stream().anyMatch(p -> page.contentHash().equals(p.contentHash()))) {
                return new LockedResult<>(null, new AttachResult(false, session, AttachRejection.DUPLICATE));
            }
            // Limite de pages ensuite : pour une multi-sélection qui sature le
            // cap, c'est le motif pertinent (PAGE_LIMIT) plutôt que la taille.
            // On n'écrit rien (sessionToPut = null) ; le contrôleur construira
            // la 429 PAGE_LIMIT_REACHED et rollback l'objet S3 déjà stocké.
            if (session.pages().size() >= maxPages) {
                return new LockedResult<>(null, new AttachResult(false, session, AttachRejection.PAGE_LIMIT));
            }
            long projectedTotal = session.totalBytes() + page.fileSize();
            if (projectedTotal > maxTotalBytes) {
                // Refus quota taille : sessionToPut = null également, le
                // contrôleur construit la 413 SESSION_SIZE_EXCEEDED.
                return new LockedResult<>(null, new AttachResult(false, session, AttachRejection.SIZE_LIMIT));
            }
            // Voir reservePageId : expiry renouvelée sous le verrou pour éviter
            // un read-modify-write séparé qui pouvait clobber des attaches
            // concurrentes.
            var updated = session.withAttachedPage(page).withRenewedExpiry(sessionTtl);
            return new LockedResult<>(updated, new AttachResult(true, updated, null));
        });
    }

    /**
     * Acquiert le verrou Hazelcast sur {@code token}, charge la session, applique
     * la mutation décrite par {@code criticalSection}, écrit la session mise à
     * jour si demandée, puis libère le verrou. Le tout exécuté
     * <strong>synchroniquement sur un même thread</strong> de
     * {@link Schedulers#boundedElastic()}.
     *
     * <p>Cette mono-thread est obligatoire : {@code IMap.lock(K)} est
     * thread-affine côté Hazelcast (cf.
     * {@code IllegalMonitorStateException} levée par le serveur si le {@code unlock}
     * vient d'un autre thread que le {@code lock}). Une précédente itération
     * découpait lock-section-unlock en trois {@code Mono} chaînés via
     * {@link Mono#usingWhen}, ce qui laissait Reactor choisir un thread
     * boundedElastic différent pour le {@code unlock} et faisait crasher la prod.
     * On retombe donc à la version "bloquante sur boundedElastic" — ce pool
     * existe précisément pour ce genre d'I/O sync.
     *
     * <p>Émet {@link Mono#empty()} si la session est absente ou expirée, OU si
     * la mutation décide de ne rien retourner ({@code result == null}).
     *
     * <p>Après écriture de la session, le TTL du binding originator
     * correspondant est rafraîchi pour garder les deux entrées Hazelcast
     * alignées. Une session active dont le binding aurait expiré laisserait
     * la place à une 2e session sur la même IP, brisant l'invariant
     * « une session par IP ».
     */
    private <T> Mono<T> withSessionLock(String token, java.util.function.Function<UploadSession, LockedResult<T>> criticalSection) {
        // Étape « lock » du relevé d'étapes (EditTrace) : attente du verrou ET
        // section critique. Passe-plat hors d'un pipeline tracé.
        return EditTrace.stage("lock", Mono.<T>fromCallable(() -> {
            // tryLock borné, et NON lock() : ce dernier attend sans limite de temps.
            // Deux mutations concurrentes sur la même session (un upload qui attache
            // une page pendant qu'une édition la retravaille) se sérialisent
            // normalement en quelques millisecondes ; mais si la détentrice traîne
            // — pod throttlé, cluster Hazelcast lent — les autres attendaient
            // indéfiniment sur un thread boundedElastic, permis de bulkhead en main,
            // sans qu'aucun log ne le signale et sans que le client reçoive jamais
            // de réponse. Le bail (leaseTime) ferme le second trou : le verrou est
            // rendu par le cluster même si le détenteur meurt en section critique.
            boolean acquired;
            try {
                acquired = sessionsMap.tryLock(token, lockWait.toMillis(), TimeUnit.MILLISECONDS,
                        lockLease.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // Thread interrompu PENDANT l'attente du verrou. La seule source
                // d'interruption ici est l'annulation Reactor de l'appelant
                // (app.edit-timeout dépassé, navigateur parti) : le scheduler
                // dispose la tâche avec cancel(true). Or le membre a pu accorder
                // le verrou juste avant que la réponse ne soit lue — le client,
                // lui, ne le saura jamais. Libération à l'aveugle : un
                // IllegalMonitorStateException dit qu'on ne le tenait pas (cas
                // normal, tu) ; sinon le verrou est rendu au lieu de rester pris
                // jusqu'à la fin du bail.
                releaseLock(token, false);
                throw e;
            }
            if (!acquired) {
                throw new SessionLockTimeoutException(token, lockWait, lockRetryAfterSeconds);
            }
            try {
                var json = sessionsMap.get(token);
                if (json == null) return null;
                var session = fromJson(json);
                if (session.isExpired()) return null;

                var locked = criticalSection.apply(session);
                if (locked.sessionToPut() != null) {
                    var toPut = locked.sessionToPut();
                    sessionsMap.put(token, toJson(toPut),
                            sessionTtl.toSeconds(), TimeUnit.SECONDS);
                    refreshOriginatorTtl(toPut.originatorIp());
                }
                return locked.result();
            } finally {
                releaseLock(token, true);
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Rend le verrou de session, quel que soit l'état d'interruption du thread.
     *
     * <p>Quand l'appelant annule (borne {@code app.edit-timeout} atteinte,
     * navigateur parti), Reactor dispose la tâche boundedElastic avec
     * {@code cancel(true)} : le thread est interrompu en pleine section critique.
     * L'invocation Hazelcast en cours lève, et l'on arrive dans le {@code finally}
     * avec le drapeau d'interruption encore posé. Or {@code unlock} est lui aussi
     * une invocation bloquante : sur un thread interrompu elle échoue avant même
     * de partir, et le verrou reste pris jusqu'à la fin du bail — soit
     * {@code app.session-lock.lease} de {@code SESSION_BUSY} pour toute mutation
     * de la session, y compris la nouvelle tentative de l'utilisateur. Avant le
     * bail : pour toujours, et c'était le « loader infini » des éditions.</p>
     *
     * <p>Le drapeau est donc effacé le temps de rendre le verrou, puis reposé :
     * l'interruption appartient au scheduler, pas à nous. Coût assumé : si le
     * cluster est réellement figé (la raison même de l'annulation), l'unlock
     * retient le thread jusqu'au timeout d'invocation du client Hazelcast —
     * quelques secondes, cf. hazelcast-client.yaml — contre un verrou de session
     * retenu tout le bail sans cela.</p>
     *
     * @param held {@code true} si le verrou a été acquis pour sûr (section
     *             critique atteinte) ; {@code false} après un {@code tryLock}
     *             interrompu, dont l'issue est inconnue — un
     *             {@code IllegalMonitorStateException} y est alors l'issue
     *             normale, pas une anomalie.
     */
    private void releaseLock(String token, boolean held) {
        boolean interrupted = Thread.interrupted();
        try {
            sessionsMap.unlock(token);
            if (!held) {
                log.warn("Session lock granted to an interrupted tryLock, released token={}", token);
            }
        } catch (IllegalMonitorStateException e) {
            if (held) {
                // Le bail a expiré avant la fin de la section critique : le
                // cluster a déjà rendu le verrou, l'unlock n'a plus de titulaire.
                // Rien à réparer ici — mais si cela se produit, c'est que la
                // section a dépassé app.session-lock.lease, donc que le cluster
                // ou le pod est en souffrance : à remonter.
                log.warn("Session lock lease expired during critical section token={} lease={}",
                        token, lockLease);
            }
        } catch (RuntimeException e) {
            // Cluster injoignable, client fermé, timeout d'invocation… Le bail
            // rendra le verrou, mais d'ici là la session est bloquée : à voir
            // dans les logs, pas à taire.
            log.warn("Session unlock failed token={} lease={} error={}", token, lockLease, e.toString());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Aligne le TTL du binding originator sur le TTL de session renouvelé. No-op
     * si l'IP est nulle (session pré-feature ou feature désactivée) ou si le
     * binding a déjà été purgé entre-temps ({@code setTtl} retourne false).
     *
     * <p>Volontairement {@code setTtl} et non une réécriture de la valeur : le
     * champ {@code expiresAt} du binding reste figé à l'expiry INITIAL de la
     * session. Le front s'en sert comme empreinte d'auto-récupération après un
     * 409 ORIGINATOR_HAS_ACTIVE_SESSION (il compare l'{@code expiresAt} du 409
     * à celui reçu à la création pour reconnaître sa propre session orpheline
     * — cf. SessionCreator côté ge-televerse-front). Réécrire la valeur ici
     * casserait cette détection (sans danger : repli sur l'écran manuel).</p>
     */
    private void refreshOriginatorTtl(String originatorIp) {
        if (originatorIp == null || originatorsMap == null) return;
        originatorsMap.setTtl(originatorIp, sessionTtl.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Résultat d'une mutation sous verrou : la session à écrire (ou
     * {@code null} pour ne rien écrire — cas d'un refus quota par exemple)
     * et la valeur à émettre par le {@link Mono} sortant. {@code result}
     * {@code null} fait émettre vide.
     */
    private record LockedResult<T>(UploadSession sessionToPut, T result) {}

    /**
     * Retire une page de la session et renouvelle l'expiry, sous verrou. Rend la page
     * telle qu'elle était sous le verrou : c'est d'ELLE que l'appelant purge les objets
     * S3, après coup — supprimer d'abord et retirer ensuite laissait, sur un verrou
     * refusé, une page listée dont les objets n'existaient plus.
     */
    public Mono<PageRemoval> removePage(String token, String pageId) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            requireOpen(session);
            var removed = session.findPage(pageId);
            var updated = session.withRemovedPage(pageId).withRenewedExpiry(sessionTtl);
            return new LockedResult<>(updated, new PageRemoval(updated, removed));
        });
    }

    /**
     * Adopte une nouvelle version de page sous verrou — édition, undo ou reset —
     * après avoir vérifié que la session est ouverte, que la page existe toujours et
     * qu'elle n'a pas changé depuis la lecture de l'appelant ({@code lastModifiedAt}),
     * puis que le quota tient.
     *
     * <p>Remplace une mise à jour aveugle, qui « réussissait » sur une page supprimée
     * entre-temps (objets S3 orphelins, 200 « appliqué »), faisait perdre l'une de deux
     * éditions concurrentes, et ignorait le quota. Chaque refus lève une
     * {@link SessionConflictException} avant toute écriture.</p>
     *
     * @param expectedLastModified {@code lastModifiedAt} de la page lue par l'appelant
     * @param edit                 nouvelle version, calculée à partir de la page courante
     * @param maxTotalBytes        quota de la session ; vérifié seulement si la page grossit
     */
    public Mono<PageCommit> commitPageEdit(String token, String pageId, Instant expectedLastModified,
                                           UnaryOperator<PageInfo> edit, long maxTotalBytes) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            requireOpen(session);
            var current = session.findPage(pageId);
            if (current == null) {
                throw SessionConflictException.pageGone();
            }
            if (!current.lastModifiedAt().equals(expectedLastModified)) {
                throw SessionConflictException.pageChanged();
            }
            var next = edit.apply(current);
            long projectedTotal = session.totalBytes() - current.fileSize() + next.fileSize();
            if (next.fileSize() > current.fileSize() && projectedTotal > maxTotalBytes) {
                throw SessionConflictException.sizeExceeded();
            }
            var updated = session.withReplacedPage(next).withRenewedExpiry(sessionTtl);
            return new LockedResult<>(updated, new PageCommit(updated, current, next));
        });
    }

    /**
     * Réordonne les pages de la session et renouvelle l'expiry, sous verrou.
     *
     * <p>La liste est revérifiée SOUS le verrou : le contrôle de l'appelant porte sur
     * une lecture antérieure. Une page attachée entre-temps était sinon retirée sans
     * erreur (et ses objets S3 n'étaient plus jamais purgés), et une page supprimée
     * entre-temps laissait un {@code null} dans la session, rendant le token illisible
     * jusqu'à expiration.</p>
     *
     * @throws SessionConflictException ({@code PAGES_CHANGED}) si la liste ne correspond
     *         plus exactement aux pages de la session
     */
    public Mono<UploadSession> reorderPages(String token, List<String> orderedPageIds) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            requireOpen(session);
            var current = new HashSet<>(session.pages().stream().map(PageInfo::pageId).toList());
            if (orderedPageIds.size() != current.size() || !current.equals(new HashSet<>(orderedPageIds))) {
                throw SessionConflictException.pagesChanged();
            }
            var updated = session.withReorderedPages(orderedPageIds).withRenewedExpiry(sessionTtl);
            return new LockedResult<>(updated, updated);
        });
    }

    // ===== Finalisation =====

    /**
     * Ouvre la finalisation sous verrou. Session ouverte (ou finalisation échue à son
     * échéance) → {@link SessionStatus#FINALIZING} jusqu'à {@code now + lease}, et la
     * session ainsi figée est rendue : ses pages sont celles à fusionner, et plus
     * aucune ne peut être ajoutée, éditée, retirée ou déplacée. Finalisation en cours
     * ailleurs → {@code IN_PROGRESS} ; déjà faite → {@code ALREADY_FINALIZED}, pour
     * qu'une finalisation rejouée (client parti avant la réponse) rende le même
     * {@code documentId}.
     *
     * @param lease durée au-delà de laquelle une finalisation non conclue (pod mort)
     *              libère la session
     */
    public Mono<FinalizeStart> beginFinalize(String token, Duration lease) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            var now = Instant.now();
            return switch (session.effectiveStatus(now)) {
                case FINALIZED -> new LockedResult<>(null,
                        new FinalizeStart(FinalizeStart.State.ALREADY_FINALIZED, session));
                case FINALIZING -> new LockedResult<>(null,
                        new FinalizeStart(FinalizeStart.State.IN_PROGRESS, session));
                case OPEN -> {
                    var frozen = session.withStatus(SessionStatus.FINALIZING, now.plus(lease));
                    yield new LockedResult<>(frozen, new FinalizeStart(FinalizeStart.State.STARTED, frozen));
                }
            };
        });
    }

    /**
     * Conclut la finalisation une fois le PDF composite écrit : session
     * {@link SessionStatus#FINALIZED}, liste de pages vidée — elles sont fusionnées,
     * et leur purge S3 est à la charge de l'appelant ; une expiration ultérieure de la
     * session n'a ainsi plus rien à purger.
     */
    public Mono<UploadSession> completeFinalize(String token) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            var done = session.withStatus(SessionStatus.FINALIZED, null).withPages(List.of());
            return new LockedResult<>(done, done);
        });
    }

    /**
     * Rouvre une session dont la finalisation a échoué (fusion en erreur, délai
     * dépassé, client parti) : de nouveau {@link SessionStatus#OPEN}. Sans effet si la
     * session n'est plus en finalisation.
     */
    public Mono<UploadSession> abortFinalize(String token) {
        if (sessionsMap == null) return Mono.empty();
        return withSessionLock(token, session -> {
            if (session.status() != SessionStatus.FINALIZING) {
                return new LockedResult<>(null, session);
            }
            var reopened = session.withStatus(SessionStatus.OPEN, null);
            return new LockedResult<>(reopened, reopened);
        });
    }

    // ===== Suppression / finalisation de session =====

    /**
     * Supprime la session côté Hazelcast, libère le binding originator
     * éventuel, complète le sink SSE et purge les pages orphelines S3 (mêmes
     * actions que le cleanup d'une session expirée). Idempotent : si la
     * session a déjà disparu, ne fait rien et retourne {@code false}.
     *
     * <p>Utilisé par {@code DELETE /sessions/{token}} (déclenché par le front
     * sur {@code beforeunload}) et indirectement par {@code POST /sessions?force=true}
     * pour purger une session bloquante.</p>
     */
    public Mono<Boolean> deleteSession(String token) {
        if (sessionsMap == null) return Mono.just(false);
        return Mono.fromCallable(() -> deleteSessionInternal(token))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Appelé sur la finalisation d'une session : libère uniquement le binding
     * originator, pour qu'une nouvelle session puisse être ouverte depuis le même poste
     * sans attendre le TTL. La session, elle, reste en {@link SessionStatus#FINALIZED}
     * (cf. {@link #completeFinalize}). Cherche l'IP via
     * {@code UploadSession.originatorIp()} pour un O(1).
     */
    public void releaseOriginator(UploadSession session) {
        if (session == null || session.originatorIp() == null || originatorsMap == null) return;
        originatorsMap.delete(session.originatorIp());
    }

    /**
     * Cleanup atomique d'une session : supprime de {@code sessionsMap}, libère
     * le binding originator, complète le sink SSE local et best-effort delete
     * des pages orphelines S3. Synchronous, à appeler depuis
     * {@link #deleteSession(String)} ou la branche force-new de
     * {@link #createSession(SizeProfile, String, boolean)}.
     *
     * @return {@code true} si une session existait, {@code false} sinon.
     */
    private boolean deleteSessionInternal(String token) {
        var json = sessionsMap.remove(token);
        cleanupSink(token);
        if (json == null) return false;

        var session = fromJson(json);
        if (session.originatorIp() != null && originatorsMap != null) {
            originatorsMap.delete(session.originatorIp());
        }
        cleanupOrphanPages(session);
        return true;
    }

    /**
     * Supprime conjointement la session expirée et son binding originator.
     * Appelé depuis {@link #getSession(String)} lorsqu'une lecture détecte une
     * session passée son {@code expiresAt} ; on garde les deux entrées
     * Hazelcast cohérentes même si le TTL Hazelcast aurait fini par s'en
     * occuper.
     */
    private void removeSessionAndOriginator(String token, UploadSession session) {
        sessionsMap.remove(token);
        if (session.originatorIp() != null && originatorsMap != null) {
            originatorsMap.delete(session.originatorIp());
        }
    }

    // ===== SSE =====

    /**
     * Ouvre le flux SSE d'une session : émet le flux si la session existe, vide si
     * elle est absente ou expirée (le contrôleur répond alors 404).
     *
     * <p>Session chargée <b>hors de l'event loop</b> ({@link #getSessionAsync}) : un
     * {@code IMap.get} bloquant sur un thread {@code reactor-http-nio} gelait la
     * boucle entière, et les ouvertures de flux arrivent en vagues — watchdog de
     * 35 s du front, reconnexions automatiques d'EventSource, aucune affinité au LB.
     * Un Hazelcast lent transformait chaque vague en gel d'event loops.</p>
     *
     * <p>Le sink est enregistré à la <b>souscription</b> du flux (quand WebFlux
     * commence à écrire la réponse), et libéré sur n'importe quel signal terminal.
     * Une fois enregistré, il est visible de {@link #cleanupExpiredSinks()} et de
     * {@link #destroy()} ; une session qui expire entre le chargement et
     * l'enregistrement est fermée au balayage suivant (lecture absente).</p>
     */
    public Mono<Flux<ServerSentEvent<String>>> subscribe(String token) {
        return getSessionAsync(token)
                .map(session -> Flux.defer(() -> openSink(token)));
    }

    /**
     * Enregistre un sink neuf pour {@code token}, en remplaçant et en complétant
     * celui qui s'y trouvait. Le cas se produit à chaque reconnexion (watchdog du
     * front) qui retombe sur le même pod : l'ancien flux, souvent une connexion
     * zombie coupée par un proxy, n'était plus référencé par la map donc plus
     * alimenté en heartbeats — il fuyait jusqu'à la mort de la socket, et sa
     * fermeture tardive retirait alors le sink du NOUVEAU flux (retrait par clé
     * seule), coupant le flux vivant et perdant ses événements.
     */
    private Flux<ServerSentEvent<String>> openSink(String token) {
        var sink = Sinks.many().unicast().<ServerSentEvent<String>>onBackpressureBuffer();
        var previous = sinks.put(token, sink);
        if (previous != null) {
            previous.tryEmitComplete();
        }
        emit(token, sink, CONNECTED_EVENT);
        // Pas de take(duration) fixe : le stream reste ouvert tant que la session est
        // valide ; le cleanup schedulé (cleanupExpiredSinks) ferme les sinks expirés.
        return sink.asFlux()
                .doFinally(signal -> releaseSink(token, sink));
    }

    /**
     * Libère le sink d'un flux terminé (annulation client, complétion, erreur) —
     * uniquement s'il est encore celui enregistré pour {@code token} : un flux
     * remplacé par une reconnexion ne doit pas retirer son successeur.
     */
    private void releaseSink(String token, Sinks.Many<ServerSentEvent<String>> sink) {
        sinks.remove(token, sink);
        sink.tryEmitComplete();
    }

    /**
     * Émet sur un sink en tolérant la concurrence : heartbeats (thread du
     * scheduler) et événements du topic (thread Hazelcast) peuvent viser le même
     * sink au même instant. {@code tryEmitNext} renvoie alors
     * {@code FAIL_NON_SERIALIZED} et l'événement — un {@code upload-complete}, par
     * exemple — était perdu en silence. On réessaie brièvement ; au-delà, ou si le
     * sink est déjà fermé, l'événement est abandonné et la raison loguée (un flux
     * fermé n'a de toute façon plus de lecteur).
     */
    private static void emit(String token, Sinks.Many<ServerSentEvent<String>> sink, ServerSentEvent<String> event) {
        try {
            sink.emitNext(event, EMIT_RETRY);
        } catch (Sinks.EmissionException e) {
            log.warn("SSE event dropped token={} event={} reason={}", token, event.event(), e.getReason());
        }
    }

    // ===== Notifications =====

    public void notifyUpload(String token, UploadEvent event) {
        uploadTopic.publish(new UploadNotification(token, event));
    }

    // ===== Cleanup =====

    /**
     * Émet un événement nommé {@code heartbeat} sur chaque sink SSE actif. Sans
     * cette écriture périodique, le canal reste muet entre deux uploads et un
     * client mort (proxy qui a coupé sans FIN, onglet fermé) n'est détecté
     * qu'à expiration de la session. Un heartbeat sur un sink mort
     * est abandonné (cf. {@link #emit}) ; le cleanup réel est câblé sur le
     * {@code doFinally} du flux ouvert par {@link #subscribe(String)}.
     */
    @Scheduled(fixedDelayString = "${app.sse-heartbeat-interval:PT15S}")
    void sendHeartbeats() {
        sinks.forEach((token, sink) -> emit(token, sink, HEARTBEAT_EVENT));
    }

    /**
     * Ferme les flux SSE locaux dont la session a disparu (TTL Hazelcast) ou expiré
     * logiquement, et purge ces dernières.
     *
     * <p>Lectures groupées ({@code getAll} par lots de {@value #SWEEP_BATCH_SIZE}) :
     * un {@code IMap.get} bloquant par sink faisait durer le balayage N × le timeout
     * d'invocation quand le cluster se dégradait. Chaque lot et chaque token sont
     * isolés : une lecture en échec reporte le lot au passage suivant (rien n'est
     * fermé sur une lecture ratée), une valeur illisible n'interrompt plus le
     * balayage des autres sinks du pod.</p>
     */
    @Scheduled(fixedDelayString = "${app.cleanup-interval:PT1M}")
    void cleanupExpiredSinks() {
        var tokens = List.copyOf(sinks.keySet());
        for (int from = 0; from < tokens.size(); from += SWEEP_BATCH_SIZE) {
            var batch = tokens.subList(from, Math.min(from + SWEEP_BATCH_SIZE, tokens.size()));
            Map<String, HazelcastJsonValue> values;
            try {
                values = sessionsMap.getAll(Set.copyOf(batch));
            } catch (RuntimeException e) {
                log.warn("SSE sink sweep read failed batch={} error={}", batch.size(), e.toString());
                continue;
            }
            for (var token : batch) {
                try {
                    sweepSink(token, values.get(token));
                } catch (RuntimeException e) {
                    log.warn("SSE sink sweep failed token={} error={}", token, e.toString());
                }
            }
        }
    }

    /** Un sink du balayage : fermé si sa session a disparu ou expiré (et purgée). */
    private void sweepSink(String token, HazelcastJsonValue json) {
        if (json != null) {
            var session = fromJson(json);
            if (!session.isExpired()) return;
            cleanupOrphanPages(session);
            removeSessionAndOriginator(token, session);
        }
        // Absente : déjà supprimée par le TTL Hazelcast (purge S3 faite par le
        // listener d'expiration).
        cleanupSink(token);
    }

    /**
     * Supprime les pages individuelles et leurs vignettes orphelines d'une session
     * disparue sans finalisation. Fire-and-forget (best effort).
     *
     * <p>Une seule purge par session, cluster-wide : le pod qui l'exécute est celui qui
     * remporte la revendication ({@link #claimPurge}). Sans elle, chaque pod recevant
     * l'événement d'expiration purgeait la session — 6 pods, 6 × jusqu'à 60 DELETE.
     * Les objets S3 partent en un seul lot, à concurrence bornée
     * ({@link StorageService#deleteAllBestEffort}) : le coût d'une purge ne dépend plus
     * du nombre de pages.</p>
     */
    private void cleanupOrphanPages(UploadSession session) {
        if (session.pages().isEmpty()) return;
        if (!claimPurge(session.token())) {
            log.debug("Session cleanup skipped token={} reason=claimed-by-another-pod", session.token());
            return;
        }

        log.info("Session cleanup token={} orphanPages={}", session.token(), session.pages().size());
        var s3Keys = session.pages().stream()
                .flatMap(page -> page.s3Keys().stream())
                .toList();
        storageService.deleteAllBestEffort(s3Keys)
                .subscribe(
                        unused -> {},
                        err -> log.warn("S3 cleanup failed token={} keys={} error={}",
                                session.token(), s3Keys.size(), err.getMessage())
                );

        // Vignettes (vivante + snapshots) : cache Hazelcast. Le TTL du cache les
        // évincerait de lui-même, mais une session expirée peut avoir été prolongée
        // juste avant de mourir — rendre la mémoire tout de suite évite de la garder
        // immobilisée jusqu'au bout du TTL.
        //
        // UN SEUL appel pour toute la session, hors de la boucle. Un appel par page
        // était fire-and-forget : les N pages émettaient leurs suppressions en même
        // temps, et la borne de concurrence de deleteAll (par appel) ne bornait donc
        // rien à l'échelle de la session — le pic restait proportionnel au nombre de
        // pages, puis au nombre de finalisations concurrentes admises par le bulkhead.
        // Résultat en test de charge : HazelcastOverloadException sur /finalize.
        // Groupé ici, le coût d'une purge est constant (PURGE_CONCURRENCY invocations
        // en vol), quelle que soit la taille de la session.
        var thumbnailKeys = session.pages().stream()
                .flatMap(page -> page.thumbnailKeys().stream())
                .toList();
        thumbnailCache.deleteAll(thumbnailKeys)
                .subscribe(
                        unused -> {},
                        err -> log.warn("Thumbnail cache cleanup failed token={} keys={} error={}",
                                session.token(), thumbnailKeys.size(), err.getMessage())
                );
    }

    /**
     * Revendique la purge de {@code token} pour ce pod ({@code putIfAbsent} atomique,
     * sérialisé par le partition owner). {@code false} si un autre pod l'a déjà prise.
     *
     * <p>Revendication impossible (cluster injoignable) : on purge quand même. Les
     * DELETE S3 sont idempotents — mieux vaut un doublon qu'un document personnel
     * laissé sur le bucket.</p>
     */
    private boolean claimPurge(String token) {
        if (purgeClaims == null) return true;
        try {
            return purgeClaims.putIfAbsent(token, podId,
                    PURGE_CLAIM_TTL.toSeconds(), TimeUnit.SECONDS) == null;
        } catch (RuntimeException e) {
            log.warn("Purge claim failed token={} error={} — purging anyway", token, e.toString());
            return true;
        }
    }

    private void cleanupSink(String token) {
        var sink = sinks.remove(token);
        if (sink != null) sink.tryEmitComplete();
    }

    // ===== JSON helpers =====

    private HazelcastJsonValue toJson(UploadSession session) {
        try {
            return new HazelcastJsonValue(objectMapper.writeValueAsString(session));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize UploadSession", e);
        }
    }

    private UploadSession fromJson(HazelcastJsonValue json) {
        try {
            return objectMapper.readValue(json.getValue(), UploadSession.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize UploadSession", e);
        }
    }
}

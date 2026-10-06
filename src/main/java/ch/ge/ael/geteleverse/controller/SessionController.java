package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.config.AdmissionConfig;
import ch.ge.ael.geteleverse.config.EditAdmissionProperties;
import ch.ge.ael.geteleverse.config.FinalizeAdmissionProperties;
import ch.ge.ael.geteleverse.config.GhostscriptProperties;
import ch.ge.ael.geteleverse.config.LimitsProperties;
import ch.ge.ael.geteleverse.config.MergeAdmissionProperties;
import ch.ge.ael.geteleverse.config.MetricsConfiguration;
import ch.ge.ael.geteleverse.config.OriginatorProperties;
import ch.ge.ael.geteleverse.config.SessionCreateProperties;
import ch.ge.ael.geteleverse.config.SessionRateLimitProperties;
import ch.ge.ael.geteleverse.model.PageInfo;
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
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;
    private final StorageService storageService;
    private final ThumbnailCacheService thumbnailCache;
    private final LimitsProperties limits;
    private final OriginatorResolver originatorResolver;
    private final OriginatorProperties originatorProperties;
    private final IpSessionRateLimiter rateLimiter;
    private final SessionRateLimitProperties rateLimitProperties;
    private final SessionCreateProperties sessionCreateProperties;
    private final Duration finalizeTimeout;
    /**
     * Échéance d'une finalisation non conclue (pod mort en pleine fusion) : au-delà, la
     * session redevient ouverte. finalizeTimeout plus une marge pour la bascule finale.
     */
    private final Duration finalizeLease;
    /** Borne de durée d'une édition de page (rotate / crop / deskew / white-balance). */
    private final Duration editTimeout;
    /** Borne de durée d'une annulation (undo / reset). Plus serrée qu'editTimeout : pas de passe native. */
    private final Duration undoTimeout;
    /** Buckets de televerse.edit.duration, dérivés d'editTimeout (cf. MetricsConfiguration). */
    private final Duration[] editDurationSlos;
    private final DistributionSummary sessionPagesCount;
    private final MeterRegistry meterRegistry;
    private final double[] sessionFinalBytesSlos;
    /** Largeur (px) de l'aperçu généré à la volée — définie côté serveur, jamais par le client. */
    private final int previewWidth;
    /** Largeur (px) des vignettes — sert aussi à les régénérer sur défaut de cache. */
    private final int thumbnailWidth;
    /** Bulkhead d'admission des éditions (rotate / crop / deskew / white-balance). */
    private final Bulkhead editBulkhead;
    /** Retry-After (s) renvoyé au client quand le bulkhead d'édition est saturé. */
    private final long editRetryAfterSeconds;
    /** Bulkhead d'admission des aperçus (preview.pdf / rendu vignette à la volée). */
    private final Bulkhead mergeBulkhead;
    /** Retry-After (s) renvoyé au client quand le bulkhead d'aperçu est saturé. */
    private final long mergeRetryAfterSeconds;
    /** Bulkhead d'admission des finalisations (mergeAndFinalize). Séparé des aperçus. */
    private final Bulkhead finalizeBulkhead;
    /** Retry-After (s) renvoyé au client quand le bulkhead de finalisation est saturé. */
    private final long finalizeRetryAfterSeconds;

    public SessionController(
            SessionService sessionService,
            StorageService storageService,
            ThumbnailCacheService thumbnailCache,
            LimitsProperties limits,
            GhostscriptProperties ghostscript,
            MeterRegistry meterRegistry,
            OriginatorResolver originatorResolver,
            OriginatorProperties originatorProperties,
            IpSessionRateLimiter rateLimiter,
            SessionRateLimitProperties rateLimitProperties,
            SessionCreateProperties sessionCreateProperties,
            @Value("${app.finalize-timeout:PT2M}") Duration finalizeTimeout,
            @Value("${app.edit-timeout:PT20S}") Duration editTimeout,
            @Value("${app.undo-timeout:PT10S}") Duration undoTimeout,
            @Value("${app.preview-width:800}") int previewWidth,
            @Value("${app.thumbnail-width:120}") int thumbnailWidth,
            DistributionSummary sessionPagesCount,
            @Qualifier(AdmissionConfig.EDIT_BULKHEAD) Bulkhead editBulkhead,
            EditAdmissionProperties editAdmission,
            @Qualifier(AdmissionConfig.MERGE_BULKHEAD) Bulkhead mergeBulkhead,
            MergeAdmissionProperties mergeAdmission,
            @Qualifier(AdmissionConfig.FINALIZE_BULKHEAD) Bulkhead finalizeBulkhead,
            FinalizeAdmissionProperties finalizeAdmission
    ) {
        this.sessionService = sessionService;
        this.storageService = storageService;
        this.thumbnailCache = thumbnailCache;
        this.limits = limits;
        this.originatorResolver = originatorResolver;
        this.originatorProperties = originatorProperties;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.sessionCreateProperties = sessionCreateProperties;
        this.finalizeTimeout = finalizeTimeout;
        this.finalizeLease = finalizeTimeout.plusSeconds(30);
        this.editTimeout = editTimeout;
        this.undoTimeout = undoTimeout;
        this.editDurationSlos = MetricsConfiguration.editDurationSlos(editTimeout);
        this.previewWidth = previewWidth;
        this.thumbnailWidth = thumbnailWidth;
        this.sessionPagesCount = sessionPagesCount;
        this.meterRegistry = meterRegistry;
        this.editBulkhead = editBulkhead;
        this.editRetryAfterSeconds = Math.max(1, editAdmission.retryAfter().toSeconds());
        this.mergeBulkhead = mergeBulkhead;
        this.mergeRetryAfterSeconds = Math.max(1, mergeAdmission.retryAfter().toSeconds());
        this.finalizeBulkhead = finalizeBulkhead;
        this.finalizeRetryAfterSeconds = Math.max(1, finalizeAdmission.retryAfter().toSeconds());
        // SLOs calculées une fois — les DistributionSummary par sizeProfile sont
        // ensuite récupérées via le cache name+tags de Micrometer à chaque finalize.
        this.sessionFinalBytesSlos = MetricsConfiguration.sessionFinalBytesSlos(limits, ghostscript);
    }

    /**
     * Charge la session <b>hors de l'event loop Netty</b>, puis passe la main au
     * handler qui porte le corps de l'endpoint.
     *
     * <p>Les contrôleurs appelaient {@code sessionService.getSession(token)}
     * directement dans leur corps, donc sur un thread {@code reactor-http-nio-*} :
     * un {@code IMap.get} Hazelcast bloquant sur l'event loop. Le pod n'en compte
     * qu'une poignée ; un cluster lent les bloquait toutes et le pod cessait
     * jusqu'à <i>lire</i> les requêtes entrantes — sans réponse ni log, pas même
     * la ligne d'entrée de {@code HttpLoggingFilter}, écrite sur ce même thread.
     * Voir {@link SessionService#getSessionAsync(String)}.</p>
     *
     * <p>La session est passée au handler telle quelle, {@code null} compris :
     * chaque endpoint garde ainsi sa propre garde « session absente » et sa propre
     * réponse d'erreur (404 pour une vignette, 400 pour une édition…). C'est aussi
     * ce qui évite le piège du {@code switchIfEmpty} après {@code flatMap}, qui
     * confondrait « session absente » et « pipeline interne vide ».</p>
     */
    private <T> Mono<T> withSession(String token, Function<UploadSession, Mono<T>> handler) {
        // Étape « session » du relevé (EditTrace) : ne s'enregistre que là où le
        // chargement est SOUS la borne, donc pour undo / reset — pour les quatre
        // éditions, la trace n'existe pas encore à ce point et l'enveloppe est
        // un passe-plat.
        return EditTrace.stage("session", sessionService.getSessionAsync(token))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybe -> handler.apply(maybe.orElse(null)));
    }

    /**
     * Décore le pipeline réactif d'une édition avec le bulkhead d'admission :
     * le permis est acquis à la souscription (avant tout appel S3) et relâché
     * sur signal terminal (succès, erreur, annulation). Si aucun permis n'est
     * disponible dans {@code maxWait}, {@link BulkheadFullException} est levée et
     * traduite en {@link EditBusyException} (429 {@code EDIT_BUSY} + Retry-After),
     * après incrément du compteur {@code televerse.edit.rejections} taggé par
     * opération. Les gardes en amont (session / page absente) restent hors du
     * bulkhead : une requête invalide ne consomme jamais de permis.
     */
    private <T> Mono<T> withEditAdmission(String op, Mono<T> pipeline) {
        var decorated = traced(op, pipeline, editTimeout)
                .transformDeferred(BulkheadOperator.of(editBulkhead))
                .onErrorMap(BulkheadFullException.class, e -> {
                    Counter.builder("televerse.edit.rejections")
                            .description("Éditions rejetées par l'admission control (bulkhead saturé).")
                            .tag("op", op)
                            .register(meterRegistry)
                            .increment();
                    log.debug("Edit rejected by bulkhead op={}", op);
                    return new EditBusyException(editRetryAfterSeconds);
                });
        return timed(op, decorated);
    }

    /**
     * Borne de durée d'annulation (undo / reset) : {@link #bounded} + chrono,
     * SANS bulkhead — contrairement aux quatre éditions.
     *
     * <p>Ces deux chemins ne font ni rendu PDFBox ni passe native, ni même de copie :
     * la section verrouillée de {@code commitPageEdit} fait pointer la page sur la
     * version visée, puis la version abandonnée est purgée en best effort. Les faire
     * concourir pour les 8 permis d'{@code edit}
     * les ferait rejeter à cause de deskews en cours — sans raison de ressource —
     * et retirerait autant de capacité aux éditions qui, elles, en ont besoin.
     * Elles gardent donc un accès non contingenté et ne renvoient jamais
     * {@code EDIT_BUSY}.</p>
     *
     * <p>La borne, elle, s'applique : chacun de ces maillons peut traîner
     * indéfiniment. À la différence des éditions, le pipeline entier est enveloppé
     * <i>chargement de session compris</i> — sans permis à protéger, rien ne
     * justifie de laisser le {@code getSessionAsync} hors de la borne, alors que
     * c'est précisément un appel Hazelcast lent qui a ouvert cet incident.</p>
     */
    private <T> Mono<T> withUndoBound(String op, Mono<T> pipeline) {
        return timed(op, traced(op, pipeline, undoTimeout));
    }

    /**
     * Arme, en une souscription, la borne de durée ({@link #bounded}) ET le
     * relevé d'étapes ({@link EditTrace}) d'un pipeline. La trace est créée à la
     * souscription — une par tentative, comme le chrono de {@link #timed} — puis
     * posée dans le contexte Reactor pour que les maillons en amont (S3, passe
     * native, cache, verrou de session) s'y inscrivent, et remise à
     * l'{@link EditTimeoutException} pour le WARN du 504.
     *
     * <p>Motif : un dépassement ne laissait qu'une ligne « Edit timed out
     * operation=deskew ». Rien sur ce chemin ne logue au-dessus de DEBUG, et
     * {@code televerse.edit.duration} ne donne que le total — impossible de dire
     * lequel des maillons avait mangé les 20 s.</p>
     */
    private <T> Mono<T> traced(String op, Mono<T> pipeline, Duration budget) {
        return Mono.defer(() -> {
            var trace = new EditTrace();
            return trace.attachTo(bounded(op, pipeline, budget, trace));
        });
    }

    /**
     * Coupe le pipeline au-delà de {@code budget} et qualifie le dépassement en
     * {@link EditTimeoutException} (504 {@code EDIT_TIMEOUT}), après incrément de
     * {@code televerse.edit.timeouts} taggé par opération.
     *
     * <p>Posée SOUS le bulkhead là où il y en a un : le chrono démarre quand le
     * permis est acquis, pas pendant l'attente ({@code <= maxWait}). L'expiration
     * annule l'amont — permis relâché, appel S3 en cours interrompu — au lieu de
     * laisser une requête morte immobiliser capacité et mémoire. Sans elle, GET S3
     * lent, passe OpenCV/ONNX sur un pod throttlé ou verrou Hazelcast retenu
     * laissaient la requête en vol indéfiniment, sans réponse ni trace.</p>
     *
     * <p>L'annulation de l'amont est aussi ce qui marque {@code cancelled}, dans
     * {@code trace}, le maillon qui était en vol : le WARN du 504 le désigne.</p>
     */
    private <T> Mono<T> bounded(String op, Mono<T> pipeline, Duration budget, EditTrace trace) {
        return pipeline
                .timeout(budget)
                .onErrorMap(TimeoutException.class, e -> {
                    Counter.builder("televerse.edit.timeouts")
                            .description("Éditions abandonnées sur dépassement de leur borne de durée.")
                            .tag("op", op)
                            .register(meterRegistry)
                            .increment();
                    return new EditTimeoutException(op, budget, trace);
                });
    }

    /**
     * Chrono démarré à la SOUSCRIPTION (d'où le defer), donc une mesure par
     * tentative et non une seule au câblage du pipeline. Il englobe l'attente du
     * permis quand il y en a une : un rejet d'admission apparaît ainsi comme une
     * mesure rapide taggée {@code outcome="busy"}, jamais confondue avec un deskew
     * qui traîne. Jusqu'ici ce chemin n'avait aucune métrique de durée :
     * impossible de dire, depuis Grafana, si les éditions ralentissaient ni
     * laquelle.
     */
    private <T> Mono<T> timed(String op, Mono<T> decorated) {
        return Mono.defer(() -> {
            var sample = Timer.start(meterRegistry);
            return decorated
                    .doOnSuccess(result -> recordEditDuration(sample, op, "success"))
                    .doOnError(error -> recordEditDuration(sample, op, editOutcome(error)))
                    // Client parti avant la réponse (onglet fermé, timeout axios) :
                    // signal distinct d'un succès comme d'une erreur, et le seul qui
                    // révèle une édition abandonnée côté navigateur.
                    .doOnCancel(() -> recordEditDuration(sample, op, "cancelled"));
        });
    }

    /**
     * Arrête le chrono sur {@code televerse.edit.duration}, taggé par opération
     * et par issue. Micrometer dédoublonne par {@code name+tags} : le builder
     * n'est pas un coût de re-création à chaque appel.
     */
    private void recordEditDuration(Timer.Sample sample, String op, String outcome) {
        sample.stop(Timer.builder("televerse.edit.duration")
                .description("Durée d'une édition de page, de l'admission à la réponse.")
                .tag("op", op)
                .tag("outcome", outcome)
                .serviceLevelObjectives(editDurationSlos)
                .register(meterRegistry));
    }

    /**
     * Issue d'une édition, en cardinalité bornée : distingue les refus de
     * capacité ({@code busy}, {@code lock_busy}), le dépassement de budget
     * ({@code timeout}) et l'entrée inexploitable ({@code corrupt}) du reste.
     * Un pic de {@code timeout} sans pic de {@code busy} dit que le pod n'est pas
     * saturé mais lent — ce que les compteurs de rejet seuls ne pouvaient pas dire.
     */
    private static String editOutcome(Throwable error) {
        if (error instanceof EditBusyException) return "busy";
        if (error instanceof EditTimeoutException) return "timeout";
        if (error instanceof SessionLockTimeoutException) return "lock_busy";
        if (error instanceof CorruptFileException) return "corrupt";
        return "error";
    }

    /**
     * Décore un pipeline d'assemblage / rendu multi-pages (aperçu, merge,
     * finalize) avec le bulkhead {@code merge}. Même contrat que
     * {@link #withEditAdmission} : permis acquis à la souscription (avant tout
     * GET S3), relâché sur signal terminal, rejet → {@link MergeBusyException}
     * (429 {@code MERGE_BUSY}) après incrément de {@code televerse.merge.rejections}.
     */
    private <T> Mono<T> withMergeAdmission(String op, Mono<T> pipeline) {
        return pipeline
                .transformDeferred(BulkheadOperator.of(mergeBulkhead))
                .onErrorMap(BulkheadFullException.class, e -> {
                    Counter.builder("televerse.merge.rejections")
                            .description("Assemblages rejetés par l'admission control (bulkhead saturé).")
                            .tag("op", op)
                            .register(meterRegistry)
                            .increment();
                    log.debug("Merge rejected by bulkhead op={}", op);
                    return new MergeBusyException(mergeRetryAfterSeconds);
                });
    }

    /**
     * Décore le pipeline de finalisation avec le bulkhead {@code finalize} —
     * séparé du bulkhead {@code merge} des aperçus car un permis de finalisation
     * est retenu bien plus longtemps (jusqu'à {@code app.finalize-timeout}). Même
     * contrat que {@link #withMergeAdmission} : permis acquis à la souscription
     * (avant tout GET S3), relâché sur signal terminal, rejet →
     * {@link FinalizeBusyException} (429 {@code FINALIZE_BUSY}) après incrément de
     * {@code televerse.finalize.rejections}.
     */
    private <T> Mono<T> withFinalizeAdmission(Mono<T> pipeline) {
        return pipeline
                .transformDeferred(BulkheadOperator.of(finalizeBulkhead))
                .onErrorMap(BulkheadFullException.class, e -> {
                    Counter.builder("televerse.finalize.rejections")
                            .description("Finalisations rejetées par l'admission control (bulkhead saturé).")
                            .register(meterRegistry)
                            .increment();
                    log.debug("Finalize rejected by bulkhead");
                    return new FinalizeBusyException(finalizeRetryAfterSeconds);
                });
    }

    /**
     * Classe une erreur de lecture S3 survenue sur le chemin des aperçus /
     * vignettes ({@link #getThumbnail}). Un objet réellement absent
     * ({@code NoSuchKey} → HTTP 404 côté store) reste un {@code 404} légitime ;
     * toute autre défaillance — timeout d'acquisition de connexion quand le pool
     * Netty sature sous charge, 5xx / throttling du store on-prem, reset TLS —
     * est une indisponibilité <em>transitoire</em>, requalifiée en
     * {@link StorageUnavailableException} (503 {@code STORAGE_UNAVAILABLE} +
     * {@code Retry-After}) plutôt que masquée en {@code 404} silencieux.
     *
     * <p>Chaque branche est tracée (INFO pour l'absence, WARN pour la panne).
     * Sans ce log, une saturation S3 sous forte charge se présentait comme une
     * vague de 404 sans aucune trace, indiscernable d'une page réellement
     * supprimée — c'était le symptôme observé en préprod à pleine charge.</p>
     */
    private Mono<ResponseEntity<byte[]>> mapStorageFetchError(Throwable e, String op,
                                                              String token, String pageId, String key,
                                                              String filename) {
        // NoSuchKey testé directement (le store on-prem — comme le fake dev —
        // ne peuple pas toujours le statusCode HTTP sur l'exception typée), plus
        // tout S3Exception portant un 404 explicite.
        if (e instanceof NoSuchKeyException
                || (e instanceof S3Exception s3 && s3.statusCode() == 404)) {
            recordPreviewError(op, "missing");
            log.info("Storage object missing op={} token={} pageId={} key={}", op, token, pageId, key);
            return Mono.just(ResponseEntity.notFound().build());
        }
        // Rendu PDFBox / ImageIO en échec sur un PDF de page illisible : défaut
        // PERMANENT du contenu, pas une panne de stockage. Le classer 503 le faisait
        // réessayer indéfiniment et polluait cause=transient, le signal de saturation S3.
        if (e instanceof IOException) {
            recordPreviewError(op, "corrupt");
            log.warn("Page render failed op={} token={} pageId={} key={} error={}",
                    op, token, pageId, key, e.toString());
            return Mono.error(new CorruptFileException(filename, e));
        }
        recordPreviewError(op, "transient");
        log.warn("Storage fetch failed op={} token={} pageId={} key={} error={}",
                op, token, pageId, key, e.toString());
        return Mono.error(new StorageUnavailableException(op, mergeRetryAfterSeconds, e));
    }

    /**
     * Compteur des erreurs de lecture S3 sur le chemin des aperçus / vignettes,
     * ventilé par opération ({@code op} = {@code preview} rendu à la volée /
     * {@code thumbnail} vignette pré-stockée) et par cause :
     * <ul>
     *   <li>{@code missing} — objet réellement absent ({@code NoSuchKey} → 404) :
     *       page purgée, ou pas encore visible après un overwrite d'édition ;</li>
     *   <li>{@code transient} — panne temporaire ({@code STORAGE_UNAVAILABLE} → 503) :
     *       pool de connexions S3 saturé, 5xx du store on-prem, reset TLS.</li>
     * </ul>
     * Sur un test de charge, une flambée de {@code cause=transient} objective
     * directement la saturation S3 (à distinguer des 404 légitimes/races côté
     * {@code cause=missing}) — c'était le point aveugle de l'incident préprod.
     */
    private void recordPreviewError(String op, String cause) {
        Counter.builder("televerse.preview.errors")
                .description("Erreurs de lecture S3 sur le chemin aperçu/vignette, par opération et cause.")
                .tag("op", op)
                .tag("cause", cause)
                .register(meterRegistry)
                .increment();
    }

    public record CreateSessionRequest(SizeProfile sizeProfile) {}
    public record CreateSessionResponse(String token, String uploadUrl, long expiresAt,
                                        SizeProfile sizeProfile, long maxTotalBytes, int maxPages) {}
    public record FinalizeResponse(String documentId, String message) {}
    public record ReorderRequest(List<String> pageIds) {}
    public record ReorderResponse(String message, int totalPages, long expiresAt) {}
    public record DeletePageResponse(String message, int totalPages, long totalBytes, long maxTotalBytes,
                                     long expiresAt) {}
    public record DeskewResponse(String message, boolean applied, long totalBytes, long maxTotalBytes,
                                 long expiresAt, boolean undoAvailable) {}
    public record CropResponse(String message, boolean applied, long totalBytes, long maxTotalBytes,
                               long expiresAt, boolean undoAvailable) {}
    public record WhiteBalanceResponse(String message, long totalBytes, long maxTotalBytes,
                                       long expiresAt, boolean undoAvailable) {}
    public record KeepAliveResponse(long expiresAt) {}
    public record PhoneConnectionResponse(String message, long expiresAt) {}
    public record RotateResponse(String message, long totalBytes, long maxTotalBytes, long expiresAt,
                                 boolean undoAvailable) {}
    public record UndoResponse(String message, long totalBytes, long maxTotalBytes, long expiresAt,
                               boolean undoAvailable) {}
    public record SessionView(UploadSession session, SizeProfile sizeProfile,
                              long totalBytes, long maxTotalBytes, int maxPages) {}

    /**
     * Crée une nouvelle session de téléversement et retourne le token utilisé
     * par le mobile pour uploader. Si {@code app.originator.enabled=true}
     * (défaut), l'IP d'origine est extraite du header {@code X-Forwarded-For}
     * et liée atomiquement à la session ; toute tentative ultérieure depuis la
     * même IP reçoit un {@code 409 ORIGINATOR_HAS_ACTIVE_SESSION} tant que
     * la session n'est pas finalisée ou supprimée.
     *
     * @param force ré-utilisable avec {@code true} pour purger explicitement
     *              une session bloquante sur la même IP (front : bouton
     *              « démarrer une nouvelle session »).
     */
    @PostMapping
    public Mono<ResponseEntity<CreateSessionResponse>> createSession(
            @RequestBody(required = false) CreateSessionRequest request,
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force,
            ServerWebExchange exchange) {
        // Résoudre l'Origin AVANT toute écriture (création de session,
        // binding originator) : un appel sans Origin ne doit pas consommer
        // de slot Hazelcast en remontant l'échec.
        var origin = resolveOrigin(exchange);
        var uploadBaseUrl = resolveUploadBaseUrl(exchange);
        // SÉCURITÉ : le profil de taille est DÉRIVÉ du header Origin (de
        // confiance, posé par le navigateur, infalsifiable en JS) via la
        // whitelist configurée — origine hors liste → S. La valeur
        // éventuellement présente dans le corps ({@code request.sizeProfile()})
        // est VOLONTAIREMENT IGNORÉE : elle est éditable par l'utilisateur final
        // (attribut DOM du webcomponent, rejeu de la requête) et permettrait une
        // auto-escalade de quota. cf. SessionCreateProperties.
        var sizeProfile = sessionCreateProperties.resolveProfile(origin);
        // Journalise les hôtes hors whitelist fine : ils reçoivent le profil par
        // défaut (S), mais leur apparition signale un domaine de formulaire à
        // peut-être ajouter à la config app.session-create.profiles.
        if (!sessionCreateProperties.hasConfiguredProfile(origin)) {
            log.info("Création de session depuis une origine hors whitelist de profils : {} "
                    + "(profil par défaut {} appliqué)", origin, sizeProfile);
        }
        String originatorIp = null;
        // L'IP est nécessaire pour le verrou originator ET pour le rate-limiter.
        // On résout dès qu'un des deux est actif : un XFF manquant fait remonter
        // un 400 (la résolution doit avoir lieu de toute façon en prod, le
        // firewall pose toujours le header).
        if (originatorProperties.enabled() || rateLimitProperties.enabled()) {
            // Lève OriginatorResolutionException (400 MISSING/INVALID) traduite par
            // GlobalExceptionHandler si XFF manque ou est invalide.
            originatorIp = originatorResolver.resolve(exchange);
        }
        if (rateLimitProperties.enabled() && originatorIp != null) {
            // Bucket par IP, coût surdimensionné si force=true. Vérifié AVANT
            // l'incrément du compteur force et AVANT l'écriture Hazelcast :
            // un attaquant qui DoS ne consomme pas de slot dans l'IMap.
            var decision = rateLimiter.tryAcquire(originatorIp, force);
            if (!decision.accepted()) {
                throw new SessionRateLimitedException(decision.retryAfterSeconds(), force);
            }
        }
        if (originatorProperties.enabled() && force) {
            recordOriginatorForce();
        }
        // Si originator est désactivé mais rate-limit activé, on a une IP qu'on
        // n'utilise pas pour le binding : on la repasse à null pour que
        // createSession garde son comportement actuel (pas de binding).
        var bindingIp = originatorProperties.enabled() ? originatorIp : null;
        // Seule l'écriture Hazelcast part hors event loop (cf. createSessionAsync).
        // Tout ce qui précède reste sur le thread appelant à dessein : ce ne sont
        // que du parsing d'en-têtes et un token bucket en mémoire, sans I/O, et
        // les y garder préserve l'ordre documenté plus haut — un appel sans Origin
        // ou rate-limité est refusé AVANT de consommer le moindre slot Hazelcast.
        return sessionService.createSessionAsync(sizeProfile, bindingIp, force)
                .map(session -> {
                    var uploadUrl = "%s/scan/%s".formatted(uploadBaseUrl, session.token());
                    return ResponseEntity.ok(new CreateSessionResponse(
                            session.token(),
                            uploadUrl,
                            session.expiresAt().toEpochMilli(),
                            session.sizeProfile(),
                            limits.maxTotalBytes(session.sizeProfile()),
                            limits.maxPages()
                    ));
                });
    }

    /**
     * Origin de l'appel, validé présent. Source de confiance double : le profil
     * de taille en est dérivé ({@link SessionCreateProperties#resolveProfile})
     * et le verrou amont (CORS + {@code SameOriginWebFilter}) repose dessus. Un
     * appel sans Origin (curl, server-to-server) est refusé en {@code 400} — il
     * n'a rien à faire sur ce point d'entrée navigateur.
     *
     * @throws IllegalArgumentException ({@code 400}) si {@code Origin} est absent
     *         ou vaut littéralement {@code "null"}.
     */
    private String resolveOrigin(ServerWebExchange exchange) {
        var origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            throw new IllegalArgumentException(
                    "Header Origin manquant — création de session refusée.");
        }
        return origin;
    }

    /**
     * Base URL publique (scheme + host) où le mobile joint la SPA, à embarquer
     * dans le QR code. Construite à partir du header {@code Host} — l'hôte qui
     * sert l'API ET la SPA en prod ({@code televerser.<env>.etat-ge.ch}). On NE
     * l'infère PLUS de l'{@code Origin} : depuis que le webcomponent crée la
     * session depuis la page hôte, l'Origin est celui du formulaire
     * (cross-origin), pas celui de la SPA. Le {@code Host} est de confiance
     * (routage par vhost garanti par le firewall — même hypothèse d'infra que le
     * verrou same-origin). Scheme figé à {@code https} (trafic public TLS).
     *
     * @throws IllegalArgumentException ({@code 400}) si {@code Host} est absent.
     */
    private String resolveUploadBaseUrl(ServerWebExchange exchange) {
        var host = exchange.getRequest().getHeaders().getFirst(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "Header Host manquant — création de session refusée.");
        }
        return "https://" + host;
    }

    /**
     * Supprime explicitement une session (front sur {@code beforeunload} via
     * {@code navigator.sendBeacon}). Libère le binding originator, le sink SSE
     * local et fait un best-effort delete des pages orphelines S3. Idempotent :
     * retourne {@code 204} même si la session n'existe plus, car le navigateur
     * peut renvoyer l'événement {@code beforeunload} plusieurs fois.
     */
    @DeleteMapping("/{token}")
    public Mono<ResponseEntity<Void>> deleteSession(@PathVariable String token) {
        return sessionService.deleteSession(token)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    /** Compteur des relances {@code force=true} : suivi de l'usage du bouton « j'ai fermé l'autre fenêtre ». */
    private void recordOriginatorForce() {
        Counter.builder("televerse.originator.force")
                .description("Sessions créées avec force=true (purge explicite d'un binding originator).")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Flux SSE de la session. {@code 404} si elle est absente ou expirée : un
     * {@code 200} à flux vide faisait reconnecter EventSource toutes les ~3 s ;
     * après un 404 il abandonne, et le watchdog du front (35 s) reprend la main en
     * resynchronisant la session.
     */
    @GetMapping(value = "/{token}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> subscribe(@PathVariable String token) {
        return sessionService.subscribe(token)
                .map(stream -> ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/{token}")
    public Mono<ResponseEntity<SessionView>> getSession(@PathVariable String token) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> getSession(token, session));
    }

    private Mono<ResponseEntity<SessionView>> getSession(String token, UploadSession session) {
        if (session == null) return Mono.just(ResponseEntity.notFound().build());
        var sizeProfile = session.sizeProfile();
        return Mono.just(ResponseEntity.ok(new SessionView(
                session,
                sizeProfile,
                session.totalBytes(),
                limits.maxTotalBytes(sizeProfile),
                limits.maxPages()
        )));
    }

    /**
     * Prolonge la session sans autre action (bouton "Rester connecté" côté UI).
     */
    @PostMapping("/{token}/keep-alive")
    public Mono<ResponseEntity<KeepAliveResponse>> keepAlive(@PathVariable String token) {
        return sessionService.renewSession(token)
                .map(renewed -> ResponseEntity.ok(new KeepAliveResponse(renewed.expiresAt().toEpochMilli())))
                .switchIfEmpty(Mono.just(ResponseEntity.badRequest().build()));
    }

    /**
     * Signale que le téléphone s'est connecté à la session (QR code scanné).
     * Le SPA desktop recevra un événement SSE {@code phone-connected} pour griser l'interface.
     */
    @PostMapping("/{token}/phone-connect")
    public Mono<ResponseEntity<PhoneConnectionResponse>> phoneConnect(@PathVariable String token) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> phoneConnect(token, session));
    }

    private Mono<ResponseEntity<PhoneConnectionResponse>> phoneConnect(String token, UploadSession session) {
        if (session == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new PhoneConnectionResponse("Session expired or invalid", 0L)));
        }

        return sessionService.phoneConnect(token)
                .map(updated -> {
                    var event = UploadEvent.phoneConnected(session.documentId());
                    sessionService.notifyUpload(token, event);
                    return ResponseEntity.ok(new PhoneConnectionResponse(
                            "Phone connected", updated.expiresAt().toEpochMilli()));
                })
                .switchIfEmpty(Mono.just(ResponseEntity.badRequest()
                        .body(new PhoneConnectionResponse("Session expired or invalid", 0L))));
    }

    /**
     * Signale que le téléphone a terminé (bouton "Terminé").
     * Le SPA desktop recevra un événement SSE {@code phone-disconnected} pour réactiver l'interface.
     */
    @PostMapping("/{token}/phone-disconnect")
    public Mono<ResponseEntity<PhoneConnectionResponse>> phoneDisconnect(@PathVariable String token) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> phoneDisconnect(token, session));
    }

    private Mono<ResponseEntity<PhoneConnectionResponse>> phoneDisconnect(String token, UploadSession session) {
        if (session == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new PhoneConnectionResponse("Session expired or invalid", 0L)));
        }

        return sessionService.phoneDisconnect(token)
                .map(updated -> {
                    var event = UploadEvent.phoneDisconnected(session.documentId());
                    sessionService.notifyUpload(token, event);
                    return ResponseEntity.ok(new PhoneConnectionResponse(
                            "Phone disconnected", updated.expiresAt().toEpochMilli()));
                })
                .switchIfEmpty(Mono.just(ResponseEntity.badRequest()
                        .body(new PhoneConnectionResponse("Session expired or invalid", 0L))));
    }

    /**
     * Retourne un aperçu JPEG d'une page uploadée.
     * Sans {@code preview} : sert la vignette du cache Hazelcast (petite taille),
     * régénérée depuis le PDF de la page sur défaut de cache.
     * Avec {@code preview=true} : génère à la volée un aperçu plus grand depuis le
     * PDF de la page, à une largeur <strong>définie côté serveur</strong>
     * ({@code app.preview-width}). La largeur n'est jamais pilotée par le client :
     * un {@code width} arbitraire ferait allouer un bitmap géant côté serveur (OOM),
     * un déni de service trivial à déclencher avec un simple token de session.
     *
     * <p>Le paramètre legacy {@code width} reste toléré pour la compat de
     * déploiement (sa <em>présence</em> demande l'aperçu, sa valeur est ignorée) ;
     * il pourra être retiré une fois le front basculé sur {@code preview=true}.</p>
     */
    @GetMapping("/{token}/pages/{pageId}/thumbnail")
    public Mono<ResponseEntity<byte[]>> getThumbnail(
            @PathVariable String token,
            @PathVariable String pageId,
            @RequestParam(required = false) Boolean preview,
            @RequestParam(required = false) Integer width
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> getThumbnail(token, pageId, preview, width, session));
    }

    private Mono<ResponseEntity<byte[]>> getThumbnail(String token, String pageId, Boolean preview,
                                                      Integer width, UploadSession session) {
        if (session == null) {
            log.info("Thumbnail 404 reason=session-missing token={} pageId={}", token, pageId);
            return Mono.just(ResponseEntity.notFound().build());
        }

        var page = session.pages().stream()
                .filter(p -> p.pageId().equals(pageId))
                .findFirst()
                .orElse(null);
        if (page == null) {
            log.info("Thumbnail 404 reason=page-missing token={} pageId={}", token, pageId);
            return Mono.just(ResponseEntity.notFound().build());
        }

        // Aperçu large à la volée : largeur fixée côté serveur (previewWidth),
        // jamais par le client. Le param legacy `width` ne sert plus que de
        // signal de présence (valeur ignorée) le temps que le front bascule.
        boolean wantsPreview = Boolean.TRUE.equals(preview) || width != null;
        if (wantsPreview) {
            return withMergeAdmission("preview", storageService.generatePreview(page.s3Key(), previewWidth)
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .cacheControl(CacheControl.noCache().cachePrivate())
                            .body(bytes))
                    .onErrorResume(e -> mapStorageFetchError(e, "preview", token, pageId, page.s3Key(),
                            page.originalFilename())));
        }

        // Vignette : cache Hazelcast d'abord — lecture de ~8 Ko, sans admission. Sur
        // défaut (TTL, éviction, cluster injoignable, page sans clé de vignette), la
        // régénération depuis le PDF de la page passe par le bulkhead merge, comme
        // l'aperçu : même coût (GET S3 du PDF complet + rendu PDFBox). Sans cette
        // borne, une perte du cache ou le kill-switch APP_THUMBNAIL_CACHE_ENABLED=false
        // sous charge transformait chaque affichage de liste en N GET S3 + N rendus
        // simultanés. Saturé : 429 MERGE_BUSY, l'<img> du front retombe sur son
        // squelette et se recharge au prochain rafraîchissement de la session.
        // switchIfEmpty sûr ici : le cache émet des octets ou rien (défaut, erreur
        // absorbée), jamais un Mono<Void>.
        return thumbnailCache.get(page.thumbnailKey())
                .map(SessionController::thumbnailResponse)
                .switchIfEmpty(Mono.defer(() -> withMergeAdmission("thumbnail",
                        storageService.regenerateThumbnail(page.s3Key(), page.thumbnailKey(), thumbnailWidth)
                                .map(SessionController::thumbnailResponse)
                                .onErrorResume(e -> mapStorageFetchError(e, "thumbnail", token, pageId,
                                        page.s3Key(), page.originalFilename())))));
    }

    private static ResponseEntity<byte[]> thumbnailResponse(byte[] bytes) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(300)).cachePrivate())
                .body(bytes);
    }

    /**
     * Aperçu du PDF composite avant finalisation : fusionne les pages courantes en
     * mémoire et renvoie le résultat. N'écrit rien sur S3 et ne supprime pas les
     * pages — appelable de façon répétée pendant que la session se construit.
     */
    @GetMapping("/{token}/preview.pdf")
    public Mono<ResponseEntity<byte[]>> getPreview(@PathVariable String token) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> getPreview(token, session));
    }

    private Mono<ResponseEntity<byte[]>> getPreview(String token, UploadSession session) {
        if (session == null || session.pages().isEmpty()) {
            log.info("Preview.pdf 404 reason={} token={}",
                    session == null ? "session-missing" : "no-pages", token);
            return Mono.just(ResponseEntity.notFound().build());
        }

        var pageKeys = session.pages().stream().map(PageInfo::s3Key).toList();
        var disposition = ContentDisposition.inline().filename("preview.pdf").build();

        return withMergeAdmission("preview-pdf", storageService.mergePagesPreview(pageKeys)
                .map(pdf -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                        .cacheControl(CacheControl.noCache().cachePrivate())
                        .body(pdf)));
    }

    /**
     * Fusionne toutes les pages individuelles en un seul PDF sur S3,
     * puis envoie l'événement session-complete au desktop.
     *
     * <p>Méthode nommée {@code complete} (et non {@code finalize}, qui masquerait
     * {@link Object#finalize()} — règle Sonar S1175) ; l'operationId publié reste
     * {@code finalize} pour ne pas changer le contrat de docs/openapi.</p>
     */
    @Operation(operationId = "finalize")
    @PostMapping("/{token}/finalize")
    public Mono<ResponseEntity<FinalizeResponse>> complete(@PathVariable String token) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> complete(token, session));
    }

    private Mono<ResponseEntity<FinalizeResponse>> complete(String token, UploadSession session) {
        if (session == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new FinalizeResponse(null, "Session expired or invalid")));
        }

        // Tout sous le bulkhead : un rejet d'admission (FINALIZE_BUSY) arrive avant
        // beginFinalize et ne laisse donc jamais la session figée.
        return withFinalizeAdmission(sessionService.beginFinalize(token, finalizeLease)
                .flatMap(start -> switch (start.state()) {
                    // Finalisation rejouée (client parti avant la réponse, retry) : même
                    // documentId, le PDF composite existe déjà.
                    case ALREADY_FINALIZED -> Mono.just(ResponseEntity.ok(
                            new FinalizeResponse(start.session().documentId(), "Session finalized")));
                    // Une autre finalisation de cette session est en vol : retryable, le
                    // rejeu tombera sur ALREADY_FINALIZED une fois la fusion terminée.
                    case IN_PROGRESS -> Mono.<ResponseEntity<FinalizeResponse>>error(
                            new FinalizeBusyException(finalizeRetryAfterSeconds));
                    case STARTED -> start.session().pages().isEmpty()
                            ? sessionService.abortFinalize(token).thenReturn(ResponseEntity.badRequest()
                                    .body(new FinalizeResponse(null, "Aucune page à fusionner")))
                            : runFinalize(token, start.session());
                })
                .switchIfEmpty(Mono.fromSupplier(() -> ResponseEntity.badRequest()
                        .body(new FinalizeResponse(null, "Session expired or invalid")))));
    }

    /**
     * Fusion d'une session figée en {@code FINALIZING} par {@link SessionService#beginFinalize} :
     * plus aucune page ne peut y être ajoutée, éditée ou retirée pendant la fusion.
     *
     * <p>Ordre : PDF composite écrit, PUIS session passée en {@code FINALIZED} (pages
     * dé-listées), PUIS purge best effort des objets des pages. Les pages étaient
     * supprimées dans le chemin de succès de la fusion : un DELETE coupé faisait
     * échouer la finalisation alors que le composite existait, et chaque rejeu
     * échouait ensuite sur les pages déjà supprimées. Tout échec avant la bascule
     * (fusion, délai, client parti) rouvre la session ; le composite, écrit sous une
     * clé fixe, sera réécrit à l'identique par la finalisation suivante.</p>
     */
    private Mono<ResponseEntity<FinalizeResponse>> runFinalize(String token, UploadSession frozen) {
        var pageKeys = frozen.pages().stream().map(PageInfo::s3Key).toList();
        // Toutes les clés des pages : versions courantes, précédentes et initiales (S3),
        // vignettes associées (cache Hazelcast).
        var s3Keys = frozen.pages().stream().flatMap(p -> p.s3Keys().stream()).toList();
        var thumbnailKeys = frozen.pages().stream().flatMap(p -> p.thumbnailKeys().stream()).toList();
        var totalPages = frozen.pages().size();

        return storageService.mergeAndFinalize(pageKeys, frozen.documentId())
                .timeout(finalizeTimeout)
                .flatMap(outcome -> sessionService.completeFinalize(token)
                        // Sur boundedElastic (fin de la section verrouillée) : les appels
                        // Hazelcast bloquants qui suivent ne tiennent pas le thread de
                        // complétion du SDK S3.
                        .map(done -> afterFinalize(token, frozen, outcome, totalPages, s3Keys))
                        // Session disparue pendant la fusion (TTL, suppression) : le
                        // composite existe, il reste la réponse ; purge quand même.
                        .defaultIfEmpty(outcome)
                        .flatMap(o -> thumbnailCache.deleteAll(thumbnailKeys)
                                .thenReturn(ResponseEntity.ok(new FinalizeResponse(o.documentId(), "Session finalized")))))
                .onErrorResume(TimeoutException.class, e -> reopenAfterFailedFinalize(token)
                        .thenReturn(ResponseEntity.status(504)
                                .body(new FinalizeResponse(null, "La fusion a dépassé le délai maximum"))))
                .onErrorResume(e -> !(e instanceof TimeoutException),
                        e -> reopenAfterFailedFinalize(token).then(Mono.error(e)))
                // Client parti (onglet fermé, timeout du navigateur) : la fusion est
                // annulée, la session est rouverte au lieu de rester figée jusqu'à
                // l'échéance de finalizeLease.
                .doOnCancel(() -> reopenAfterFailedFinalize(token).subscribe());
    }

    /**
     * Effets d'une finalisation réussie, une fois la session passée en
     * {@code FINALIZED} : métriques, libération du binding originator, événement
     * {@code session-complete}, purge best effort (bornée) des objets S3 des pages.
     */
    private StorageService.FinalizeOutcome afterFinalize(String token, UploadSession frozen,
                                                         StorageService.FinalizeOutcome outcome,
                                                         int totalPages, List<String> s3Keys) {
        sessionPagesCount.record(totalPages);
        recordSessionFinalBytes(frozen.sizeProfile(), outcome.bytes());
        // Libère le slot « une session par IP » dès que le PDF composite est écrit :
        // l'utilisateur peut relancer une session depuis le même poste sans attendre
        // l'expiration du binding.
        sessionService.releaseOriginator(frozen);
        sessionService.notifyUpload(token, UploadEvent.sessionComplete(outcome.documentId(), totalPages));
        storageService.deleteAllBestEffort(s3Keys)
                .subscribe(unused -> {}, err -> log.warn("Finalize page purge failed token={} error={}",
                        token, err.toString()));
        return outcome;
    }

    /** Rouvre une session dont la finalisation a échoué ; best effort (l'échéance du lease reste le filet). */
    private Mono<Void> reopenAfterFailedFinalize(String token) {
        return sessionService.abortFinalize(token)
                .onErrorResume(e -> {
                    log.warn("Finalize abort failed token={} error={}", token, e.toString());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Applique la correction de perspective sur une page existante.
     * En cas d'échec de détection du document, retourne applied=false et l'image reste inchangée.
     */
    @PostMapping("/{token}/pages/{pageId}/deskew")
    public Mono<ResponseEntity<DeskewResponse>> deskewPage(
            @PathVariable String token,
            @PathVariable String pageId
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> applyEdit("deskew", token, pageId, session,
                (page, keys) -> storageService.deskewPage(page.s3Key(), keys.pageKey(), keys.thumbnailKey()),
                DeskewResponse::new, "Correction de perspective appliquée"));
    }

    /**
     * Recadre automatiquement une page sur le document détecté (bounding rectangle).
     * Contrairement au deskew, ne corrige pas la perspective — conserve les pixels originaux.
     * En cas d'échec de détection, retourne applied=false et l'image reste inchangée.
     */
    @PostMapping("/{token}/pages/{pageId}/crop")
    public Mono<ResponseEntity<CropResponse>> cropPage(
            @PathVariable String token,
            @PathVariable String pageId
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> applyEdit("crop", token, pageId, session,
                (page, keys) -> storageService.cropPage(page.s3Key(), keys.pageKey(), keys.thumbnailKey()),
                CropResponse::new, "Recadrage appliqué"));
    }

    /**
     * Applique la correction de balance des blancs sur une page existante.
     */
    @PostMapping("/{token}/pages/{pageId}/white-balance")
    public Mono<ResponseEntity<WhiteBalanceResponse>> whiteBalancePage(
            @PathVariable String token,
            @PathVariable String pageId
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> applyEdit("white-balance", token, pageId, session,
                (page, keys) -> storageService.whiteBalancePage(page.s3Key(), keys.pageKey(), keys.thumbnailKey())
                        .map(size -> new StorageService.DeskewResult(size, true)),
                (message, applied, totalBytes, maxTotalBytes, expiresAt, undoAvailable) ->
                        new WhiteBalanceResponse(message, totalBytes, maxTotalBytes, expiresAt, undoAvailable),
                "Balance des blancs appliquée"));
    }

    /**
     * Applique une rotation de 90° (horaire ou anti-horaire) sur une page existante.
     *
     * @param direction "cw" (clockwise) ou "ccw" (counter-clockwise)
     */
    @PostMapping("/{token}/pages/{pageId}/rotate")
    public Mono<ResponseEntity<RotateResponse>> rotatePage(
            @PathVariable String token,
            @PathVariable String pageId,
            @RequestParam(defaultValue = "cw") String direction
    ) {
        boolean clockwise = !"ccw".equalsIgnoreCase(direction);
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> applyEdit("rotate", token, pageId, session,
                (page, keys) -> storageService.rotatePage(page.s3Key(), clockwise, keys.pageKey(), keys.thumbnailKey())
                        .map(size -> new StorageService.DeskewResult(size, true)),
                (message, applied, totalBytes, maxTotalBytes, expiresAt, undoAvailable) ->
                        new RotateResponse(message, totalBytes, maxTotalBytes, expiresAt, undoAvailable),
                "Page tournée"));
    }

    /** Clés S3 et de cache d'une nouvelle version de page. */
    record VersionKeys(String pageKey, String thumbnailKey) {}

    /**
     * Corps de réponse d'une édition, par endpoint : les quatre partagent la même forme,
     * {@code applied} n'existant que pour crop et deskew.
     */
    @FunctionalInterface
    private interface EditBody<R> {
        R of(String message, boolean applied, long totalBytes, long maxTotalBytes, long expiresAt,
             boolean undoAvailable);
    }

    /** Écriture de la nouvelle version d'une page, sous les clés neuves données. */
    @FunctionalInterface
    private interface EditStorage {
        Mono<StorageService.DeskewResult> write(PageInfo page, VersionKeys keys);
    }

    /**
     * Clés neuves pour la prochaine version d'une page ({@code <préfixe>/page-001-3f2a9c1e.pdf}
     * et sa vignette), dans le préfixe de la version courante. Suffixe aléatoire (UUID) :
     * deux éditions concurrentes de la même page n'écrivent jamais sous la même clé.
     */
    static VersionKeys newVersionKeys(PageInfo page) {
        var prefix = page.s3Key().substring(0, page.s3Key().lastIndexOf('/') + 1);
        var base = prefix + page.pageId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return new VersionKeys(base + ".pdf", base + "-thumb.jpg");
    }

    /**
     * Pipeline commun des quatre éditions (rotate / crop / deskew / white-balance).
     *
     * <ol>
     *   <li>La nouvelle version est écrite sous des clés NEUVES : rien d'existant n'est
     *       réécrit.</li>
     *   <li>La session l'adopte sous verrou ({@link SessionService#commitPageEdit}), si la
     *       page existe encore, n'a pas changé depuis la lecture et tient dans le quota.</li>
     *   <li>Adoptée : les clés que la page ne référence plus sont purgées. Refusée (conflit,
     *       verrou non obtenu, session disparue) : la nouvelle version est supprimée, et
     *       rien d'autre n'a bougé.</li>
     * </ol>
     *
     * <p>Les éditions écrasaient la page et son {@code -prev} en place AVANT de prendre
     * le verrou, et le front rejoue {@code SESSION_BUSY} : un verrou contesté faisait
     * tourner la page deux ou trois fois pour un clic, recapturait l'état « initial »
     * sur une version déjà éditée, et deux éditions concurrentes se perdaient. Ici un
     * rejeu repart de la session inchangée : il s'applique une seule fois.</p>
     */
    private <R> Mono<ResponseEntity<R>> applyEdit(String op, String token, String pageId, UploadSession session,
                                                  EditStorage storage, EditBody<R> body, String appliedMessage) {
        if (session == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(body.of("Session expired or invalid", false, 0L, 0L, 0L, false)));
        }
        var page = session.findPage(pageId);
        if (page == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        var maxTotalBytes = limits.maxTotalBytes(session.sizeProfile());
        var keys = newVersionKeys(page);

        return withEditAdmission(op, storage.write(page, keys)
                // Image embarquée illisible / hors budget pixels → 422 (contenu
                // inexploitable) plutôt que 500 — même sémantique que l'upload.
                .onErrorMap(IOException.class, e -> new CorruptFileException(page.originalFilename(), e))
                .flatMap(result -> {
                    if (!result.applied()) {
                        return notApplied(token, session, page, maxTotalBytes, body);
                    }
                    return commitEdit(token, page, keys,
                            current -> current.withNewVersion(keys.pageKey(), keys.thumbnailKey(), result.newSize()),
                            maxTotalBytes)
                            .map(commit -> ResponseEntity.ok(body.of(appliedMessage, true,
                                    commit.session().totalBytes(), maxTotalBytes,
                                    commit.session().expiresAt().toEpochMilli(), true)))
                            // Session disparue avant l'adoption : la version écrite ne sert
                            // plus à rien.
                            .switchIfEmpty(Mono.defer(() -> discardVersion(keys).thenReturn(ResponseEntity.ok(
                                    body.of("Session expired or invalid", false, 0L, maxTotalBytes, 0L, false)))));
                }));
    }

    /**
     * Correction non appliquée (pas de document détecté) : rien n'a été écrit. Renouvelle
     * la session — l'usager était actif — et le signale à l'autre appareil.
     */
    private <R> Mono<ResponseEntity<R>> notApplied(String token, UploadSession session, PageInfo page,
                                                   long maxTotalBytes, EditBody<R> body) {
        var message = "Aucun document détecté — image inchangée";
        return sessionService.renewSession(token)
                .map(renewed -> {
                    sessionService.notifyUpload(token, UploadEvent.pageEdited(session.documentId(), page.pageId(),
                            renewed.pages().size(), renewed.totalBytes(), maxTotalBytes));
                    return ResponseEntity.ok(body.of(message, false, session.totalBytes(), maxTotalBytes,
                            renewed.expiresAt().toEpochMilli(), page.previousVersion() != null));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> ResponseEntity.ok(body.of(message, false,
                        session.totalBytes(), maxTotalBytes, 0L, page.previousVersion() != null))));
    }

    /**
     * Fait adopter une nouvelle version par la session, puis purge ce que la page ne
     * référence plus et notifie l'autre appareil. Si l'adoption est refusée sans que
     * rien n'ait été écrit ({@link SessionService#isNotApplied}), la nouvelle version
     * est supprimée avant de propager l'erreur (409 / 413 / 429 SESSION_BUSY).
     *
     * @param newVersion clés de la version écrite par cette requête, ou null (undo /
     *                   reset : aucune écriture préalable)
     */
    private Mono<SessionService.PageCommit> commitEdit(String token, PageInfo page, VersionKeys newVersion,
                                                       UnaryOperator<PageInfo> edit, long maxTotalBytes) {
        return sessionService.commitPageEdit(token, page.pageId(), page.lastModifiedAt(), edit, maxTotalBytes)
                .doOnNext(commit -> {
                    purgeDroppedKeys(token, commit);
                    sessionService.notifyUpload(token, UploadEvent.pageEdited(commit.session().documentId(),
                            page.pageId(), commit.session().pages().size(), commit.session().totalBytes(),
                            maxTotalBytes));
                })
                .onErrorResume(SessionService::isNotApplied, e -> newVersion == null
                        ? Mono.error(e)
                        : discardVersion(newVersion).then(Mono.error(e)));
    }

    /** Purge best effort des clés que la page adoptée ne référence plus. */
    private void purgeDroppedKeys(String token, SessionService.PageCommit commit) {
        var s3Keys = commit.before().s3KeysDroppedBy(commit.after());
        var thumbnailKeys = commit.before().thumbnailKeysDroppedBy(commit.after());
        storageService.deleteAllBestEffort(s3Keys)
                .then(thumbnailCache.deleteAll(thumbnailKeys))
                .subscribe(unused -> {}, err -> log.warn("Dropped version purge failed token={} pageId={} error={}",
                        token, commit.after().pageId(), err.toString()));
    }

    /** Supprime une version écrite mais jamais adoptée par la session. */
    private Mono<Void> discardVersion(VersionKeys keys) {
        return storageService.deleteAllBestEffort(List.of(keys.pageKey()))
                .then(thumbnailCache.delete(keys.thumbnailKey()));
    }

    /**
     * Annule la dernière édition (rotate / white-balance / deskew / crop) en
     * revenant à la version sauvegardée. Idempotent : retourne 200 sans rien
     * faire si aucune sauvegarde n'est disponible.
     *
     * <p>Borné par {@code app.undo-timeout} (cf. {@link #withUndoBound}) : c'est le geste
     * de RATTRAPAGE — celui que l'utilisateur tente quand une édition vient d'échouer,
     * donc sur un pod déjà dégradé.</p>
     *
     * <p>La page pointe de nouveau sur les clés de la version précédente (aucune copie
     * S3), adoptée sous verrou ; la version annulée est purgée APRÈS. Restaurer d'abord
     * par copie puis supprimer la sauvegarde laissait, si la mise à jour de session
     * échouait ensuite, une page dont chaque rejeu échouait sur la sauvegarde disparue.</p>
     */
    @PostMapping("/{token}/pages/{pageId}/undo")
    public Mono<ResponseEntity<UndoResponse>> undoPage(
            @PathVariable String token,
            @PathVariable String pageId
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withUndoBound("undo", withSession(token, session -> revertPage(token, pageId, session,
                PageInfo::previousVersion, PageInfo::restoredFromBackup, "Modification annulée")));
    }

    /**
     * Réinitialise une page à son état d'upload initial (« Annuler tout ») : la page
     * pointe de nouveau sur la version {@code initialVersion}, les deux sauvegardes sont
     * effacées puis leurs objets purgés. Idempotent : retourne 200 sans rien faire si
     * aucun état initial n'est disponible (page jamais éditée ou déjà réinitialisée).
     *
     * <p>Même borne et même principe que {@link #undoPage}.</p>
     */
    @PostMapping("/{token}/pages/{pageId}/reset")
    public Mono<ResponseEntity<UndoResponse>> resetPage(
            @PathVariable String token,
            @PathVariable String pageId
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withUndoBound("reset", withSession(token, session -> revertPage(token, pageId, session,
                PageInfo::initialVersion, PageInfo::restoredFromInitial, "Modifications annulées")));
    }

    /**
     * Undo / reset : retour à une version sauvegardée, adoptée sous verrou (même
     * contrôle d'existence et de fraîcheur que les éditions), puis purge de ce que la
     * page ne référence plus.
     *
     * @param target  version visée ({@code previousVersion} / {@code initialVersion})
     * @param restore passage de la page à cette version
     */
    private Mono<ResponseEntity<UndoResponse>> revertPage(String token, String pageId, UploadSession session,
                                                          Function<PageInfo, PageInfo.PreviousVersion> target,
                                                          UnaryOperator<PageInfo> restore, String doneMessage) {
        if (session == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new UndoResponse("Session expired or invalid", 0L, 0L, 0L, false)));
        }
        var page = session.findPage(pageId);
        if (page == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        var maxTotalBytes = limits.maxTotalBytes(session.sizeProfile());
        if (target.apply(page) == null) {
            var message = "Aucune modification à annuler";
            return sessionService.renewSession(token)
                    .map(renewed -> {
                        sessionService.notifyUpload(token, UploadEvent.pageEdited(session.documentId(), pageId,
                                renewed.pages().size(), renewed.totalBytes(), maxTotalBytes));
                        return ResponseEntity.ok(new UndoResponse(message, session.totalBytes(),
                                maxTotalBytes, renewed.expiresAt().toEpochMilli(), page.previousVersion() != null));
                    })
                    .switchIfEmpty(Mono.fromSupplier(() -> ResponseEntity.ok(new UndoResponse(message,
                            session.totalBytes(), maxTotalBytes, 0L, page.previousVersion() != null))));
        }
        // Pas de contrôle de quota sur un retour arrière : il ramène à un état déjà admis.
        return commitEdit(token, page, null, restore, Long.MAX_VALUE)
                .map(commit -> ResponseEntity.ok(new UndoResponse(doneMessage,
                        commit.session().totalBytes(), maxTotalBytes,
                        commit.session().expiresAt().toEpochMilli(), commit.after().previousVersion() != null)))
                .switchIfEmpty(Mono.fromSupplier(() -> ResponseEntity.ok(new UndoResponse(
                        "Session expired or invalid", 0L, maxTotalBytes, 0L, false))));
    }

    /**
     * Histogramme de la taille du PDF composite final, taggé par profil pour
     * observer la distribution par tee-shirt size. Le builder Micrometer est
     * idempotent sur {@code name+tags} : pas de coût de re-création à chaque
     * appel — il retourne l'instance déjà enregistrée.
     */
    private void recordSessionFinalBytes(SizeProfile sizeProfile, long bytes) {
        DistributionSummary.builder("televerse.session.final.bytes")
                .description("Taille du PDF composite final après fusion.")
                .baseUnit("bytes")
                .serviceLevelObjectives(sessionFinalBytesSlos)
                .tag("sizeProfile", sizeProfile != null ? sizeProfile.name() : "unknown")
                .register(meterRegistry)
                .record(bytes);
    }

    /**
     * Supprime une page individuelle de la session, puis ses objets.
     *
     * <p>Retrait de la session d'abord, sous verrou, puis purge des objets de la page
     * TELLE QUE LE VERROU L'A VUE (une édition a pu en changer les clés). L'ordre
     * inverse laissait, sur un verrou refusé, une page listée dont les objets
     * n'existaient plus.</p>
     */
    @DeleteMapping("/{token}/pages/{pageId}")
    public Mono<ResponseEntity<DeletePageResponse>> deletePage(
            @PathVariable String token,
            @PathVariable String pageId
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> deletePage(token, pageId, session));
    }

    private Mono<ResponseEntity<DeletePageResponse>> deletePage(String token, String pageId, UploadSession session) {
        if (session == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new DeletePageResponse("Session expired or invalid", 0, 0L, 0L, 0L)));
        }
        if (session.findPage(pageId) == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        var maxTotalBytes = limits.maxTotalBytes(session.sizeProfile());

        return sessionService.removePage(token, pageId)
                .map(removal -> {
                    var removed = removal.removed();
                    if (removed != null) {
                        storageService.deleteAllBestEffort(removed.s3Keys())
                                .then(thumbnailCache.deleteAll(removed.thumbnailKeys()))
                                .subscribe(unused -> {}, err -> log.warn(
                                        "Deleted page purge failed token={} pageId={} error={}",
                                        token, pageId, err.toString()));
                    }
                    var updated = removal.session();
                    var totalPages = updated.pages().size();
                    var totalBytes = updated.totalBytes();
                    sessionService.notifyUpload(token, UploadEvent.pageDeleted(session.documentId(), pageId,
                            totalPages, totalBytes, maxTotalBytes));
                    return ResponseEntity.ok(new DeletePageResponse(
                            "Page supprimée", totalPages, totalBytes, maxTotalBytes,
                            updated.expiresAt().toEpochMilli()));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> ResponseEntity.ok(new DeletePageResponse(
                        "Page supprimée", 0, 0L, maxTotalBytes, 0L))));
    }

    /**
     * Réordonne les pages de la session.
     */
    @PutMapping("/{token}/pages/order")
    public Mono<ResponseEntity<ReorderResponse>> reorderPages(
            @PathVariable String token,
            @RequestBody ReorderRequest request
    ) {
        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> reorderPages(token, request, session));
    }

    private Mono<ResponseEntity<ReorderResponse>> reorderPages(String token, ReorderRequest request,
                                                               UploadSession session) {
        if (session == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new ReorderResponse("Session expired or invalid", 0, 0L)));
        }
        if (request == null || request.pageIds() == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new ReorderResponse("Liste de pages invalide", session.pages().size(), 0L)));
        }

        // Pré-contrôle sur la lecture : une liste d'emblée fausse est un 400 sans verrou.
        // Le contrôle qui fait foi est refait sous le verrou (409 PAGES_CHANGED si la
        // session a bougé entre-temps).
        var existingIds = new HashSet<>(session.pages().stream().map(PageInfo::pageId).toList());
        var requestedIds = new HashSet<>(request.pageIds());
        if (!existingIds.equals(requestedIds) || existingIds.size() != request.pageIds().size()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(new ReorderResponse("Liste de pages invalide", session.pages().size(), 0L)));
        }

        var maxTotalBytes = limits.maxTotalBytes(session.sizeProfile());

        return sessionService.reorderPages(token, request.pageIds())
                .map(updated -> {
                    var totalPages = updated.pages().size();
                    var event = UploadEvent.pagesReordered(session.documentId(), totalPages,
                            updated.totalBytes(), maxTotalBytes);
                    sessionService.notifyUpload(token, event);
                    return ResponseEntity.ok(new ReorderResponse(
                            "Pages réordonnées", totalPages, updated.expiresAt().toEpochMilli()));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> ResponseEntity.badRequest()
                        .body(new ReorderResponse("Session expired or invalid", 0, 0L))));
    }
}

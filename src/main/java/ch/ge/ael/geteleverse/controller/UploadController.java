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
import ch.ge.ael.geteleverse.config.AdmissionConfig;
import ch.ge.ael.geteleverse.controller.GlobalExceptionHandler.ErrorResponse;
import ch.ge.ael.geteleverse.service.SessionService;
import ch.ge.ael.geteleverse.service.StorageService;
import ch.ge.ael.geteleverse.service.ThumbnailCacheService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/upload")
@Slf4j
public class UploadController {

    private final SessionService sessionService;
    private final StorageService storageService;
    private final PdfService pdfService;
    private final GhostscriptService ghostscriptService;
    private final AntivirusService antivirusService;
    private final LimitsProperties limits;
    private final UploadFormatProperties uploadFormats;
    private final DistributionSummary uploadRawBytes;
    private final Bulkhead uploadBulkhead;
    private final MeterRegistry meterRegistry;
    private final ThumbnailCacheService thumbnailCache;

    public UploadController(SessionService sessionService, StorageService storageService,
                            PdfService pdfService, GhostscriptService ghostscriptService,
                            AntivirusService antivirusService,
                            LimitsProperties limits, UploadFormatProperties uploadFormats,
                            DistributionSummary uploadRawBytes,
                            @Qualifier(AdmissionConfig.UPLOAD_BULKHEAD) Bulkhead uploadBulkhead,
                            MeterRegistry meterRegistry, ThumbnailCacheService thumbnailCache) {
        this.sessionService = sessionService;
        this.thumbnailCache = thumbnailCache;
        this.storageService = storageService;
        this.pdfService = pdfService;
        this.ghostscriptService = ghostscriptService;
        this.antivirusService = antivirusService;
        this.limits = limits;
        this.uploadFormats = uploadFormats;
        this.uploadRawBytes = uploadRawBytes;
        this.uploadBulkhead = uploadBulkhead;
        this.meterRegistry = meterRegistry;
    }

    public record UploadResponse(String documentId, String message, Long expiresAt,
                                 String pageId, int pageIndex, int totalPages,
                                 String thumbnailUrl,
                                 SizeProfile sizeProfile,
                                 long totalBytes,
                                 long maxTotalBytes,
                                 int pagesAdded,
                                 String errorCode) {}

    /**
     * Triplet collecté par page attachée dans {@link #processPdfPages} : permet
     * au mapping final de construire la réponse depuis le dernier attachement
     * sans relire la session (lecture qui rouvrirait la fenêtre de race fermée
     * en intégrant l'expiry au verrou de {@code SessionService.attachPage}).
     */
    private record AttachedPage(String pageId, UploadSession session, Instant uploadedAt, String thumbnailKey) {}

    /**
     * Quota cumulé de la session dépassé (413). Discriminateur stable consommé
     * par {@code ge-televerse-front} (cf. {@code useUpload.js}) pour réutiliser
     * la mise en forme « Impossible de joindre votre fichier » en cas de course
     * entre deux appareils — le pré-contrôle local du front ne voit pas l'upload
     * concurrent qui fait franchir le cap entre deux requêtes.
     */
    public static final String ERROR_SESSION_SIZE_EXCEEDED = "SESSION_SIZE_EXCEEDED";
    /** Limite du nombre de pages atteinte (429). */
    public static final String ERROR_PAGE_LIMIT = "PAGE_LIMIT_REACHED";
    /** PDF multi-pages plus volumineux que les slots restants (429). */
    public static final String ERROR_PDF_TOO_MANY_PAGES = "PDF_TOO_MANY_PAGES";
    /**
     * Extension de fichier hors {@code app.upload.allowed-formats} (415).
     * Le front pré-filtre par media-type ; ce code matérialise le contrôle
     * indépendant côté back qui ne fait pas confiance au {@code Content-Type}
     * envoyé par le client.
     */
    public static final String ERROR_INVALID_FILE_FORMAT = "INVALID_FILE_FORMAT";
    /**
     * Page déjà présente dans la session : re-upload octet pour octet du même
     * fichier (429). Garde-fou anti-spam — l'empreinte SHA-256 du contenu brut
     * est comparée aux pages déjà attachées. Non-retryable à l'identique (le
     * front doit soit ignorer, soit inviter à supprimer d'abord la page). Compté
     * dans {@code televerse.upload.rejections} pour la visibilité Grafana d'un
     * abus. Une suppression de page libère l'empreinte : le re-upload repasse.
     */
    public static final String ERROR_DUPLICATE_PAGE = "DUPLICATE_PAGE";

    public record AllowedFormatsResponse(List<String> allowedFormats) {}

    /**
     * Liste publique des extensions autorisées à l'upload. Consommée par les
     * deux fronts (webcomponent desktop, app mobile) pour éviter de dupliquer
     * la valeur côté JS. Pas de cache HTTP : la liste change rarement mais doit
     * suivre une recharge ConfigMap sans purge CDN.
     */
    @GetMapping("/formats")
    public AllowedFormatsResponse getAllowedFormats() {
        return new AllowedFormatsResponse(uploadFormats.allowedFormats());
    }

    @PostMapping("/{token}")
    public Mono<ResponseEntity<?>> upload(
            @PathVariable String token,
            @RequestPart("file") FilePart filePart
    ) {
        // NB : ne pas lire filePart.headers() ici. Les rejets précoces (session invalide,
        // limite de pages, extension interdite) ne dépendent pas du Content-Type et doivent
        // court-circuiter avant toute lecture d'en-tête. Le Content-Type est loggé plus bas,
        // une fois l'upload accepté pour bufferisation.
        log.debug("Upload request token={} filename={}", token, filePart.filename());

        // Session chargée hors event loop (cf. withSession).
        return withSession(token, session -> upload(token, filePart, session));
    }

    /**
     * Charge la session hors de l'event loop Netty avant de dérouler l'upload.
     * Même motif — et mêmes conséquences quand il manque — que
     * {@link SessionController} : voir {@link SessionService#getSessionAsync(String)}.
     */
    private <T> Mono<T> withSession(String token, Function<UploadSession, Mono<T>> handler) {
        return sessionService.getSessionAsync(token)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybe -> handler.apply(maybe.orElse(null)));
    }

    private Mono<ResponseEntity<?>> upload(String token, FilePart filePart, UploadSession session) {
        if (session == null) {
            log.debug("Upload rejected: invalid or expired session token={}", token);
            return Mono.just(ResponseEntity.badRequest()
                    .body(invalidSessionResponse()));
        }

        var sizeProfile = session.sizeProfile();
        var maxTotalBytes = limits.maxTotalBytes(sizeProfile);

        log.debug("Upload session found token={} profile={} pages={} totalBytes={} maxTotalBytes={} maxPages={}",
                token, sizeProfile, session.pages().size(), session.totalBytes(), maxTotalBytes, limits.maxPages());

        if (session.pages().size() >= limits.maxPages()) {
            log.warn("Page limit reached token={} maxPages={}", token, limits.maxPages());
            recordRejection(ERROR_PAGE_LIMIT, sizeProfile);
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(pageLimitResponse(session, sizeProfile, maxTotalBytes)));
        }

        // Contrôle indépendant côté back de l'extension du fichier — on ne fait
        // pas confiance au Content-Type envoyé par le client (trivialement
        // forgeable). Le rejet se fait AVANT bufferFilePart pour ne pas charger en
        // mémoire ni scanner un fichier d'emblée invalide. La part, elle, est déjà
        // reçue à ce stade : le codec l'a lue en résolvant le @RequestPart, dans la
        // limite de spring.webflux.multipart.max-disk-usage-per-part.
        var filename = filePart.filename();
        if (!uploadFormats.isAllowed(filename)) {
            log.warn("Invalid file format token={} filename={} allowed={}",
                    token, filename, uploadFormats.allowedFormats());
            recordRejection(ERROR_INVALID_FILE_FORMAT, sizeProfile);
            var displayName = (filename == null || filename.isBlank()) ? "(fichier sans nom)" : filename;
            var message = "%s: Le fichier doit être au format autorisé (%s)"
                    .formatted(displayName, uploadFormats.formattedList());
            return Mono.just(ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(new UploadResponse(
                            session.documentId(),
                            message,
                            null, null, -1, session.pages().size(), null,
                            sizeProfile, session.totalBytes(), maxTotalBytes, 0, ERROR_INVALID_FILE_FORMAT)));
        }

        var contentType = filePart.headers().getContentType() != null
                ? Objects.requireNonNull(filePart.headers().getContentType()).toString()
                : "application/octet-stream";

        log.debug("Upload accepted for buffering token={} filename={} contentType={}",
                token, filename, contentType);

        return storageService.bufferFilePart(filePart)
                .doOnNext(uploadBytes -> log.debug("Upload buffered token={} filename={} bytes={}",
                        token, filename, uploadBytes.length))
                // Empreinte du contenu BRUT (avant conversion/split), stable pour un
                // re-upload octet pour octet. Calcul SHA-256 sur boundedElastic — jusqu'à
                // 50 Mo, on ne veut pas occuper la boucle d'événements Netty (même
                // discipline que la conversion/split en aval).
                .flatMap(uploadBytes -> Mono.fromCallable(() -> sha256Hex(uploadBytes))
                        .subscribeOn(Schedulers.boundedElastic())
                        .<ResponseEntity<?>>flatMap(uploadHash -> {
                    // Pré-contrôle anti-spam hors verrou sur le snapshot de session :
                    // court-circuite AV + conversion + S3 pour le cas courant (double-tap,
                    // retry réseau, boucle d'abus). La course strictement concurrente —
                    // deux requêtes identiques avant qu'aucune n'ait attaché, donc invisible
                    // de ce snapshot — est refermée sous le verrou par attachPage(...,
                    // rejectDuplicate=true) sur le chemin image.
                    if (isDuplicateUpload(session, uploadHash)) {
                        log.info("Duplicate upload rejected (pre-check) token={} filename={} hash={}",
                                token, filename, uploadHash);
                        recordRejection(ERROR_DUPLICATE_PAGE, sizeProfile);
                        return Mono.<ResponseEntity<?>>just(duplicateResponse(session, sizeProfile, maxTotalBytes));
                    }

                    // Scan AV unique en amont de la branche image/PDF (les CVE de parseurs
                    // d'images sont rares mais le coût marginal est faible). Refus → 422
                    // INFECTED_FILE ; clamd injoignable + fail-mode=closed → 503
                    // ANTIVIRUS_UNAVAILABLE. Le bulkhead reste en aval (cf. transformDeferred)
                    // donc clamd ne voit jamais plus de scans concurrents que la capacité
                    // d'admission du pod (app.admission.upload.max-concurrent).
                    log.debug("Antivirus scan start token={} filename={}", token, filename);
                    return antivirusService.scan(uploadBytes, filePart.filename())
                            .doOnSuccess(unused -> log.debug("Antivirus scan OK token={} filename={}", token, filename))
                            .thenReturn(uploadBytes)
                            .<ResponseEntity<?>>flatMap(scanned -> {
                                // Volumétrie brute reçue — avant conversion image→PDF, recompression
                                // Ghostscript et split multi-pages. Reflète la consommation réseau réelle.
                                uploadRawBytes.record(scanned.length);

                                // Routage PDF vs image par magic bytes (%PDF- en tête de fichier),
                                // pas par le Content-Type client — forgeable et souvent faux sur
                                // mobile. Les octets sont déjà entièrement bufferisés pour le scan
                                // AV : la détection lit quelques octets en mémoire, sans toucher au
                                // flux. Un contenu qui ne matche ni PDF ni image décodable échoue
                                // de toute façon au parsing aval (CorruptFileException → 422).
                                boolean isPdf = PdfService.isPdf(scanned);

                                // Type effectif propagé aux métadonnées (PageInfo, événements SSE) :
                                // aligné sur la détection par contenu, pas sur l'en-tête déclaré.
                                var effectiveContentType = isPdf ? "application/pdf" : contentType;

                                log.debug("Upload routing token={} filename={} detectedType={} declaredType={} rawBytes={}",
                                        token, filename, isPdf ? "PDF" : "IMAGE_OR_OTHER", contentType, scanned.length);

                                if (isPdf) {
                                    return handlePdfUpload(token, session, scanned, filePart.filename(),
                                            effectiveContentType, sizeProfile, maxTotalBytes, uploadHash);
                                }

                                return handleSinglePageUpload(token, session, scanned, filePart.filename(),
                                        effectiveContentType, sizeProfile, maxTotalBytes, uploadHash);
                            });
                        }))
                // Admission control : borne le nombre d'uploads concurrents par pod.
                // Au-delà de la capacité, BulkheadFullException est levée puis traduite
                // en 429 + Retry-After par GlobalExceptionHandler. Soulage la pression
                // RAM le temps que l'autoscaler monte de nouveaux pods.
                .transformDeferred(BulkheadOperator.of(uploadBulkhead))
                // Le rejet par le bulkhead se voit dans televerse.upload.rejections,
                // taggé par profil pour correler saturation et tee-shirt size — l'erreur
                // continue ensuite sa route vers GlobalExceptionHandler pour le 429.
                .onErrorResume(io.github.resilience4j.bulkhead.BulkheadFullException.class, e -> {
                    log.debug("Upload rejected by bulkhead token={} filename={} profile={}",
                            token, filename, sizeProfile);
                    recordRejection(GlobalExceptionHandler.ERROR_UPLOAD_BUSY, sizeProfile);
                    return Mono.error(e);
                });
    }

    /**
     * Upload classique (image ou PDF 1 page) : convertit en PDF, stocke, ajoute à la session.
     */
    private Mono<ResponseEntity<?>> handleSinglePageUpload(
            String token, UploadSession session, byte[] uploadBytes, String filename,
            String contentType, SizeProfile sizeProfile, long maxTotalBytes, String uploadHash) {

        log.debug("Single-page upload start token={} filename={} contentType={} bytes={}",
                token, filename, contentType, uploadBytes.length);

        // Réservation atomique du pageId AVANT le stockage S3 — évite que deux
        // uploads concurrents écrivent sous la même clé. Le compteur est
        // incrémenté maintenant ; un échec de stockage en aval laissera juste
        // un trou numérique (cosmétique).
        return sessionService.reservePageId(token)
                .doOnNext(reservation -> log.debug("Reserved page token={} pageId={} pageKey={} thumbnailKey={}",
                        token, reservation.pageId(), reservation.pageKey(), reservation.thumbnailKey()))
                .<ResponseEntity<?>>flatMap(reservation ->
                        Mono.fromCallable(() -> pdfService.convertToPdf(uploadBytes))
                                .subscribeOn(Schedulers.boundedElastic())
                                .doOnNext(pdfBytes -> log.debug("PDF conversion OK token={} pageId={} pdfBytes={}",
                                        token, reservation.pageId(), pdfBytes.length))
                                // ImageIO ne décode pas l'image (tronquée / format invalide) →
                                // IOException ici = contenu inexploitable, pas une faute serveur.
                                .onErrorMap(IOException.class, e -> new CorruptFileException(filename, e))
                                .flatMap(pdfBytes -> storageService.storePageFromBytes(
                                        reservation.pageKey(), reservation.thumbnailKey(), pdfBytes, filename))
                                .doOnNext(result -> log.debug("Stored page token={} pageId={} pageKey={} storedSize={} thumbnailKey={}",
                                        token, reservation.pageId(), result.pageKey(), result.storedSize(), result.thumbnailKey()))
                                .<ResponseEntity<?>>flatMap(result -> {
                                    var now = Instant.now();
                                    var pageInfo = new PageInfo(reservation.pageId(), result.pageKey(), filename,
                                            contentType, result.storedSize(), now, now, result.thumbnailKey())
                                            .withContentHash(uploadHash);
                                    // Limites de pages ET de quota vérifiées sous le verrou Hazelcast
                                    // par attachPage : pas de race possible entre la lecture de l'état
                                    // et l'append, même pour N uploads multi-sélection concurrents.
                                    // rejectDuplicate=true : ferme aussi sous le verrou la course de
                                    // deux uploads image identiques que le pré-contrôle n'a pas vus.
                                    return sessionService.attachPage(token, pageInfo, limits.maxPages(), maxTotalBytes, true)
                                            .<ResponseEntity<?>>flatMap(attach -> {
                                                if (!attach.accepted()) {
                                                    log.debug("Attach refused token={} pageId={} reason={}",
                                                            token, reservation.pageId(), attach.rejection());
                                                    var rejection = rejectedAttachResponse(token, attach, sizeProfile, maxTotalBytes);
                                                    return rollbackResult(result).then(Mono.just(rejection));
                                                }
                                                var updated = attach.session();
                                                emitUploadEvent(token, session.documentId(), filename, contentType, result,
                                                        reservation.pageId(), updated, maxTotalBytes);

                                                // L'expiry est renouvelée sous le verrou par attachPage (cf.
                                                // SessionService) : pas d'appel séparé à renewSession qui
                                                // ferait un read-modify-write hors verrou et pourrait
                                                // clobber l'attache d'un upload concurrent.
                                                var expiresAt = updated.expiresAt().toEpochMilli();
                                                var thumbUrl = thumbUrl(token, reservation.pageId(), result, now);

                                                log.debug("Single-page upload finished token={} pageId={} totalPages={} totalBytes={} expiresAt={}",
                                                        token, reservation.pageId(), updated.pages().size(), updated.totalBytes(), expiresAt);

                                                return Mono.just(ResponseEntity.ok(new UploadResponse(
                                                        session.documentId(), "Upload successful", expiresAt,
                                                        reservation.pageId(), updated.pages().size() - 1, updated.pages().size(), thumbUrl,
                                                        sizeProfile, updated.totalBytes(), maxTotalBytes, 1, null)));
                                            })
                                            // attachPage émet vide si la session a disparu entre la réservation et
                                            // l'attachement : on rollback l'objet S3 puis on signale 400.
                                            .switchIfEmpty(Mono.defer(() -> {
                                                log.debug("Attach empty: session disappeared token={} pageId={}",
                                                        token, reservation.pageId());
                                                return rollbackResult(result)
                                                        .then(Mono.just(ResponseEntity.badRequest().body(invalidSessionResponse())));
                                            }))
                                            // Attach refusé en ERREUR sans rien écrire (verrou non
                                            // obtenu, session passée en finalisation) : même rollback
                                            // qu'un refus, puis l'erreur (429 SESSION_BUSY / 409). Sans
                                            // lui, la page — un document personnel — restait sur S3
                                            // jusqu'au cycle de vie du bucket, et chaque rejeu en
                                            // écrivait une copie de plus.
                                            .onErrorResume(SessionService::isNotApplied,
                                                    e -> rollbackResult(result).then(Mono.error(e)));
                                })
                )
                // reservePageId vide → session déjà disparue, aucun objet S3 à rollback.
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Reserve page empty: session disappeared token={}", token);
                    return Mono.just(ResponseEntity.badRequest().body(invalidSessionResponse()));
                }));
    }

    /**
     * Upload d'un PDF : découpe en pages individuelles, stocke chacune séparément.
     * Si le PDF n'a qu'une page, délègue au traitement classique.
     */
    private Mono<ResponseEntity<?>> handlePdfUpload(
            String token, UploadSession session, byte[] uploadBytes, String filename,
            String contentType, SizeProfile sizeProfile, long maxTotalBytes, String uploadHash) {

        log.debug("PDF upload start token={} filename={} bytes={}", token, filename, uploadBytes.length);

        // Page count is invariant under Ghostscript recompression — vérifier avant
        // de lancer gs évite une recompression coûteuse pour un PDF qu'on rejettera.
        // Loader.loadPDF est aussi le portail de validité du PDF : un fichier
        // tronqué / mal formé lève IOException ici (ex. « Missing root object
        // specification in trailer »), qu'on traduit en 422 plutôt qu'en 500.
        return Mono.fromCallable(() -> pdfService.countPdfPages(uploadBytes))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(sourcePageCount -> log.debug("PDF page count token={} filename={} pages={}",
                        token, filename, sourcePageCount))
                .onErrorMap(IOException.class, e -> new CorruptFileException(filename, e))
                .flatMap(sourcePageCount -> {
                    int remaining = limits.maxPages() - session.pages().size();
                    if (sourcePageCount > remaining) {
                        log.warn("PDF exceeds remaining pages token={} pdfPages={} remaining={}",
                                token, sourcePageCount, remaining);
                        recordRejection(ERROR_PDF_TOO_MANY_PAGES, sizeProfile);
                        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                .body(new UploadResponse(
                                        session.documentId(),
                                        "PDF de %d pages dépasse les %d slots restants".formatted(sourcePageCount, remaining),
                                        null, null, -1, session.pages().size(), null,
                                        sizeProfile, session.totalBytes(), maxTotalBytes, 0, ERROR_PDF_TOO_MANY_PAGES)));
                    }
                    return processPdfPages(token, session, uploadBytes, filename, contentType, sizeProfile, maxTotalBytes, sourcePageCount, uploadHash);
                });
    }

    /**
     * Produit les pages individuelles d'un PDF uploadé, prêtes à stocker.
     *
     * <ul>
     *   <li><b>Multi-pages + gs disponible</b> : recompression ET découpe en une seule passe
     *       Ghostscript (pages fidèles). Le {@code Splitter} PDFBox est évité car il corrompt
     *       l'encodage des polices sous-ensembles (lettres accentuées → mauvais glyphes) et
     *       détache les flux de colorimétrie ICC. Repli sur le split PDFBox si gs échoue/timeout.</li>
     *   <li><b>Mono-page, ou gs indisponible (dev)</b> : recompression puis split PDFBox — sur
     *       un mono-page le {@code Splitter} n'extrait rien (retourne l'original tel quel), donc
     *       pas de corruption ; en multi-page sans gs, repli dégradé mais fonctionnel.</li>
     * </ul>
     */
    private Mono<List<byte[]>> resolvePdfPages(byte[] uploadBytes, int sourcePageCount,
                                               String token, String filename) {
        if (sourcePageCount > 1 && ghostscriptService.isAvailable()) {
            return ghostscriptService.recompressAndSplitToPages(uploadBytes, sourcePageCount)
                    .onErrorResume(err -> {
                        log.warn("gs recompress+split KO, repli sur split PDFBox token={} filename={} error={}",
                                token, filename, err.getMessage());
                        // Timeout : gs vient de passer tout son budget sur CE document. Une
                        // recompression du même document entier expirerait à son tour —
                        // 15 s de plus et un worker gs occupé, pour rendre l'original.
                        return err instanceof TimeoutException
                                ? pdfboxSplit(uploadBytes)
                                : recompressThenPdfboxSplit(uploadBytes);
                    });
        }
        return recompressThenPdfboxSplit(uploadBytes);
    }

    /** Chemin historique : recompression gs (si bénéfique) puis découpe PDFBox. */
    private Mono<List<byte[]>> recompressThenPdfboxSplit(byte[] uploadBytes) {
        return ghostscriptService.recompressPdfIfNeeded(uploadBytes)
                .flatMap(this::pdfboxSplit);
    }

    private Mono<List<byte[]>> pdfboxSplit(byte[] pdfBytes) {
        return Mono.fromCallable(() -> pdfService.splitPdfToPages(pdfBytes))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ResponseEntity<?>> processPdfPages(
            String token, UploadSession session, byte[] uploadBytes, String filename,
            String contentType, SizeProfile sizeProfile, long maxTotalBytes, int sourcePageCount, String uploadHash) {

        log.debug("PDF processing start token={} filename={} pages={}", token, filename, sourcePageCount);

        return resolvePdfPages(uploadBytes, sourcePageCount, token, filename)
                .doOnNext(pages -> log.debug("PDF pages ready token={} filename={} pages={}",
                        token, filename, pages.size()))
                .flatMap(pages -> {
                    // Motif du premier refus sous verrou (concatMap séquentiel,
                    // la référence n'est écrite que par cette boucle) : sans lui,
                    // le mapping final ne peut pas distinguer un dépassement de
                    // pages d'un dépassement d'octets — le front afficherait la
                    // bannière « limite de taille » pour un problème de pages.
                    var firstRejection = new AtomicReference<SessionService.AttachRejection>();
                    // Chaque page passe par reserve → store S3 → attach. Les trois
                    // mutations IMap se font sous verrou Hazelcast (reservePageId
                    // et attachPage), donc pas de collision même si un autre upload
                    // entrelace ses propres opérations sur la même session.
                    return Flux.fromIterable(pages)
                            .index()
                            .concatMap(indexed -> {
                                // Arrêt au premier refus. Les pages suivantes ne feraient que
                                // vignette + PUT S3 + refus + DELETE, une par une : un PDF
                                // Word de 943 pages sur une session S (5 Mo) tenait ainsi la
                                // requête plusieurs minutes pour 25 pages gardées, au-delà du
                                // délai du reverse-proxy (5xx côté usager). Sur un refus de
                                // quota, une page suivante plus légère aurait pu passer, mais
                                // elle aurait laissé un trou dans le document.
                                // Lecture sûre : concatMap n'applique ce mapper qu'une fois la
                                // page précédente terminée.
                                if (firstRejection.get() != null) {
                                    return Mono.<AttachedPage>empty();
                                }
                                log.debug("Processing PDF page token={} pageIndex={}/{}",
                                        token, indexed.getT1() + 1, pages.size());

                                return sessionService.reservePageId(token)
                                        .doOnNext(reservation -> log.debug("Reserved PDF page token={} pageId={} pageKey={} thumbnailKey={}",
                                                token, reservation.pageId(), reservation.pageKey(), reservation.thumbnailKey()))
                                        .flatMap(reservation -> {
                                            // PDF multi-pages : on suffixe le nom affiché « (p.N) » pour distinguer
                                            // les pages issues d'un même fichier source. PDF mono-page : nom inchangé.
                                            var pageFilename = pages.size() > 1
                                                    ? "%s (p.%d)".formatted(filename, indexed.getT1() + 1)
                                                    : filename;

                                            return storageService.storePageFromBytes(reservation.pageKey(), reservation.thumbnailKey(), indexed.getT2(), filename)
                                                    .doOnNext(result -> log.debug("Stored PDF page token={} pageId={} pageIndex={}/{} storedSize={} thumbnailKey={}",
                                                            token, reservation.pageId(), indexed.getT1() + 1, pages.size(),
                                                            result.storedSize(), result.thumbnailKey()))
                                                    .flatMap(result -> {
                                                        var now = Instant.now();
                                                        // Toutes les pages d'un même PDF portent l'empreinte du fichier
                                                        // source : un re-upload du PDF entier est vu comme doublon par le
                                                        // pré-contrôle du contrôleur. attachPage reste en mode 4-arguments
                                                        // (rejectDuplicate=false) car ces pages partagent l'empreinte et se
                                                        // rejetteraient entre elles sous le verrou (collision intra-lot).
                                                        var pageInfo = new PageInfo(reservation.pageId(), result.pageKey(), pageFilename,
                                                                contentType, result.storedSize(), now, now, result.thumbnailKey())
                                                                .withContentHash(uploadHash);
                                                        // Limites vérifiées sous le verrou par attachPage : la lecture
                                                        // voit toutes les pages déjà attachées par cette boucle ou par
                                                        // un upload concurrent. Le pré-contrôle de page count en amont
                                                        // (handlePdfUpload) reste un snapshot hors verrou ; ce check
                                                        // sérialisé est l'autorité qui borne effectivement la session.
                                                        return sessionService.attachPage(token, pageInfo, limits.maxPages(), maxTotalBytes)
                                                                // Session disparue entre reserve et attach. Posé sur
                                                                // attachPage même, pas après le flatMap : un refus y
                                                                // rend aussi vide, et la page refusée repassait ici
                                                                // pour un second rollback.
                                                                .switchIfEmpty(Mono.defer(() -> {
                                                                    log.debug("Attach empty mid-PDF: session disappeared token={} pageIndex={}/{} pageId={}",
                                                                            token, indexed.getT1() + 1, pages.size(), reservation.pageId());
                                                                    return rollbackResult(result).then(Mono.empty());
                                                                }))
                                                                .flatMap(attach -> {
                                                                    if (!attach.accepted()) {
                                                                        log.warn("Attach refused mid-PDF token={} pageIndex={} totalPages={} reason={}",
                                                                                token, indexed.getT1() + 1, pages.size(), attach.rejection());
                                                                        firstRejection.compareAndSet(null, attach.rejection());
                                                                        return rollbackResult(result).then(Mono.empty());
                                                                    }
                                                                    emitUploadEvent(token, session.documentId(), pageFilename,
                                                                            contentType, result, reservation.pageId(), attach.session(), maxTotalBytes);

                                                                    log.debug("Attached PDF page token={} pageId={} pageIndex={}/{} totalPages={} totalBytes={}",
                                                                            token, reservation.pageId(), indexed.getT1() + 1, pages.size(),
                                                                            attach.session().pages().size(), attach.session().totalBytes());

                                                                    return Mono.just(new AttachedPage(
                                                                            reservation.pageId(), attach.session(), now, result.thumbnailKey()));
                                                                })
                                                                // Refus en erreur sans écriture : cf. chemin mono-page.
                                                                .onErrorResume(SessionService::isNotApplied,
                                                                        e -> rollbackResult(result).then(Mono.error(e)));
                                                    });
                                        });
                            })
                            .collectList()
                            .<ResponseEntity<?>>map(results -> {
                                int pagesAdded = results.size();
                                boolean pageLimitHit = firstRejection.get() == SessionService.AttachRejection.PAGE_LIMIT;
                                if (pagesAdded == 0) {
                                    // Aucune page attachée : restitue le motif réel du refus
                                    // sous verrou. PAGE_LIMIT → 429 PDF_TOO_MANY_PAGES, comme
                                    // le pré-contrôle d'entrée (le front partage la même
                                    // bannière « trop de pages ») ; sinon quota d'octets → 413.
                                    if (pageLimitHit) {
                                        log.debug("PDF upload rejected: no pages attached due to page limit token={} sourcePages={}",
                                                token, pages.size());
                                        recordRejection(ERROR_PDF_TOO_MANY_PAGES, sizeProfile);
                                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                                .body(new UploadResponse(
                                                        session.documentId(),
                                                        "PDF de %d pages dépasse les slots restants".formatted(pages.size()),
                                                        null, null, -1, session.pages().size(), null,
                                                        sizeProfile, session.totalBytes(), maxTotalBytes, 0, ERROR_PDF_TOO_MANY_PAGES));
                                    }
                                    log.debug("PDF upload rejected: no pages attached due to size limit token={} sourcePages={}",
                                            token, pages.size());
                                    return oversizedResponse(session, maxTotalBytes);
                                }

                                // L'expiry et l'état final viennent du DERNIER attachPage de
                                // cette boucle, lui-même renouvelé sous le verrou Hazelcast.
                                // Évite un getSession + renewSession en queue qui pouvait
                                // clobber des attaches concurrentes (cf. SessionService).
                                var last = results.getLast();
                                var finalSession = last.session();
                                var expiresAt = finalSession.expiresAt().toEpochMilli();
                                var lastPageId = last.pageId();
                                var thumbUrl = last.thumbnailKey() != null
                                        ? "/api/sessions/%s/pages/%s/thumbnail?v=%d".formatted(
                                        token, lastPageId, last.uploadedAt().toEpochMilli())
                                        : null;
                                var totalPages = finalSession.pages().size();
                                var totalBytes = finalSession.totalBytes();

                                // Upload partiel : l'errorCode porte le motif réel du refus
                                // pour que le front affiche la bonne bannière (« trop de
                                // pages » vs « limite de taille ») sur la réponse 200.
                                var partialError = pageLimitHit ? ERROR_PDF_TOO_MANY_PAGES : ERROR_SESSION_SIZE_EXCEEDED;
                                if (pagesAdded < pages.size()) {
                                    log.debug("PDF upload partial token={} pagesAdded={} sourcePages={} partialError={}",
                                            token, pagesAdded, pages.size(), partialError);
                                    recordRejection(partialError, sizeProfile);
                                }

                                log.debug("PDF upload finished token={} pagesAdded={} totalPages={} totalBytes={} expiresAt={}",
                                        token, pagesAdded, totalPages, totalBytes, expiresAt);

                                return ResponseEntity.ok(new UploadResponse(
                                        session.documentId(),
                                        pagesAdded == pages.size() ? "Upload successful"
                                                : pageLimitHit ? "Upload partiel — limite de pages atteinte"
                                                  : "Upload partiel — limite de taille atteinte",
                                        expiresAt,
                                        lastPageId, totalPages - 1, totalPages, thumbUrl,
                                        sizeProfile, totalBytes, maxTotalBytes, pagesAdded,
                                        pagesAdded == pages.size() ? null : partialError));
                            });
                });
    }

    private void emitUploadEvent(String token, String documentId, String filename,
                                 String contentType, StorageService.StorePageResult result,
                                 String pageId, UploadSession updated, long maxTotalBytes) {
        var event = UploadEvent.completed(documentId, filename, contentType, result.storedSize(),
                pageId, updated.pages().size() - 1, updated.pages().size(),
                updated.totalBytes(), maxTotalBytes);
        sessionService.notifyUpload(token, event);
    }

    private String thumbUrl(String token, String pageId, StorageService.StorePageResult result, Instant lastModifiedAt) {
        return result.thumbnailKey() != null
                ? "/api/sessions/%s/pages/%s/thumbnail?v=%d".formatted(token, pageId, lastModifiedAt.toEpochMilli())
                : null;
    }

    /**
     * Réponse 413 dédiée aux rejets pour quota de session — voir
     * {@link #ERROR_SESSION_SIZE_EXCEEDED}. {@code totalBytes} reflète la
     * session AVANT le fichier rejeté ; le front recompose
     * {@code available = maxTotalBytes - totalBytes} pour rafraîchir la barre
     * de progression.
     */
    private ResponseEntity<ErrorResponse> oversizedResponse(UploadSession session, long maxTotalBytes) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse(ERROR_SESSION_SIZE_EXCEEDED,
                        "Session size limit exceeded",
                        session.totalBytes(), maxTotalBytes));
    }

    /**
     * Corps de réponse 429 {@link #ERROR_PAGE_LIMIT}, partagé par le pré-contrôle
     * d'entrée (session déjà pleine) et le refus sous verrou d'{@code attachPage}
     * (course multi-sélection qui franchit le cap entre deux requêtes). {@code session}
     * reflète l'état AVANT la page refusée.
     */
    private UploadResponse pageLimitResponse(UploadSession session, SizeProfile sizeProfile, long maxTotalBytes) {
        return new UploadResponse(
                session.documentId(),
                "Limite de pages atteinte (" + limits.maxPages() + ")",
                null, null, -1, session.pages().size(), null,
                sizeProfile, session.totalBytes(), maxTotalBytes, 0, ERROR_PAGE_LIMIT);
    }

    /**
     * Traduit un refus d'{@code attachPage} (page déjà stockée sur S3 mais non
     * rattachée) en réponse HTTP selon le motif : 429 {@link #ERROR_DUPLICATE_PAGE}
     * pour un doublon détecté sous le verrou, 429 {@link #ERROR_PAGE_LIMIT} pour un
     * dépassement du nombre de pages, 413 {@link #ERROR_SESSION_SIZE_EXCEEDED} pour
     * un dépassement du quota d'octets. Incrémente le compteur de rejets et loggue ;
     * l'appelant rollback l'objet S3 avant d'émettre la réponse.
     */
    private ResponseEntity<?> rejectedAttachResponse(String token, SessionService.AttachResult attach,
                                                     SizeProfile sizeProfile, long maxTotalBytes) {
        if (attach.rejection() == SessionService.AttachRejection.DUPLICATE) {
            // Doublon vu seulement sous le verrou (course concurrente non captée par
            // le pré-contrôle). Même 429 DUPLICATE_PAGE que le pré-contrôle, alimente
            // le même compteur pour la visibilité Grafana de l'abus.
            log.info("Duplicate upload rejected (under lock) token={}", token);
            recordRejection(ERROR_DUPLICATE_PAGE, sizeProfile);
            return duplicateResponse(attach.session(), sizeProfile, maxTotalBytes);
        }
        if (attach.rejection() == SessionService.AttachRejection.PAGE_LIMIT) {
            log.warn("Page limit reached under lock token={} maxPages={}", token, limits.maxPages());
            recordRejection(ERROR_PAGE_LIMIT, sizeProfile);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(pageLimitResponse(attach.session(), sizeProfile, maxTotalBytes));
        }
        log.warn("Session quota exceeded token={} currentBytes={} maxBytes={}",
                token, attach.session().totalBytes(), maxTotalBytes);
        recordRejection(ERROR_SESSION_SIZE_EXCEEDED, sizeProfile);
        return oversizedResponse(attach.session(), maxTotalBytes);
    }

    /**
     * Vrai si la session contient déjà une page portant l'empreinte de contenu
     * brut {@code uploadHash} — re-upload octet pour octet du même fichier. Lecture
     * hors verrou sur le snapshot : sert le pré-contrôle anti-spam qui court-circuite
     * avant AV/conversion/S3. La garantie stricte (course concurrente) est portée
     * par {@code attachPage(..., rejectDuplicate=true)} sur le chemin image.
     */
    private static boolean isDuplicateUpload(UploadSession session, String uploadHash) {
        return session.pages().stream().anyMatch(p -> uploadHash.equals(p.contentHash()));
    }

    /**
     * Réponse 429 {@link #ERROR_DUPLICATE_PAGE} pour un re-upload à l'identique.
     * {@code pagesAdded = 0} : rien n'a été ajouté. {@code session} reflète l'état
     * courant (inchangé par le doublon).
     */
    private ResponseEntity<?> duplicateResponse(UploadSession session, SizeProfile sizeProfile, long maxTotalBytes) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new UploadResponse(
                        session.documentId(),
                        "Cette page a déjà été ajoutée.",
                        null, null, -1, session.pages().size(), null,
                        sizeProfile, session.totalBytes(), maxTotalBytes, 0, ERROR_DUPLICATE_PAGE));
    }

    /**
     * Empreinte SHA-256 (hex) du contenu brut uploadé. Sur le fichier reçu avant
     * toute conversion/split, donc stable et comparable d'un upload à l'autre. Le
     * coût est marginal (les octets sont déjà en mémoire pour le scan AV).
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est garanti présent sur toute JVM conforme — inatteignable.
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Compteur des uploads rejetés, taggé par code d'erreur et profil de session. */
    private void recordRejection(String errorCode, SizeProfile sizeProfile) {
        Counter.builder("televerse.upload.rejections")
                .description("Uploads rejetés par le back, par code d'erreur et profil de session.")
                .tag("errorCode", errorCode)
                .tag("sizeProfile", sizeProfile != null ? sizeProfile.name() : "unknown")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Défait le stockage d'une page non rattachée : PDF sur S3, vignette dans le
     * cache Hazelcast (où elle vit depuis la migration du cache — la supprimer sur S3
     * émettait un DELETE inutile et laissait l'entrée en cache jusqu'à son TTL).
     */
    private Mono<Void> rollbackResult(StorageService.StorePageResult result) {
        log.debug("Rollback start pageKey={} thumbnailKey={}", result.pageKey(), result.thumbnailKey());

        var deletePdf = storageService.deleteFromS3(result.pageKey())
                .doOnSuccess(unused -> log.debug("S3 rollback PDF OK key={}", result.pageKey()))
                .onErrorResume(err -> {
                    log.warn("S3 rollback failed key={} error={}", result.pageKey(), err.getMessage());
                    return Mono.empty();
                });
        return Mono.when(deletePdf, thumbnailCache.delete(result.thumbnailKey()));
    }

    private static UploadResponse invalidSessionResponse() {
        return new UploadResponse(null, "Session expired or invalid", null, null, -1, 0, null,
                null, 0L, 0L, 0, null);
    }
}

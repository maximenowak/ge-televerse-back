package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.config.UploadAdmissionProperties;
import ch.ge.ael.geteleverse.config.logging.ExceptionMessages;
import ch.ge.ael.geteleverse.service.SessionLockTimeoutException;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.netty.channel.AbortedException;

@RestControllerAdvice
@Slf4j(topic = "http")
public class GlobalExceptionHandler {

    /**
     * Réponse d'erreur JSON. Le front-end branche d'abord sur {@code error}
     * (discriminateur stable, ex. {@code SESSION_SIZE_EXCEEDED}) et utilise
     * {@code message} comme repli. Les champs {@code totalBytes} /
     * {@code maxTotalBytes} sont peuplés uniquement quand l'erreur les rend
     * pertinents (rejet pour quota de session) et sont sinon omis du JSON.
     * Le champ {@code expiresAt} est peuplé pour {@code ORIGINATOR_HAS_ACTIVE_SESSION}
     * afin que le front puisse afficher un compte à rebours avant libération
     * automatique du slot.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String error, String message,
                                Long totalBytes, Long maxTotalBytes,
                                Long expiresAt) {
        public ErrorResponse(String error, String message) {
            this(error, message, null, null, null);
        }
        public ErrorResponse(String error, String message, Long totalBytes, Long maxTotalBytes) {
            this(error, message, totalBytes, maxTotalBytes, null);
        }
    }

    /** Code d'erreur retourné quand l'admission control upload sature (429). */
    public static final String ERROR_UPLOAD_BUSY = "UPLOAD_BUSY";
    /** Code d'erreur retourné quand l'admission control des éditions sature (429). */
    public static final String ERROR_EDIT_BUSY = "EDIT_BUSY";
    /** Code d'erreur retourné quand l'admission control des aperçus (preview.pdf / vignette à la volée) sature (429). */
    public static final String ERROR_MERGE_BUSY = "MERGE_BUSY";
    /** Code d'erreur retourné quand l'admission control des finalisations sature (429). */
    public static final String ERROR_FINALIZE_BUSY = "FINALIZE_BUSY";
    /** L'IP d'origine a déjà une session active. */
    public static final String ERROR_ORIGINATOR_BUSY = "ORIGINATOR_HAS_ACTIVE_SESSION";
    /** Fichier détecté comme infecté par l'antivirus (422). Non-retryable côté front. */
    public static final String ERROR_INFECTED_FILE = "INFECTED_FILE";
    /** Fichier illisible / corrompu : ni PDF ni image décodable (422). Non-retryable côté front. */
    public static final String ERROR_CORRUPT_FILE = "CORRUPT_FILE";
    /** Recadrage / redressement / balance des blancs sur une page qui n'est pas une image seule (422). */
    public static final String ERROR_PAGE_NOT_EDITABLE = "PAGE_NOT_EDITABLE";
    /** Fichier au-delà de {@code app.limits.multipart-ceiling}, refusé par le codec (413). */
    public static final String ERROR_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    /** Corps multipart refusé par le codec : plusieurs parts, ou flux mal formé (400). */
    public static final String ERROR_INVALID_MULTIPART = "INVALID_MULTIPART";
    /** Antivirus injoignable et fail-mode=closed (503). Retryable comme {@code UPLOAD_BUSY}. */
    public static final String ERROR_ANTIVIRUS_UNAVAILABLE = "ANTIVIRUS_UNAVAILABLE";
    /** Lecture S3 en échec transitoire sur le chemin aperçu/vignette (503). Retryable comme {@code MERGE_BUSY}. */
    public static final String ERROR_STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";
    /** Token bucket par IP épuisé sur {@code POST /sessions} (429). */
    public static final String ERROR_SESSION_RATE_LIMITED = "SESSION_RATE_LIMITED";
    /** Édition de page abandonnée sur dépassement de {@code app.edit-timeout} (504). Retryable. */
    public static final String ERROR_EDIT_TIMEOUT = "EDIT_TIMEOUT";
    /** Verrou de session non acquis dans le délai imparti — contention transitoire (429). Retryable. */
    public static final String ERROR_SESSION_BUSY = "SESSION_BUSY";

    private final UploadAdmissionProperties admissionProperties;
    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(UploadAdmissionProperties admissionProperties,
                                  MeterRegistry meterRegistry) {
        this.admissionProperties = admissionProperties;
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex, ServerWebExchange exchange) {
        log.error("Bad request method={} path={} error={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", ex.getMessage()));
    }

    /**
     * Bulkhead saturé : tous les permis sont pris et l'attente {@code maxWait}
     * a expiré. On renvoie 429 + {@code Retry-After} pour signaler au client
     * mobile qu'il peut réessayer après une courte pause. Le client doit
     * traiter cette réponse comme un rejet temporaire — pas une erreur métier.
     */
    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ErrorResponse> handleBulkheadFull(BulkheadFullException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, admissionProperties.retryAfter().toSeconds());
        log.info("Upload rejected reason=admission-full method={} path={} retryAfterSeconds={} error={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                retryAfterSeconds,
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_UPLOAD_BUSY,
                        "Service saturé, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Bulkhead des éditions saturé (rotate / crop / deskew / white-balance) :
     * même sémantique que {@link #handleBulkheadFull} mais code {@code EDIT_BUSY}
     * distinct pour que le client discrimine une saturation d'édition d'une
     * saturation d'upload. Le {@code Retry-After} est porté par l'exception
     * (calculé au point de rejet depuis {@code app.admission.edit.retry-after}).
     */
    @ExceptionHandler(EditBusyException.class)
    public ResponseEntity<ErrorResponse> handleEditBusy(EditBusyException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, ex.retryAfterSeconds());
        log.info("Edit rejected reason=admission-full method={} path={} retryAfterSeconds={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_EDIT_BUSY,
                        "Service saturé, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Opération de page abandonnée sur dépassement de sa borne de durée :
     * {@code app.edit-timeout} pour les quatre éditions, {@code app.undo-timeout}
     * — plus serré — pour undo / reset. 504 : l'échec vient d'un maillon aval trop
     * lent (GET S3, passe native OpenCV/ONNX sur un pod throttlé, verrou Hazelcast
     * retenu), pas de la requête. Le {@code Retry-After} reprend celui de
     * l'admission control des éditions : côté client la conduite à tenir est la
     * même que pour un {@code EDIT_BUSY} — réessayer plus tard. L'opération
     * fautive n'est pas dans le corps mais dans le log et le tag {@code op} du
     * compteur, pour ne pas faire dépendre le client d'un vocabulaire interne.
     *
     * <p>Niveau WARN, pas ERROR : c'est une dégradation attendue sous charge,
     * mais dont la récurrence doit se voir. À corréler avec le compteur
     * {@code televerse.edit.timeouts} (taggé par opération) et la saturation du
     * pool S3. Avant ce handler, ces requêtes ne s'achevaient tout simplement
     * jamais — ni réponse au navigateur, ni ligne de log.</p>
     *
     * <p>Le champ {@code stages} est le relevé d'étapes du pipeline coupé
     * (cf. {@link ch.ge.ael.geteleverse.service.EditTrace}) : rien d'autre sur ce
     * chemin ne logue au-dessus de DEBUG, donc sans lui la ligne disait
     * <i>qu'</i>une édition avait dépassé le budget, jamais <i>où</i>. L'étape
     * notée {@code cancelled} ou {@code running} est celle qui l'a mangé.</p>
     */
    @ExceptionHandler(EditTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleEditTimeout(EditTimeoutException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, admissionProperties.retryAfter().toSeconds());
        log.warn("Edit timed out method={} path={} operation={} budgetMs={} stages={} retryAfterSeconds={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.operation(),
                ex.budget().toMillis(),
                ex.stages(),
                retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_EDIT_TIMEOUT,
                        "L'opération a dépassé le délai maximum, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Verrou Hazelcast de session non acquis dans le délai imparti : deux
     * mutations concurrentes se disputent la même session et la détentrice
     * traîne. Contention transitoire, donc 429 + {@code Retry-After} — même
     * conduite à tenir côté client que les codes {@code *_BUSY} de l'admission
     * control, dont ce code partage le suffixe à dessein.
     *
     * <p>Avant, {@code IMap.lock} attendait sans borne : la requête restait en
     * vol indéfiniment, permis de bulkhead en main, sans trace.</p>
     */
    @ExceptionHandler(SessionLockTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleSessionLockTimeout(SessionLockTimeoutException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, ex.retryAfterSeconds());
        log.warn("Session lock timed out method={} path={} retryAfterSeconds={} error={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                retryAfterSeconds,
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_SESSION_BUSY,
                        "Une autre opération est en cours sur cette session, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Bulkhead des assemblages saturé (aperçu {@code preview.pdf}, rendu de
     * vignette à la volée, {@code finalize}) : même sémantique que
     * {@link #handleBulkheadFull} mais code {@code MERGE_BUSY} distinct. Le
     * {@code Retry-After} est porté par l'exception (depuis
     * {@code app.admission.merge.retry-after}).
     */
    @ExceptionHandler(MergeBusyException.class)
    public ResponseEntity<ErrorResponse> handleMergeBusy(MergeBusyException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, ex.retryAfterSeconds());
        log.info("Merge rejected reason=admission-full method={} path={} retryAfterSeconds={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_MERGE_BUSY,
                        "Service saturé, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Bulkhead des finalisations saturé : même sémantique que
     * {@link #handleBulkheadFull} mais code {@code FINALIZE_BUSY} distinct. Le
     * {@code Retry-After} est porté par l'exception (depuis
     * {@code app.admission.finalize.retry-after}).
     */
    @ExceptionHandler(FinalizeBusyException.class)
    public ResponseEntity<ErrorResponse> handleFinalizeBusy(FinalizeBusyException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, ex.retryAfterSeconds());
        log.info("Finalize rejected reason=admission-full method={} path={} retryAfterSeconds={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_FINALIZE_BUSY,
                        "Service saturé, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Une session est déjà active pour l'IP d'origine. Le front utilise
     * {@code expiresAt} pour afficher un compte à rebours et propose au
     * choix « patienter » ou « démarrer quand même » ({@code POST /sessions?force=true}).
     *
     * <p>{@code expiresAt} sert aussi d'empreinte d'auto-récupération : c'est
     * l'expiry INITIAL de la session liée (la valeur du binding n'est jamais
     * réécrite, cf. {@code SessionService#refreshOriginatorTtl}), identique à
     * celui renvoyé par {@code POST /sessions} à la création. Une fenêtre qui
     * retombe en 409 après rechargement compare cette valeur à celle qu'elle
     * avait reçue pour reconnaître sa propre session : elle la reprend alors
     * telle quelle si elle est encore vivante ({@code GET /sessions/&#123;token&#125;}),
     * sinon la remplace via {@code force=true} — silencieusement dans les
     * deux cas.</p>
     */
    @ExceptionHandler(OriginatorBusyException.class)
    public ResponseEntity<ErrorResponse> handleOriginatorBusy(OriginatorBusyException ex, ServerWebExchange exchange) {
        recordOriginatorRejection(ERROR_ORIGINATOR_BUSY);
        log.info("Originator busy method={} path={} existingExpiresAt={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.existing().expiresAt());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ERROR_ORIGINATOR_BUSY,
                        "Une session est déjà ouverte depuis ce poste.",
                        null, null, ex.existing().expiresAt().toEpochMilli()));
    }

    /**
     * Header {@code X-Forwarded-For} absent ou invalide. En prod, doit faire
     * remonter une alerte ops via {@code televerse.originator.invalid} : le
     * firewall pose normalement toujours ce header. Code 400 sec, ni message
     * d'erreur métier ni détail sur le format attendu.
     */
    @ExceptionHandler(OriginatorResolutionException.class)
    public ResponseEntity<ErrorResponse> handleOriginatorResolution(OriginatorResolutionException ex, ServerWebExchange exchange) {
        recordOriginatorRejection(ex.errorCode());
        log.warn("Originator resolution failed code={} method={} path={} error={}",
                ex.errorCode(),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.errorCode(), ex.getMessage()));
    }

    /**
     * Fichier détecté comme infecté par clamd. Code 422 — la requête est
     * syntaxiquement valide mais le contenu est sémantiquement refusé.
     * Niveau de log WARN : c'est un événement métier légitime (détection
     * positive), pas une faute serveur. Les noms de signatures restent
     * dans le log côté serveur ; la réponse au client ne les renvoie pas
     * pour ne pas livrer d'info exploitable à un attaquant qui sonde.
     */
    @ExceptionHandler(InfectedFileException.class)
    public ResponseEntity<ErrorResponse> handleInfectedFile(InfectedFileException ex, ServerWebExchange exchange) {
        log.warn("Infected file rejected method={} path={} filename={} signatures={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.filename(),
                ex.signatures());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ERROR_INFECTED_FILE,
                        "Fichier rejeté par le contrôle antivirus."));
    }

    /**
     * Fichier impossible à décoder comme PDF ou image (PDFBox / ImageIO lèvent
     * {@link java.io.IOException} sur un contenu tronqué ou mal formé). Code 422,
     * même sémantique que {@link #handleInfectedFile} : requête bien formée,
     * contenu sémantiquement inexploitable, non-retryable. Niveau WARN — c'est
     * une erreur d'entrée utilisateur (fichier endommagé), pas une faute serveur :
     * la laisser remonter en 500 polluerait les alertes 5xx. La cause technique
     * reste loguée pour le support ; le client ne reçoit qu'un message générique.
     */
    @ExceptionHandler(CorruptFileException.class)
    public ResponseEntity<ErrorResponse> handleCorruptFile(CorruptFileException ex, ServerWebExchange exchange) {
        log.warn("Corrupt file rejected method={} path={} filename={} cause={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.filename(),
                ex.getCause() != null ? ex.getCause().getMessage() : null);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ERROR_CORRUPT_FILE,
                        "Le fichier est illisible ou endommagé."));
    }

    /**
     * Édition d'image demandée sur une page de PDF natif (texte, tracés, plusieurs
     * images) : la reconstruction depuis l'image effacerait le reste. Niveau INFO :
     * c'est un geste d'usager, le front propose ces éditions sur toutes les pages.
     */
    @ExceptionHandler(PageNotEditableException.class)
    public ResponseEntity<ErrorResponse> handlePageNotEditable(PageNotEditableException ex,
                                                               ServerWebExchange exchange) {
        log.info("Page edit refused reason=not-single-image method={} path={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ERROR_PAGE_NOT_EDITABLE,
                        "Cette retouche ne s'applique qu'aux photos et aux pages numérisées : "
                                + "cette page contient du texte ou des éléments vectoriels. "
                                + "Vous pouvez la tourner ou la supprimer."));
    }

    /**
     * Mutation refusée sous le verrou de session parce que l'état a changé (session
     * finalisée ou en cours de finalisation, page supprimée ou modifiée entre-temps,
     * liste de pages divergente, quota dépassé par une édition). Niveau INFO : c'est
     * une course d'usage entre deux appareils ou deux onglets, pas une faute serveur.
     */
    @ExceptionHandler(SessionConflictException.class)
    public ResponseEntity<ErrorResponse> handleSessionConflict(SessionConflictException ex, ServerWebExchange exchange) {
        log.info("Session conflict method={} path={} code={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.code());
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    /**
     * Part multipart au-delà de {@code spring.webflux.multipart.max-disk-usage-per-part}
     * (= {@code app.limits.multipart-ceiling}), ou en-têtes de part trop longs. Le codec
     * lève l'exception en résolvant le {@code @RequestPart}, avant tout code applicatif,
     * et l'écriture sur disque s'arrête au plafond. Sur le chemin {@code @RequestBody},
     * Spring la convertit lui-même en {@code ContentTooLargeException} (413) : une
     * {@link DataBufferLimitException} brute ne peut donc venir que du multipart.
     * Niveau WARN : le front refuse déjà ces fichiers, ce rejet signale un client qui
     * le contourne (ou un plafond désaligné entre front et back).
     */
    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<ErrorResponse> handleFileTooLarge(DataBufferLimitException ex, ServerWebExchange exchange) {
        log.warn("Multipart limit exceeded method={} path={} error={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new ErrorResponse(ERROR_FILE_TOO_LARGE,
                        "Fichier trop volumineux pour un envoi."));
    }

    /**
     * Corps multipart refusé par le codec : plus d'une part
     * ({@code spring.webflux.multipart.max-parts}) ou flux mal formé. Même raisonnement
     * que {@link #handleFileTooLarge} : sur {@code @RequestBody}, Spring convertit
     * déjà une {@link DecodingException} en {@code ServerWebInputException} (400).
     */
    @ExceptionHandler(DecodingException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMultipart(DecodingException ex, ServerWebExchange exchange) {
        log.warn("Invalid multipart request method={} path={} error={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ERROR_INVALID_MULTIPART,
                        "Envoi invalide : un seul fichier par envoi."));
    }

    /**
     * Antivirus injoignable et {@code app.antivirus.fail-mode=closed} : on
     * refuse plutôt que de stocker du contenu non scanné. 503 avec
     * {@code Retry-After} aligné sur celui de l'admission control — même
     * sémantique côté front (« réessayez bientôt »). Niveau ERROR car
     * c'est une dégradation critique : signal pour le runbook oncall.
     */
    @ExceptionHandler(AntivirusUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAntivirusUnavailable(AntivirusUnavailableException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, admissionProperties.retryAfter().toSeconds());
        log.error("Antivirus unavailable method={} path={} error={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_ANTIVIRUS_UNAVAILABLE,
                        "Contrôle antivirus indisponible, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Lecture S3 en échec transitoire sur le chemin des aperçus / vignettes
     * (timeout d'acquisition de connexion quand le pool Netty sature sous
     * charge, 5xx / throttling du store on-prem, reset TLS) — requalifiée par
     * {@code SessionController} depuis une erreur de fetch qui n'est <em>pas</em>
     * un objet réellement absent. 503 + {@code Retry-After} porté par
     * l'exception (calculé depuis {@code app.admission.merge.retry-after}). Le
     * front la traite comme un rejet temporaire retryable, à l'égal de
     * {@code MERGE_BUSY}.
     *
     * <p>Niveau WARN : dégradation transitoire attendue sous forte charge, à
     * corréler avec la saturation du pool S3 — pas une faute serveur, et surtout
     * pas un 404 silencieux comme avant (cf. {@link StorageUnavailableException}).
     * Un objet réellement absent reste un 404, émis directement par le
     * contrôleur avec sa propre trace.</p>
     */
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStorageUnavailable(StorageUnavailableException ex, ServerWebExchange exchange) {
        long retryAfterSeconds = Math.max(1, ex.retryAfterSeconds());
        log.warn("Storage unavailable method={} path={} retryAfterSeconds={} error={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                retryAfterSeconds,
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(new ErrorResponse(ERROR_STORAGE_UNAVAILABLE,
                        "Stockage temporairement indisponible, réessayez dans %d s.".formatted(retryAfterSeconds)));
    }

    /**
     * Bucket par IP épuisé sur {@code POST /sessions}. 429 + {@code Retry-After}
     * calculé par le rate-limiter à partir du déficit de jetons. Le compteur
     * {@code televerse.sessions.rate_limited} (taggé {@code mode}) a déjà été
     * incrémenté par le service au point de rejet — pas de double comptage ici.
     * Niveau de log INFO sur un rejet normal, WARN si {@code force=true} : un
     * client qui boucle sur force est suspect, à corréler avec
     * {@code televerse.originator.force}.
     */
    @ExceptionHandler(SessionRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleSessionRateLimited(SessionRateLimitedException ex, ServerWebExchange exchange) {
        long retryAfter = ex.retryAfterSeconds();
        if (ex.force()) {
            log.warn("Session rate-limited mode=force method={} path={} retryAfterSeconds={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    retryAfter);
        } else {
            log.info("Session rate-limited mode=normal method={} path={} retryAfterSeconds={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    retryAfter);
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(new ErrorResponse(ERROR_SESSION_RATE_LIMITED,
                        "Trop de créations de session depuis votre poste, réessayez dans %d s.".formatted(retryAfter)));
    }

    /**
     * Compteur des rejets de session pour cause de verrou originator (busy,
     * missing, invalid). Permet d'objectiver le ratio « légitimes refus » vs
     * « pannes XFF / mauvaise config réseau » côté Grafana.
     */
    private void recordOriginatorRejection(String errorCode) {
        Counter.builder("televerse.originator.rejections")
                .description("Refus de création de session pour cause originator.")
                .tag("errorCode", errorCode)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Exceptions de routage portées par le framework : chemin inconnu
     * ({@link org.springframework.web.reactive.resource.NoResourceFoundException}, 404),
     * méthode non autorisée (405), média non supporté (415)… Toutes étendent
     * {@link ResponseStatusException} et transportent déjà le bon statut HTTP.
     *
     * <p>Sans ce handler, le catch-all {@link #handleGeneric(Exception, ServerWebExchange)}
     * les ré-emballe en 500 et les logge en ERROR : un simple chemin inexistant
     * (scan Internet, typo de route, {@code /api/actuator/*} désormais servi sur le
     * port de gestion) devient une fausse alerte serveur. On propage ici le statut
     * d'origine et on logge au niveau adapté (INFO pour les 4xx clients, WARN pour
     * les rares 5xx). Aucun code applicatif ne lève {@link ResponseStatusException} :
     * ce handler ne capture donc que des erreurs d'origine framework.</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, ServerWebExchange exchange) {
        HttpStatusCode status = ex.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved != null ? resolved.name() : "REQUEST_FAILED";
        String message = resolved != null ? resolved.getReasonPhrase() : "Request failed";
        if (status.is5xxServerError()) {
            log.warn("Request failed method={} path={} status={} reason={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    status.value(),
                    ex.getReason());
        } else {
            log.info("Request rejected method={} path={} status={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    status.value());
        }
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, ServerWebExchange exchange) {
        if (AbortedException.isConnectionReset(ex)) {
            // Client (mobile) parti avant la fin de l'upload : log INFO, pas ERROR.
            // La réponse ne sera de toute façon pas reçue — on rend un 499 symbolique.
            log.info("Client disconnected method={} path={} reason={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    ex.getMessage());
            return ResponseEntity.status(499).build();
        }
        // Message condensé : les exceptions d'infrastructure (timeout d'invocation
        // Hazelcast en tête) embarquent un dump de contexte de plusieurs centaines
        // de caractères qui noie la ligne. Cf. ExceptionMessages.
        log.error("Internal error method={} path={} exception={} message={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                ex.getClass().getSimpleName(),
                ExceptionMessages.condense(ex));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}

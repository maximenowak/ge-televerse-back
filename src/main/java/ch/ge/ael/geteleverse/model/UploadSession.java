package ch.ge.ael.geteleverse.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Session de téléversement, stockée en JSON dans l'IMap Hazelcast.
 *
 * @param status      cycle de vie (cf. {@link SessionStatus}) ; {@link SessionStatus#OPEN}
 *                    par défaut, y compris pour les sessions écrites avant son introduction
 * @param statusUntil échéance d'un statut transitoire ({@link SessionStatus#FINALIZING}) ;
 *                    null sinon
 */
public record UploadSession(
        String token,
        String documentId,
        SizeProfile sizeProfile,
        Instant createdAt,
        Instant expiresAt,
        List<PageInfo> pages,
        int pageCounter,
        boolean phoneConnected,
        String originatorIp,
        SessionStatus status,
        Instant statusUntil
) {

    /**
     * Invariants : liste de pages immuable et <b>sans élément nul</b>
     * ({@code List.copyOf} lève sur un {@code null}), statut {@link SessionStatus#OPEN}
     * par défaut. Un réordonnancement concurrent d'une suppression enregistrait un
     * {@code null} dans l'IMap : toutes les lectures du token échouaient ensuite jusqu'à
     * expiration, et ses pages n'étaient plus purgées. L'échec survient désormais à la
     * construction, avant toute écriture.
     */
    public UploadSession {
        pages = pages == null ? List.of() : List.copyOf(pages);
        if (status == null) status = SessionStatus.OPEN;
    }

    /**
     * Constructeur Jackson avec valeurs par défaut :
     * sizeProfile null → DEFAULT, pages null → List.of(),
     * pageCounter absent → 0, phoneConnected absent → false,
     * originatorIp absent → null (sessions en vol au moment du déploiement de
     * la feature « une session par IP », ou feature désactivée par
     * {@code app.originator.enabled=false}), status absent → OPEN (sessions écrites
     * avant le cycle de vie).
     */
    @JsonCreator
    public UploadSession(
            @JsonProperty("token") String token,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("sizeProfile") SizeProfile sizeProfile,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("expiresAt") Instant expiresAt,
            @JsonProperty("pages") List<PageInfo> pages,
            @JsonProperty("pageCounter") Integer pageCounter,
            @JsonProperty("phoneConnected") Boolean phoneConnected,
            @JsonProperty("originatorIp") String originatorIp,
            @JsonProperty("status") SessionStatus status,
            @JsonProperty("statusUntil") Instant statusUntil
    ) {
        this(token, documentId,
                sizeProfile != null ? sizeProfile : SizeProfile.DEFAULT,
                createdAt, expiresAt, pages,
                pageCounter != null ? pageCounter : 0,
                phoneConnected != null && phoneConnected,
                originatorIp, status, statusUntil);
    }

    /**
     * Compat : signature pré-cycle de vie — session {@link SessionStatus#OPEN}.
     */
    public UploadSession(String token, String documentId, SizeProfile sizeProfile,
                         Instant createdAt, Instant expiresAt, List<PageInfo> pages,
                         int pageCounter, boolean phoneConnected, String originatorIp) {
        this(token, documentId, sizeProfile, createdAt, expiresAt, pages,
                pageCounter, phoneConnected, originatorIp, SessionStatus.OPEN, null);
    }

    /**
     * Compat : signature pré-{@code originatorIp} (sessions construites en
     * test ou par du code qui n'utilise pas la feature « une session par IP »).
     * Délègue avec {@code originatorIp = null}.
     */
    public UploadSession(String token, String documentId, SizeProfile sizeProfile,
                         Instant createdAt, Instant expiresAt, List<PageInfo> pages,
                         int pageCounter, boolean phoneConnected) {
        this(token, documentId, sizeProfile, createdAt, expiresAt, pages,
                pageCounter, phoneConnected, null);
    }

    public static UploadSession create(SizeProfile sizeProfile, Duration ttl) {
        return create(sizeProfile, ttl, null);
    }

    /**
     * Crée une nouvelle session liée à une IP d'origine (peut être {@code null}
     * quand la feature « une session par IP » est désactivée par configuration).
     *
     * <p>Le {@code documentId} est tiré indépendamment du token. Il valait
     * {@code <token>.pdf} : l'identifiant remis à l'application hôte (postMessage, sa
     * base, ses logs) était le jeton porteur de la session, et le token présent dans
     * chaque ligne d'access log donnait l'identifiant du document. Même forme
     * {@code <uuid>.pdf}, attendue par {@code DocumentController}.</p>
     */
    public static UploadSession create(SizeProfile sizeProfile, Duration ttl, String originatorIp) {
        var now = Instant.now();
        var token = UUID.randomUUID().toString();
        var documentId = "%s.pdf".formatted(UUID.randomUUID());
        return new UploadSession(token, documentId,
                sizeProfile != null ? sizeProfile : SizeProfile.DEFAULT,
                now, now.plus(ttl), List.of(), 0, false, originatorIp);
    }

    /**
     * Retourne une copie de la session avec l'expiration repoussée.
     */
    public UploadSession withRenewedExpiry(Duration ttl) {
        return new UploadSession(token, documentId, sizeProfile, createdAt,
                Instant.now().plus(ttl), pages, pageCounter, phoneConnected, originatorIp, status, statusUntil);
    }

    /**
     * Retourne une copie de la session avec l'état de connexion du téléphone modifié.
     */
    public UploadSession withPhoneConnected(boolean connected) {
        return new UploadSession(token, documentId, sizeProfile, createdAt, expiresAt, pages,
                pageCounter, connected, originatorIp, status, statusUntil);
    }

    /**
     * Retourne une copie de la session dans le statut donné.
     *
     * @param until échéance d'un statut transitoire, null sinon
     */
    public UploadSession withStatus(SessionStatus newStatus, Instant until) {
        return new UploadSession(token, documentId, sizeProfile, createdAt, expiresAt, pages,
                pageCounter, phoneConnected, originatorIp, newStatus, until);
    }

    /** Retourne une copie de la session avec la liste de pages donnée. */
    public UploadSession withPages(List<PageInfo> newPages) {
        return new UploadSession(token, documentId, sizeProfile, createdAt, expiresAt, newPages,
                pageCounter, phoneConnected, originatorIp, status, statusUntil);
    }

    /**
     * Statut en vigueur à {@code now} : une finalisation dont l'échéance est passée
     * (pod mort en pleine fusion) est traitée comme {@link SessionStatus#OPEN}.
     */
    public SessionStatus effectiveStatus(Instant now) {
        if (status == SessionStatus.FINALIZING && statusUntil != null && now.isAfter(statusUntil)) {
            return SessionStatus.OPEN;
        }
        return status;
    }

    @JsonIgnore
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Somme des octets stockés sur S3 pour toutes les pages converties de la session.
     */
    @JsonIgnore
    public long totalBytes() {
        return pages.stream()
                .mapToLong(p -> Math.max(0L, p.fileSize()))
                .sum();
    }

    /** Page d'identifiant {@code pageId}, ou null. */
    public PageInfo findPage(String pageId) {
        return pages.stream()
                .filter(p -> p.pageId().equals(pageId))
                .findFirst()
                .orElse(null);
    }

    // ===== Helpers pour les clés S3 =====

    /**
     * Préfixe S3 pour les pages individuelles : token/
     * Le token est un UUID globalement unique, suffisant pour isoler les pages
     * d'une session.
     */
    @JsonIgnore
    public String pagePrefix() {
        return token + "/";
    }

    /**
     * Génère l'ID de la prochaine page (ex: "page-001").
     */
    @JsonIgnore
    public String nextPageId() {
        return "page-%03d".formatted(pageCounter + 1);
    }

    /**
     * Génère la clé S3 complète pour la prochaine page (ex: token/page-001.pdf).
     */
    @JsonIgnore
    public String nextPageKey() {
        return pagePrefix() + nextPageId() + ".pdf";
    }

    /**
     * Génère la clé S3 pour la vignette de la prochaine page (ex: token/page-001-thumb.jpg).
     */
    @JsonIgnore
    public String nextThumbnailKey() {
        return pagePrefix() + nextPageId() + "-thumb.jpg";
    }

    // ===== Méthodes de mutation immutables =====

    public UploadSession withAddedPage(PageInfo page) {
        var newPages = new ArrayList<>(pages);
        newPages.add(page);
        return new UploadSession(token, documentId, sizeProfile, createdAt, expiresAt,
                newPages, pageCounter + 1, phoneConnected, originatorIp, status, statusUntil);
    }

    /**
     * Variante atomique-friendly de {@link #withAddedPage(PageInfo)} : ajoute
     * la page sans toucher au {@code pageCounter}. Le compteur est incrémenté
     * séparément par {@link #withBumpedCounter()} lors de la réservation du
     * {@code pageId} — split nécessaire pour permettre à Hazelcast de
     * sérialiser indépendamment (1) la réservation du slot et (2) l'attachement
     * de la page, et ainsi éviter deux uploads concurrents qui se voient
     * attribuer le même {@code pageId}.
     */
    public UploadSession withAttachedPage(PageInfo page) {
        var newPages = new ArrayList<>(pages);
        newPages.add(page);
        return new UploadSession(token, documentId, sizeProfile, createdAt, expiresAt,
                newPages, pageCounter, phoneConnected, originatorIp, status, statusUntil);
    }

    /**
     * Incrémente {@code pageCounter} sans ajouter de page. Utilisé par la
     * réservation atomique du prochain {@code pageId} : le compteur monte AVANT
     * le stockage S3, puis la {@link PageInfo} finale est rattachée via
     * {@link #withAttachedPage(PageInfo)} une fois l'objet écrit. Un échec
     * entre les deux laisse un trou numérique (acceptable : les {@code pageId}
     * restent uniques).
     */
    public UploadSession withBumpedCounter() {
        return new UploadSession(token, documentId, sizeProfile, createdAt, expiresAt, pages,
                pageCounter + 1, phoneConnected, originatorIp, status, statusUntil);
    }

    public UploadSession withRemovedPage(String pageId) {
        var newPages = pages.stream()
                .filter(p -> !p.pageId().equals(pageId))
                .toList();
        return new UploadSession(token, documentId, sizeProfile, createdAt, expiresAt, newPages,
                pageCounter, phoneConnected, originatorIp, status, statusUntil);
    }

    /** Remplace la page de même {@code pageId} ; inchangée si elle n'existe plus. */
    public UploadSession withReplacedPage(PageInfo page) {
        var newPages = pages.stream()
                .map(p -> p.pageId().equals(page.pageId()) ? page : p)
                .toList();
        return withPages(newPages);
    }

    /**
     * Réordonne les pages. {@code orderedPageIds} doit contenir exactement les
     * identifiants des pages de la session — vérifié par l'appelant SOUS le verrou ;
     * un identifiant inconnu produirait un {@code null}, que le constructeur refuse.
     */
    public UploadSession withReorderedPages(List<String> orderedPageIds) {
        var pageMap = pages.stream()
                .collect(java.util.stream.Collectors.toMap(PageInfo::pageId, p -> p));
        var reordered = orderedPageIds.stream()
                .map(pageMap::get)
                .toList();
        return withPages(reordered);
    }
}

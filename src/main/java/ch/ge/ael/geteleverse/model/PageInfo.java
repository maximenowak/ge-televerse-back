package ch.ge.ael.geteleverse.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public record PageInfo(
        String pageId,
        String s3Key,
        String originalFilename,
        String contentType,
        long fileSize,
        Instant uploadedAt,
        Instant lastModifiedAt,
        String thumbnailKey,
        PreviousVersion previousVersion,
        PreviousVersion initialVersion,
        String contentHash
) implements Serializable {

    /**
     * Snapshot d'une version antérieure d'une page, conservé sur ses propres clés S3
     * (non comptées dans le quota de session). Deux usages :
     *
     * <ul>
     *   <li>{@link #previousVersion} — état juste avant la dernière édition,
     *       pour l'undo « simple coup » (« Annuler dernière opération »).
     *       Remplacé à chaque édition : c'est la version que l'édition vient de
     *       supplanter, sous ses propres clés.</li>
     *   <li>{@link #initialVersion} — état d'upload d'origine, capturé une
     *       seule fois à la première édition et conservé jusqu'au reset /
     *       suppression / finalisation, pour le « Annuler tout » — en général
     *       les clés de l'upload d'origine ({@code token/page-001.pdf}).</li>
     * </ul>
     */
    public record PreviousVersion(
            String s3Key,
            String thumbnailKey,
            long fileSize,
            Instant lastModifiedAt
    ) implements Serializable {

        @JsonCreator
        public PreviousVersion(
                @JsonProperty("s3Key") String s3Key,
                @JsonProperty("thumbnailKey") String thumbnailKey,
                @JsonProperty("fileSize") long fileSize,
                @JsonProperty("lastModifiedAt") Instant lastModifiedAt
        ) {
            this.s3Key = s3Key;
            this.thumbnailKey = thumbnailKey;
            this.fileSize = fileSize;
            this.lastModifiedAt = lastModifiedAt;
        }
    }

    @JsonCreator
    public PageInfo(
            @JsonProperty("pageId") String pageId,
            @JsonProperty("s3Key") String s3Key,
            @JsonProperty("originalFilename") String originalFilename,
            @JsonProperty("contentType") String contentType,
            @JsonProperty("fileSize") long fileSize,
            @JsonProperty("uploadedAt") Instant uploadedAt,
            @JsonProperty("lastModifiedAt") Instant lastModifiedAt,
            @JsonProperty("thumbnailKey") String thumbnailKey,
            @JsonProperty("previousVersion") PreviousVersion previousVersion,
            @JsonProperty("initialVersion") PreviousVersion initialVersion,
            @JsonProperty("contentHash") String contentHash
    ) {
        this.pageId = pageId;
        this.s3Key = s3Key;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.uploadedAt = uploadedAt;
        this.lastModifiedAt = lastModifiedAt != null ? lastModifiedAt : uploadedAt;
        this.thumbnailKey = thumbnailKey;
        this.previousVersion = previousVersion;
        this.initialVersion = initialVersion;
        this.contentHash = contentHash;
    }

    /** Compat : pages sans historique de version (uploadedAt-only constructor). */
    public PageInfo(String pageId, String s3Key, String originalFilename, String contentType,
                    long fileSize, Instant uploadedAt, String thumbnailKey) {
        this(pageId, s3Key, originalFilename, contentType, fileSize, uploadedAt, uploadedAt, thumbnailKey, null, null, null);
    }

    /** Compat : signature pré-versioning avec lastModifiedAt explicite. */
    public PageInfo(String pageId, String s3Key, String originalFilename, String contentType,
                    long fileSize, Instant uploadedAt, Instant lastModifiedAt, String thumbnailKey) {
        this(pageId, s3Key, originalFilename, contentType, fileSize, uploadedAt, lastModifiedAt, thumbnailKey, null, null, null);
    }

    /** Compat : signature pré-{@code initialVersion} (previousVersion seul). */
    public PageInfo(String pageId, String s3Key, String originalFilename, String contentType,
                    long fileSize, Instant uploadedAt, Instant lastModifiedAt, String thumbnailKey,
                    PreviousVersion previousVersion) {
        this(pageId, s3Key, originalFilename, contentType, fileSize, uploadedAt, lastModifiedAt,
                thumbnailKey, previousVersion, null, null);
    }

    /** Compat : signature canonique pré-{@code contentHash} (previousVersion + initialVersion). */
    public PageInfo(String pageId, String s3Key, String originalFilename, String contentType,
                    long fileSize, Instant uploadedAt, Instant lastModifiedAt, String thumbnailKey,
                    PreviousVersion previousVersion, PreviousVersion initialVersion) {
        this(pageId, s3Key, originalFilename, contentType, fileSize, uploadedAt, lastModifiedAt,
                thumbnailKey, previousVersion, initialVersion, null);
    }

    /**
     * Retourne une copie de la page portant l'empreinte SHA-256 (hex) de son
     * contenu brut uploadé. Sert au dédoublonnage anti-spam : deux uploads
     * octet pour octet identiques partagent la même empreinte. L'empreinte est
     * conservée à travers les éditions (crop, rotate, balance des blancs…) et
     * disparaît avec la page à sa suppression — un re-upload après suppression
     * est donc de nouveau accepté.
     */
    public PageInfo withContentHash(String contentHash) {
        return new PageInfo(pageId, s3Key, originalFilename, contentType, fileSize,
                uploadedAt, lastModifiedAt, thumbnailKey, previousVersion, initialVersion, contentHash);
    }

    /** Version courante de la page, en snapshot. */
    private PreviousVersion currentVersion() {
        return new PreviousVersion(s3Key, thumbnailKey, fileSize, lastModifiedAt);
    }

    /**
     * Nouvelle version de la page après une édition, écrite sous des clés NEUVES :
     * la version courante devient la version précédente (undo simple) et, à la
     * première édition, la version initiale (« Annuler tout »).
     *
     * <p>Aucun objet existant n'est réécrit. Les éditions écrasaient auparavant le PDF
     * vivant et son {@code -prev} en place, avant de prendre le verrou de session : un
     * rejeu après {@code SESSION_BUSY} retournait la page une seconde fois, deux
     * éditions concurrentes se perdaient, et la version initiale pouvait être
     * capturée sur un état déjà édité. Ici, tant que la session n'a pas adopté la
     * nouvelle version (sous verrou), elle n'existe pour personne.</p>
     */
    public PageInfo withNewVersion(String newS3Key, String newThumbnailKey, long newFileSize) {
        var current = currentVersion();
        return new PageInfo(pageId, newS3Key, originalFilename, contentType, newFileSize,
                uploadedAt, Instant.now(), newThumbnailKey, current,
                initialVersion != null ? initialVersion : current, contentHash);
    }

    /**
     * Clés S3 que {@code next} ne référence plus : à purger une fois {@code next}
     * adoptée par la session. Vaut pour édition, undo et reset — et pour les pages
     * antérieures au versionnement (objets {@code -prev} / {@code -init}).
     */
    public List<String> s3KeysDroppedBy(PageInfo next) {
        var kept = next.s3Keys();
        return s3Keys().stream().filter(k -> !kept.contains(k)).distinct().toList();
    }

    /** Clés de vignette que {@code next} ne référence plus (cf. {@link #s3KeysDroppedBy}). */
    public List<String> thumbnailKeysDroppedBy(PageInfo next) {
        var kept = next.thumbnailKeys();
        return thumbnailKeys().stream().filter(k -> !kept.contains(k)).distinct().toList();
    }

    /**
     * Toutes les clés S3 portées par la page, sans doublon : le PDF vivant et ceux de
     * ses snapshots d'undo. Après une première édition, versions précédente et initiale
     * partagent la même clé. Sert aux purges (expiration, suppression, finalisation).
     * Les vignettes vivent dans le cache Hazelcast, cf. {@link #thumbnailKeys()}.
     */
    @JsonIgnore
    public List<String> s3Keys() {
        return Stream.concat(Stream.of(s3Key),
                        Stream.of(previousVersion, initialVersion)
                                .filter(Objects::nonNull)
                                .map(PreviousVersion::s3Key))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Toutes les clés de cache de vignette portées par la page, sans doublon : la
     * vivante et celles de ses snapshots d'undo.
     */
    @JsonIgnore
    public List<String> thumbnailKeys() {
        return Stream.concat(Stream.of(thumbnailKey),
                        Stream.of(previousVersion, initialVersion)
                                .filter(Objects::nonNull)
                                .map(PreviousVersion::thumbnailKey))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Vrai si la version précédente (undo simple) EST l'état d'upload initial :
     * un undo simple ramènerait alors la page à son état d'origine. Distingué
     * par l'identité de {@code lastModifiedAt} — la version initiale et le
     * premier backup partagent l'instant d'upload, qu'aucune édition ultérieure
     * ne peut reproduire.
     */
    public boolean previousIsInitial() {
        return previousVersion != null && initialVersion != null
                && initialVersion.lastModifiedAt().equals(previousVersion.lastModifiedAt());
    }

    /**
     * Restaure la version précédente (undo simple) en faisant pointer la page sur ses
     * clés — aucune copie S3 — et efface le backup. Efface aussi la version initiale
     * si l'undo ramène à l'état d'upload (plus rien à réinitialiser). Les clés de la
     * version abandonnée sont données par {@link #s3KeysDroppedBy}.
     */
    public PageInfo restoredFromBackup() {
        if (previousVersion == null) return this;
        var keptInitial = previousIsInitial() ? null : initialVersion;
        return new PageInfo(pageId, previousVersion.s3Key(), originalFilename, contentType,
                previousVersion.fileSize(), uploadedAt, previousVersion.lastModifiedAt(),
                previousVersion.thumbnailKey(), null, keptInitial, contentHash);
    }

    /**
     * Restaure l'état d'upload initial (« Annuler tout ») en faisant pointer la page
     * sur ses clés, et efface les deux backups (précédent et initial).
     */
    public PageInfo restoredFromInitial() {
        if (initialVersion == null) return this;
        return new PageInfo(pageId, initialVersion.s3Key(), originalFilename, contentType,
                initialVersion.fileSize(), uploadedAt, initialVersion.lastModifiedAt(),
                initialVersion.thumbnailKey(), null, null, contentHash);
    }
}

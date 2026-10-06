package ch.ge.ael.geteleverse.model;

import java.io.Serializable;
import java.time.Instant;

public record UploadEvent(
        String type,
        String documentId,
        String fileName,
        String contentType,
        long fileSize,
        Instant uploadedAt,
        String pageId,
        int pageIndex,
        int totalPages,
        long totalBytes,
        long maxTotalBytes
) implements Serializable {

    public static UploadEvent completed(String documentId, String fileName, String contentType,
                                        long fileSize, String pageId, int pageIndex, int totalPages,
                                        long totalBytes, long maxTotalBytes) {
        return new UploadEvent("upload-complete", documentId, fileName, contentType, fileSize,
                Instant.now(), pageId, pageIndex, totalPages, totalBytes, maxTotalBytes);
    }

    public static UploadEvent pageDeleted(String documentId, String pageId, int totalPages,
                                          long totalBytes, long maxTotalBytes) {
        return new UploadEvent("page-deleted", documentId, null, null, 0,
                Instant.now(), pageId, -1, totalPages, totalBytes, maxTotalBytes);
    }

    public static UploadEvent pagesReordered(String documentId, int totalPages,
                                              long totalBytes, long maxTotalBytes) {
        return new UploadEvent("pages-reordered", documentId, null, null, 0,
                Instant.now(), null, -1, totalPages, totalBytes, maxTotalBytes);
    }

    /**
     * Une page a été retouchée (rotate / white-balance / deskew / crop / undo).
     * Émis aussi quand la retouche n'a rien changé (deskew/crop sans document
     * détecté, undo sans sauvegarde) : l'action a renouvelé le TTL de la
     * session, et l'autre appareil doit en être informé — sans cet événement,
     * son compte à rebours local expire une session encore vivante (cf.
     * SessionExpiryDialog côté front).
     */
    public static UploadEvent pageEdited(String documentId, String pageId, int totalPages,
                                         long totalBytes, long maxTotalBytes) {
        return new UploadEvent("page-edited", documentId, null, null, 0,
                Instant.now(), pageId, -1, totalPages, totalBytes, maxTotalBytes);
    }

    /**
     * Signale que l'utilisateur a terminé l'upload et que le document final est prêt sur S3.
     */
    public static UploadEvent sessionComplete(String documentId, int totalPages) {
        return new UploadEvent("session-complete", documentId, null, null, 0,
                Instant.now(), null, -1, totalPages, 0L, 0L);
    }

    /**
     * Le téléphone s'est connecté à la session (QR code scanné, page d'upload ouverte).
     */
    public static UploadEvent phoneConnected(String documentId) {
        return new UploadEvent("phone-connected", documentId, null, null, 0,
                Instant.now(), null, -1, 0, 0L, 0L);
    }

    /**
     * Le téléphone a terminé (utilisateur a appuyé sur "Terminé").
     */
    public static UploadEvent phoneDisconnected(String documentId) {
        return new UploadEvent("phone-disconnected", documentId, null, null, 0,
                Instant.now(), null, -1, 0, 0L, 0L);
    }
}

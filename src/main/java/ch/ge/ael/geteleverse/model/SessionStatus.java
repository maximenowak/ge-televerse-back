package ch.ge.ael.geteleverse.model;

/**
 * Cycle de vie d'une session de téléversement.
 *
 * <p>Sans état, la finalisation travaillait sur une copie non verrouillée et ne
 * retirait jamais la session : le téléphone pouvait attacher une page pendant la
 * fusion (réponse 200, page absente du document final), une édition en vol pouvait
 * réécrire une page que la finalisation venait de purger. Toutes les mutations de
 * contenu exigent désormais {@link #OPEN}, vérifié sous le verrou de session.</p>
 */
public enum SessionStatus {

    /** Session ouverte : uploads, éditions, suppressions et réordonnancements admis. */
    OPEN,

    /**
     * Fusion en cours. Porte une échéance ({@code UploadSession.statusUntil}) : un pod
     * mort en pleine finalisation ne gèle pas la session au-delà — passé ce délai, elle
     * redevient {@link #OPEN} et peut être finalisée à nouveau.
     */
    FINALIZING,

    /**
     * PDF composite écrit sous {@code documentId}. Les pages ne sont plus listées
     * (fusionnées, puis purgées) ; une finalisation rejouée renvoie le même
     * {@code documentId}.
     */
    FINALIZED
}

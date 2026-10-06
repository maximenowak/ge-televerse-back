package ch.ge.ael.geteleverse.controller;

/**
 * Recadrage, redressement ou balance des blancs demandé sur une page qui ne peint pas
 * une image seule — page de PDF natif portant du texte, des tracés vectoriels ou
 * plusieurs images. Ces éditions reconstruisent la page à partir de son image : tout
 * le reste était perdu en silence (un undo restait possible, mais rien ne le
 * signalait).
 *
 * <p>Levée avant toute écriture. Traduite par {@link GlobalExceptionHandler} en
 * {@code 422 PAGE_NOT_EDITABLE}, non rejouable : la rotation et la suppression
 * restent possibles sur ces pages.</p>
 */
public class PageNotEditableException extends RuntimeException {

    public PageNotEditableException() {
        super("Page content is not a single image");
    }
}

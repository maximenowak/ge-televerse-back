package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.service.EditTrace;

import java.time.Duration;

/**
 * Levée par {@code SessionController} quand une édition de page
 * ({@code rotate / crop / deskew / white-balance}) dépasse {@code app.edit-timeout}.
 * Traduite en {@code 504 EDIT_TIMEOUT} par {@link GlobalExceptionHandler}.
 *
 * <p>Motif : le chemin d'édition n'avait aucune borne de durée, contrairement à la
 * finalisation qui porte la sienne depuis toujours ({@code app.finalize-timeout}).
 * Or il enchaîne un GET S3, une passe CPU native (PDFBox + OpenCV + ONNX pour le
 * deskew), ~5 aller-retours S3 de plus et une section verrouillée Hazelcast : la
 * moindre lenteur sur l'un de ces maillons laissait la requête en vol
 * indéfiniment. Côté navigateur cela se voyait comme un appel « pending » sans
 * réponse, sans trace côté pod ; côté pod cela immobilisait un permis du bulkhead
 * {@code edit} et de la mémoire, aggravant la saturation qui avait causé la
 * lenteur.</p>
 *
 * <p>Le timeout annule l'amont : le permis de bulkhead est relâché et l'appel S3
 * en cours interrompu. La valeur doit rester <b>sous</b> le timeout HTTP du client
 * (30 s côté {@code ge-televerse-front}) pour que celui-ci reçoive une vraie
 * réponse JSON qualifiée plutôt qu'un abandon de socket indiscernable d'une
 * coupure réseau.</p>
 *
 * <p>Porte le relevé d'étapes ({@link EditTrace}) du pipeline coupé : le WARN
 * du 504 nomme ainsi le maillon qui a mangé le budget — l'étape encore en cours
 * au moment de la coupure — au lieu de se borner à l'opération.</p>
 */
public class EditTimeoutException extends RuntimeException {

    private final String operation;
    private final Duration budget;
    private final EditTrace trace;

    public EditTimeoutException(String operation, Duration budget) {
        this(operation, budget, EditTrace.NONE);
    }

    public EditTimeoutException(String operation, Duration budget, EditTrace trace) {
        super("Edit operation '" + operation + "' timed out after " + budget);
        this.operation = operation;
        this.budget = budget;
        this.trace = trace;
    }

    public String operation() {
        return operation;
    }

    /** Borne dépassée ({@code app.edit-timeout} ou {@code app.undo-timeout}). */
    public Duration budget() {
        return budget;
    }

    /**
     * Étapes du pipeline au moment où le log est écrit, au format de
     * {@link EditTrace#summary()} ; {@code -} si le pipeline n'était pas tracé.
     */
    public String stages() {
        return trace.summary();
    }
}

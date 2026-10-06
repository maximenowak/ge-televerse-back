package ch.ge.ael.geteleverse.service;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Relevé des étapes d'une opération de page (édition, undo, reset) : nom, durée
 * et issue de chaque maillon, dans l'ordre où ils ont démarré.
 *
 * <p><b>Motif.</b> Un dépassement d'{@code app.edit-timeout} ne laissait qu'une
 * ligne de log — « Edit timed out operation=deskew » — sans dire lequel des
 * maillons avait mangé le budget : GET S3, passe native PDFBox/OpenCV/ONNX,
 * écritures S3 et cache de vignettes, section verrouillée Hazelcast. Rien sur ce
 * chemin ne logue au-dessus de DEBUG, et la métrique {@code televerse.edit.duration}
 * ne donne que le total. Ce relevé est porté par l'{@link ch.ge.ael.geteleverse.controller.EditTimeoutException}
 * et imprimé dans le WARN du 504 : une étape encore en cours au moment de la
 * coupure est LA coupable, les précédentes disent combien il restait.</p>
 *
 * <p><b>Transport.</b> La trace voyage dans le {@link reactor.util.context.Context}
 * Reactor, posé par {@link #attachTo(Mono)} au point où la borne de durée est
 * armée (cf. {@code SessionController#traced}). Les maillons n'ont ainsi pas à
 * la recevoir en paramètre — {@code StorageService} et {@code SessionService}
 * gardent leurs signatures — et un pipeline assemblé sans trace (tests, appels
 * hors chemin d'édition) traverse les enveloppes sans rien enregistrer :
 * {@link #from(ContextView)} rend alors {@link #NONE}, une trace inerte.</p>
 *
 * <p><b>Deux enveloppes.</b> {@link #stage(String, Mono)} pour un maillon
 * réactif : début à la souscription, fin au signal terminal — ou à
 * l'<i>annulation</i>, qui est précisément ce que produit l'expiration de la
 * borne sur le maillon en vol. {@link #time(String, Callable)} pour un maillon
 * synchrone (la passe native, dans un {@code fromCallable} : voir
 * {@code StorageService#deskewPage}) ; celui-ci ignore l'interruption et
 * continue après la coupure, d'où l'issue {@code running} dans le WARN, qui le
 * désigne sans ambiguïté.</p>
 *
 * <p><b>Format.</b> {@code nom:duréems:issue} joints par des virgules, sans
 * espace, pour rester extractible comme un seul champ {@code stages=…} dans une
 * ligne key=value : {@code s3.get:118ms:ok,pdf.extract:402ms:ok,detect:19480ms:running}.
 * Issues : {@code ok}, {@code error}, {@code cancelled}, {@code running}.
 * {@code -} quand rien n'a été enregistré.</p>
 *
 * <p>Thread-safe : les maillons s'enregistrent depuis les threads du SDK S3, de
 * boundedElastic et du client Hazelcast. Le coût est un objet par maillon.</p>
 */
public final class EditTrace {

    /** Clé de contexte Reactor : la classe elle-même, comme le veut l'usage. */
    private static final Object KEY = EditTrace.class;

    /**
     * Trace inerte : n'enregistre rien, {@link #summary()} rend {@code -}.
     * Rendue par {@link #from(ContextView)} hors de tout contexte tracé, pour que
     * les enveloppes restent de simples passe-plats.
     */
    public static final EditTrace NONE = new EditTrace(false);

    private final boolean enabled;
    /** Gardée par {@code this}. */
    private final List<Stage> stages = new ArrayList<>();

    public EditTrace() {
        this(true);
    }

    private EditTrace(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Pose cette trace dans le contexte de {@code mono} : tout maillon enveloppé
     * en amont (souscrit par lui) la retrouve via {@link #from(ContextView)}.
     * À appliquer en AVAL des maillons à tracer — le contexte Reactor remonte du
     * souscripteur vers la source.
     */
    public <T> Mono<T> attachTo(Mono<T> mono) {
        return enabled ? mono.contextWrite(ctx -> ctx.put(KEY, this)) : mono;
    }

    /** Trace du contexte courant, ou {@link #NONE}. */
    public static EditTrace from(ContextView ctx) {
        return ctx.getOrDefault(KEY, NONE);
    }

    /**
     * Enveloppe un maillon réactif. Le chrono démarre à la souscription (une
     * mesure par tentative, pas une au câblage) et s'arrête au signal terminal
     * ou à l'annulation.
     */
    public static <T> Mono<T> stage(String name, Mono<T> mono) {
        return Mono.deferContextual(ctx -> from(ctx).wrap(name, mono));
    }

    private <T> Mono<T> wrap(String name, Mono<T> mono) {
        if (!enabled) return mono;
        return Mono.defer(() -> {
            Stage stage = begin(name);
            return mono.doFinally(signal -> end(stage, outcomeOf(signal)));
        });
    }

    /**
     * Enveloppe un maillon synchrone. L'exception traverse telle quelle — le
     * maillon est noté {@code error} — pour que l'appelant garde sa propre
     * qualification (un {@code IOException} de PDFBox devient un 422 plus haut).
     */
    public <T> T time(String name, Callable<T> step) throws Exception {
        if (!enabled) return step.call();
        Stage stage = begin(name);
        try {
            T result = step.call();
            end(stage, "ok");
            return result;
        } catch (Throwable t) {
            end(stage, "error");
            throw t;
        }
    }

    /**
     * Résumé pour le log, calculé à l'appel : un maillon non terminé est noté
     * {@code running} avec sa durée écoulée jusqu'ici.
     */
    public synchronized String summary() {
        if (stages.isEmpty()) return "-";
        long now = System.nanoTime();
        return stages.stream()
                .map(stage -> stage.describe(now))
                .collect(Collectors.joining(","));
    }

    private synchronized Stage begin(String name) {
        Stage stage = new Stage(name, System.nanoTime());
        stages.add(stage);
        return stage;
    }

    private synchronized void end(Stage stage, String outcome) {
        // Une seule fin par maillon : doFinally n'est émis qu'une fois, mais
        // rien ne coûte de le garantir ici plutôt que de le supposer.
        if (stage.outcome == null) {
            stage.endNanos = System.nanoTime();
            stage.outcome = outcome;
        }
    }

    private static String outcomeOf(SignalType signal) {
        return switch (signal) {
            case ON_COMPLETE -> "ok";
            case ON_ERROR -> "error";
            case CANCEL -> "cancelled";
            // doFinally n'émet que ces trois-là ; le reste est théorique.
            default -> signal.name().toLowerCase();
        };
    }

    private static final class Stage {
        private final String name;
        private final long startNanos;
        private long endNanos;
        /** {@code null} tant que le maillon n'est pas terminé. */
        private String outcome;

        private Stage(String name, long startNanos) {
            this.name = name;
            this.startNanos = startNanos;
        }

        private String describe(long now) {
            long end = outcome == null ? now : endNanos;
            long millis = (end - startNanos) / 1_000_000;
            return name + ":" + millis + "ms:" + (outcome == null ? "running" : outcome);
        }
    }
}

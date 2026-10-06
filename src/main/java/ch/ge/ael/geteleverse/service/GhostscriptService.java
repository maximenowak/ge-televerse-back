package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.GhostscriptProperties;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service de recompression PDF via Ghostscript invoqué en sous-processus.
 *
 * <p>Le binaire est résolu via le {@code PATH} (configuré par {@link GhostscriptProperties#binaryName()}).
 * Sa présence est sondée au démarrage par un appel {@code <binary> --version} ; si le binaire
 * est absent ou échoue, le service reste fonctionnel mais retourne toujours le PDF d'origine
 * inchangé — la feature dégrade silencieusement, ne bloque pas l'upload.</p>
 *
 * <p>La recompression est cadrée par plusieurs garde-fous :</p>
 * <ul>
 *   <li><b>Seuil de taille</b> ({@link GhostscriptProperties#sizeThreshold()}) — les PDF en dessous
 *       gagnent rarement à passer par Ghostscript et sont laissés intacts.</li>
 *   <li><b>Scheduler borné</b> — au plus {@code workers} processus {@code gs} concurrents,
 *       file d'attente {@code queueSize}, rejet au-delà (retombe sur l'original).</li>
 *   <li><b>Timeout</b> — {@code gs} est tué (descendants compris) et l'original conservé
 *       en cas de blocage ; cf. {@link #runGs}.</li>
 *   <li><b>Comparaison de taille</b> — si la sortie est plus volumineuse que l'entrée
 *       (PDF déjà optimisés, contenu vectoriel), on garde l'original.</li>
 *   <li><b>Stderr</b> — capturé et tracé en WARN en cas d'échec ; ne fait jamais échouer l'upload.</li>
 * </ul>
 *
 * <p>Métriques Micrometer exposées via {@code /actuator/prometheus} :</p>
 * <ul>
 *   <li>{@code televerse.gs.duration} (timer, tags {@code status=success|failure|timeout|skipped})</li>
 *   <li>{@code televerse.gs.input.bytes} / {@code televerse.gs.output.bytes} (distribution summaries)</li>
 *   <li>{@code televerse.gs.concurrent} (gauge — nombre de processus {@code gs} en cours)</li>
 *   <li>{@code televerse.gs.queue.depth} (gauge — tâches en attente sur le scheduler borné)</li>
 *   <li>{@code televerse.gs.peak.rss.bytes} (gauge — peak RSS max d'un processus {@code gs}
 *       depuis le démarrage du pod, échantillonné via {@code /proc/<pid>/status:VmHWM} ;
 *       Linux seulement, reste à 0 en dev Windows)</li>
 *   <li>{@code televerse.gs.current.rss.bytes} (gauge — somme du {@code VmRSS} des processus
 *       {@code gs} actifs à l'instant du scrape, reflète la pression mémoire instantanée)</li>
 * </ul>
 */
@Service
@Slf4j(topic = "pdfprocessing")
public class GhostscriptService {

    private final GhostscriptProperties properties;
    private final boolean available;
    private final Scheduler scheduler;

    /** Nombre de tâches admises (Mono actif), incrémenté avant {@code subscribeOn}. */
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Nombre de processus {@code gs} effectivement en train de tourner. */
    private final AtomicInteger running = new AtomicInteger();

    private final Timer durationSuccess;
    private final Timer durationFailure;
    private final Timer durationTimeout;
    private final Timer durationSkipped;
    private final DistributionSummary inputBytes;
    private final DistributionSummary outputBytes;

    /**
     * Peak RSS maximum observé sur un processus {@code gs} depuis le démarrage du pod.
     * Monotone : aggregate via {@link AtomicLong#accumulateAndGet(long, java.util.function.LongBinaryOperator)}
     * avec {@link Math#max}. Une seule série Prometheus, parfaite pour calibrer
     * {@code app.ghostscript.workers} par rapport à la limite mémoire du pod.
     */
    private final AtomicLong peakRssMaxBytes = new AtomicLong();

    /**
     * Samplers en cours d'exécution. Sommés au scrape pour alimenter la gauge
     * {@code televerse.gs.current.rss.bytes}. Un sampler est ajouté juste avant
     * le {@code start()} du thread et retiré en finally — contribution = 0 dès
     * que le processus {@code gs} a quitté.
     */
    private final Set<RssSampler> activeSamplers = ConcurrentHashMap.newKeySet();

    /** /proc/&lt;pid&gt;/status est lisible uniquement sur Linux. Sur Windows (dev) on saute la mesure. */
    private static final boolean PROC_AVAILABLE = Files.isDirectory(Path.of("/proc"));

    /**
     * Permissions des répertoires de travail gs, qui contiennent des documents
     * d'usagers : propriétaire seul ({@code rwx------}) dans le répertoire temporaire
     * partagé. Java les crée déjà ainsi sous POSIX ; les poser explicitement en fait
     * une exigence vérifiable (règle Sonar S5443) plutôt qu'un détail
     * d'implémentation du JDK. Aucun attribut hors POSIX (poste de dev Windows, où le
     * répertoire temporaire est propre à l'utilisateur).
     */
    private static final FileAttribute<?>[] OWNER_ONLY =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------"))}
                    : new FileAttribute<?>[0];

    public GhostscriptService(GhostscriptProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.available = probeAvailability(properties.binaryName());
        this.scheduler = available
                ? Schedulers.newBoundedElastic(properties.workers(), properties.queueSize(), "gs-worker")
                : null;

        Duration[] gsDurationSlos = {
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                properties.timeout()
        };
        this.durationSuccess = Timer.builder("televerse.gs.duration")
                .description("Durée d'exécution de Ghostscript par appel.")
                .tag("status", "success")
                .serviceLevelObjectives(gsDurationSlos)
                .register(meterRegistry);
        this.durationFailure = Timer.builder("televerse.gs.duration")
                .tag("status", "failure")
                .serviceLevelObjectives(gsDurationSlos)
                .register(meterRegistry);
        this.durationTimeout = Timer.builder("televerse.gs.duration")
                .tag("status", "timeout")
                .serviceLevelObjectives(gsDurationSlos)
                .register(meterRegistry);
        this.durationSkipped = Timer.builder("televerse.gs.duration")
                .tag("status", "skipped")
                .serviceLevelObjectives(gsDurationSlos)
                .register(meterRegistry);
        this.inputBytes = DistributionSummary.builder("televerse.gs.input.bytes")
                .description("Taille du PDF en entrée de Ghostscript (octets).")
                .baseUnit("bytes")
                .serviceLevelObjectives(5_000_000, 25_000_000, 100_000_000)
                .register(meterRegistry);
        this.outputBytes = DistributionSummary.builder("televerse.gs.output.bytes")
                .description("Taille du PDF en sortie de Ghostscript (octets, après comparaison de bénéfice).")
                .baseUnit("bytes")
                .serviceLevelObjectives(5_000_000, 25_000_000, 100_000_000)
                .register(meterRegistry);

        // NB : on ancre toutes les gauges sur `this` (singleton Spring) plutôt que
        // sur les compteurs directement. Micrometer garde l'argument en WeakReference ;
        // ancrer sur le service garantit que le holder ne peut pas être GC'd tant que
        // le contexte vit, et évite des cas où la weak-ref vers un champ AtomicInteger
        // se vide alors que le champ reste atteignable transitivement.
        Gauge.builder("televerse.gs.concurrent", this, gs -> gs.running.doubleValue())
                .description("Processus Ghostscript en cours d'exécution.")
                .register(meterRegistry);
        Gauge.builder("televerse.gs.queue.depth", this, GhostscriptService::queueDepth)
                .description("Tâches en attente sur le scheduler borné Ghostscript.")
                .register(meterRegistry);
        Gauge.builder("televerse.gs.peak.rss.bytes", this, gs -> gs.peakRssMaxBytes.doubleValue())
                .description("Peak RSS max observé sur un processus gs depuis le démarrage du pod "
                        + "(lu via /proc/<pid>/status:VmHWM).")
                .baseUnit("bytes")
                .register(meterRegistry);
        Gauge.builder("televerse.gs.current.rss.bytes", this, GhostscriptService::currentRssSumBytes)
                .description("Somme du RSS courant (VmRSS) des processus gs actifs à l'instant du scrape. "
                        + "Reflète la pression mémoire réelle ; à comparer à workers × peak.rss.")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    /** Somme des VmRSS des samplers actifs (octets). Évaluée à chaque scrape Prometheus. */
    private double currentRssSumBytes() {
        long sumKb = 0;
        for (RssSampler s : activeSamplers) sumKb += s.currentKb();
        return sumKb * 1024.0;
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) scheduler.dispose();
    }

    public boolean isAvailable() {
        return available;
    }

    /** queueDepth = inFlight - running (tâches admises mais pas encore prises par un worker). */
    private double queueDepth() {
        return Math.max(0, inFlight.get() - running.get());
    }

    /**
     * Recompresse un PDF si bénéfique. Retourne :
     * <ul>
     *   <li>les bytes recompressés, si Ghostscript a réussi à produire un PDF plus petit ;</li>
     *   <li>les bytes d'origine, dans tous les autres cas (binaire absent, taille sous le seuil,
     *       échec, timeout, sortie plus volumineuse).</li>
     * </ul>
     *
     * <p>Cette méthode est non-bloquante côté Reactor : l'appel à {@code gs} est exécuté sur
     * le scheduler borné dédié.</p>
     */
    public Mono<byte[]> recompressPdfIfNeeded(byte[] pdfBytes) {
        if (!available) {
            durationSkipped.record(0, TimeUnit.NANOSECONDS);
            return Mono.just(pdfBytes);
        }
        if (pdfBytes.length < properties.sizeThreshold().toBytes()) {
            durationSkipped.record(0, TimeUnit.NANOSECONDS);
            log.debug("PDF recompression skipped reason=under-threshold bytes={}", pdfBytes.length);
            return Mono.just(pdfBytes);
        }

        // defer : compteur et chrono armés à la SOUSCRIPTION (une mesure par tentative),
        // pas à l'assemblage — un Mono construit mais jamais souscrit faussait queue.depth.
        return Mono.defer(() -> {
            inFlight.incrementAndGet();
            long startNanos = System.nanoTime();
            return Mono.fromCallable(() -> uninterrupted(() -> recompressTimed(pdfBytes)))
                    .subscribeOn(scheduler)
                    .timeout(properties.timeout())
                    .onErrorResume(err -> {
                        recordFailure(err, startNanos);
                        log.warn("PDF recompression failed exception={} error={}",
                                err.getClass().getSimpleName(), err.getMessage());
                        return Mono.just(pdfBytes);
                    })
                    // Vide seulement si le worker a été interrompu hors annulation
                    // (arrêt du pod) : l'original reste la réponse sûre.
                    .defaultIfEmpty(pdfBytes)
                    .doFinally(signal -> inFlight.decrementAndGet());
        });
    }

    /** Travail gs susceptible d'être interrompu. */
    @FunctionalInterface
    private interface GsWork<T> {
        T run() throws IOException, InterruptedException, TimeoutException;
    }

    /**
     * Exécute {@code work} et convertit une interruption en résultat {@code null}
     * (donc un {@code Mono} vide), après avoir tué gs (cf. {@link #runGs}).
     *
     * <p>Seule source d'interruption ici : l'annulation Reactor — timeout de la chaîne
     * ou client parti. L'aval ne lit alors plus rien, et une exception levée à ce
     * moment finissait en {@code onErrorDropped} : WARN et stack trace à chaque
     * timeout gs, pour une issue normale. Une complétion vide après annulation, elle,
     * est ignorée sans bruit. Le drapeau d'interruption est reposé.</p>
     */
    private static <T> T uninterrupted(GsWork<T> work) throws IOException, TimeoutException {
        try {
            return work.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Ghostscript run cancelled, process killed");
            return null;
        }
    }

    /** Durée réelle d'un échec, classée timeout (gs tué ou chaîne expirée) ou failure. */
    private void recordFailure(Throwable err, long startNanos) {
        boolean isTimeout = err instanceof TimeoutException;
        (isTimeout ? durationTimeout : durationFailure)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /** Wrapper qui mesure la durée de l'appel {@code gs} et alimente les histogrammes. */
    private byte[] recompressTimed(byte[] pdfBytes) throws IOException, InterruptedException, TimeoutException {
        running.incrementAndGet();
        long startNanos = System.nanoTime();
        try {
            byte[] result = recompress(pdfBytes);
            long elapsed = System.nanoTime() - startNanos;
            durationSuccess.record(elapsed, TimeUnit.NANOSECONDS);
            inputBytes.record(pdfBytes.length);
            outputBytes.record(result.length);
            return result;
        } finally {
            running.decrementAndGet();
        }
    }

    private byte[] recompress(byte[] pdfBytes) throws IOException, InterruptedException, TimeoutException {
        // Un répertoire de travail par appel : entrée, sortie, log et TMPDIR de gs
        // (cf. runGs). Préfixe « televerse-gs- » suivi par TmpFileMonitor.
        Path work = Files.createTempDirectory("televerse-gs-", OWNER_ONLY);
        try {
            Path input = work.resolve("in.pdf");
            Path output = work.resolve("out.pdf");
            Files.write(input, pdfBytes);

            GsRun run = runGs(pdfwriteArgs("-sOutputFile=" + output.toAbsolutePath(), input), work);

            if (run.exitCode() != 0) {
                log.warn("Ghostscript exited non-zero exitCode={} durationMs={} peakRssKb={} output={}",
                        run.exitCode(), run.durationMs(), run.peakRssKb(), run.captured());
                return pdfBytes;
            }

            byte[] recompressed = Files.readAllBytes(output);
            if (recompressed.length >= pdfBytes.length) {
                log.debug("PDF recompression not beneficial inputBytes={} outputBytes={} durationMs={} peakRssKb={}",
                        pdfBytes.length, recompressed.length, run.durationMs(), run.peakRssKb());
                return pdfBytes;
            }

            log.debug("PDF recompressed inputBytes={} outputBytes={} ratio={} durationMs={} peakRssKb={}",
                    pdfBytes.length, recompressed.length,
                    String.format("%.2f", (double) pdfBytes.length / recompressed.length),
                    run.durationMs(), run.peakRssKb());
            return recompressed;
        } finally {
            deleteDirQuietly(work);
        }
    }

    /**
     * Recompresse ET découpe un PDF multi-pages en pages individuelles, en une seule passe
     * Ghostscript. À utiliser à la place du couple {@code recompressPdfIfNeeded} +
     * {@code PdfService.splitPdfToPages} pour les PDF multi-pages.
     *
     * <p><b>Pourquoi</b> : le {@code Splitter} PDFBox corrompt l'extraction page-à-page —
     * l'encodage des polices sous-ensembles est cassé (lettres accentuées → mauvais glyphes,
     * é→Ø) et les flux de colorimétrie ICC sont détachés (le PDF ne s'affiche plus dans les
     * lecteurs stricts). gs réécrit au contraire des pages autonomes fidèles. Voir la mémoire
     * {@code pdfbox-splitter-corrupts-subset-fonts}.</p>
     *
     * <p>Émet une {@link Mono#error(Throwable) erreur} si gs est indisponible, échoue ou
     * dépasse le timeout : l'appelant retombe alors sur le split PDFBox (dégradé mais
     * fonctionnel). Non-bloquant : exécuté sur le scheduler borné dédié.</p>
     *
     * @param pdfBytes      le PDF source (déjà validé)
     * @param expectedPages nombre de pages attendu (pré-calculé par l'appelant) ; sert à
     *                      valider que gs a bien produit toutes les pages
     */
    public Mono<List<byte[]>> recompressAndSplitToPages(byte[] pdfBytes, int expectedPages) {
        if (!available) {
            return Mono.error(new IllegalStateException("Ghostscript indisponible"));
        }
        // defer : cf. recompressPdfIfNeeded.
        return Mono.defer(() -> {
            inFlight.incrementAndGet();
            long startNanos = System.nanoTime();
            return Mono.fromCallable(() -> uninterrupted(() -> recompressAndSplitTimed(pdfBytes, expectedPages)))
                    .subscribeOn(scheduler)
                    .timeout(properties.timeout())
                    // Vide seulement si le worker a été interrompu hors annulation (arrêt du
                    // pod) : l'appelant retombe sur le split PDFBox, comme pour tout échec.
                    .switchIfEmpty(Mono.error(() -> new IOException("Ghostscript interrompu")))
                    .doOnError(err -> {
                        recordFailure(err, startNanos);
                        log.warn("PDF recompress+split failed exception={} error={}",
                                err.getClass().getSimpleName(), err.getMessage());
                    })
                    .doFinally(signal -> inFlight.decrementAndGet());
        });
    }

    /** Wrapper métrique du split (miroir de {@link #recompressTimed(byte[])}). */
    private List<byte[]> recompressAndSplitTimed(byte[] pdfBytes, int expectedPages)
            throws IOException, InterruptedException, TimeoutException {
        running.incrementAndGet();
        long startNanos = System.nanoTime();
        try {
            List<byte[]> pages = recompressAndSplit(pdfBytes, expectedPages);
            durationSuccess.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            inputBytes.record(pdfBytes.length);
            outputBytes.record(pages.stream().mapToLong(p -> p.length).sum());
            return pages;
        } finally {
            running.decrementAndGet();
        }
    }

    private List<byte[]> recompressAndSplit(byte[] pdfBytes, int expectedPages)
            throws IOException, InterruptedException, TimeoutException {
        // Répertoire de travail unique (entrée, pages, log, TMPDIR) — cf. recompress.
        Path outDir = Files.createTempDirectory("televerse-gs-split-", OWNER_ONLY);
        try {
            Path input = outDir.resolve("in.pdf");
            Files.write(input, pdfBytes);

            // gs substitue %d par le numéro de page (1-based) dans -sOutputFile.
            String pattern = outDir.resolve("page_%d.pdf").toAbsolutePath().toString();
            GsRun run = runGs(pdfwriteArgs("-sOutputFile=" + pattern, input), outDir);

            // gs peut sortir en code non-zéro à cause d'annotations de lien inter-pages
            // devenues invalides à l'extraction page-à-page (« pdfmark destination page N
            // beyond last page 1 ») : sans conséquence sur le CONTENU des pages. On valide
            // donc par la présence des N fichiers plutôt que par le seul code de sortie.
            var pages = new ArrayList<byte[]>(expectedPages);
            for (int i = 1; i <= expectedPages; i++) {
                Path page = outDir.resolve("page_" + i + ".pdf");
                if (!Files.exists(page) || Files.size(page) == 0) {
                    throw new IOException(("split gs incomplet: %d/%d pages produites "
                            + "(exitCode=%d output=%s)").formatted(
                            i - 1, expectedPages, run.exitCode(), run.captured()));
                }
                pages.add(Files.readAllBytes(page));
            }

            log.debug("PDF recompressed+split inputBytes={} pages={} outputBytes={} durationMs={} peakRssKb={} exitCode={}",
                    pdfBytes.length, pages.size(), pages.stream().mapToLong(p -> p.length).sum(),
                    run.durationMs(), run.peakRssKb(), run.exitCode());
            return pages;
        } finally {
            deleteDirQuietly(outDir);
        }
    }

    /**
     * Argv gs commun à la recompression et au split (mêmes réglages qualité/couleur),
     * paramétré par la cible de sortie et le fichier d'entrée. Les commentaires détaillent
     * chaque garde-fou critique.
     */
    private List<String> pdfwriteArgs(String outputFileArg, Path input) {
        return List.of(
                properties.binaryName(),
                // Sandbox explicite : -dSAFER désactive l'accès fichier/pipe par PostScript
                // (ex. %pipe%, -sOutputFile vers un chemin arbitraire). Défaut depuis GS 9.50,
                // mais on le force pour ne pas dépendre de la version packagée dans l'image de
                // base — l'entrée est un PDF non fiable, uploadé par un usager non authentifié.
                "-dSAFER",
                "-sDEVICE=pdfwrite",
                "-dPDFSETTINGS=" + properties.pdfSettings(),
                // Préserve les groupes de transparence. Les presets /screen, /ebook et /printer
                // convertissent l'espace colorimétrique par défaut (ColorConversionStrategy=/sRGB) ;
                // sur un PDF à transparence, cette conversion l'aplatit et fait DISPARAÎTRE le
                // contenu semi-transparent. /LeaveColorUnchanged neutralise ce bug. Correction
                // obligatoire, pas un réglage de compression.
                "-dColorConversionStrategy=/LeaveColorUnchanged",
                // Relève le plafond de sous-échantillonnage : /ebook écrase les images à 150 DPI
                // (moitié du chemin image→PDF, cf. app.image.dpi=300). On aligne sur la même cible.
                "-dColorImageResolution=" + properties.imageResolutionDpi(),
                "-dGrayImageResolution=" + properties.imageResolutionDpi(),
                // Seuil de sous-échantillonnage = 1.0 (défaut /ebook = 1.5, ne touche pas la plage
                // 300-450 DPI des scans) : même plafond dur que le chemin image.
                "-dColorImageDownsampleThreshold=1.0",
                "-dGrayImageDownsampleThreshold=1.0",
                "-dNOPAUSE", "-dQUIET", "-dBATCH",
                outputFileArg,
                input.toAbsolutePath().toString());
    }

    /** Résultat d'une exécution gs : code de sortie, sortie combinée, durée, peak RSS. */
    private record GsRun(int exitCode, String captured, long durationMs, long peakRssKb) {}

    /** Plafond de la sortie gs conservée pour les logs : un PDF hostile peut en produire des Mo. */
    private static final int MAX_CAPTURED_BYTES = 4096;

    /**
     * Lance {@code gs} et attend sa fin, <b>au plus {@code app.ghostscript.timeout}</b> :
     * au-delà, le process est tué (descendants compris) et une {@link TimeoutException}
     * remonte.
     *
     * <p>La sortie combinée stdout+stderr part dans un fichier du répertoire de travail,
     * pas dans un pipe. Le drainage d'un pipe ({@code readAllBytes()}) est une lecture
     * bloquante qu'aucune interruption ne débloque : le timeout Reactor en amont avait
     * beau se déclencher, le worker restait pendu jusqu'à ce que gs termine de lui-même,
     * et le {@code destroyForcibly()} du {@code finally} n'arrivait qu'après. Un PDF lourd
     * ou hostile gardait ainsi un worker, sa RAM et son /tmp pendant des minutes ; six
     * suffisaient à occuper les 12 workers, et tout PDF multi-pages du pod retombait
     * alors sur le split PDFBox, qui corrompt les accents. {@code waitFor(timeout)} est,
     * lui, interruptible : l'annulation Reactor (timeout de la chaîne, client parti) tue
     * gs aussi.</p>
     *
     * <p>{@code TMPDIR} pointe sur le répertoire de travail de l'appel : les fichiers
     * intermédiaires de pdfwrite y restent, et partent avec lui même après un SIGKILL.</p>
     *
     * @param workDir répertoire de travail de l'appel (supprimé par l'appelant)
     */
    private GsRun runGs(List<String> command, Path workDir)
            throws IOException, InterruptedException, TimeoutException {
        Process process = null;
        RssSampler sampler = null;
        Path logFile = workDir.resolve("gs.log");
        try {
            long startNanos = System.nanoTime();
            var builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            builder.environment().put("TMPDIR", workDir.toAbsolutePath().toString());
            process = builder.start();
            if (PROC_AVAILABLE) {
                sampler = new RssSampler(process.pid());
                activeSamplers.add(sampler);
                sampler.start();
            }

            if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Ghostscript killed after " + properties.timeout()
                        + " output=" + readCaptured(logFile));
            }
            int exitCode = process.exitValue();
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            long peakRssKb = 0;
            if (sampler != null) {
                peakRssKb = sampler.stopAndGet();
                activeSamplers.remove(sampler);
                sampler = null;
            }
            if (peakRssKb > 0) {
                peakRssMaxBytes.accumulateAndGet(peakRssKb * 1024L, Math::max);
            }
            return new GsRun(exitCode, readCaptured(logFile), durationMs, peakRssKb);
        } finally {
            if (sampler != null) {
                activeSamplers.remove(sampler);
                sampler.stopAndGet();
            }
            if (process != null && process.isAlive()) {
                kill(process);
            }
        }
    }

    /**
     * Tue gs et ses éventuels descendants, en rendant la main même sur un thread
     * interrompu (annulation Reactor) : l'attente de fin est bornée et le drapeau
     * d'interruption reposé ensuite.
     */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Début de la sortie gs, borné à {@value #MAX_CAPTURED_BYTES} octets. */
    private static String readCaptured(Path logFile) {
        try (var in = Files.newInputStream(logFile)) {
            return new String(in.readNBytes(MAX_CAPTURED_BYTES)).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Échantillonne {@code /proc/<pid>/status} pendant la durée de vie d'un sous-processus
     * {@code gs}. Lit deux champs à chaque tick :
     * <ul>
     *   <li>{@code VmHWM} — peak RSS maintenu monotonement par le kernel, alimente la
     *       gauge {@code televerse.gs.peak.rss.bytes}.</li>
     *   <li>{@code VmRSS} — RSS courant, alimente la gauge {@code televerse.gs.current.rss.bytes}
     *       (sommée sur tous les samplers actifs).</li>
     * </ul>
     * Une fois le sampler arrêté, {@link #currentKb()} retourne 0 — le processus a quitté,
     * sa contribution mémoire aussi.
     */
    private static final class RssSampler implements Runnable {
        private static final long SAMPLE_INTERVAL_MS = 100;
        private final long pid;
        private final Thread thread;
        private volatile boolean running = true;
        private volatile long peakKb = 0;
        private volatile long currentKb = 0;

        RssSampler(long pid) {
            this.pid = pid;
            this.thread = new Thread(this, "gs-rss-sampler-" + pid);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        /** Idempotent : appels suivants retournent la valeur déjà capturée. */
        long stopAndGet() {
            running = false;
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            currentKb = 0;
            return peakKb;
        }

        long currentKb() {
            return currentKb;
        }

        @Override
        public void run() {
            Path statusPath = Path.of("/proc", String.valueOf(pid), "status");
            while (running) {
                sample(statusPath);
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            sample(statusPath);
        }

        /** Une lecture de status, mise à jour de peakKb (VmHWM) et currentKb (VmRSS). */
        private void sample(Path statusPath) {
            try {
                for (String line : Files.readAllLines(statusPath)) {
                    if (line.startsWith("VmHWM:")) {
                        long v = parseKb(line);
                        if (v > peakKb) peakKb = v;
                    } else if (line.startsWith("VmRSS:")) {
                        currentKb = parseKb(line);
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // /proc/<pid>/status disparaît à la sortie du process — comportement attendu.
            }
        }

        /** Format kernel : "VmHWM:\t   123456 kB" → 123456. */
        private static long parseKb(String line) {
            String[] tokens = line.split("\\s+");
            return Long.parseLong(tokens[tokens.length - 2]);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Temp file delete failed path={} error={}", path, e.getMessage());
        }
    }

    /** Supprime un répertoire temporaire de split et tout son contenu (pages gs). */
    private static void deleteDirQuietly(Path dir) {
        if (dir == null) return;
        try (var entries = Files.list(dir)) {
            entries.forEach(GhostscriptService::deleteQuietly);
        } catch (IOException e) {
            log.warn("Temp dir listing failed path={} error={}", dir, e.getMessage());
        }
        deleteQuietly(dir);
    }

    private static boolean probeAvailability(String binaryName) {
        try {
            var process = new ProcessBuilder(binaryName, "--version")
                    .redirectErrorStream(true)
                    .start();
            String version;
            try (var in = process.getInputStream()) {
                version = new String(in.readAllBytes()).strip();
            }
            boolean ok = process.waitFor(5, TimeUnit.SECONDS);
            if (!ok) {
                process.destroyForcibly();
                log.warn("Ghostscript probe timeout binary={}", binaryName);
                return false;
            }
            if (process.exitValue() != 0) {
                log.warn("Ghostscript probe failed binary={} exitCode={}",
                        binaryName, process.exitValue());
                return false;
            }
            log.info("Ghostscript ready binary={} version={}", binaryName, version);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Ghostscript unavailable binary={} error={}",
                    binaryName, e.getMessage());
            return false;
        }
    }
}

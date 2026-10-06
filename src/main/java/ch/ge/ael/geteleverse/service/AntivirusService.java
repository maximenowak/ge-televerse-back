package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.AntivirusProperties;
import ch.ge.ael.geteleverse.config.AntivirusProperties.FailMode;
import ch.ge.ael.geteleverse.controller.AntivirusUnavailableException;
import ch.ge.ael.geteleverse.controller.InfectedFileException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Scan antivirus des uploads via le démon clamd (protocole INSTREAM TCP natif).
 *
 * <p>Cycle de vie :</p>
 * <ul>
 *   <li><b>Démarrage</b> : un PING borné par {@code app.antivirus.timeout} initialise
 *       l'état de disponibilité. La lib {@code clamav-client} n'expose pas de
 *       connect-timeout dans ses constructeurs : un firewall qui blackhole les SYN
 *       (pas de RST) figerait le thread jusqu'au timeout TCP système (~75 s). Le
 *       probe est donc enveloppé dans un {@link CompletableFuture} borné.</li>
 *   <li><b>Re-probe périodique</b> : un {@link Scheduled} relance le PING toutes les
 *       {@code app.antivirus.probe-interval} et met à jour la gauge
 *       {@code televerse.av.available} (0/1). Permet d'alerter quand clamd tombe
 *       sans attendre le prochain scan. <b>En cas d'échec du probe, le {@link ClamavClient}
 *       est recréé</b> via le factory : la lib gèle l'IP résolue dans un
 *       {@link java.net.InetSocketAddress} à la construction et ne ré-interroge
 *       jamais le DNS, donc un pod clamd qui revient sur une nouvelle endpoint
 *       (ou un conntrack obsolète côté node) resterait sinon coincé. Recréer le
 *       client force une nouvelle résolution DNS au prochain probe.</li>
 *   <li><b>Scan par upload</b> : {@link #scan(byte[], String)} est appelé depuis
 *       {@code UploadController} juste après {@code bufferFilePart}, avant la
 *       branche image/PDF (un seul appel par upload, indépendant du type).</li>
 * </ul>
 *
 * <p>Politique d'échec (fail-mode) : un scan en erreur protocole (clamd
 * injoignable, timeout, exception de la lib) est traité selon
 * {@code app.antivirus.fail-mode}. {@code CLOSED} (défaut prod) lève
 * {@link AntivirusUnavailableException} → 503. {@code OPEN} laisse passer
 * l'upload avec un WARN. <b>Une infection avérée reste toujours bloquée</b>
 * indépendamment du fail-mode.</p>
 */
@Service
@Slf4j(topic = "antivirus")
public class AntivirusService {

    private final AntivirusProperties properties;
    private final Supplier<ClamavClient> clientFactory;
    private volatile ClamavClient client;
    private final AtomicInteger availableFlag = new AtomicInteger(0);

    private final Timer scanClean;
    private final Timer scanInfected;
    private final Timer scanError;
    private final DistributionSummary scanBytes;
    private final Counter infections;

    @Autowired
    public AntivirusService(AntivirusProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry,
                properties.enabled()
                        ? () -> new ClamavClient(properties.host(), properties.port())
                        : () -> null);
    }

    /**
     * Constructeur de test : permet d'injecter un {@link ClamavClient} mocké.
     * Le factory renvoie toujours la même instance, donc le rebuild déclenché
     * par un probe en échec reste no-op côté tests (la même mock est réutilisée).
     * Exécute le même chemin de probe initial que la version Spring — les tests
     * stubent {@code client.ping()} avant l'appel pour contrôler le verdict.
     */
    AntivirusService(AntivirusProperties properties, MeterRegistry meterRegistry, ClamavClient client) {
        this(properties, meterRegistry, () -> client);
    }

    private AntivirusService(AntivirusProperties properties, MeterRegistry meterRegistry,
                              Supplier<ClamavClient> clientFactory) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.client = clientFactory.get();

        this.scanClean = Timer.builder("televerse.av.scan.duration")
                .description("Durée des scans antivirus par appel.")
                .tag("result", "clean")
                .register(meterRegistry);
        this.scanInfected = Timer.builder("televerse.av.scan.duration")
                .tag("result", "infected")
                .register(meterRegistry);
        this.scanError = Timer.builder("televerse.av.scan.duration")
                .tag("result", "error")
                .register(meterRegistry);
        this.scanBytes = DistributionSummary.builder("televerse.av.scan.bytes")
                .description("Taille des flux soumis au scan antivirus (octets).")
                .baseUnit("bytes")
                .register(meterRegistry);
        this.infections = Counter.builder("televerse.av.infections.total")
                .description("Total des infections détectées (toutes signatures confondues).")
                .register(meterRegistry);
        // Ancrée sur `this` (singleton) plutôt que sur le AtomicInteger directement :
        // Micrometer garde l'argument en WeakReference, ancrer sur le service évite
        // que la weak-ref ne se vide pendant que le champ reste atteignable
        // transitivement (même précaution que GhostscriptService).
        Gauge.builder("televerse.av.available", this, av -> av.availableFlag.doubleValue())
                .description("Disponibilité du démon clamd vue par ce pod (0 = down, 1 = up).")
                .register(meterRegistry);

        if (!properties.enabled()) {
            log.info("Antivirus disabled (app.antivirus.enabled=false) — uploads will proceed without AV scan");
            return;
        }
        boolean ready = ping();
        if (ready) {
            log.info("Antivirus ready host={} port={}", properties.host(), properties.port());
        }
    }

    /**
     * État renvoyé par le dernier PING (initial ou re-probe). Reflète la
     * gauge {@code televerse.av.available}.
     */
    public boolean isAvailable() {
        return availableFlag.get() == 1;
    }

    /**
     * Scanne un fichier déjà bufferisé en mémoire.
     *
     * @return {@code Mono.empty()} si propre ; {@link Mono#error} avec
     *         {@link InfectedFileException} si une signature est détectée ;
     *         {@link Mono#error} avec {@link AntivirusUnavailableException} si
     *         clamd est injoignable et {@code fail-mode=CLOSED} ;
     *         {@code Mono.empty()} (avec WARN) si {@code fail-mode=OPEN}.
     *         No-op {@code Mono.empty()} si le service est désactivé.
     */
    public Mono<Void> scan(byte[] bytes, String filename) {
        if (!properties.enabled()) {
            return Mono.empty();
        }
        long start = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("AV scan request filename={} bytes={} tail={}", filename, bytes.length, tailAscii(bytes, 80));
        }
        return Mono.fromCallable(() -> client.scan(new ByteArrayInputStream(bytes)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.scanTimeout())
                .doOnNext(result -> log.debug("AV scan response filename={} result={}", filename, result))
                .flatMap(result -> handleResult(result, bytes, filename, start))
                .onErrorResume(err -> handleError(err, filename, start));
    }

    private Mono<Void> handleResult(ScanResult result, byte[] bytes, String filename, long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        if (result instanceof ScanResult.OK) {
            scanClean.record(elapsed, TimeUnit.NANOSECONDS);
            scanBytes.record(bytes.length);
            availableFlag.set(1);
            return Mono.empty();
        }
        if (result instanceof ScanResult.VirusFound vf) {
            scanInfected.record(elapsed, TimeUnit.NANOSECONDS);
            availableFlag.set(1);
            var signatures = flatten(vf.getFoundViruses().values());
            infections.increment();
            log.warn("Malware detected filename={} signatures={}", filename, signatures);
            return Mono.error(new InfectedFileException(filename, signatures));
        }
        // ScanResult est scellé (OK / VirusFound), mais on garde un fallback défensif
        // pour un éventuel ajout futur dans la lib qui ferait reach ce point.
        scanError.record(elapsed, TimeUnit.NANOSECONDS);
        return Mono.error(new IllegalStateException("Unexpected scan result: " + result));
    }

    private Mono<Void> handleError(Throwable err, String filename, long startNanos) {
        // Une infection avérée est portée par InfectedFileException : on la propage
        // telle quelle, indépendamment du fail-mode (un virus n'est pas une « panne »).
        if (err instanceof InfectedFileException) {
            return Mono.error(err);
        }
        scanError.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        availableFlag.set(0);
        log.error("Antivirus scan failed filename={} error={}", filename, err.getMessage());
        return properties.failMode() == FailMode.CLOSED
                ? Mono.error(new AntivirusUnavailableException(err))
                : Mono.empty();
    }

    /**
     * Re-probe périodique de la disponibilité de clamd. Maintient la gauge
     * {@code televerse.av.available} à jour entre deux uploads — sinon, un
     * clamd tombé n'est détecté qu'au premier upload qui sature derrière.
     * Quand {@code enabled=false}, la méthode existe mais ne fait rien
     * (gauge reste à 0).
     *
     * <p>Si le probe échoue, le {@link ClamavClient} est rebâti via le factory.
     * La lib {@code clamav-client} résout le DNS une seule fois — à la
     * construction de l'{@link java.net.InetSocketAddress} — puis fige l'IP
     * pour toute la vie de l'instance. Recréer le client est le seul moyen
     * de forcer une nouvelle résolution DNS et d'éviter qu'un conntrack
     * obsolète sur la node ne bloque toutes les connexions
     * suivantes après un redémarrage des pods clamd.</p>
     */
    @Scheduled(fixedDelayString = "${app.antivirus.probe-interval:PT30S}", initialDelayString = "${app.antivirus.probe-interval:PT30S}")
    void reprobeAvailability() {
        if (!properties.enabled()) return;
        if (!ping()) {
            var fresh = clientFactory.get();
            if (fresh != null) {
                this.client = fresh;
                log.info("Antivirus client rebuilt — next probe will retry with a fresh DNS resolution");
            }
        }
    }

    /**
     * PING borné par {@code app.antivirus.timeout}. Met à jour {@link #availableFlag}
     * et logue le verdict. Retourne {@code true} si clamd a répondu, {@code false}
     * sinon — utilisé par le probe de démarrage pour conditionner le log
     * « Antivirus ready ».
     */
    private boolean ping() {
        var host = properties.host();
        var port = properties.port();
        var timeout = properties.timeout();
        var future = CompletableFuture.runAsync(client::ping);
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            availableFlag.set(1);
            return true;
        } catch (TimeoutException e) {
            future.cancel(true);
            availableFlag.set(0);
            log.warn("Antivirus probe timeout host={} port={} timeout={}", host, port, timeout);
            return false;
        } catch (ExecutionException e) {
            availableFlag.set(0);
            log.warn("Antivirus unreachable host={} port={} error={}", host, port, rootMessage(e));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            availableFlag.set(0);
            log.warn("Antivirus probe interrupted host={} port={}", host, port);
            return false;
        }
    }

    /**
     * Aplatit la {@code Map<streamName, Collection<signature>>} retournée par
     * clamd en une simple {@code Set<signature>}. INSTREAM a quasi toujours
     * une seule entrée (clé « stream »), mais on traite le cas général pour
     * robustesse.
     */
    private static Collection<String> flatten(Collection<? extends Collection<String>> values) {
        Set<String> out = new LinkedHashSet<>();
        for (Collection<String> v : values) out.addAll(v);
        return out;
    }

    /**
     * Représentation ASCII des {@code n} derniers octets (non imprimables remplacés
     * par {@code .}). Permet de vérifier au log la présence d'une signature de test
     * (EICAR notamment) en queue de fichier — avant qu'elle ne soit interceptée par
     * un retraitement front (canvas → ré-encodage JPEG qui tronque l'EOI).
     */
    private static String tailAscii(byte[] bytes, int n) {
        int start = Math.max(0, bytes.length - n);
        var sb = new StringBuilder(bytes.length - start);
        for (int i = start; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            sb.append(b >= 32 && b < 127 ? (char) b : '.');
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        var cause = t.getCause() != null ? t.getCause() : t;
        var msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}

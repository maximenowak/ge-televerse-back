package ch.ge.ael.geteleverse.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Surveille périodiquement les fichiers temporaires Ghostscript dans le répertoire
 * système {@code /tmp} (ou son équivalent Windows). Expose deux gauges Micrometer :
 *
 * <ul>
 *   <li>{@code televerse.tmp.bytes.used} — taille cumulée des fichiers {@code televerse-gs-*}.</li>
 *   <li>{@code televerse.tmp.file.count} — nombre de fichiers {@code televerse-gs-*} actifs.</li>
 * </ul>
 *
 * <p>Une croissance monotone signale une fuite de fichiers orphelins (pod tué brutalement
 * entre l'écriture et le {@code finally}). À surveiller en prod via Prometheus/Grafana :
 * un dépassement durable au-delà de quelques centaines de Mo doit déclencher une alerte.</p>
 */
@Component
@Slf4j
public class TmpFileMonitor {

    private static final String FILE_PREFIX = "televerse-gs-";
    private static final long SCAN_INTERVAL_MS = 30_000;

    private final Path tmpDir;
    private final AtomicLong bytesUsed = new AtomicLong();
    private final AtomicLong fileCount = new AtomicLong();
    private final MeterRegistry meterRegistry;

    public TmpFileMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
    }

    /**
     * Lecture instantanée de la taille cumulée des fichiers tmp Ghostscript
     * (`televerse-gs-*`). Mise à jour toutes les 30 s par {@link #scan()}.
     * Utilisée par {@link TmpFileHealthIndicator} pour faire passer
     * {@code /actuator/health} en DOWN si la fuite franchit le seuil.
     */
    public long bytesUsed() {
        return bytesUsed.get();
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("televerse.tmp.bytes.used", bytesUsed, AtomicLong::doubleValue)
                .description("Taille cumulée des fichiers temporaires Ghostscript orphelins ou en cours.")
                .baseUnit("bytes")
                .register(meterRegistry);
        Gauge.builder("televerse.tmp.file.count", fileCount, AtomicLong::doubleValue)
                .description("Nombre de fichiers temporaires Ghostscript actifs ou orphelins.")
                .register(meterRegistry);
        // Initial scan synchrone pour que la première scrape Prometheus voie une vraie valeur.
        scan();
    }

    @Scheduled(fixedRate = SCAN_INTERVAL_MS)
    void scan() {
        long bytes = 0;
        long count = 0;
        try (Stream<Path> stream = Files.list(tmpDir)) {
            for (Path entry : (Iterable<Path>) stream::iterator) {
                if (!entry.getFileName().toString().startsWith(FILE_PREFIX)) continue;
                count++;
                try {
                    bytes += Files.size(entry);
                } catch (IOException ignored) {
                    // Fichier supprimé entre-temps : ignoré
                }
            }
        } catch (IOException e) {
            log.debug("Tmp dir scan failed path={} error={}", tmpDir, e.getMessage());
            return;
        }
        bytesUsed.set(bytes);
        fileCount.set(count);
    }
}

package ch.ge.ael.geteleverse.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Canari sur la fuite de fichiers temporaires Ghostscript. Passe
 * {@code /actuator/health} en DOWN dès que la somme observée par
 * {@link TmpFileMonitor} dépasse {@code app.tmp.health-threshold} (5 Go par
 * défaut). Le but : déclencher l'alerte avant la saturation effective du
 * disque, alors qu'il reste assez de marge pour drainer la session en cours.
 *
 * <p>Le seuil par défaut est sciemment supérieur à la consommation nominale
 * (quelques centaines de Mo cumulés pendant un pic) — on ne veut un DOWN que
 * sur une vraie fuite (pod tué entre l'écriture {@code gs} et le {@code finally}
 * de nettoyage), pas sur une charge élevée transitoire.
 */
@Component
public class TmpFileHealthIndicator implements HealthIndicator {

    private final TmpFileMonitor monitor;
    private final long thresholdBytes;

    public TmpFileHealthIndicator(
            TmpFileMonitor monitor,
            @Value("${app.tmp.health-threshold:5GB}") DataSize threshold) {
        this.monitor = monitor;
        this.thresholdBytes = threshold.toBytes();
    }

    @Override
    public Health health() {
        long bytes = monitor.bytesUsed();
        var builder = bytes > thresholdBytes ? Health.down() : Health.up();
        return builder
                .withDetail("bytesUsed", bytes)
                .withDetail("thresholdBytes", thresholdBytes)
                .build();
    }
}

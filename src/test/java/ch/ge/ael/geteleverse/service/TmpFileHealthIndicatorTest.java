package ch.ge.ael.geteleverse.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Status;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmpFileHealthIndicatorTest {

    @Mock TmpFileMonitor monitor;

    private static final long THRESHOLD = 5L * 1024 * 1024 * 1024; // 5 GB

    @Test
    void up_whenBelowThreshold() {
        when(monitor.bytesUsed()).thenReturn(THRESHOLD - 1);
        var indicator = new TmpFileHealthIndicator(monitor, DataSize.ofBytes(THRESHOLD));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bytesUsed", THRESHOLD - 1);
        assertThat(health.getDetails()).containsEntry("thresholdBytes", THRESHOLD);
    }

    @Test
    void up_atExactThreshold() {
        // Seuil franchi STRICTEMENT (>) — l'égalité reste UP, pour qu'une
        // observation borderline ne déclenche pas l'alerte.
        when(monitor.bytesUsed()).thenReturn(THRESHOLD);
        var indicator = new TmpFileHealthIndicator(monitor, DataSize.ofBytes(THRESHOLD));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void down_whenAboveThreshold() {
        when(monitor.bytesUsed()).thenReturn(THRESHOLD + 1);
        var indicator = new TmpFileHealthIndicator(monitor, DataSize.ofBytes(THRESHOLD));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("bytesUsed", THRESHOLD + 1);
    }
}

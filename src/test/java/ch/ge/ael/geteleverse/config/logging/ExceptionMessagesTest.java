package ch.ge.ael.geteleverse.config.logging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ExceptionMessagesTest {

    /** Exemple représentatif d'une exception de dépassement de délai d'invocation Hazelcast. */
    private static final String HZ_TIMEOUT = """
            ClientInvocation{clientMessage = ClientMessage{connection=null, length=396, \
            operation=Map.Put, isRetryable=false, correlationId=780003, messageType=10100, \
            isEvent=false, isfragmented=false}, objectName = televerse-sessions, \
            target = partition 47, sentConnection = null} timed out because exception occurred \
            after client invocation timeout 120000 ms. Current time: 2020-01-01 00:02:00.044. \
            Start time: 2020-01-01 00:00:00.000. Total elapsed time: 120044 ms.""";

    @Test
    void condensesHazelcastInvocationTimeout() {
        var condensed = ExceptionMessages.condense(new RuntimeException(HZ_TIMEOUT));

        assertThat(condensed).isEqualTo("Map.Put on televerse-sessions timed out after 120044 ms");
    }

    @Test
    void condensesMapSizeTimeoutOnTheThumbnailCache() {
        String message = HZ_TIMEOUT
                .replace("operation=Map.Put", "operation=Map.Size")
                .replace("televerse-sessions", "televerse-thumbnails");

        assertThat(ExceptionMessages.condense(new RuntimeException(message)))
                .isEqualTo("Map.Size on televerse-thumbnails timed out after 120044 ms");
    }

    @Test
    void condensesTimeoutSpreadOverSeveralLines() {
        String message = HZ_TIMEOUT
                .replace(", objectName", ",\r\n  objectName")
                .replace(" timed out", "\n timed out");

        assertThat(ExceptionMessages.condense(new RuntimeException(message)))
                .isEqualTo("Map.Put on televerse-sessions timed out after 120044 ms");
    }

    @Test
    void doesNotCondenseAWrappedTimeoutAndKeepsItsPrefix() {
        String message = "java.util.concurrent.CompletionException: " + HZ_TIMEOUT;

        assertThat(ExceptionMessages.condense(new RuntimeException(message)))
                .startsWith("java.util.concurrent.CompletionException: ClientInvocation{")
                .endsWith("…");
    }

    @Test
    void staysLinearOnHostileInput() {
        // Sans l'ancre ^, find() relance le balayage à chaque « ClientInvocation{ » :
        // ~8 s sur cette entrée de 124 Ko, contre quelques ms ancré.
        String hostile = "ClientInvocation{operation=Map.Put, objectName = x, timed out ".repeat(2000);

        var condensed = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> ExceptionMessages.condense(new RuntimeException(hostile)));

        assertThat(condensed).hasSize(201).endsWith("…");
    }

    @Test
    void keepsShortMessagesVerbatim() {
        assertThat(ExceptionMessages.condense(new RuntimeException("S3 connection refused")))
                .isEqualTo("S3 connection refused");
    }

    @Test
    void flattensMultilineMessagesToASingleLine() {
        assertThat(ExceptionMessages.condense(new RuntimeException("échec\n  détail\tsuite")))
                .isEqualTo("échec détail suite");
    }

    @Test
    void truncatesOversizedMessages() {
        var condensed = ExceptionMessages.condense(new RuntimeException("x".repeat(500)));

        assertThat(condensed).hasSize(201).endsWith("…");
    }

    @Test
    void returnsNullWhenTheExceptionCarriesNoMessage() {
        assertThat(ExceptionMessages.condense(new NullPointerException())).isNull();
        assertThat(ExceptionMessages.condense(new RuntimeException("   "))).isNull();
        assertThat(ExceptionMessages.condense(null)).isNull();
    }
}

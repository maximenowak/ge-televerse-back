package ch.ge.ael.geteleverse.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Messages de référence capturés en recette sous charge extrême (29.07.2026,
 * ~4000 usagers simultanés) : c'est la forme exacte produite par le client
 * Hazelcast 5.4.0, stack de 18 frames comprise.
 */
class HazelcastClientNoiseFilterTest {

    private static final String INVOCATION_LOGGER = "com.hazelcast.client.impl.spi.ClientInvocationService";
    private static final String CONNECTION_LOGGER = "com.hazelcast.client.impl.connection.tcp.TcpClientConnection";

    private static final String DOUBLE_COMPLETE = """
            ge-televerse [hazelcast-cluster] [5.4.0] Future.complete(Object) on completed future. \
            Request: ClientMessage{connection=null, length=48, operation=Map.Size, isRetryable=true, \
            correlationId=767173, messageType=12a00, isEvent=false, isfragmented=false}, \
            current value: ExceptionalResult{cause=java.lang.InterruptedException}, \
            offered value: ExceptionalResult{cause=com.hazelcast.core.OperationTimeoutException: \
            ClientInvocation{clientMessage = ClientMessage{connection=null, length=48, operation=Map.Size, \
            isRetryable=true, correlationId=779610, messageType=12a00, isEvent=false, isfragmented=false}, \
            objectName = televerse-thumbnails, target = random, sentConnection = null} timed out because \
            exception occurred after client invocation timeout 120000 ms. Current time: 2026-07-29 08:01:43.397. \
            Start time: 2026-07-29 07:59:43.357. Total elapsed time: 120041 ms. }""";

    private static final String CONNECTION_CLOSED = """
            ge-televerse [hazelcast-cluster] [5.4.0] ClientConnection{alive=false, connectionId=10, \
            channel=NioChannel{/0.0.0.0:45975->/0.0.0.0:5701}, remoteAddress=null, \
            lastReadTime=2026-07-29 08:01:43.483, lastWriteTime=2026-07-29 08:01:43.482, \
            closedTime=2026-07-29 08:01:44.349} closed. Reason: Failed to authenticate connection""";

    private final HazelcastClientNoiseFilter filter = new HazelcastClientNoiseFilter();
    private Logger hazelcastLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        hazelcastLogger = (Logger) LoggerFactory.getLogger("hazelcast");
        appender = new ListAppender<>();
        appender.start();
        hazelcastLogger.addAppender(appender);
        hazelcastLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        hazelcastLogger.detachAppender(appender);
        hazelcastLogger.setLevel(null);
    }

    @Test
    void compactsDoubleCompleteToASingleStacklessLine() {
        var reply = decide(INVOCATION_LOGGER, Level.WARN, DOUBLE_COMPLETE, new Exception(DOUBLE_COMPLETE));

        assertThat(reply).isEqualTo(FilterReply.DENY);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .doesNotContain("\n")
                    .isEqualTo("double-complete ignoré (race interne au client) op=Map.Size "
                            + "objet=televerse-thumbnails initial=InterruptedException "
                            + "tardif=OperationTimeoutException");
        });
    }

    @Test
    void compactsConnectionClosedToASingleStacklessLine() {
        var reply = decide(CONNECTION_LOGGER, Level.WARN, CONNECTION_CLOSED, new TimeoutException());

        assertThat(reply).isEqualTo(FilterReply.DENY);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .doesNotContain("\n")
                    .startsWith("connexion cluster fermée id=10 pair=/")
                    .contains(":5701 raison=\"Failed to authenticate connection\" cause=TimeoutException");
        });
    }

    @Test
    void keepsTheOriginalLevelForErrors() {
        decide(CONNECTION_LOGGER, Level.ERROR, CONNECTION_CLOSED, new TimeoutException());

        assertThat(appender.list).singleElement()
                .extracting(ILoggingEvent::getLevel).isEqualTo(Level.ERROR);
    }

    @Test
    void compactsUnknownHazelcastMessagesGenerically() {
        var reply = decide(CONNECTION_LOGGER, Level.WARN,
                "ge-televerse [hazelcast-cluster] [5.4.0] Quelque chose d'inattendu",
                new IllegalStateException("détail\nsuite ignorée"));

        assertThat(reply).isEqualTo(FilterReply.DENY);
        assertThat(appender.list).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Quelque chose d'inattendu cause=IllegalStateException: détail");
    }

    @Test
    void leavesStacklessHazelcastLogsUntouched() {
        var reply = decide("com.hazelcast.client.impl.connection.ClientConnectionManager", Level.WARN,
                "ge-televerse [hazelcast-cluster] [5.4.0] Unable to get live cluster connection, retry in 1050 ms",
                null);

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void leavesNonHazelcastLoggersUntouched() {
        var reply = decide("ch.ge.ael.geteleverse.service.SessionService", Level.WARN,
                "Hazelcast injoignable", new IllegalStateException("boom"));

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
        assertThat(appender.list).isEmpty();
    }

    /**
     * Le TurboFilter est consulté avant le test de niveau : un événement que la
     * conf voulait taire ne doit pas ressurgir compacté sous « hazelcast ».
     */
    @Test
    void honoursTheConfiguredLevelOfTheSourceLogger() {
        Logger source = (Logger) LoggerFactory.getLogger(INVOCATION_LOGGER);
        source.setLevel(Level.ERROR);
        try {
            var reply = decide(INVOCATION_LOGGER, Level.WARN, DOUBLE_COMPLETE, new Exception());

            assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
            assertThat(appender.list).isEmpty();
        } finally {
            source.setLevel(null);
        }
    }

    private FilterReply decide(String loggerName, Level level, String message, Throwable t) {
        Logger source = (Logger) LoggerFactory.getLogger(loggerName);
        return filter.decide(null, source, level, message, null, t);
    }
}

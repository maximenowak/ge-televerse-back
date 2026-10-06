package ch.ge.ael.geteleverse.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que le filtre émet bien l'access-log au niveau INFO (et non DEBUG
 * comme historiquement) : c'est la ligne de sortie porteuse du statut HTTP qui
 * rendait un 404 forgé par un contrôleur invisible en prod.
 */
class HttpLoggingFilterTest {

    private final HttpLoggingFilter filter = new HttpLoggingFilter();
    private Logger httpLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        httpLogger = (Logger) LoggerFactory.getLogger("http");
        appender = new ListAppender<>();
        appender.start();
        httpLogger.addAppender(appender);
        httpLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        httpLogger.detachAppender(appender);
        httpLogger.setLevel(null);
    }

    @Test
    void logsEntryAndExitLinesWithStatus_atInfo() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sessions/tok/pages/page-001/thumbnail"));
        // Simule un contrôleur qui pose un 404 forgé (ResponseEntity.notFound())
        // puis complète — le cas qui ne laissait aucune trace auparavant.
        WebFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).startsWith("→ GET").contains("thumbnail"));
        assertThat(messages).anySatisfy(m -> assertThat(m).startsWith("← 404 GET").contains("thumbnail"));
        assertThat(appender.list).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    void masksCredentialHeaders_atDebug() {
        // Un LOGLEVEL_HTTP=DEBUG posé en incident ne doit pas verser de JWT ni de
        // cookie de session dans les logs : un JWT AEL.TELEVERSE suffit à consommer
        // le document à usage unique avant l'application hôte.
        httpLogger.setLevel(Level.DEBUG);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/x.pdf")
                .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.secret.sig")
                .header("Proxy-Authorization", "Basic c2VjcmV0")
                .header("Cookie", "SESSION=abc123")
                .header("Accept", "application/pdf"));
        WebFilterChain chain = ex -> {
            ex.getResponse().getHeaders().add("Set-Cookie", "SESSION=def456; HttpOnly");
            ex.getResponse().getHeaders().add("Content-Type", "application/pdf");
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(String.join("\n", messages))
                .doesNotContain("eyJhbGciOiJSUzI1NiJ9", "c2VjcmV0", "abc123", "def456");
        assertThat(messages)
                .anySatisfy(m -> assertThat(m).contains("Authorization: [masqué]"))
                .anySatisfy(m -> assertThat(m).contains("Proxy-Authorization: [masqué]"))
                .anySatisfy(m -> assertThat(m).contains("Cookie: [masqué]"))
                .anySatisfy(m -> assertThat(m).contains("Set-Cookie: [masqué]"))
                // Les autres en-têtes restent lisibles : c'est l'intérêt du DEBUG.
                .anySatisfy(m -> assertThat(m).contains("Accept: application/pdf"))
                .anySatisfy(m -> assertThat(m).contains("Content-Type: application/pdf"));
    }

    @Test
    void doesNotLog_whenHttpLevelAboveInfo() {
        // http=WARN (défaut prod actuel) : l'access-log reste silencieux, mais
        // c'est désormais un choix d'exploitation (LOGLEVEL_HTTP), pas un bug.
        httpLogger.setLevel(Level.WARN);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/health"));
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(appender.list).isEmpty();
    }
}

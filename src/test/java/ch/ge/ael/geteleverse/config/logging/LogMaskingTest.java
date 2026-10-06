package ch.ge.ael.geteleverse.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le motif de sortie de {@code logback-spring.xml} — celui de la production — réduit
 * tout UUID (token de session, documentId) à ses 8 premiers caractères, dans le
 * message comme dans la trace d'exception.
 */
class LogMaskingTest {

    private static final String TOKEN = "3f2a9c1e-7b4d-4e8a-9f01-2c3d4e5f6a7b";
    private static final String DOCUMENT_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d.pdf";

    /** Motif lu dans le fichier livré, pas recopié : le test suit toute modification. */
    private static String productionPattern() {
        try (var in = LogMaskingTest.class.getResourceAsStream("/logback-spring.xml")) {
            var xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var start = xml.indexOf("<pattern>") + "<pattern>".length();
            return xml.substring(start, xml.indexOf("</pattern>", start));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String render(String message, Throwable error) {
        var context = new LoggerContext();
        var layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(productionPattern());
        layout.start();
        var event = new LoggingEvent(LogMaskingTest.class.getName(), context.getLogger("test"),
                Level.WARN, message, error, null);
        return layout.doLayout(event);
    }

    @Test
    void theProductionConfigurationLoadsWithoutError() throws Exception {
        // Les tests Spring chargent logback-test.xml : sans ce test, une erreur dans
        // logback-spring.xml ne se verrait qu'au démarrage du pod.
        var context = new LoggerContext();
        var configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(LogMaskingTest.class.getResource("/logback-spring.xml"));

        assertThat(new StatusUtil(context).getHighestLevel(0)).isLessThan(Status.ERROR);
        context.stop();
    }

    @Test
    void tokensAndDocumentIdsAreCutToTheirPrefix() {
        var line = render("Session cleanup token=" + TOKEN + " key=" + TOKEN + "/page-001.pdf doc="
                + DOCUMENT_ID + " path=/api/sessions/" + TOKEN + "/finalize", null);

        assertThat(line)
                .doesNotContain(TOKEN)
                .doesNotContain("e5f6-4a7b-8c9d-0e1f2a3b4c5d")
                .contains("token=3f2a9c1e… ")
                .contains("key=3f2a9c1e…/page-001.pdf")
                .contains("doc=a1b2c3d4….pdf")
                .contains("path=/api/sessions/3f2a9c1e…/finalize");
    }

    @Test
    void theExceptionTraceIsMasked_andPrintedOnce() {
        var error = new IllegalStateException("Objet introuvable: " + TOKEN + "/page-001.pdf");

        var line = render("S3 fetch failed", error);

        assertThat(line).doesNotContain(TOKEN).contains("Objet introuvable: 3f2a9c1e…/page-001.pdf");
        assertThat(line.split("IllegalStateException", -1)).hasSize(2);
    }

    @Test
    void theRestOfTheLineIsUntouched() {
        var line = render("Originator busy originator=10.1.2.3 pageId=page-003 bytes=1048576", null);

        assertThat(line).contains("originator=10.1.2.3 pageId=page-003 bytes=1048576");
    }
}

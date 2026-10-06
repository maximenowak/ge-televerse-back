package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.GhostscriptProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cycle de vie du processus gs, vérifié avec un faux binaire (script shell ou .cmd) :
 * tourne sur toute CI, sans Ghostscript installé — contrairement à
 * {@code GhostscriptServiceTest}, dont la plupart des cas sont sautés en CI.
 *
 * <p>Régression visée : le timeout ne tuait pas gs. Le worker restait bloqué dans la
 * lecture du pipe de sortie jusqu'à ce que gs termine de lui-même, gardant sa place
 * dans le pool (quelques PDF lourds suffisaient à le saturer).</p>
 */
class GhostscriptProcessLifecycleTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** Sous target/ plutôt que /tmp, parfois monté noexec sur les runners. */
    private Path dir;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(Path.of("target", "fake-gs-" + UUID.randomUUID()));
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void recompressionTimeoutKillsGsAndFreesTheWorker() throws Exception {
        var service = serviceWith(fakeGs(hangCommand()));
        var pdf = "%PDF-1.4 contenu".getBytes(StandardCharsets.US_ASCII);

        var result = service.recompressPdfIfNeeded(pdf).block(Duration.ofSeconds(10));

        assertThat(result).as("original conservé").isSameAs(pdf);
        assertGsReleasedPromptly();
        assertThat(registry.get("televerse.gs.duration").tag("status", "timeout").timer().count())
                .isEqualTo(1);
    }

    @Test
    void splitTimeoutKillsGsAndSurfacesATimeout() throws Exception {
        var service = serviceWith(fakeGs(hangCommand()));
        var pdf = "%PDF-1.4 contenu".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> service.recompressAndSplitToPages(pdf, 3).block(Duration.ofSeconds(10)))
                .hasRootCauseInstanceOf(TimeoutException.class);
        assertGsReleasedPromptly();
    }

    @Test
    void failingGsKeepsTheOriginalWithoutWaiting() throws Exception {
        var service = serviceWith(fakeGs(WINDOWS ? "echo boom & exit /b 3" : "echo boom; exit 3"));
        var pdf = "%PDF-1.4 contenu".getBytes(StandardCharsets.US_ASCII);

        var result = service.recompressPdfIfNeeded(pdf).block(Duration.ofSeconds(10));

        assertThat(result).isSameAs(pdf);
        // Pas d'assertion immédiate sur la jauge : son décompte (doFinally) suit la
        // remise du résultat à l'abonné, block() peut donc rendre la main avant.
        assertGsReleasedPromptly();
    }

    /**
     * gs tué : la jauge de processus en cours retombe à 0 en quelques secondes, bien
     * avant les 30 s au bout desquelles le faux gs rendrait la main de lui-même. Une
     * seconde suffisait sur un poste au repos, pas sous charge : tuer l'arbre d'un
     * .cmd sous Windows peut y prendre plus.
     */
    private void assertGsReleasedPromptly() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (gsRunning() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(gsRunning()).as("processus gs encore compté en cours après le timeout").isZero();
    }

    private double gsRunning() {
        return registry.get("televerse.gs.concurrent").gauge().value();
    }

    private GhostscriptService serviceWith(Path fakeGs) {
        var props = new GhostscriptProperties(fakeGs.toAbsolutePath().toString(), DataSize.ofBytes(1),
                1, 10, TIMEOUT, "/ebook", 300);
        var service = new GhostscriptService(props, registry);
        assertThat(service.isAvailable()).as("sonde --version du faux gs").isTrue();
        return service;
    }

    /** Bloque bien au-delà du timeout (30 s). */
    private static String hangCommand() {
        return WINDOWS ? "ping -n 31 127.0.0.1 >nul" : "exec sleep 30";
    }

    /** Faux gs : répond à la sonde --version, exécute {@code body} sinon. */
    private Path fakeGs(String body) throws IOException {
        if (WINDOWS) {
            var script = dir.resolve("gs.cmd");
            Files.writeString(script, String.join("\r\n",
                    "@echo off",
                    "if \"%~1\"==\"--version\" (echo 10.0.0-fake & exit /b 0)",
                    body, ""));
            return script;
        }
        var script = dir.resolve("gs");
        Files.writeString(script, String.join("\n",
                "#!/bin/sh",
                "if [ \"$1\" = \"--version\" ]; then echo 10.0.0-fake; exit 0; fi",
                body, ""));
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }
}

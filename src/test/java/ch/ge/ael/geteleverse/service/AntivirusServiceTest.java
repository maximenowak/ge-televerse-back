package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.AntivirusProperties;
import ch.ge.ael.geteleverse.config.AntivirusProperties.FailMode;
import ch.ge.ael.geteleverse.controller.AntivirusUnavailableException;
import ch.ge.ael.geteleverse.controller.InfectedFileException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import reactor.test.StepVerifier;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.CommunicationException;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.InputStream;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires d'{@link AntivirusService}. Couvrent les cinq verdicts :
 * propre, infecté, erreur fail-closed, erreur fail-open, et service désactivé.
 * Le {@link ClamavClient} est mocké via le constructeur de test
 * package-private — pas besoin de clamd local.
 */
class AntivirusServiceTest {

    private static final byte[] PAYLOAD = "hello".getBytes();
    private static final String FILENAME = "facture.pdf";

    private static AntivirusProperties props(boolean enabled, FailMode failMode) {
        return new AntivirusProperties(
                enabled,
                "localhost",
                3310,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                DataSize.ofMegabytes(60),
                failMode,
                Duration.ofSeconds(30)
        );
    }

    private MeterRegistry registry() {
        return new SimpleMeterRegistry();
    }

    @Test
    void scan_returnsEmpty_whenClean() {
        var client = mock(ClamavClient.class);
        when(client.scan(any(InputStream.class))).thenReturn(ScanResult.OK.INSTANCE);
        var service = new AntivirusService(props(true, FailMode.CLOSED), registry(), client);

        StepVerifier.create(service.scan(PAYLOAD, FILENAME))
                .verifyComplete();
        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void scan_emitsInfectedFileException_whenVirusFound() {
        var client = mock(ClamavClient.class);
        var virus = new ScanResult.VirusFound(Map.of("stream", List.of("Eicar-Test-Signature")));
        when(client.scan(any(InputStream.class))).thenReturn(virus);
        var service = new AntivirusService(props(true, FailMode.CLOSED), registry(), client);

        StepVerifier.create(service.scan(PAYLOAD, FILENAME))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InfectedFileException.class);
                    var infected = (InfectedFileException) err;
                    assertThat(infected.filename()).isEqualTo(FILENAME);
                    assertThat(infected.signatures()).containsExactly("Eicar-Test-Signature");
                })
                .verify();
    }

    @Test
    void scan_emitsAntivirusUnavailableException_whenClamdUnreachable_andFailModeClosed() {
        var client = mock(ClamavClient.class);
        // CommunicationException est la classe levée par la lib quand le socket échoue ;
        // wrappée derrière `client.ping` ET `client.scan`. Ici on cible le scan.
        doThrow(new CommunicationException(new SocketException("Connection refused")))
                .when(client).scan(any(InputStream.class));
        var service = new AntivirusService(props(true, FailMode.CLOSED), registry(), client);

        StepVerifier.create(service.scan(PAYLOAD, FILENAME))
                .expectError(AntivirusUnavailableException.class)
                .verify();
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void scan_completesEmpty_whenClamdUnreachable_andFailModeOpen() {
        var client = mock(ClamavClient.class);
        doThrow(new CommunicationException(new SocketException("Connection refused")))
                .when(client).scan(any(InputStream.class));
        var service = new AntivirusService(props(true, FailMode.OPEN), registry(), client);

        StepVerifier.create(service.scan(PAYLOAD, FILENAME))
                .verifyComplete();
    }

    @Test
    void scan_isNoOp_whenDisabled() {
        var client = mock(ClamavClient.class);
        var service = new AntivirusService(props(false, FailMode.CLOSED), registry(), client);

        StepVerifier.create(service.scan(PAYLOAD, FILENAME))
                .verifyComplete();
        // Aucun appel à la lib : pas de probe au démarrage, pas de scan.
        verify(client, never()).ping();
        verify(client, never()).scan(any(InputStream.class));
        assertThat(service.isAvailable()).isFalse();
    }
}

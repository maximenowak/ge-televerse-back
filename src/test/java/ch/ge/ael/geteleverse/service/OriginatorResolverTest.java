package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.controller.OriginatorResolutionException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OriginatorResolverTest {

    private final OriginatorResolver resolver = new OriginatorResolver();

    private static MockServerWebExchange exchangeWith(String xff) {
        var builder = MockServerHttpRequest.post("/api/sessions");
        if (xff != null) builder.header("X-Forwarded-For", xff);
        return MockServerWebExchange.from(builder);
    }

    @Test
    void resolve_returnsSingleIpv4() {
        assertThat(resolver.resolve(exchangeWith("10.0.0.42"))).isEqualTo("10.0.0.42");
    }

    @Test
    void resolve_returnsFirstHopFromCommaSeparatedChain() {
        // Le firewall est l'edge de confiance ; le left-most est l'originateur réel.
        assertThat(resolver.resolve(exchangeWith("10.0.0.42, 192.168.1.1, 192.168.1.2")))
                .isEqualTo("10.0.0.42");
    }

    @Test
    void resolve_trimsWhitespace() {
        assertThat(resolver.resolve(exchangeWith("   10.0.0.42   "))).isEqualTo("10.0.0.42");
    }

    @Test
    void resolve_normalizesIpv6() {
        // Inet6Address canonicalise les zéros — un même poste ne doit pas apparaître
        // sous deux clés IMap selon la forme courte/longue.
        var canonical = resolver.resolve(exchangeWith("0:0:0:0:0:0:0:1"));
        var shortForm = resolver.resolve(exchangeWith("::1"));
        assertThat(canonical).isEqualTo(shortForm);
    }

    @Test
    void resolve_throwsMissing_whenHeaderAbsent() {
        assertThatThrownBy(() -> resolver.resolve(exchangeWith(null)))
                .isInstanceOf(OriginatorResolutionException.class)
                .extracting(e -> ((OriginatorResolutionException) e).errorCode())
                .isEqualTo(OriginatorResolutionException.MISSING);
    }

    @Test
    void resolve_throwsInvalid_whenHeaderIsEmpty() {
        assertThatThrownBy(() -> resolver.resolve(exchangeWith("   ")))
                .isInstanceOf(OriginatorResolutionException.class)
                .extracting(e -> ((OriginatorResolutionException) e).errorCode())
                .isEqualTo(OriginatorResolutionException.MISSING);
    }

    @Test
    void resolve_throwsInvalid_whenLiteralIsNotAnIp() {
        // « not-an-ip » sera vu par InetAddress.getByName() comme un hostname à
        // résoudre, ce qu'on bloque pour éviter un DNS lookup involontaire — la
        // validation se charge de rejeter ce qui n'est manifestement pas une IP.
        assertThatThrownBy(() -> resolver.resolve(exchangeWith("not an ip")))
                .isInstanceOf(OriginatorResolutionException.class)
                .extracting(e -> ((OriginatorResolutionException) e).errorCode())
                .isEqualTo(OriginatorResolutionException.INVALID);
    }
}

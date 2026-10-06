package ch.ge.ael.geteleverse.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre la matrice de décision du verrou same-origin : exemption document,
 * absence d'Origin, same-origin, cross-origin (403 + compteur), préflight CORS
 * laissé passer, hôtes supplémentaires, et débrayage par config. Vérifie aussi
 * que le tag {@code path} du compteur reste borné (premier segment, pas le
 * chemin brut qui porte le token).
 */
class SameOriginWebFilterTest {

    private static final String HOST = "televerser.app.ge.ch";
    private static final String SELF_ORIGIN = "https://" + HOST;
    private static final String EVIL_ORIGIN = "https://evil.example.com";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    // Realm de création cross-origin : .ge.ch / .etat-ge.ch (whitelist profil non
    // pertinente ici — ce filtre ne regarde que l'autorisation d'origine).
    private static final SessionCreateProperties CREATE_PROPS =
            new SessionCreateProperties(List.of(".ge.ch", ".etat-ge.ch"), List.of());

    private SameOriginWebFilter filter(boolean enabled, List<String> extraHosts) {
        return new SameOriginWebFilter(new SameOriginProperties(enabled, extraHosts), CREATE_PROPS, registry);
    }

    private SameOriginWebFilter filter() {
        return filter(true, List.of());
    }

    /** Chaîne qui mémorise si elle a été appelée (= requête laissée passer). */
    private static final class RecordingChain implements WebFilterChain {
        boolean called;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            called = true;
            return Mono.empty();
        }
    }

    private record Outcome(boolean passed, HttpStatus status) {
    }

    private Outcome run(SameOriginWebFilter filter, MockServerHttpRequest.BaseBuilder<?> request) {
        var exchange = MockServerWebExchange.from(request);
        var chain = new RecordingChain();
        filter.filter(exchange, chain).block();
        return new Outcome(chain.called, (HttpStatus) exchange.getResponse().getStatusCode());
    }

    private double rejectionCount(String pathTag) {
        return registry.find("televerse.same_origin.rejections").tag("path", pathTag).counter().count();
    }

    @Test
    void noOrigin_passes() {
        // Sonde K8s, curl debug, vieux client : pas d'Origin → laisser passer.
        var out = run(filter(), MockServerHttpRequest.get("/sessions/abc").header(HttpHeaders.HOST, HOST));
        assertThat(out.passed()).isTrue();
        assertThat(out.status()).isNull();
    }

    @Test
    void sameOrigin_passes() {
        var out = run(filter(), (MockServerHttpRequest.BaseBuilder<?>) MockServerHttpRequest.get("/sessions/abc")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, SELF_ORIGIN));
        assertThat(out.passed()).isTrue();
        assertThat(out.status()).isNull();
    }

    @Test
    void crossOrigin_onSessions_rejected() {
        var out = run(filter(), MockServerHttpRequest.post("/sessions")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, EVIL_ORIGIN));
        assertThat(out.passed()).isFalse();
        assertThat(out.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejectionCount("sessions")).isEqualTo(1.0);
    }

    @Test
    void crossOrigin_onFinalize_rejected() {
        // Route interne au flow desktop <-> mobile : reste verrouillée.
        var out = run(filter(), MockServerHttpRequest.post("/sessions/xxx/finalize")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, EVIL_ORIGIN));
        assertThat(out.passed()).isFalse();
        assertThat(out.status()).isEqualTo(HttpStatus.FORBIDDEN);
        // Tag borné au premier segment, pas le chemin avec token.
        assertThat(rejectionCount("sessions")).isEqualTo(1.0);
    }

    @Test
    void crossOrigin_onDocuments_exempted() {
        // Frontière = JWT (audience), pas Origin : laisser passer, Security valide en aval.
        var out = run(filter(), (MockServerHttpRequest.BaseBuilder<?>) MockServerHttpRequest.get("/documents/abc.pdf")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, EVIL_ORIGIN));
        assertThat(out.passed()).isTrue();
        assertThat(out.status()).isNull();
    }

    @Test
    void preflight_passedThroughToCors() {
        // OPTIONS + Access-Control-Request-Method = préflight CORS → laissé à l'étage CORS de Spring Security.
        var out = run(filter(), MockServerHttpRequest.method(HttpMethod.OPTIONS, "/sessions")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, EVIL_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"));
        assertThat(out.passed()).isTrue();
        assertThat(out.status()).isNull();
    }

    @Test
    void allowedExtraHost_passes() {
        var out = run(filter(true, List.of("monitoring.example.ch")),
                (MockServerHttpRequest.BaseBuilder<?>) MockServerHttpRequest.get("/sessions/abc")
                        .header(HttpHeaders.HOST, HOST)
                        .header(HttpHeaders.ORIGIN, "https://monitoring.example.ch"));
        assertThat(out.passed()).isTrue();
        assertThat(out.status()).isNull();
    }

    @Test
    void disabled_passesEverything() {
        var out = run(filter(false, List.of()), MockServerHttpRequest.post("/sessions")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, EVIL_ORIGIN));
        assertThat(out.passed()).isTrue();
        assertThat(out.status()).isNull();
    }

    @Test
    void crossOrigin_createSession_fromAllowedRealm_passes() {
        // Le webcomponent embarqué sur un formulaire .etat-ge.ch crée la session
        // en cross-origin : autorisé (POST /sessions uniquement).
        var out = run(filter(), MockServerHttpRequest.post("/sessions")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, "https://demarches.etat-ge.ch"));
        assertThat(out.passed()).isTrue();
        assertThat(out.status()).isNull();
    }

    @Test
    void crossOrigin_nonCreateRoute_fromAllowedRealm_stillRejected() {
        // Même origine de confiance, mais une autre route (finalize) reste
        // verrouillée same-origin : seul POST /sessions est ouvert au realm.
        var out = run(filter(), MockServerHttpRequest.post("/sessions/xxx/finalize")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, "https://demarches.etat-ge.ch"));
        assertThat(out.passed()).isFalse();
        assertThat(out.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void crossOrigin_onUnknownPath_countedAsOther() {
        // Path arbitraire choisi par l'attaquant → tag `other`, jamais une série par path.
        var out = run(filter(), (MockServerHttpRequest.BaseBuilder<?>) MockServerHttpRequest.get("/zzz-probe")
                .header(HttpHeaders.HOST, HOST)
                .header(HttpHeaders.ORIGIN, EVIL_ORIGIN));
        assertThat(out.passed()).isFalse();
        assertThat(out.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejectionCount("other")).isEqualTo(1.0);
    }
}

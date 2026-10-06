package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.config.AppSecurityProperties;
import ch.ge.ael.geteleverse.config.CorsConfig;
import ch.ge.ael.geteleverse.config.CorsProperties;
import ch.ge.ael.geteleverse.config.SecurityConfiguration;
import ch.ge.ael.geteleverse.config.SessionCreateProperties;
import ch.ge.ael.geteleverse.config.UploadAdmissionProperties;
import ch.ge.ael.geteleverse.service.StorageService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.HttpHandlerConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.AUD;

/**
 * Test d'intégration de la chaîne Spring Security WebFlux + audience validator
 * sur {@code GET /api/documents/{id}}.
 *
 * <p>Une paire RSA est générée par test pour pouvoir signer des JWT localement
 * (sans WireMock ni JWKS HTTP) : on fournit ici un {@code ReactiveJwtDecoder}
 * adossé à cette clé publique, en lieu et place de celui de
 * {@code ResourceServerConfiguration} qui pointerait sur une URL JWKS
 * fictive injoignable en test.</p>
 *
 * <p>Le contexte est volontairement <strong>minimal</strong> via
 * {@link SlimTestApp} (pas de composant scan global) — on évite ainsi de
 * charger les services d'image-processing (OpenCV / ONNX DocAligner) dont
 * les natives JavaCPP plantent dans les conteneurs CI minimalistes
 * (stack-guard warning sur {@code libgcc_s.so} → SIGSEGV silencieux).
 * Le test n'a besoin que de la chaîne sécurité + du controller +
 * {@code GlobalExceptionHandler} pour les 400.</p>
 */
@SpringBootTest(
        classes = DocumentControllerTest.SlimTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.webflux.base-path=/api",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.hazelcast.HazelcastAutoConfiguration",
                "app.security.enabled=true",
                "app.security.jwt-audience=AEL.TELEVERSE",
                "app.cors.allowed-origins=https://ge-televerse.app.ge.ch",
                "app.admission.upload.max-concurrent=8",
                "app.admission.upload.max-wait=PT0.2S",
                "app.admission.upload.retry-after=PT2S",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://test.invalid/jwks",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test.invalid"
        })
class DocumentControllerTest {

    /**
     * App minimale du test : pas de {@code scanBasePackages} → seuls les
     * beans explicitement {@code @Import}-és sont chargés. Suffit pour
     * exercer la chaîne sécurité du proxy OIDC, sans tirer les services
     * d'image qui crashent les natives sur la CI.
     */
    @SpringBootApplication(scanBasePackages = "ch.ge.ael.geteleverse.fake-package")
    @EnableConfigurationProperties({
            AppSecurityProperties.class,
            UploadAdmissionProperties.class,
            CorsProperties.class,
            SessionCreateProperties.class
    })
    @Import({
            DocumentController.class,
            SecurityConfiguration.class,
            CorsConfig.class,
            GlobalExceptionHandler.class,
            TestSecurityConfig.class
    })
    static class SlimTestApp {}


    private static final String DOC_ID = "11111111-2222-3333-4444-555555555555.pdf";
    private static final String AUDIENCE = "AEL.TELEVERSE";
    private static final String ISSUER = "https://test.invalid";

    @TestConfiguration
    static class TestSecurityConfig {

        static final RSAKey RSA_KEY = generateKey();

        private static RSAKey generateKey() {
            try {
                return new RSAKeyGenerator(2048)
                        .keyID("test-key")
                        .algorithm(JWSAlgorithm.RS256)
                        .keyUse(KeyUse.SIGNATURE)
                        .generate();
            } catch (JOSEException e) {
                throw new IllegalStateException("Cannot generate test RSA key", e);
            }
        }

        @Bean
        ReactiveJwtDecoder testJwtDecoder() throws JOSEException {
            var publicKey = (RSAPublicKey) RSA_KEY.toRSAPublicKey();
            var decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();
            OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                    AUD, aud -> aud != null && aud.contains(AUDIENCE));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER),
                    audienceValidator));
            return decoder;
        }
    }

    @Autowired
    HttpHandler httpHandler;

    @MockitoBean
    StorageService storageService;

    private WebTestClient webTestClient;

    @BeforeEach
    void buildWebTestClient() {
        // bindToApplicationContext bypasse le wrapper qui applique
        // spring.webflux.base-path ; on s'attache directement au HttpHandler
        // auto-configuré pour que /api/... soit correctement strippé avant le
        // dispatcher.
        webTestClient = WebTestClient.bindToServer(new HttpHandlerConnector(httpHandler))
                .build();
    }

    private static String signJwt(String audience) throws JOSEException {
        return signJwt(audience, ISSUER, Duration.ofMinutes(5));
    }

    private static String signJwt(String audience, String issuer, Duration validity) throws JOSEException {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .subject("test-subject")
                .issuer(issuer)
                .audience(List.of(audience))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(validity)))
                .build();
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(TestSecurityConfig.RSA_KEY.getKeyID())
                .build();
        var jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(TestSecurityConfig.RSA_KEY));
        return jwt.serialize();
    }

    /**
     * Une application hôte qui récupère le PDF depuis le navigateur envoie un
     * préflight (le header Authorization n'est pas « simple ») : il doit être servi
     * par la config CORS, jamais tomber sur la règle authenticated() de la chaîne
     * Security — sinon 401 sans en-tête CORS, et le navigateur n'envoie jamais le GET.
     */
    @Test
    void answersCorsPreflightForAnyHostOriginWithoutRequiringAJwt() {
        // URI absolue : sans hôte, la requête de test ne peut être comparée à l'Origin
        // et le processeur CORS la rejette (« origin is malformed ») — artefact du
        // harnais, une vraie requête porte toujours Host.
        webTestClient.options()
                .uri("http://televerser.test/api/documents/" + DOC_ID)
                .header(HttpHeaders.ORIGIN, "https://formulaire.ge.ch")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                .exchange()
                .expectStatus().isOk()
                // Pattern « * » : Spring renvoie l'origine appelante, pas « * ».
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://formulaire.ge.ch")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        v -> org.assertj.core.api.Assertions.assertThat(v).containsIgnoringCase("authorization"));
    }

    @Test
    void addsCorsHeadersToTheAuthenticatedResponse() throws JOSEException {
        when(storageService.fetchFromS3(DOC_ID)).thenReturn(Mono.just("%PDF-1.7".getBytes()));
        when(storageService.deleteFromS3(DOC_ID)).thenReturn(Mono.empty());

        webTestClient.get()
                .uri("http://televerser.test/api/documents/" + DOC_ID)
                .header(HttpHeaders.ORIGIN, "https://formulaire.ge.ch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signJwt(AUDIENCE))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://formulaire.ge.ch");
    }

    @Test
    void rejectsRequestWithoutJwt() {
        webTestClient.get()
                .uri("/api/documents/" + DOC_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsJwtWithWrongAudience() throws JOSEException {
        webTestClient.get()
                .uri("/api/documents/" + DOC_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signJwt("WRONG.AUDIENCE"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsJwtWithWrongIssuer() throws JOSEException {
        var token = signJwt(AUDIENCE, "https://attacker.invalid", Duration.ofMinutes(5));
        webTestClient.get()
                .uri("/api/documents/" + DOC_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsExpiredJwt() throws JOSEException {
        var token = signJwt(AUDIENCE, ISSUER, Duration.ofMinutes(-1));
        webTestClient.get()
                .uri("/api/documents/" + DOC_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void streamsAndDeletesOnSuccess() throws JOSEException {
        var pdf = "%PDF-1.4\nfake".getBytes();
        when(storageService.fetchFromS3(DOC_ID)).thenReturn(Mono.just(pdf));
        when(storageService.deleteFromS3(DOC_ID)).thenReturn(Mono.empty());

        webTestClient.get()
                .uri("/api/documents/" + DOC_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signJwt(AUDIENCE))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectBody(byte[].class).isEqualTo(pdf);

        verify(storageService).deleteFromS3(DOC_ID);
    }

    @Test
    void returnsGoneWhenS3KeyMissing() throws JOSEException {
        when(storageService.fetchFromS3(DOC_ID))
                .thenReturn(Mono.error(NoSuchKeyException.builder().build()));

        webTestClient.get()
                .uri("/api/documents/" + DOC_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signJwt(AUDIENCE))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GONE);

        verify(storageService, never()).deleteFromS3(anyString());
    }

    @Test
    void rejectsMalformedDocumentId() throws JOSEException {
        webTestClient.get()
                .uri("/api/documents/not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signJwt(AUDIENCE))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void deleteReturnsNoContentOnSuccess() throws JOSEException {
        when(storageService.deleteFromS3(DOC_ID)).thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/api/documents/" + DOC_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signJwt(AUDIENCE))
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        verify(storageService).deleteFromS3(DOC_ID);
    }

    @Test
    void deleteRejectsRequestWithoutJwt() {
        webTestClient.delete()
                .uri("/api/documents/" + DOC_ID)
                .exchange()
                .expectStatus().isUnauthorized();

        verify(storageService, never()).deleteFromS3(anyString());
    }

    @Test
    void deleteRejectsMalformedDocumentId() throws JOSEException {
        webTestClient.delete()
                .uri("/api/documents/not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signJwt(AUDIENCE))
                .exchange()
                .expectStatus().isBadRequest();

        verify(storageService, never()).deleteFromS3(anyString());
    }
}

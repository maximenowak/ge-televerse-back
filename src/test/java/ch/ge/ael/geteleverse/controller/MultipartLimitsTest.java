package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.config.SecurityConfiguration;
import ch.ge.ael.geteleverse.config.UploadAdmissionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.test.web.reactive.server.HttpHandlerConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

/**
 * Plafonds du codec multipart, vérifiés de bout en bout à travers le vrai pipeline
 * WebFlux (codecs auto-configurés + {@link GlobalExceptionHandler}).
 *
 * <p>Les limites se posent via {@code spring.webflux.multipart.*} : le
 * {@code CodecCustomizer} de Boot réécrit tout {@code DefaultPartHttpMessageReader},
 * y compris un lecteur configuré à la main. C'est ainsi qu'un ancien
 * {@code WebFluxConfigurer} laissait les uploads sans aucune borne. Le plafond est
 * abaissé à 1 Mo pour garder le test léger ; il transite par
 * {@code app.limits.multipart-ceiling}, ce qui vérifie aussi ce câblage.</p>
 *
 * <p>Le contrôleur de sonde reprend la signature de {@code UploadController.upload}
 * ({@code @RequestPart FilePart}) : la limite s'applique à la résolution de
 * l'argument, avant tout code applicatif.</p>
 */
@SpringBootTest(
        classes = MultipartLimitsTest.SlimTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.limits.multipart-ceiling=1MB",
                "app.security.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://test.invalid/jwks"
        })
class MultipartLimitsTest {

    @SpringBootApplication(scanBasePackages = "ch.ge.ael.geteleverse.fake-package")
    @EnableConfigurationProperties(UploadAdmissionProperties.class)
    @org.springframework.context.annotation.Import({
            ProbeUploadController.class,
            GlobalExceptionHandler.class,
            SecurityConfiguration.class
    })
    static class SlimTestApp {}

    @RestController
    static class ProbeUploadController {
        @PostMapping("/probe/{token}")
        Mono<String> upload(@PathVariable String token, @RequestPart("file") FilePart filePart) {
            return DataBufferUtils.join(filePart.content()).map(buffer -> {
                int size = buffer.readableByteCount();
                DataBufferUtils.release(buffer);
                return String.valueOf(size);
            });
        }
    }

    @Autowired
    HttpHandler httpHandler;

    private WebTestClient webTestClient;

    @BeforeEach
    void buildWebTestClient() {
        webTestClient = WebTestClient.bindToServer(new HttpHandlerConnector(httpHandler)).build();
    }

    @Test
    void acceptsAFileUnderTheCeiling() {
        var body = new MultipartBodyBuilder();
        body.part("file", file("page.pdf", 900 * 1024));

        post(body).expectStatus().isOk()
                .expectBody(String.class).isEqualTo(String.valueOf(900 * 1024));
    }

    @Test
    void rejectsAFileOverTheCeilingWith413() {
        var body = new MultipartBodyBuilder();
        body.part("file", file("page.pdf", 1024 * 1024 + 100 * 1024));

        post(body).expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectBody().jsonPath("$.error").isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void rejectsASecondPartWith400() {
        var body = new MultipartBodyBuilder();
        body.part("file", file("page.pdf", 1024));
        body.part("other", file("autre.pdf", 1024));

        post(body).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("INVALID_MULTIPART");
    }

    private WebTestClient.ResponseSpec post(MultipartBodyBuilder body) {
        // Préfixe spring.webflux.base-path (application.yml), comme en production.
        return webTestClient.post().uri("/api/probe/tok")
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange();
    }

    private static ByteArrayResource file(String name, int size) {
        return new ByteArrayResource(new byte[size]) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }
}

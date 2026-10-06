package ch.ge.ael.geteleverse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que {@link CorsProperties} binde correctement les trois formes
 * de {@code app.cors.allowed-origins} qu'on rencontre dans les déploiements :
 *
 * <ol>
 *   <li>YAML list indexée (profil prod) — la forme qui a fait planter le
 *       premier merge Boot 4 en live parce que {@code @Value("${app.cors.allowed-origins}")}
 *       ne sait pas binder cette forme ;</li>
 *   <li>String CSV (override par env var {@code APP_CORS_ALLOWED_ORIGINS=a,b,c}) ;</li>
 *   <li>String unique ({@code "*"} du profil dev).</li>
 * </ol>
 *
 * <p>Ce test est volontairement light : on charge un context Spring Boot
 * minimal via {@link ApplicationContextRunner} (pas {@code @SpringBootTest}
 * complet) pour ne pas dévier de la convention de tests Mockito pure du reste
 * du projet plus que nécessaire.</p>
 */
class CorsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void binds_yamlList() {
        runner.withPropertyValues(
                "app.cors.allowed-origins[0]=https://a.example.ch",
                "app.cors.allowed-origins[1]=https://b.example.ch"
        ).run(ctx -> {
            var props = ctx.getBean(CorsProperties.class);
            assertThat(props.allowedOrigins())
                    .containsExactly("https://a.example.ch", "https://b.example.ch");
        });
    }

    @Test
    void binds_csvString() {
        runner.withPropertyValues(
                "app.cors.allowed-origins=https://a.example.ch,https://b.example.ch"
        ).run(ctx -> {
            var props = ctx.getBean(CorsProperties.class);
            assertThat(props.allowedOrigins())
                    .containsExactly("https://a.example.ch", "https://b.example.ch");
        });
    }

    @Test
    void binds_singleValue() {
        runner.withPropertyValues("app.cors.allowed-origins=*").run(ctx -> {
            var props = ctx.getBean(CorsProperties.class);
            assertThat(props.allowedOrigins()).containsExactly("*");
        });
    }

    @Configuration
    @EnableConfigurationProperties(CorsProperties.class)
    @org.springframework.context.annotation.Import(PropertyPlaceholderAutoConfiguration.class)
    static class TestConfig {
    }
}

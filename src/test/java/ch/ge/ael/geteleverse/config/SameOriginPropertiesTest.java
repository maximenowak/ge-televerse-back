package ch.ge.ael.geteleverse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que {@link SameOriginProperties} binde {@code app.same-origin.*} sous
 * les formes rencontrées en déploiement. {@code allowed-extra-hosts} est une
 * YAML list : on teste explicitement la forme indexée (celle qui a fait planter
 * un merge Boot 4 avec {@code @Value}) en plus du CSV env var et du défaut vide.
 */
class SameOriginPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_enabledTrue_emptyExtraHosts() {
        runner.run(ctx -> {
            var props = ctx.getBean(SameOriginProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.allowedExtraHosts()).isEmpty();
        });
    }

    @Test
    void binds_yamlList_extraHosts() {
        runner.withPropertyValues(
                "app.same-origin.allowed-extra-hosts[0]=monitor.example.ch",
                "app.same-origin.allowed-extra-hosts[1]=probe.example.ch"
        ).run(ctx -> {
            var props = ctx.getBean(SameOriginProperties.class);
            assertThat(props.allowedExtraHosts())
                    .containsExactly("monitor.example.ch", "probe.example.ch");
        });
    }

    @Test
    void binds_csvString_extraHosts() {
        runner.withPropertyValues(
                "app.same-origin.allowed-extra-hosts=monitor.example.ch,probe.example.ch"
        ).run(ctx -> {
            var props = ctx.getBean(SameOriginProperties.class);
            assertThat(props.allowedExtraHosts())
                    .containsExactly("monitor.example.ch", "probe.example.ch");
        });
    }

    @Test
    void binds_disabled() {
        runner.withPropertyValues("app.same-origin.enabled=false").run(ctx ->
                assertThat(ctx.getBean(SameOriginProperties.class).enabled()).isFalse());
    }

    @Configuration
    @EnableConfigurationProperties(SameOriginProperties.class)
    @Import(PropertyPlaceholderAutoConfiguration.class)
    static class TestConfig {
    }
}

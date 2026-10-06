package ch.ge.ael.geteleverse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie le binding de {@link UploadFormatProperties} sur les trois formes
 * de {@code app.upload.allowed-formats} (YAML list / CSV env var / valeur
 * unique), ainsi que la normalisation des extensions et le défaut quand la
 * config est vide.
 *
 * <p>Couvre la classe de bug rappelée par
 * {@code feedback_yaml_list_value.md} : Spring Boot 4 lève
 * {@code PlaceholderResolutionException} si on essaie de binder une YAML list
 * via {@code @Value}, alors que le {@code Binder} de
 * {@code @ConfigurationProperties} gère indifféremment les trois formes.</p>
 */
class UploadFormatPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void binds_yamlList() {
        runner.withPropertyValues(
                "app.upload.allowed-formats[0]=pdf",
                "app.upload.allowed-formats[1]=jpg",
                "app.upload.allowed-formats[2]=png"
        ).run(ctx -> {
            var props = ctx.getBean(UploadFormatProperties.class);
            assertThat(props.allowedFormats()).containsExactly("pdf", "jpg", "png");
        });
    }

    @Test
    void binds_csvString() {
        runner.withPropertyValues(
                "app.upload.allowed-formats=pdf,jpg,png"
        ).run(ctx -> {
            var props = ctx.getBean(UploadFormatProperties.class);
            assertThat(props.allowedFormats()).containsExactly("pdf", "jpg", "png");
        });
    }

    @Test
    void normalizes_stripsDotAndLowercases() {
        runner.withPropertyValues(
                "app.upload.allowed-formats[0]=.PDF",
                "app.upload.allowed-formats[1]= JPG ",
                "app.upload.allowed-formats[2]=Tiff"
        ).run(ctx -> {
            var props = ctx.getBean(UploadFormatProperties.class);
            assertThat(props.allowedFormats()).containsExactly("pdf", "jpg", "tiff");
        });
    }

    @Test
    void appliesDefault_whenConfigAbsent() {
        runner.run(ctx -> {
            var props = ctx.getBean(UploadFormatProperties.class);
            assertThat(props.allowedFormats())
                    .containsExactly("pdf", "jpg", "jpeg", "png", "tiff");
        });
    }

    @Test
    void isAllowed_matchesExtensionCaseInsensitively() {
        var props = new UploadFormatProperties(java.util.List.of("pdf", "jpg"));
        assertThat(props.isAllowed("doc.PDF")).isTrue();
        assertThat(props.isAllowed("photo.jpg")).isTrue();
        assertThat(props.isAllowed("evil.exe")).isFalse();
        assertThat(props.isAllowed("noextension")).isFalse();
        assertThat(props.isAllowed("trailing.")).isFalse();
        assertThat(props.isAllowed(null)).isFalse();
    }

    @Test
    void formattedList_joinsCommaSeparated() {
        var props = new UploadFormatProperties(java.util.List.of("pdf", "jpg", "png"));
        assertThat(props.formattedList()).isEqualTo("pdf, jpg, png");
    }

    @Configuration
    @EnableConfigurationProperties(UploadFormatProperties.class)
    @org.springframework.context.annotation.Import(PropertyPlaceholderAutoConfiguration.class)
    static class TestConfig {
    }
}

package ch.ge.ael.geteleverse.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Profil dev : remplace le client S3 distant (firewallé hors du réseau de prod)
 * par un faux client adossé au système de fichiers local. Les objets persistent
 * entre redémarrages dans {@code ${user.home}/.ge-televerse/s3-dev}.
 */
@Configuration
@Profile("dev")
@Slf4j
public class S3DevConfiguration {

    @Bean
    public S3AsyncClient s3AsyncClient() throws IOException {
        var root = Path.of(System.getProperty("user.home"), ".ge-televerse", "s3-dev");
        Files.createDirectories(root);
        log.warn("Dev profile S3 simulated path={}", root);
        return new FakeS3AsyncClient(root);
    }
}

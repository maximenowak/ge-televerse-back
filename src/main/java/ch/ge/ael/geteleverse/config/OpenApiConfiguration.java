package ch.ge.ael.geteleverse.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Métadonnées de la spec OpenAPI, actives seulement quand springdoc est sur le
 * classpath — c'est-à-dire sous le profil Maven {@code openapi-generation}, pas dans
 * le jar de production (cf. pom.xml, section OpenAPI).
 */
@Configuration
@ConditionalOnClass(name = "org.springdoc.core.configuration.SpringDocConfiguration")
public class OpenApiConfiguration {

    /**
     * Métadonnées de la spec, servie sur 8080 sous le profil Spring {@code openapi}
     * (génération Maven). La version vient de {@link BuildProperties}
     * (build-info.properties généré par le spring-boot-maven-plugin) ;
     * absente lors d'un lancement IDE sans packaging, d'où l'ObjectProvider.
     */
    @Bean
    public OpenAPI teleVerseOpenAPI(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("GE-Televerse Backend API")
                        .description("API REST du backend GE-Televerse — scan de documents mobile, "
                                + "conversion PDF, assemblage et stockage S3 pour l'État de Genève.")
                        .version(build != null ? build.getVersion() : "dev")
                        .contact(new Contact()
                                .name("État de Genève")
                                .email("opensource@etat.ge.ch")))
                // Liste explicite : springdoc cesse d'auto-générer l'entrée
                // localhost dès qu'un serveur est déclaré, d'où sa présence ici.
                .servers(List.of(
                        new Server()
                                .url("https://televerser.app.ge.ch/api")
                                .description("Environnement de prod"),
                        new Server()
                                .url("http://localhost:8080/api")
                                .description("Local")));
    }
}

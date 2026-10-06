package ch.ge.ael.geteleverse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bibliothèques volontairement absentes du classpath de l'application — celui des
 * tests en est le reflet, hors profil Maven. Chacune a été retirée pour solder des
 * constats NexusIQ sans version corrigée à viser, ou qui revenaient à chaque rapport :
 *
 * <ul>
 *   <li>Jackson 2 (databind) : n'arrivait que par swagger-core, via springdoc, désormais
 *       cantonné au profil {@code openapi-generation} ;</li>
 *   <li>springdoc lui-même ;</li>
 *   <li>log4j-api : n'arrivait que par le pont log4j-to-slf4j, exclu de
 *       spring-boot-starter-logging.</li>
 * </ul>
 *
 * Un échec signale qu'une dépendance les ramène : l'écarter, ou justifier son retour
 * dans le POM.
 */
class ProductionClasspathTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "com.fasterxml.jackson.databind.ObjectMapper",
            "org.springdoc.core.configuration.SpringDocConfiguration",
            "org.apache.logging.log4j.LogManager",
    })
    void staysOffTheClasspath(String className) {
        assertThatThrownBy(() -> Class.forName(className, false, getClass().getClassLoader()))
                .isInstanceOf(ClassNotFoundException.class);
    }
}

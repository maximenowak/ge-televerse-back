package ch.ge.ael.geteleverse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Chaîne Spring Security WebFlux.
 *
 * <p>Seules les routes {@code /api/documents/**} exigent un Bearer JWT
 * valide (validé par {@link ResourceServerConfiguration}). Tout le reste
 * (sessions, upload, SSE, actuator) reste {@code permitAll} : ces routes
 * étaient publiques avant l'ajout du resource server et le restent —
 * leur protection contre les appels cross-origin passe par le verrou
 * server-side {@link SameOriginWebFilter} (primaire) et la config CORS
 * (voir {@link CorsConfig}, allowlist navigateur défensive).</p>
 *
 * <p>CSRF désactivé : API stateless, pas de session servlet, pas de cookie
 * d'auth — le seul vecteur d'identité est le header {@code Authorization}.</p>
 *
 * <p>CORS appliqué par la chaîne elle-même ({@code .cors(withDefaults())}, qui prend
 * le bean {@code CorsConfigurationSource} de {@link CorsConfig}) : son étage CORS
 * précède l'autorisation, donc un préflight vers {@code /documents/**} reçoit sa
 * réponse CORS au lieu du 401 de {@code authenticated()}. Les deux chaînes le
 * portent — le dev local est cross-origin (Parcel :1234 → API :8080).</p>
 *
 * <p>Le bean « unsecure » est activé sur le profil dev
 * ({@code app.security.enabled=false}) pour autoriser un développement local
 * sans JWT à fabriquer.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "true")
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        // Le préfixe /api défini par spring.webflux.base-path est strippé par
        // ContextPathCompositeHandler AVANT la chaîne WebFilter, donc Spring
        // Security voit la requête au chemin /documents/{id}.
        return http
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/documents/**").authenticated()
                        .anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "false")
    SecurityWebFilterChain springSecurityFilterChainUnsecure(ServerHttpSecurity http) {
        return http
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}

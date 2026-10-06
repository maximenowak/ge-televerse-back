package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration CORS, alimentée par {@code app.cors.*}.
 *
 * <p>On passe par un {@code @ConfigurationProperties} plutôt qu'un
 * {@code @Value("${app.cors.allowed-origins}")} parce que ce dernier ne sait
 * pas binder une YAML list (présente en prod) — la clé bare
 * {@code app.cors.allowed-origins} n'existe pas dans l'environnement, seules
 * les entrées indexées {@code [0]}, {@code [1]}, … le sont, et Spring Boot 4
 * lève {@link org.springframework.util.PlaceholderResolutionException} au
 * démarrage. Le {@code Binder} utilisé par {@code @ConfigurationProperties}
 * gère lui les YAML lists, la string unique ({@code "*"} du profil dev) et
 * la conversion à partir de l'env var {@code APP_CORS_ALLOWED_ORIGINS}
 * (CSV) — les trois formes se retrouvent indifféremment dans
 * {@code allowedOrigins}.
 *
 * @param allowedOrigins origines acceptées en CORS. Patterns au sens de
 *                       {@link org.springframework.web.cors.CorsConfiguration#addAllowedOriginPattern(String)}
 *                       — accepte les origines littérales et les wildcards
 *                       (ex. {@code https://*.ge.ch}).
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}

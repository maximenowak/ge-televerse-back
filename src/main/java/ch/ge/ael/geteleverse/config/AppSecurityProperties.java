package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de la chaîne Spring Security.
 *
 * <p>{@code enabled=false} (profil dev) débraye complètement la sécurité :
 * aucune route ne demande de JWT. {@code enabled=true} active la chaîne qui
 * protège {@code /api/documents/**} avec un Bearer JWT validé via le JWKS
 * configuré sous {@code spring.security.oauth2.resourceserver.jwt.*}. Le
 * claim {@code aud} doit contenir {@link #jwtAudience()}.</p>
 */
@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(boolean enabled, String jwtAudience) {
}

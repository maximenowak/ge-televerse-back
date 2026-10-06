package ch.ge.ael.geteleverse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.util.List;

import static org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.AUD;

/**
 * Décoder JWT du Resource Server.
 *
 * <p>Mêmes briques que {@code api-config} : {@link NimbusReactiveJwtDecoder}
 * adossé au JWKS configuré, augmenté d'un {@link JwtClaimValidator} qui
 * impose la présence de l'audience attendue dans le claim {@code aud}.
 * Le validateur standard d'issuer (signature, expiration, {@code iss}) reste
 * en place via {@link JwtValidators#createDefaultWithIssuer(String)}.</p>
 *
 * <p>Le bean n'est créé que si {@code app.security.enabled=true} — en dev
 * (où le bean est absent) la chaîne de filtres
 * {@link SecurityConfiguration#springSecurityFilterChainUnsecure} prend le
 * relais et laisse tout passer.</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.security.enabled", havingValue = "true")
public class ResourceServerConfiguration {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    private final AppSecurityProperties appSecurityProperties;

    public ResourceServerConfiguration(AppSecurityProperties appSecurityProperties) {
        this.appSecurityProperties = appSecurityProperties;
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder() {
        var jwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        var withAudience = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                audienceValidator());
        jwtDecoder.setJwtValidator(withAudience);
        return jwtDecoder;
    }

    private OAuth2TokenValidator<Jwt> audienceValidator() {
        return new JwtClaimValidator<List<String>>(AUD,
                aud -> aud != null && aud.contains(appSecurityProperties.jwtAudience()));
    }
}

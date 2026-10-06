package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Paramètres du cache de vignettes Hazelcast.
 *
 * @param enabled activation du cache. À {@code false}, les vignettes ne sont ni
 *                écrites ni lues dans Hazelcast et chaque affichage passe par la
 *                régénération à la volée depuis le PDF de la page (S3). Sert de
 *                coupe-circuit d'exploitation sans redéploiement de code.
 * @param ttl     durée de vie d'une entrée. Doit rester nettement supérieure au
 *                {@code app.session-ttl} (5 min) parce que ce dernier est
 *                repoussé à chaque activité : une entrée posée au premier upload
 *                doit survivre à une session prolongée par des éditions
 *                successives. Une expiration prématurée n'est pas une erreur —
 *                elle coûte une régénération.
 */
@ConfigurationProperties(prefix = "app.thumbnail-cache")
public record ThumbnailCacheProperties(
        boolean enabled,
        Duration ttl
) {
    public ThumbnailCacheProperties {
        if (ttl == null) ttl = Duration.ofMinutes(30);
    }
}

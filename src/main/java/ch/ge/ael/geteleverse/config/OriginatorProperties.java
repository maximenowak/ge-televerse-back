package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration du verrou « une session par IP d'origine ».
 *
 * <p>Quand {@code enabled} est vrai (défaut), {@code POST /sessions} extrait
 * l'IP d'origine du header {@code X-Forwarded-For} (posé par le firewall) et
 * bloque toute deuxième création de session tant que la première est active.
 * En cas de conflit, la deuxième requête reçoit un {@code 409} avec le code
 * d'erreur {@code ORIGINATOR_HAS_ACTIVE_SESSION} et la date d'expiration de
 * la session bloquante, pour permettre au front d'afficher un message.</p>
 *
 * <p>Le flag est lu une seule fois au démarrage côté contrôleur. Débrayer en
 * production permet de relancer un load test représentatif ou de neutraliser
 * la fonction sans redéployer du code.</p>
 *
 * @param enabled {@code true} (défaut) pour activer le verrou ; {@code false}
 *                pour ignorer entièrement le header XFF et autoriser
 *                plusieurs sessions concurrentes depuis la même IP.
 */
@ConfigurationProperties(prefix = "app.originator")
public record OriginatorProperties(@DefaultValue("true") boolean enabled) {
}

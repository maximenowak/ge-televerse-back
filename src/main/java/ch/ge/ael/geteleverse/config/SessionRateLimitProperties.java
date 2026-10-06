package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Token bucket par IP d'origine sur {@code POST /api/sessions}, anti-DoS.
 *
 * <p>Une création de session normale consomme 1 jeton ; un {@code ?force=true}
 * en consomme {@link #forceCost()}, car forcer purge délibérément le verrou
 * « une session par IP » et permet la création immédiate d'une nouvelle
 * session — c'est exactement le tour de magie qu'un attaquant utiliserait
 * pour boucler sur la création/invalidation. Le coût plus élevé rend le
 * scénario rentable seulement à un débit que la métrique
 * {@code televerse.originator.force} permet déjà d'observer (cf.
 * {@link ch.ge.ael.geteleverse.controller.SessionController#createSession}).</p>
 *
 * <p>Le bucket est par-pod (mémoire locale), pas distribué via Hazelcast :
 * un attaquant qui round-robin sur N pods voit au pire N × le débit configuré
 * — toujours borné, et trivial à serrer en réduisant
 * {@code refillTokensPerMinute}. Le LB d'État applique normalement une
 * affinité par IP source vers le même pod, ce qui réduit cet écart en
 * pratique.</p>
 *
 * @param enabled                {@code true} (défaut) pour appliquer le bucket.
 *                               Mettre à {@code false} en load-test ou pour
 *                               diagnostic local.
 * @param capacity               capacité du bucket (burst maximum autorisé en
 *                               instantané). Défaut 10 : permet 10 créations
 *                               immédiates depuis une IP fraîche avant de
 *                               retomber sur le débit de refill.
 * @param refillTokensPerMinute  nombre de jetons rajoutés par minute (régime
 *                               stationnaire). Défaut 10 : 1 jeton toutes les
 *                               6 s, largement au-dessus du débit usager
 *                               légitime (1 session ouverte à la fois,
 *                               retentatives inclues).
 * @param forceCost              jetons consommés par un {@code ?force=true}.
 *                               Défaut 5 : autorise ~2 forces/min en régime
 *                               permanent, soit l'usage humain réaliste
 *                               (« j'ai fermé l'autre fenêtre, relance ») sans
 *                               laisser un script en abuser.
 * @param idleEviction           durée d'inactivité au-delà de laquelle un
 *                               bucket plein est évincé de la map. Borne la
 *                               consommation mémoire face à un attaquant qui
 *                               scanne des IPs sans recycler.
 */
@ConfigurationProperties(prefix = "app.rate-limit.session-create")
public record SessionRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        int capacity,
        int refillTokensPerMinute,
        int forceCost,
        Duration idleEviction
) {
    public SessionRateLimitProperties {
        if (capacity <= 0) capacity = 10;
        if (refillTokensPerMinute <= 0) refillTokensPerMinute = 10;
        if (forceCost <= 0) forceCost = 5;
        if (idleEviction == null) idleEviction = Duration.ofMinutes(10);
    }

    /** Coût en jetons d'une requête selon le mode. */
    public int costFor(boolean force) {
        return force ? forceCost : 1;
    }
}

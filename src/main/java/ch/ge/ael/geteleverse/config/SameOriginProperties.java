package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration du verrou same-origin server-side (cf. {@link SameOriginWebFilter}).
 *
 * <p>Le verrou refuse les requêtes <strong>non-document</strong> dont le header
 * {@code Origin} ne correspond pas à l'hôte courant. C'est une défense en
 * profondeur derrière CORS : CORS protège le navigateur via le préflight, ce
 * filtre coupe aussi les appels cross-origin qui ne déclenchent pas de préflight
 * et garde un point de contrôle explicite côté serveur que les audits
 * KICS/Sonar réclament.</p>
 *
 * <p>On passe par un {@code @ConfigurationProperties} (et non
 * {@code @Value}) pour {@code allowedExtraHosts} parce que ce dernier ne sait
 * pas binder une YAML list — même piège que {@link CorsProperties}.</p>
 *
 * @param enabled           {@code true} (défaut) pour appliquer le verrou. À
 *                          mettre à {@code false} en dev (la SPA Parcel sur
 *                          {@code :1234} n'est jamais same-origin avec le backend
 *                          sur {@code :8080}) ou pour une fenêtre d'observation
 *                          en preprod avant la bascule.
 * @param allowedExtraHosts hôtes supplémentaires acceptés en plus du {@code Host}
 *                          courant (sondes externes nommées, monitoring tiers qui
 *                          poseraient un {@code Origin}). Vide par défaut. Chaque
 *                          entrée {@code h} autorise l'origine {@code https://h}.
 */
@ConfigurationProperties(prefix = "app.same-origin")
public record SameOriginProperties(
        @DefaultValue("true") boolean enabled,
        List<String> allowedExtraHosts
) {
    public SameOriginProperties {
        allowedExtraHosts = allowedExtraHosts == null ? List.of() : List.copyOf(allowedExtraHosts);
    }
}

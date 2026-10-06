package ch.ge.ael.geteleverse.config;

import ch.ge.ael.geteleverse.model.SizeProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Configuration de la création de session cross-origin et de la dérivation du
 * profil de taille à partir du header {@code Origin}.
 *
 * <p><strong>Pourquoi.</strong> Le profil de taille (S/M/L/XL → quota d'octets
 * cumulés par session) ne doit JAMAIS être choisi par le client : il transitait
 * historiquement en clair (attribut {@code size-profile} du webcomponent → param
 * d'URL → corps du {@code POST /sessions}), donc l'utilisateur final pouvait
 * s'auto-augmenter son quota. La seule donnée non falsifiable côté navigateur est
 * le header {@code Origin}, posé par le navigateur et impossible à forger en JS.
 * On dérive donc le profil de l'{@code Origin} de l'appel — ce qui suppose que la
 * création parte de la page hôte (le webcomponent {@code <ge-televerse>} appelle
 * lui-même {@code POST /sessions}), pas de la SPA.</p>
 *
 * <p><strong>Deux niveaux.</strong></p>
 * <ul>
 *   <li>{@code allowedOriginSuffixes} — le <em>realm</em> autorisé à créer une
 *       session en cross-origin (consommé par {@link CorsConfig} et
 *       {@link SameOriginWebFilter}, pour la création UNIQUEMENT). Large par
 *       nature : tout sous-domaine d'État (ex. {@code .ge.ch}).
 *       Autoriser largement ici n'affaiblit rien : l'{@code Origin} reste
 *       infalsifiable, c'est lui qui gouverne le profil.</li>
 *   <li>{@code profiles} — la whitelist fine {@code suffixe → profil}. Le suffixe
 *       configuré le plus spécifique (le plus long) qui matche le host gagne.
 *       Une origine absente de cette liste reçoit {@link SizeProfile#DEFAULT}
 *       (S) — défaut sûr, jamais d'escalade implicite.</li>
 * </ul>
 *
 * <p>On passe par un {@code @ConfigurationProperties} (et non {@code @Value})
 * pour binder les YAML lists — même piège que {@link CorsProperties}.</p>
 *
 * @param allowedOriginSuffixes suffixes de host autorisés à créer en cross-origin
 *                              (ex. {@code .ge.ch}). Le point initial est optionnel
 *                              à la configuration ; le matching impose toujours une
 *                              frontière de label (un suffixe {@code ge.ch} ne
 *                              matche pas {@code evil-ge.ch}).
 * @param profiles              mappings {@code suffixe → profil}, longest-match.
 */
@ConfigurationProperties(prefix = "app.session-create")
public record SessionCreateProperties(
        List<String> allowedOriginSuffixes,
        List<OriginProfile> profiles
) {

    /** Une entrée de la whitelist : un suffixe de host et le profil qu'il octroie. */
    public record OriginProfile(String suffix, SizeProfile profile) {}

    public SessionCreateProperties {
        allowedOriginSuffixes = allowedOriginSuffixes == null ? List.of() : List.copyOf(allowedOriginSuffixes);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    /**
     * {@code true} si l'origine (https) appartient au realm autorisé à créer une
     * session en cross-origin. Utilisé par CORS (préflight navigateur) et par le
     * verrou same-origin (rejet server-side).
     */
    public boolean isAllowedCreateOrigin(String origin) {
        var host = hostOf(origin);
        if (host == null) return false;
        for (var suffix : allowedOriginSuffixes) {
            if (matches(host, suffix)) return true;
        }
        return false;
    }

    /**
     * Profil de taille dérivé de l'origine : suffixe configuré le plus spécifique
     * (le plus long) qui matche le host. {@link SizeProfile#DEFAULT} (S) si aucune
     * correspondance — la valeur envoyée par le client n'est jamais consultée.
     */
    public SizeProfile resolveProfile(String origin) {
        var host = hostOf(origin);
        if (host == null) return SizeProfile.DEFAULT;
        SizeProfile best = SizeProfile.DEFAULT;
        int bestLen = -1;
        for (var op : profiles) {
            if (op.suffix() == null || op.profile() == null) continue;
            if (matches(host, op.suffix()) && op.suffix().length() > bestLen) {
                best = op.profile();
                bestLen = op.suffix().length();
            }
        }
        return best;
    }

    /**
     * {@code true} si le host de l'origine matche un suffixe explicitement
     * configuré dans {@code profiles}. {@code false} pour une origine absente de
     * la whitelist fine — qui recevra alors {@link SizeProfile#DEFAULT} via
     * {@link #resolveProfile}. À distinguer de « le profil résolu vaut S » : un
     * suffixe peut être explicitement mappé sur S et reste alors « connu ».
     * Sert à journaliser les hôtes inconnus qui créent des sessions, pour
     * repérer les domaines de formulaire à ajouter à la configuration.
     */
    public boolean hasConfiguredProfile(String origin) {
        var host = hostOf(origin);
        if (host == null) return false;
        for (var op : profiles) {
            if (op.suffix() == null || op.profile() == null) continue;
            if (matches(host, op.suffix())) return true;
        }
        return false;
    }

    /**
     * Patterns CORS dérivés des suffixes autorisés (ex. {@code .ge.ch} →
     * {@code https://*.ge.ch}), au sens de
     * {@link org.springframework.web.cors.CorsConfiguration#addAllowedOriginPattern(String)}.
     */
    public List<String> corsOriginPatterns() {
        return allowedOriginSuffixes.stream()
                .map(SessionCreateProperties::dotted)
                .map(s -> "https://*" + s)
                .toList();
    }

    /** Host (sans scheme ni port) d'une origine https, ou {@code null} si ce n'est pas une origine https exploitable. */
    private static String hostOf(String origin) {
        if (origin == null) return null;
        var lower = origin.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) return null;
        var host = lower.substring("https://".length());
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.isBlank() ? null : host;
    }

    /**
     * Matching avec frontière de label : le suffixe est normalisé avec un point
     * initial, donc {@code ge.ch} (et {@code .ge.ch}) matchent {@code x.ge.ch} et
     * l'apex {@code ge.ch}, mais jamais {@code evil-ge.ch}.
     */
    private static boolean matches(String host, String suffix) {
        if (suffix == null || suffix.isBlank()) return false;
        var dotted = dotted(suffix.trim().toLowerCase(Locale.ROOT)); // ".ge.ch"
        var apex = dotted.substring(1);                              // "ge.ch"
        return host.equals(apex) || host.endsWith(dotted);
    }

    private static String dotted(String suffix) {
        return suffix.startsWith(".") ? suffix : "." + suffix;
    }
}

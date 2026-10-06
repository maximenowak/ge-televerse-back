package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Configuration du scan antivirus des uploads via clamd (protocole TCP natif 3310).
 *
 * <p>Quand {@code enabled=true}, chaque upload passe par un PING au démarrage puis
 * un scan INSTREAM avant la branche image/PDF. Quand {@code enabled=false}, le
 * service log « disabled » au démarrage et la méthode {@code scan} est un
 * no-op — utile en dev local (pas de clamd disponible).</p>
 *
 * @param enabled        active la création du client, le probe au démarrage et le scan
 *                       par upload. {@code false} = aucun appel réseau. Profil dev par défaut.
 * @param host           nom DNS du service clamd. En prod : {@code clamav-svc} (même namespace,
 *                       résolution courte). Surchargeable via env var pour pointer un namespace
 *                       tiers ou un host externe.
 * @param port           port TCP du démon clamd. Standard 3310.
 * @param timeout        délai max d'un PING (probe au démarrage et re-probe périodique).
 *                       La lib clamav-client n'expose pas de connect-timeout ; le service
 *                       enveloppe chaque ping dans un {@link java.util.concurrent.Future}
 *                       borné par cette valeur pour ne pas figer le pod si clamd est
 *                       blackholé. <b>Ne pas augmenter</b> sans raison : un PING qui dépasse
 *                       quelques secondes signale un vrai problème côté clamd ou réseau.
 * @param scanTimeout    délai max d'un scan INSTREAM. Doit couvrir le worst-case : push
 *                       d'un fichier de taille {@code maxStreamSize} sur TCP + temps de
 *                       scan côté clamd. Distinct de {@code timeout} car un PING reste
 *                       instantané alors qu'un scan de 10–50 MB prend plusieurs secondes.
 * @param maxStreamSize  taille max d'un flux scanné. <b>Doit rester ≥
 *                       {@code app.limits.multipart-ceiling}</b> sinon clamd tronque
 *                       silencieusement le stream → faux négatif.
 * @param failMode       politique en cas de scan en échec (clamd injoignable, timeout,
 *                       erreur protocole). {@code CLOSED} = refuse l'upload (503).
 *                       {@code OPEN} = laisse passer (log + WARN). Défaut prod = CLOSED.
 * @param probeInterval  période entre deux PINGs de disponibilité après le démarrage.
 *                       Alimente la gauge {@code televerse.av.available} (0/1).
 */
@ConfigurationProperties(prefix = "app.antivirus")
public record AntivirusProperties(
        boolean enabled,
        String host,
        int port,
        Duration timeout,
        Duration scanTimeout,
        DataSize maxStreamSize,
        FailMode failMode,
        Duration probeInterval
) {

    public AntivirusProperties {
        if (host == null || host.isBlank()) host = "clamav-svc";
        if (port <= 0) port = 3310;
        if (timeout == null) timeout = Duration.ofSeconds(5);
        if (scanTimeout == null) scanTimeout = Duration.ofSeconds(30);
        if (maxStreamSize == null) maxStreamSize = DataSize.ofMegabytes(60);
        if (failMode == null) failMode = FailMode.CLOSED;
        if (probeInterval == null) probeInterval = Duration.ofSeconds(30);
    }

    /**
     * Politique de gestion d'un scan en échec côté clamd (injoignable, timeout,
     * erreur protocole — <b>pas</b> une infection, qui reste toujours bloquée).
     */
    public enum FailMode {
        /** Refuse l'upload : 503 ANTIVIRUS_UNAVAILABLE. Défaut prod : on préfère refuser plutôt que stocker non scanné. */
        CLOSED,
        /** Laisse passer l'upload avec un WARN. Réservé aux environnements où l'AV est best-effort. */
        OPEN
    }
}

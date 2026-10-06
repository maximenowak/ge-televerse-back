package ch.ge.ael.geteleverse.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Réduit à une ligne les logs du client Hazelcast qui embarquent une stacktrace.
 *
 * <p>Sous forte charge, le client 5.4.0 produit deux familles de WARN dont la
 * stack fait 18 frames, toutes internes à Hazelcast — aucun frame applicatif,
 * donc rien d'actionnable :</p>
 * <ul>
 *   <li><b>double-complete</b> ({@code com.hazelcast.client.impl.spi.ClientInvocationService})
 *       — une invocation en vol est interrompue, son {@code Future} est complété
 *       en erreur, puis le timeout d'invocation tente de le compléter une seconde
 *       fois. La stack pointe toujours
 *       {@code AbstractInvocationFuture.warnIfSuspiciousDoubleCompletion} ;</li>
 *   <li><b>connexion fermée</b> ({@code com.hazelcast.client.impl.connection.tcp.TcpClientConnection})
 *       — l'authentification d'une connexion cluster expire ; la stack pointe
 *       toujours {@code TcpClientConnectionManager.authenticateOnCluster}.</li>
 * </ul>
 *
 * <p>Le filtre ne touche qu'aux événements {@code com.hazelcast.*} <b>porteurs
 * d'un {@link Throwable}</b> : les WARN déjà tenus sur une ligne (retry de
 * connexion, backoff) traversent sans modification. Le message compacté est
 * ré-émis au même niveau sous le logger {@code hazelcast}, ce qui laisse à la
 * conf ({@code logging.level.hazelcast}) le dernier mot pour tout couper.</p>
 *
 * @see ReactorFatalErrorFilter même approche pour le WARN fatal de Reactor
 */
public class HazelcastClientNoiseFilter extends TurboFilter {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("hazelcast");
    private static final String TARGET_PACKAGE = "com.hazelcast.";

    /** Borne la ligne produite : certains messages Hazelcast dépassent 1 ko. */
    private static final int MAX_LENGTH = 260;

    /**
     * Préfixe posé par Hazelcast sur chaque message : « instance [groupe] [version] ».
     * Constant pour un pod donné (l'identité du pod vient déjà des métadonnées k8s),
     * donc retiré.
     */
    private static final Pattern HZ_PREFIX = Pattern.compile("^\\S+ \\[[^\\]]*] \\[[^\\]]*] ");

    private static final Pattern OPERATION = Pattern.compile("operation=([\\w.$]+)");
    private static final Pattern OBJECT_NAME = Pattern.compile("objectName\\s*=\\s*([^,}]+)");
    private static final Pattern CURRENT_CAUSE = Pattern.compile("current value: ExceptionalResult\\{cause=([\\w.$]+)");
    private static final Pattern OFFERED_CAUSE = Pattern.compile("offered value: ExceptionalResult\\{cause=([\\w.$]+)");
    private static final Pattern CONNECTION_ID = Pattern.compile("connectionId=(\\d+)");
    private static final Pattern REMOTE_ENDPOINT = Pattern.compile("->([^}\\s]+)}");
    private static final Pattern CLOSE_REASON = Pattern.compile("closed\\. Reason: (.+)");

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (logger == null || format == null || t == null) {
            return FilterReply.NEUTRAL;
        }
        if (!logger.getName().startsWith(TARGET_PACKAGE)) {
            return FilterReply.NEUTRAL;
        }
        // Un TurboFilter est consulté avant le test de niveau : sans ce garde, un
        // événement que la conf voulait taire ressortirait par la porte de derrière.
        // getEffectiveLevel() est une simple remontée d'arbre — pas de récursion,
        // contrairement à isEnabledFor() qui rappellerait la chaîne de filtres.
        if (level.levelInt < logger.getEffectiveLevel().levelInt) {
            return FilterReply.NEUTRAL;
        }

        String message = params == null || params.length == 0
                ? format
                : MessageFormatter.arrayFormat(format, params).getMessage();
        emit(level, compact(stripPrefix(message), t));
        return FilterReply.DENY;
    }

    private void emit(Level level, String line) {
        switch (level.levelInt) {
            case Level.ERROR_INT -> LOG.error(line);
            case Level.WARN_INT -> LOG.warn(line);
            case Level.INFO_INT -> LOG.info(line);
            default -> LOG.debug(line);
        }
    }

    private static String compact(String message, Throwable t) {
        if (message.startsWith("Future.complete")) {
            return "double-complete ignoré (race interne au client)"
                    + kv("op", group(OPERATION, message))
                    + kv("objet", group(OBJECT_NAME, message))
                    + kv("initial", simpleName(group(CURRENT_CAUSE, message)))
                    + kv("tardif", simpleName(group(OFFERED_CAUSE, message)));
        }
        if (message.startsWith("ClientConnection{")) {
            return "connexion cluster fermée"
                    + kv("id", group(CONNECTION_ID, message))
                    + kv("pair", group(REMOTE_ENDPOINT, message))
                    + kv("raison", quote(group(CLOSE_REASON, message)))
                    + kv("cause", describe(t));
        }
        return truncate(firstLine(message)) + kv("cause", describe(t));
    }

    private static String stripPrefix(String message) {
        return HZ_PREFIX.matcher(message).replaceFirst("");
    }

    private static String group(Pattern pattern, String message) {
        Matcher m = pattern.matcher(message);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String kv(String key, String value) {
        return value == null || value.isEmpty() ? "" : " " + key + "=" + value;
    }

    private static String quote(String value) {
        return value == null ? null : "\"" + value + "\"";
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) return null;
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    private static String describe(Throwable t) {
        String name = t.getClass().getSimpleName();
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? name : name + ": " + truncate(firstLine(msg));
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    private static String truncate(String s) {
        return s.length() <= MAX_LENGTH ? s : s.substring(0, MAX_LENGTH) + "…";
    }
}

package ch.ge.ael.geteleverse.config.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Condense un {@code getMessage()} d'exception destiné au champ {@code message=}
 * d'une ligne de log.
 *
 * <p>Motivation : certaines exceptions d'infrastructure embarquent un dump
 * complet de leur contexte interne. Le timeout d'invocation Hazelcast tient à
 * lui seul ~400 caractères — la ligne {@code Internal error method=POST
 * path=/api/sessions …} devient illisible alors que trois champs suffisent
 * (opération, structure visée, durée écoulée). Sous charge, cette ligne sort à
 * chaque requête en échec : le volume n'est pas anecdotique.</p>
 *
 * <p>Complément côté événements de log — et non côté exceptions — de
 * {@link HazelcastClientNoiseFilter}.</p>
 */
public final class ExceptionMessages {

    /** Borne le champ {@code message=} pour les exceptions non reconnues. */
    private static final int MAX_LENGTH = 200;

    /**
     * Timeout d'invocation du client Hazelcast. La forme visée :
     * {@code ClientInvocation{clientMessage = ClientMessage{…, operation=Map.Put, …},
     * objectName = televerse-sessions, target = partition 47, …} timed out because …
     * Total elapsed time: 120044 ms.}
     *
     * <p>Écriture sans retour arrière exploitable (règle Sonar S5852, ReDoS) : chaque
     * « .*? » est remplacé par un jeton tempéré possessif {@code (?:(?!X)[\s\S])*+X},
     * qui avance jusqu'à la première occurrence du repère X sans jamais revenir en
     * arrière — temps linéaire, y compris sur un message multi-ligne. L'ancre {@code ^}
     * reste indispensable : sans elle, {@code find()} relance ce balayage à chaque
     * « ClientInvocation{ » du message (coût k·n sur une entrée hostile), et un message
     * enveloppé (« CompletionException: …ClientInvocation{… ») serait condensé en
     * perdant son préfixe au lieu d'être tronqué.</p>
     */
    private static final Pattern HZ_INVOCATION_TIMEOUT = Pattern.compile(
            "^ClientInvocation\\{"
                    + "(?:(?!\\boperation\\s*=)[\\s\\S])*+\\boperation\\s*+=\\s*+([\\w.$]++)"
                    + "(?:(?!\\bobjectName\\s*=)[\\s\\S])*+\\bobjectName\\s*+=\\s*+([^,\\s}]+)"
                    + "(?:(?!\\btimed out\\b)[\\s\\S])*+\\btimed out\\b"
                    + "(?:(?!\\bTotal elapsed time:)[\\s\\S])*+\\bTotal elapsed time:\\s*+(\\d++)\\s*+ms");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ExceptionMessages() {
    }

    /**
     * @return le message condensé sur une ligne, ou {@code null} si l'exception
     *         n'en porte pas (le champ {@code message=null} reste un signal utile).
     */
    public static String condense(Throwable t) {
        if (t == null) return null;
        String message = t.getMessage();
        if (message == null || message.isBlank()) return null;

        Matcher hz = HZ_INVOCATION_TIMEOUT.matcher(message);
        if (hz.find()) {
            return hz.group(1) + " on " + hz.group(2).trim()
                    + " timed out after " + hz.group(3) + " ms";
        }

        String flattened = WHITESPACE.matcher(message).replaceAll(" ").trim();
        return flattened.length() <= MAX_LENGTH
                ? flattened
                : flattened.substring(0, MAX_LENGTH) + "…";
    }
}

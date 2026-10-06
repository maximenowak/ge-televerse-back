package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.controller.OriginatorResolutionException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Extrait, valide et normalise l'IP d'origine d'une requête entrante à partir
 * du header {@code X-Forwarded-For} (XFF) posé par le firewall d'État.
 *
 * <p>Le firewall est l'edge de confiance unique : aucun trafic n'atteint le
 * service sans y passer. On peut donc considérer la première valeur du XFF
 * (left-most) comme l'originateur réel, même si des proxies en aval ajoutent
 * d'autres entrées au-delà (ce qui ne devrait pas se produire dans
 * l'infrastructure cible).</p>
 *
 * <p>Pas de fallback sur l'IP TCP source : en prod celle-ci est l'IP du
 * firewall, pas du client.</p>
 */
@Component
public class OriginatorResolver {

    public static final String HEADER = "X-Forwarded-For";

    /**
     * Pré-filtre lexical : on n'autorise que les caractères qu'on peut voir dans
     * une IP littérale (digits, hex, {@code .}, {@code :}, brackets IPv6, zone
     * IPv6 via {@code %}). Bloque tout ce qui ressemble à un hostname avant de
     * passer à {@link InetAddress#getByName(String)}, sinon ce dernier
     * déclencherait un DNS lookup involontaire — XFF ne doit JAMAIS contenir
     * un nom à résoudre.
     */
    private static final Pattern IP_LITERAL_CHARS = Pattern.compile("[0-9a-fA-F:.\\[\\]%]+");

    /**
     * Lit et normalise l'IP d'origine depuis le XFF de l'exchange courant.
     * Lève {@link OriginatorResolutionException} si le header est absent
     * ({@link OriginatorResolutionException#MISSING}) ou impossible à parser
     * comme adresse IP ({@link OriginatorResolutionException#INVALID}).
     */
    public String resolve(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders();
        var raw = firstNonBlank(headers);
        if (raw == null) {
            throw new OriginatorResolutionException(OriginatorResolutionException.MISSING,
                    "Missing %s header".formatted(HEADER));
        }

        var firstHop = raw.split(",", 2)[0].trim();
        if (firstHop.isEmpty()) {
            throw new OriginatorResolutionException(OriginatorResolutionException.INVALID,
                    "Empty originator address in %s".formatted(HEADER));
        }

        return normalize(firstHop);
    }

    /**
     * Première occurrence non-vide du header XFF : Spring expose chaque valeur
     * comme entrée distincte dans la liste, séparée ou non par des virgules
     * selon le proxy en amont. On prend le premier non-vide, puis on découpe
     * par virgule pour ne garder que la valeur left-most.
     */
    private static String firstNonBlank(HttpHeaders headers) {
        var values = headers.get(HEADER);
        if (values == null) return null;
        for (var v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * Valide via {@link InetAddress#getByName(String)} (sans DNS lookup pour un
     * littéral IP — Java reconnaît directement les chaînes IPv4/IPv6) puis
     * normalise les IPv6 sur leur forme canonique ({@code Inet6Address.getHostAddress})
     * pour éviter qu'un même poste apparaisse sous deux clés différentes dans
     * l'IMap (ex. {@code ::1} vs {@code 0:0:0:0:0:0:0:1}).
     */
    private static String normalize(String literal) {
        if (!IP_LITERAL_CHARS.matcher(literal).matches()) {
            throw new OriginatorResolutionException(OriginatorResolutionException.INVALID,
                    "Originator address contains non-IP characters: %s".formatted(literal));
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new OriginatorResolutionException(OriginatorResolutionException.INVALID,
                    "Unparseable originator address: %s".formatted(literal));
        }
        // Pour IPv6 comme IPv4, getHostAddress() renvoie la forme canonique
        // (zéros padding éliminés pour IPv6) — clé IMap stable indépendamment
        // de l'écriture choisie côté client.
        return addr.getHostAddress();
    }
}

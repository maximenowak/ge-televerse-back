package ch.ge.ael.geteleverse.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Verrou same-origin server-side : refuse les requêtes <strong>non-document</strong>
 * dont le header {@code Origin} ne correspond pas à l'hôte courant.
 *
 * <p>Logique (toutes méthodes, GET inclus) :</p>
 * <pre>
 *   préflight CORS                         → laisser passer (géré par l'étage CORS de Spring Security)
 *   path /documents/**                     → laisser passer (frontière = JWT, pas Origin)
 *   header Origin absent                   → laisser passer (curl, sondes K8s, vieux clients)
 *   Origin == https://&lt;Host&gt;              → laisser passer (same-origin)
 *   Origin == https://&lt;hôte extra autorisé&gt; → laisser passer
 *   sinon                                  → 403 Forbidden + log INFO + compteur
 * </pre>
 *
 * <p>Ordonné en {@link Ordered#HIGHEST_PRECEDENCE} + 10 : après le logging
 * ({@link HttpLoggingFilter}, pour que le rejet soit tracé) mais AVANT
 * la chaîne Spring Security (ordre -100), qui applique elle-même la config CORS
 * ({@link CorsConfig}, cf. {@link SecurityConfiguration}). Les préflight OPTIONS
 * sont donc laissés à cet étage CORS en aval.</p>
 *
 * <p>Le préfixe {@code /api} de {@code spring.webflux.base-path} est strippé en
 * amont de la chaîne WebFilter (cf. {@link CorsConfig} et
 * {@link SecurityConfiguration}) : le filtre raisonne donc sur le chemin
 * applicatif ({@code /sessions/**}, {@code /upload/**}, {@code /documents/**}).</p>
 *
 * <p><strong>Hypothèse d'infra :</strong> l'intégrité du header {@code Host} est
 * garantie par le firewall d'État (routage par vhost) — un {@code Host} forgé
 * n'atteint pas le service. C'est la même hypothèse de confiance que celle qui
 * sous-tend le XFF côté originator. Le scheme attendu est figé à {@code https}
 * (tout le trafic public est TLS, terminé au firewall ; le filtre est désactivé
 * en dev).</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class SameOriginWebFilter implements WebFilter {

    private static final String DOCUMENTS_PREFIX = "/documents";
    private static final String OTHER = "other";

    private final SameOriginProperties props;
    private final SessionCreateProperties createProps;
    private final Map<String, Counter> rejectionCounters;

    public SameOriginWebFilter(SameOriginProperties props, SessionCreateProperties createProps,
                               MeterRegistry meterRegistry) {
        this.props = props;
        this.createProps = createProps;
        // Compteurs pré-enregistrés sur un ensemble BORNÉ de familles de routes.
        // Le tag `path` n'utilise jamais le chemin brut : il porte le token de
        // session (un paramètre), donc taguer dessus serait à la fois à haute
        // cardinalité ET pilotable par l'attaquant — le rejet n'arrive que sur
        // des requêtes hostiles, dont le path est choisi par l'appelant. On
        // réduit au premier segment, mappé sur une allowlist, tout le reste
        // tombant dans `other` : 3 séries temporelles, pas N.
        this.rejectionCounters = Map.of(
                "sessions", rejectionCounter(meterRegistry, "sessions"),
                "upload", rejectionCounter(meterRegistry, "upload"),
                OTHER, rejectionCounter(meterRegistry, OTHER));
        log.info("SameOriginWebFilter ready enabled={} allowedExtraHosts={} createOriginSuffixes={}",
                props.enabled(), props.allowedExtraHosts(), createProps.allowedOriginSuffixes());
    }

    @Override
    public @NotNull Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!props.enabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();

        // Préflight CORS : laissé à l'étage CORS de la chaîne Security (en aval),
        // qui répond sans Access-Control-Allow-Origin pour une origine inconnue.
        if (CorsUtils.isPreFlightRequest(request)) {
            return chain.filter(exchange);
        }

        String path = request.getPath().pathWithinApplication().value();

        // /documents/** : la frontière est le JWT (audience AEL.TELEVERSE), pas
        // l'Origin. Les applications hôtes consomment le PDF en cross-origin
        // légitime ; Spring Security valide le Bearer en aval.
        if (isDocumentPath(path)) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = request.getHeaders();
        String origin = headers.getOrigin();
        // Pas d'Origin (curl, sondes, vieux clients) : laisser passer. Le but est
        // de couper le cross-origin NAVIGATEUR (qui force l'Origin réel) ; un
        // client non-navigateur qui omet Origin n'est pas la cible de ce verrou.
        if (origin == null) {
            return chain.filter(exchange);
        }

        String host = headers.getFirst(HttpHeaders.HOST);
        log.debug("Same-origin check method={} path={} origin={} host={}",
                request.getMethod(), path, origin, host);
        if (isAllowedOrigin(origin, host)) {
            return chain.filter(exchange);
        }
        // Création de session en cross-origin : autorisée depuis le realm d'État
        // configuré (suffixes ge.ch / etat-ge.ch). Le webcomponent <ge-televerse>
        // embarqué sur un formulaire hôte crée ainsi la session avec l'Origin
        // RÉEL du formulaire — infalsifiable côté usager, c'est lui qui gouverne
        // le profil de taille. Limité à POST /sessions ; toutes les autres routes
        // (upload, finalize, delete, réordonnancement…) restent verrouillées
        // same-origin.
        if (isCreateSessionRequest(request, path) && createProps.isAllowedCreateOrigin(origin)) {
            log.debug("Same-origin accepted create-session cross-origin origin={} host={} suffixes={}",
                    origin, host, createProps.allowedOriginSuffixes());
            return chain.filter(exchange);
        }
        return reject(exchange, origin, host, path);
    }

    private static boolean isCreateSessionRequest(ServerHttpRequest request, String path) {
        return HttpMethod.POST.equals(request.getMethod()) && "/sessions".equals(path);
    }

    private static boolean isDocumentPath(String path) {
        return path.equals(DOCUMENTS_PREFIX) || path.startsWith(DOCUMENTS_PREFIX + "/");
    }

    /**
     * Same-origin si l'{@code Origin} reçu vaut {@code https://<Host>} (ou
     * {@code https://<hôte supplémentaire autorisé>}). Voir la note d'infra de
     * la classe sur la confiance dans {@code Host} et le scheme figé à https.
     */
    private boolean isAllowedOrigin(String origin, String host) {
        if (host != null && origin.equals("https://" + host)) {
            log.debug("Same-origin accepted same-host origin={} host={}", origin, host);
            return true;
        }

        for (String extra : props.allowedExtraHosts()) {
            String expected = "https://" + extra.trim();

            if (origin.equals(expected)) {
                log.debug("Same-origin accepted extra-host origin={} matched={}", origin, extra);
                return true;
            }
        }

        log.debug("Same-origin not allowed origin={} host={} expectedSameOrigin={} allowedExtraHosts={}",
                origin,
                host,
                host != null ? "https://" + host : null,
                props.allowedExtraHosts());

        return false;
    }

    private Mono<Void> reject(ServerWebExchange exchange, String origin, String host, String path) {
        counterFor(path).increment();
        // INFO (pas WARN) : les scans de bots cross-origin seront massifs sans
        // être actionnables un par un — la métrique porte l'alerte. Le détail
        // (suffixes autorisés, same-origin attendu) est tracé en DEBUG par
        // isAllowedOrigin pour le diagnostic ponctuel.
        log.info("Same-origin rejection origin={} host={} path={}", origin, host, path);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }

    private Counter counterFor(String path) {
        return rejectionCounters.getOrDefault(firstSegment(path), rejectionCounters.get(OTHER));
    }

    /** Premier segment du chemin ({@code /sessions/x/y} → {@code sessions}), {@code ""} à la racine. */
    private static String firstSegment(String path) {
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private static Counter rejectionCounter(MeterRegistry registry, String pathTag) {
        return Counter.builder("televerse.same_origin.rejections")
                .description("Requêtes non-document rejetées par le verrou same-origin (Origin != Host).")
                .tag("path", pathTag)
                .register(registry);
    }
}

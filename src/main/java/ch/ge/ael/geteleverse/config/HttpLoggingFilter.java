package ch.ge.ael.geteleverse.config;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Logue chaque requête HTTP entrante et la réponse correspondante sur le topic
 * {@code http} (variable d'environnement {@code LOGLEVEL_HTTP}, partagée avec
 * {@link ch.ge.ael.geteleverse.controller.GlobalExceptionHandler}).
 *
 * <p>Filtre placé en {@link Ordered#HIGHEST_PRECEDENCE} pour englober toute la
 * chaîne — chronométrage, statut et fin de requête fidèles au comportement
 * observé côté client (incluant les annulations).</p>
 *
 * <p>Niveaux :</p>
 * <ul>
 *   <li>{@code INFO} (défaut) — ligne d'entrée ({@code → méthode URI from IP})
 *       et ligne de sortie ({@code ← statut méthode URI (durée)}). Suffisant
 *       pour suivre le trafic en production.</li>
 *   <li>{@code DEBUG} — ajoute le dump des en-têtes requête puis réponse.
 *       Utile pour diagnostiquer les pré-vols CORS et les content-types. Les
 *       en-têtes porteurs de secrets ({@code Authorization},
 *       {@code Proxy-Authorization}, {@code Cookie}, {@code Set-Cookie}) sont
 *       masqués : leur présence reste visible, jamais leur valeur.</li>
 *   <li>{@code TRACE} — non implémenté. Loger le corps des requêtes nécessite
 *       de bufferiser le body via {@code DataBufferUtils.join} avant de le
 *       réémettre vers le handler. Incompatible avec l'endpoint d'upload
 *       multipart qui accepte des fichiers jusqu'à 100 MB (risque OOM).
 *       À implémenter à la demande, avec exclusion explicite des routes
 *       d'upload.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j(topic = "http")
public class HttpLoggingFilter implements WebFilter {

    /**
     * En-têtes porteurs de secrets, jamais logués en clair (comparaison insensible à
     * la casse, comme HTTP). Un JWT AEL.TELEVERSE dans les logs suffirait à consommer
     * le document à usage unique de {@code /documents/{id}} avant l'application hôte.
     */
    private static final Set<String> MASKED_HEADERS = maskedHeaders(
            "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie");

    private static final String MASK = "[masqué]";

    private static Set<String> maskedHeaders(String... names) {
        var set = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(List.of(names));
        return Collections.unmodifiableSet(set);
    }

    @Override
    public @NotNull Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startNanos = System.nanoTime();

        // Ligne d'entrée au niveau INFO (cf. Javadoc). Le dump des en-têtes
        // reste réservé au DEBUG.
        if (log.isInfoEnabled()) {
            log.info("→ {} {} from {}", request.getMethod(), pathWithQuery(request), remoteAddr(request));
        }
        if (log.isDebugEnabled()) {
            request.getHeaders().forEach((name, values) ->
                    log.debug("    req  {}: {}", name, headerValue(name, values)));
        }

        return chain.filter(exchange).doFinally(signal -> {
            // Ligne de sortie au niveau INFO : c'est elle qui porte le statut HTTP.
            // Sans elle, un 404 forgé par un contrôleur (ResponseEntity.notFound(),
            // qui court-circuite GlobalExceptionHandler) ne laissait aucune trace.
            if (log.isInfoEnabled()) {
                long ms = (System.nanoTime() - startNanos) / 1_000_000;
                ServerHttpResponse response = exchange.getResponse();
                String status = response.getStatusCode() != null
                        ? String.valueOf(response.getStatusCode().value())
                        : "-";
                log.info("← {} {} {} ({} ms)", status, request.getMethod(), pathWithQuery(request), ms);
                if (log.isDebugEnabled()) {
                    response.getHeaders().forEach((name, values) ->
                            log.debug("    res  {}: {}", name, headerValue(name, values)));
                }
            }
        });
    }

    /** Valeur loguable d'un en-tête : masquée pour les porteurs de secrets. */
    private static String headerValue(String name, List<String> values) {
        return MASKED_HEADERS.contains(name) ? MASK : String.join(", ", values);
    }

    private static String pathWithQuery(ServerHttpRequest request) {
        var uri = request.getURI();
        return uri.getRawQuery() != null
                ? uri.getRawPath() + "?" + uri.getRawQuery()
                : uri.getRawPath();
    }

    private static String remoteAddr(ServerHttpRequest request) {
        var remote = request.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }
}

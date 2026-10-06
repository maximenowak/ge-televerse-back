package ch.ge.ael.geteleverse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Configuration CORS du back.
 *
 * <p>En production tout est <strong>same-origin</strong> : SPA et mobile sont
 * servis sur le même hôte que l'API (route {@code /api} sur
 * {@code televerser.<env>}), donc les preflight cross-origin ne
 * sont déclenchés qu'en local dev (Parcel sur {@code :1234} → backend sur
 * {@code :8080}) ou par un script externe qui tape l'API en direct. On garde
 * néanmoins une liste explicite et restreinte des origines acceptées pour ne
 * pas exposer une « surface CORS muette mais ouverte » dans les audits.
 *
 * <p>Depuis {@link SameOriginWebFilter}, le verrou same-origin <em>primaire</em>
 * est server-side (refus 403 explicite, hors navigateur) : cette config CORS
 * reste l'allowlist défensive côté navigateur (préflight) et le garde-fou si le
 * filtre était désactivé.</p>
 *
 * <p>Deux appels cross-origin légitimes existent : le webcomponent
 * {@code <ge-televerse>} crée la session par {@code POST /sessions} depuis le
 * formulaire hôte, et les applications hôtes peuvent récupérer le PDF par
 * {@code GET /documents/{id}} depuis le navigateur. Le reste du dialogue passe par
 * la SPA ouverte via {@code window.open} et {@code postMessage}.</p>
 *
 * <p>Exposée en {@link CorsConfigurationSource} — et non en {@code CorsWebFilter}
 * autonome — pour que la chaîne Spring Security l'applique elle-même, à son étage
 * CORS, AVANT ses règles d'autorisation (cf. {@link SecurityConfiguration}). Un
 * {@code CorsWebFilter} déclaré en bean sans ordre passait APRÈS la chaîne : le
 * préflight d'un {@code GET /documents/{id}} (en-tête {@code Authorization}, donc
 * non « simple ») tombait sur {@code authenticated()} et recevait un 401 sans
 * en-tête CORS — le navigateur n'envoyait jamais le GET.</p>
 */
@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props, SessionCreateProperties sessionCreate) {
        var config = new CorsConfiguration();
        // addAllowedOriginPattern accepte aussi bien les origines littérales que
        // les wildcards ({@code https://*.ge.ch}). On reste compatible avec
        // les deux formats sans forcer la liste à choisir.
        props.allowedOrigins().forEach(config::addAllowedOriginPattern);
        // DELETE pour /sessions/{token} (kill-switch beforeunload) et la
        // suppression de page ; PUT pour réordonner les pages. Sans cette
        // ligne, les preflight OPTIONS échouent pour ces deux verbes.
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);

        // /api/documents/** est ouvert à toute origine : la frontière de
        // sécurité est le JWT (audience AEL.TELEVERSE), pas le header Origin.
        // Les applications hôtes qui consomment le PDF tournent sur des
        // domaines variés (formulaires métier de l'État), on ne maintient
        // donc pas d'allowlist pour ce point d'entrée.
        var documents = new CorsConfiguration();
        documents.addAllowedOriginPattern("*");
        documents.setAllowedMethods(List.of("GET", "OPTIONS"));
        documents.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        documents.setAllowCredentials(false);

        // Création de session : le webcomponent <ge-televerse> embarqué sur un
        // formulaire hôte appelle POST /sessions en cross-origin, pour que le
        // navigateur estampille l'Origin RÉEL du formulaire (qui gouverne le
        // profil de taille côté serveur). On autorise le realm d'État configuré
        // (suffixes ge.ch → patterns https://*.ge.ch) EN PLUS de
        // l'allowlist same-origin, pour la création UNIQUEMENT : ce bloc ne
        // s'applique qu'à `/sessions` exact ; /sessions/{token}/** (upload,
        // finalize, delete…) reste sur la config restreinte `config`.
        var create = new CorsConfiguration();
        props.allowedOrigins().forEach(create::addAllowedOriginPattern);
        sessionCreate.corsOriginPatterns().forEach(create::addAllowedOriginPattern);
        create.setAllowedMethods(List.of("POST", "OPTIONS"));
        create.setAllowedHeaders(List.of("*"));
        create.setAllowCredentials(false);

        var source = new UrlBasedCorsConfigurationSource();
        // Appliquée dans la chaîne WebFilter (étage CORS de Spring Security) : le
        // préfixe /api de spring.webflux.base-path est strippé en amont, on
        // enregistre donc sur le chemin applicatif sans préfixe. Le premier pattern
        // qui matche gagne — on enregistre du plus spécifique au plus large :
        // documents, puis la création de session, puis le fallback restreint.
        source.registerCorsConfiguration("/documents/**", documents);
        source.registerCorsConfiguration("/sessions", create);
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
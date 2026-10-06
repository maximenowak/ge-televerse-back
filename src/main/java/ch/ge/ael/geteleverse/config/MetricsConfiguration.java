package ch.ge.ael.geteleverse.config;

import ch.ge.ael.geteleverse.service.SessionService;
import ch.ge.ael.geteleverse.service.ThumbnailCacheService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.TreeSet;

/**
 * Définit les métriques Micrometer transverses à l'application :
 * jauge des sessions actives (Hazelcast IMap, vue cluster-wide) et
 * histogrammes de volumétrie d'upload / finalisation, exposés via
 * {@code /actuator/prometheus}.
 *
 * <p>Les métriques propres à un service (Ghostscript, fichiers tmp) sont
 * enregistrées dans le service lui-même, plus près de leur état.</p>
 */
@Configuration
public class MetricsConfiguration {

    /**
     * Sessions actives — délègue à {@link SessionService#activeSessionCount()},
     * qui interroge l'IMap Hazelcast distribué : chaque pod retourne le même
     * total. En cas de présence de plusieurs pods Prometheus va voir N points
     * identiques par scrape, à dédupliquer côté Grafana via {@code max() by (...)}.
     */
    @Bean
    Gauge activeSessionsGauge(MeterRegistry meterRegistry, SessionService sessionService) {
        return Gauge.builder("televerse.active.sessions", sessionService, SessionService::activeSessionCount)
                .description("Sessions actives dans l'IMap Hazelcast (cluster-wide).")
                .register(meterRegistry);
    }

    /**
     * Vignettes en cache — délègue à
     * {@link ThumbnailCacheService#cachedThumbnailCount()}. Multipliée par ~8 ko
     * (taille mesurée d'une vignette 120 px), donne l'empreinte mémoire du cache
     * sur le cluster. À croiser avec le compteur
     * {@code televerse_thumbnail_cache_total{result="miss"}} : un taux de défaut
     * qui monte alors que la jauge plafonne signale que l'éviction LRU mord,
     * donc qu'il faut relever {@code size} côté conf cluster.
     *
     * <p>Même sémantique de sentinelle {@code -1} que la jauge des sessions
     * (cluster injoignable), et même déduplication à faire côté Grafana.</p>
     */
    @Bean
    Gauge cachedThumbnailsGauge(MeterRegistry meterRegistry, ThumbnailCacheService thumbnailCache) {
        return Gauge.builder("televerse.thumbnail.cache.entries", thumbnailCache,
                        ThumbnailCacheService::cachedThumbnailCount)
                .description("Vignettes présentes dans l'IMap Hazelcast (cluster-wide).")
                .register(meterRegistry);
    }

    /**
     * Octets reçus en multipart par {@code POST /api/upload/{token}}, mesurés
     * <i>avant</i> toute conversion ou recompression. Reflète la consommation
     * réseau réelle du back-end vis-à-vis des clients mobiles.
     */
    @Bean
    DistributionSummary uploadRawBytes(MeterRegistry meterRegistry) {
        return DistributionSummary.builder("televerse.upload.raw.bytes")
                .description("Volume brut uploadé par requête, avant conversion/recompression.")
                .baseUnit("bytes")
                .serviceLevelObjectives(500_000, 2_000_000, 10_000_000)
                .register(meterRegistry);
    }

    /**
     * Nombre de pages dans une session au moment de la finalisation.
     * Permet d'analyser la distribution réelle pour calibrer {@code app.limits.max-pages}
     * et anticiper la charge de fusion PDF.
     */
    @Bean
    DistributionSummary sessionPagesCount(MeterRegistry meterRegistry) {
        return DistributionSummary.builder("televerse.session.pages.count")
                .description("Nombre de pages d'une session à la finalisation.")
                .serviceLevelObjectives(1, 5, 20)
                .register(meterRegistry);
    }

    /**
     * Bornes de l'histogramme {@code televerse.session.final.bytes}, dérivées
     * de la configuration : seuil de recompression Ghostscript
     * ({@code app.ghostscript.size-threshold}) + plafond de chaque profil de
     * tee-shirt size ({@code app.limits.profiles.*.max-total-size}), triés et
     * dédupliqués. Toute évolution de ces valeurs dans {@code application.yml}
     * réaligne automatiquement les buckets.
     *
     * <p>Méthode publique pour que {@code SessionController} construise à la
     * volée des {@code DistributionSummary} taggés par {@code sizeProfile} sur
     * le même nom de métrique — Micrometer dédoublonne par {@code name+tags},
     * pas de coût de re-création.
     */
    public static double[] sessionFinalBytesSlos(LimitsProperties limits, GhostscriptProperties ghostscript) {
        var slos = new TreeSet<Double>();
        slos.add((double) ghostscript.sizeThreshold().toBytes());
        limits.profiles().values().forEach(p -> slos.add((double) p.maxTotalSize().toBytes()));
        return slos.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Bornes de l'histogramme {@code televerse.edit.duration}, dérivées de
     * {@code app.edit-timeout} : quelques paliers fixes utiles pour lire la queue
     * de distribution, puis la moitié du budget et le budget lui-même. Toute
     * évolution du timeout réaligne automatiquement les buckets, et les paliers
     * fixes au-delà du budget sont écartés — un bucket qu'aucune requête ne peut
     * atteindre n'est que du bruit dans Prometheus.
     *
     * <p>Méthode publique et statique pour la même raison que
     * {@link #sessionFinalBytesSlos} : {@code SessionController} construit ses
     * {@code Timer} à la volée, taggés par opération et par issue, sur un nom de
     * métrique unique — Micrometer dédoublonne par {@code name+tags}.</p>
     */
    public static Duration[] editDurationSlos(Duration editTimeout) {
        var slos = new TreeSet<Duration>();
        for (var candidate : List.of(Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            if (candidate.compareTo(editTimeout) < 0) slos.add(candidate);
        }
        var half = editTimeout.dividedBy(2);
        if (!half.isZero() && !half.isNegative()) slos.add(half);
        slos.add(editTimeout);
        return slos.toArray(Duration[]::new);
    }

    /**
     * Connexions SSE actuellement ouvertes sur ce pod
     * ({@link SessionService#localSinkCount()}). Différent de
     * {@code televerse.active.sessions} : ce dernier compte les entrées de
     * l'IMap Hazelcast (cluster-wide) tandis qu'ici on mesure les sinks SSE
     * locaux — utile pour observer la répartition des consommateurs SSE par
     * pod côté load-balancer.
     */
    @Bean
    Gauge sseSinksActive(MeterRegistry meterRegistry, SessionService sessionService) {
        return Gauge.builder("televerse.sse.sinks.active", sessionService, SessionService::localSinkCount)
                .description("Connexions SSE actives sur ce pod (sinks locaux).")
                .register(meterRegistry);
    }

    /**
     * Bindings « une IP d'origine → un token » actifs dans l'IMap Hazelcast
     * {@code televerse-originators} (vue cluster-wide). Doit suivre la courbe
     * de {@code televerse.active.sessions} à un facteur près lié aux sessions
     * créées avant l'activation de la feature. Un écart durable signale soit
     * un bug de cleanup, soit des sessions héritées qu'on n'a pas tracées.
     */
    @Bean
    Gauge originatorBindingsActive(MeterRegistry meterRegistry, SessionService sessionService) {
        return Gauge.builder("televerse.originator.bindings.active", sessionService,
                        SessionService::activeOriginatorBindingCount)
                .description("Bindings actifs dans l'IMap televerse-originators (cluster-wide).")
                .register(meterRegistry);
    }

    /**
     * N'exporte le timer {@code http.server.requests} que pour les routes
     * {@code /api/upload/*} : ~99 % du trafic, seul signal pertinent pour
     * dimensionner les pods (autoscaling HPA sur la latence/le RPS d'upload).
     * Les autres routes (sessions, finalize, actuator, SSE) restent observées
     * par leurs métriques métier dédiées ({@code televerse.*}, {@code resilience4j.*}).
     */
    @Bean
    MeterFilter onlyUploadHttpRequests() {
        return MeterFilter.denyUnless(id -> {
            if (!"http.server.requests".equals(id.getName())) return true;
            String uri = id.getTag("uri");
            String method = id.getTag("method");
            return uri != null && method != null && (
                    method.equals("DELETE") ||
                    method.equals("POST") ||
                    method.equals("PUT") ||
                    (method.equals("GET") && uri.endsWith("thumbnail")) ||
                    (method.equals("GET") && uri.endsWith("preview.pdf"))
            );
        });
    }

    /**
     * Supprime le LongTaskTimer {@code http.server.requests.active} : ses tags
     * (notamment {@code uri}) sont figés au démarrage de la requête, avant la
     * résolution de route, et restent donc à {@code UNKNOWN}. La métrique n'a
     * pas d'utilité opérationnelle ici — le nombre de requêtes en vol par pod
     * est déjà couvert par {@code reactor.netty.http.server.connections.active}.
     */
    @Bean
    MeterFilter denyActiveHttpRequests() {
        return MeterFilter.deny(id -> "http.server.requests.active".equals(id.getName()));
    }
}

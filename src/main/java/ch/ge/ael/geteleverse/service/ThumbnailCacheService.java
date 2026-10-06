package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.ThumbnailCacheProperties;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Cache distribué des vignettes de pages, adossé à un {@link IMap} Hazelcast.
 *
 * <p><b>Pourquoi pas S3.</b> Les vignettes sont l'objet le plus fréquemment lu
 * du flux (une requête par page à chaque ouverture de la liste) alors qu'elles
 * pèsent ~8 ko et vivent le temps d'une session. Les servir depuis S3 chargeait
 * le lien réseau vers le stockage objet d'un trafic sans rapport avec la valeur
 * livrée (le PDF composite final), et c'est ce trafic qui décrochait vers 4000
 * usagers simultanés (sockets coupées, 404 suspects). Hazelcast est déjà sur le
 * chemin critique pour l'état de session : y poser les vignettes n'ajoute pas de
 * dépendance, et l'{@code IMap} étant distribué, une vignette écrite par le pod
 * qui a reçu l'upload est lisible par n'importe quel pod servant la requête
 * suivante — ce que le disque local du pod ne permettrait pas.</p>
 *
 * <p><b>Cache, pas source de vérité.</b> Toute vignette est reconstructible à
 * partir du PDF de la page, qui reste sur S3. Un défaut de cache (expiration,
 * éviction mémoire, perte d'un membre du cluster, Hazelcast injoignable) n'est
 * donc jamais une erreur fonctionnelle : il coûte une régénération à la volée.
 * C'est ce qui autorise la configuration côté cluster à être agressive
 * ({@code backup-count: 0}, éviction LRU sur seuil) et borne définitivement
 * l'empreinte mémoire — le cluster évince au lieu de saturer.</p>
 *
 * <p><b>Format des clés.</b> Identiques aux anciennes clés S3
 * ({@code <token>/page-001-thumb.jpg}, puis une clé neuve par version éditée,
 * {@code <token>/page-001-3f2a9c1e-thumb.jpg}). Elles restent portées par
 * {@link ch.ge.ael.geteleverse.model.PageInfo#thumbnailKey()} : le changement de
 * support est invisible pour la logique d'undo/reset, et le préfixe {@code token/}
 * permet la purge groupée en fin de session.</p>
 *
 * <p><b>Sérialisation.</b> Les valeurs sont des {@code byte[]}, type natif
 * Hazelcast : aucune classe applicative n'a besoin d'être déployée sur le
 * classpath des membres du cluster — contrainte qui avait déjà imposé le
 * stockage des sessions en {@code HazelcastJsonValue} plutôt qu'en objets
 * sérialisés (cf. {@link SessionService#reservePageId}).</p>
 */
@Service
@Slf4j(topic = "pdfprocessing")
public class ThumbnailCacheService {

    /** Nom de l'{@code IMap} — doit correspondre à la {@code map} configurée côté cluster. */
    public static final String MAP_NAME = "televerse-thumbnails";

    /**
     * Suppressions concurrentes maximales dans un {@link #deleteAll(Collection)}.
     *
     * <p>Borne le nombre d'invocations Hazelcast en vol pendant une purge. Sans elle,
     * {@code flatMap} prend sa concurrence par défaut (256, {@code Queues.SMALL_BUFFER_SIZE})
     * et une purge de session émettait autant de {@code deleteAsync} simultanés qu'elle
     * avait de clés. Cumulé au bulkhead de finalisation
     * ({@code app.admission.finalize.max-concurrent}), le pic dépassait de deux ordres de
     * grandeur {@code hazelcast.client.max.concurrent.invocations}, d'où des
     * {@code HazelcastOverloadException} en test de charge sur {@code /finalize}.
     *
     * <p>Valeur basse assumée : cette purge est du best effort sur un cache dont le TTL
     * est le filet (cf. javadoc de classe). La rendre lente ne coûte rien ; la rendre
     * illimitée coûtait la disponibilité du client Hazelcast, donc aussi les écritures
     * de session qui partagent le même quota d'invocations.
     */
    static final int PURGE_CONCURRENCY = 4;

    private final HazelcastInstance hazelcast;
    private final ThumbnailCacheProperties properties;
    private final long ttlSeconds;

    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    private IMap<String, byte[]> thumbnailsMap;

    public ThumbnailCacheService(HazelcastInstance hazelcast,
                                 ThumbnailCacheProperties properties,
                                 MeterRegistry meterRegistry) {
        this.hazelcast = hazelcast;
        this.properties = properties;
        this.ttlSeconds = Math.max(1, properties.ttl().toSeconds());

        this.hits = Counter.builder("televerse.thumbnail.cache")
                .description("Accès au cache de vignettes Hazelcast, par issue.")
                .tag("result", "hit")
                .register(meterRegistry);
        this.misses = Counter.builder("televerse.thumbnail.cache")
                .description("Accès au cache de vignettes Hazelcast, par issue.")
                .tag("result", "miss")
                .register(meterRegistry);
        this.errors = Counter.builder("televerse.thumbnail.cache")
                .description("Accès au cache de vignettes Hazelcast, par issue.")
                .tag("result", "error")
                .register(meterRegistry);
    }

    @PostConstruct
    void init() {
        if (!properties.enabled()) {
            log.info("Thumbnail cache disabled — vignettes régénérées à la volée depuis S3");
            return;
        }
        thumbnailsMap = hazelcast.getMap(MAP_NAME);
        log.info("Thumbnail cache ready map={} ttl={}s", MAP_NAME, ttlSeconds);
    }

    /** Vrai si le cache est actif (propriété activée et map obtenue au démarrage). */
    public boolean isEnabled() {
        return thumbnailsMap != null;
    }

    /**
     * Écrit une vignette avec le TTL configuré.
     *
     * <p>Best-effort : un échec Hazelcast est logué puis absorbé. Une vignette
     * non écrite se régénère à la lecture — la faire remonter ferait échouer un
     * upload par ailleurs réussi, ce que l'ancien chemin S3 évitait déjà.</p>
     */
    public Mono<Void> put(String key, byte[] bytes) {
        if (!isEnabled() || key == null || bytes == null || bytes.length == 0) {
            return Mono.empty();
        }
        return Mono.fromCompletionStage(() ->
                        thumbnailsMap.setAsync(key, bytes, ttlSeconds, TimeUnit.SECONDS))
                .doOnSuccess(unused -> log.debug("Thumbnail cached key={} bytes={}", key, bytes.length))
                .onErrorResume(e -> {
                    errors.increment();
                    log.warn("Thumbnail cache put failed key={} error={}", key, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Lit une vignette. Émet {@link Mono#empty()} sur défaut de cache <em>et</em>
     * sur erreur Hazelcast : les deux se traitent pareil côté appelant, par une
     * régénération. Les deux cas restent distingués en métrique
     * ({@code result=miss} vs {@code result=error}).
     */
    public Mono<byte[]> get(String key) {
        if (!isEnabled() || key == null) {
            return Mono.empty();
        }
        return Mono.fromCompletionStage(() -> thumbnailsMap.getAsync(key))
                .doOnNext(bytes -> hits.increment())
                .switchIfEmpty(Mono.<byte[]>fromRunnable(misses::increment))
                .onErrorResume(e -> {
                    errors.increment();
                    log.warn("Thumbnail cache get failed key={} error={}", key, e.getMessage());
                    return Mono.empty();
                });
    }

    /** Supprime une entrée. Best-effort. */
    public Mono<Void> delete(String key) {
        if (!isEnabled() || key == null) {
            return Mono.empty();
        }
        return Mono.fromCompletionStage(() -> thumbnailsMap.deleteAsync(key))
                .onErrorResume(e -> {
                    errors.increment();
                    log.warn("Thumbnail cache delete failed key={} error={}", key, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Supprime un lot d'entrées en parallèle. Utilisé en fin de session
     * (finalisation, suppression de page, expiration) pour rendre la mémoire
     * immédiatement plutôt que d'attendre le TTL.
     *
     * <p>Le lot est construit par l'appelant à partir des clés portées par la
     * session — pas par un scan de la map. À 4000 sessions simultanées, un
     * {@code removeAll(Predicate)} par session déclencherait autant de balayages
     * distribués du keyset ; une suppression par clé connue reste en O(pages).</p>
     *
     * <p>Les suppressions sont émises {@value #PURGE_CONCURRENCY} à la fois, quelle que
     * soit la taille du lot : le coût en invocations Hazelcast d'une purge est donc
     * constant, et non proportionnel au nombre de pages de la session. Passer TOUTES les
     * clés d'une session en un seul appel est ce qui rend cette borne effective — un
     * appel par page rendrait le pic à nouveau proportionnel.</p>
     */
    public Mono<Void> deleteAll(Collection<String> keys) {
        if (!isEnabled() || keys == null || keys.isEmpty()) {
            return Mono.empty();
        }
        // Le filtrage doit précéder fromIterable : Reactive Streams interdit les
        // éléments null dans un flux, l'itération lèverait avant tout .filter().
        var nonNull = keys.stream().filter(java.util.Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(nonNull)
                .flatMap(this::delete, PURGE_CONCURRENCY)
                .then();
    }

    /**
     * Nombre d'entrées dans le cache (vue cluster-wide), ou -1 si Hazelcast est
     * injoignable. Même sémantique de sentinelle que
     * {@link SessionService#activeSessionCount()} : distingue « cache vide » de
     * « cluster injoignable » côté Grafana.
     */
    public int cachedThumbnailCount() {
        if (!isEnabled()) return -1;
        try {
            return thumbnailsMap.size();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}

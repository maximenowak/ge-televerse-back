package ch.ge.ael.geteleverse.config;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientClasspathYamlConfig;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.ClasspathYamlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Slf4j
public class HazelcastConfiguration {

    /**
     * DEV profile: start an embedded Hazelcast member using hazelcast-dev.yaml
     * (same behaviour as -Dhazelcast.config=classpath:hazelcast-dev.yaml)
     */
    @Bean(name = "hazelcastInstance")
    @Profile("dev")
    public HazelcastInstance devHazelcastInstance() {
        Config config = new ClasspathYamlConfig("hazelcast-dev.yaml");
        log.info("Hazelcast starting mode=embedded cluster={}", config.getClusterName());
        return Hazelcast.newHazelcastInstance(config);
    }

    /**
     * Non-dev profiles: use Hazelcast CLIENT with hazelcast-client.yaml
     * (pure client connecting to the cluster).
     *
     * <p>Le cluster Hazelcast vit dans un namespace dédié, distinct de celui
     * de l'app et variable selon l'environnement (dev / uat / prod). On utilise
     * le mode DNS-based discovery du plugin Kubernetes : pas d'appel à l'API
     * Kubernetes, donc aucun RBAC à provisionner cross-namespace ; un simple
     * SRV lookup sur le headless service suffit.</p>
     *
     * <p>La FQDN ({@code hazelcast.<namespace>.svc.cluster.local}) est construite
     * à partir de la propriété Spring {@code app.hazelcast.namespace} (alimentée
     * par l'env var {@code HAZELCAST_NAMESPACE} du manifest Helm) et surcharge
     * la conf statique chargée du YAML. Échec rapide au démarrage si l'env var
     * est absente.</p>
     */
    @Bean(name = "hazelcastInstance")
    @Profile("!dev")
    public HazelcastInstance clientHazelcastInstance(
            @Value("${app.hazelcast.namespace}") String namespace) {
        ClientConfig clientConfig = new ClientClasspathYamlConfig("hazelcast-client.yaml");
        String serviceDns = "hazelcast." + namespace + ".svc.cluster.local";
        clientConfig.getNetworkConfig().getKubernetesConfig().setProperty("service-dns", serviceDns);
        log.info("Hazelcast starting mode=client cluster={} serviceDns={}",
                clientConfig.getClusterName(), serviceDns);
        return HazelcastClient.newHazelcastClient(clientConfig);
    }
}

package ch.ge.ael.geteleverse.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;

@Configuration
@Profile("!dev")
@Slf4j
@RequiredArgsConstructor
public class S3Configuration {

    private final AwsProperties awsProperties;
    private final SslBundles sslBundles;

    @Bean
    public S3MetricsPublisher s3MetricsPublisher(MeterRegistry meterRegistry) {
        return new S3MetricsPublisher(meterRegistry);
    }

    @Bean
    public S3AsyncClient s3AsyncClient(S3MetricsPublisher s3MetricsPublisher) {
        return S3AsyncClient.builder()
                .region(Region.of(awsProperties.region().staticRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                awsProperties.credentials().accessKey(),
                                awsProperties.credentials().secretKey())))
                .httpClient(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(awsProperties.http().maxConcurrency())
                        .maxPendingConnectionAcquires(awsProperties.http().maxPendingAcquires())
                        .readTimeout(awsProperties.http().readTimeout())
                        .connectionAcquisitionTimeout(awsProperties.http().connectionAcquisitionTimeout())
                        // Désactive l'auto-discovery du proxy HTTP. Depuis le SDK 2.21 le
                        // client Netty honore https.proxyHost / HTTPS_PROXY quand aucune
                        // ProxyConfiguration explicite n'est posée
                        .proxyConfiguration(ProxyConfiguration.builder()
                                .useSystemPropertyValues(false)
                                .useEnvironmentVariableValues(false)
                                .build())
                        .tlsTrustManagersProvider(() -> sslBundles.getBundle("trustStore")
                                .getManagers()
                                .getTrustManagers())
                        .build())
                .serviceConfiguration(
                        software.amazon.awssdk.services.s3.S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .chunkedEncodingEnabled(false)
                                .build())
                .endpointOverride(URI.create(awsProperties.s3().endpoint()))
                // Bornes de durée d'appel, absentes jusqu'ici : readTimeout ne coupe
                // qu'une lecture socket INACTIVE, si bien qu'un GET/PUT au
                // goutte-à-goutte pouvait durer sans limite et laisser en vol la
                // requête HTTP qui l'attendait. Cf. AwsProperties.HttpProperties
                // pour le dimensionnement des deux valeurs.
                .overrideConfiguration(o -> o
                        .apiCallTimeout(awsProperties.http().apiCallTimeout())
                        .apiCallAttemptTimeout(awsProperties.http().apiCallAttemptTimeout())
                        .addMetricPublisher(s3MetricsPublisher))
                .build();
    }
}
package ch.ge.ael.geteleverse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "spring.cloud.aws")
public record AwsProperties(
        RegionProperties region,
        CredentialsProperties credentials,
        S3Properties s3,
        @DefaultValue HttpProperties http
) {
    public record RegionProperties(String staticRegion) {}
    public record CredentialsProperties(String accessKey, String secretKey) {}
    public record S3Properties(String bucket, String endpoint) {}

    /**
     * Paramètres du pool de connexions HTTP Netty du SDK AWS ({@code S3AsyncClient}).
     *
     * <p>Ces valeurs plafonnent le nombre de requêtes S3 en vol <b>par pod</b> :
     * {@code maxConcurrency} connexions actives, plus {@code maxPendingAcquires}
     * requêtes en file d'attente ; au-delà, le SDK rejette immédiatement avec
     * « Maximum pending connection acquisitions exceeded ». À dimensionner selon
     * la capacité de l'endpoint S3 on-prem, pas selon le nombre de pods.</p>
     *
     * <p>Les défauts reprennent les valeurs historiquement codées en dur
     * (comportement inchangé si la section {@code http} est absente).</p>
     *
     * <p>{@code readTimeout} borne une <b>lecture socket</b> inactive : une réponse
     * qui arrive au goutte-à-goutte ne le déclenche jamais. Les deux bornes de
     * durée réelle manquaient, ce qui rendait un appel S3 potentiellement infini —
     * l'un des chemins par lesquels une édition de page restait en vol sans jamais
     * répondre :</p>
     * <ul>
     *   <li>{@code apiCallAttemptTimeout} — plafond en temps mural d'<b>une</b>
     *       tentative, retries du SDK compris. Le baisser accélère le basculement
     *       sur une connexion malade, mais doit rester au-dessus du temps de
     *       transfert légitime du plus gros objet écrit (le PDF composite d'une
     *       session XL, jusqu'à 200 MB).</li>
     *   <li>{@code apiCallTimeout} — plafond de l'appel complet, toutes tentatives
     *       cumulées. Défaut aligné sur {@code app.finalize-timeout} (2 min) : aucun
     *       appel S3 unitaire ne peut légitimement dépasser le budget de la
     *       finalisation entière, qui en enchaîne plusieurs.</li>
     * </ul>
     */
    public record HttpProperties(
            @DefaultValue("1000") int maxConcurrency,
            @DefaultValue("2000") int maxPendingAcquires,
            @DefaultValue("60s") Duration readTimeout,
            @DefaultValue("10s") Duration connectionAcquisitionTimeout,
            @DefaultValue("120s") Duration apiCallTimeout,
            @DefaultValue("60s") Duration apiCallAttemptTimeout
    ) {}
}

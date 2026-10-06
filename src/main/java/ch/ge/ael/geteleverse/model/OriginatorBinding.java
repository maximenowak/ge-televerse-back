package ch.ge.ael.geteleverse.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;

/**
 * Valeur stockée dans l'IMap Hazelcast {@code televerse-originators} sous la
 * clé {@code originatorIp} (cf. {@link UploadSession#originatorIp()}). Lie
 * une IP d'origine à la session active correspondante.
 *
 * <p>Le {@code sessionToken} n'est PAS exposé côté réponse 409 : on ne veut
 * pas qu'une IP voisine (NAT partagé) puisse deviner le token d'une session
 * ouverte par un poste tiers. Seul {@link #expiresAt()} est rendu au front
 * pour permettre l'affichage d'un compte à rebours.</p>
 */
public record OriginatorBinding(
        String sessionToken,
        Instant createdAt,
        Instant expiresAt
) implements Serializable {

    @JsonCreator
    public OriginatorBinding(
            @JsonProperty("sessionToken") String sessionToken,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("expiresAt") Instant expiresAt
    ) {
        this.sessionToken = sessionToken;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}

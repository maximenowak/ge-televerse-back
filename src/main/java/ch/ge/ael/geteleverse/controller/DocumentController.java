package ch.ge.ael.geteleverse.controller;

import ch.ge.ael.geteleverse.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.regex.Pattern;

/**
 * API proxy de récupération du PDF composite finalisé, exposée aux applications
 * hôtes (formulaires métier) via OIDC. Une session s'ouvre côté front, l'utilisateur
 * scanne ses pages depuis son téléphone, finalize fusionne le tout sur S3 et expose
 * un {@code documentId}. L'application hôte récupère ensuite le PDF via cet endpoint
 * en présentant un JWT dont le claim {@code aud} contient {@code AEL.TELEVERSE}.
 *
 * <p><strong>Consommation unique</strong> : un GET réussi supprime le PDF sur S3
 * juste après l'écriture de la réponse. Un second appel renvoie 410 Gone. Si la
 * réponse échoue (déconnexion client), la suppression n'a pas lieu et un nouveau
 * GET peut réussir.</p>
 *
 * <p><strong>Suppression explicite</strong> : un DELETE permet à l'application hôte
 * d'abandonner un document sans le récupérer (annulation de formulaire, nettoyage).
 * Idempotent, il renvoie 204 que le document existe encore ou non.</p>
 */
@RestController
@RequestMapping("/documents")
@Slf4j
public class DocumentController {

    /**
     * Format attendu pour {@code documentId} : {@code <uuid>.pdf}, généré par
     * {@code UploadSession.newSession}. Toute autre forme est rejetée en 400
     * pour éviter qu'un appelant ne soit en mesure de lire une clé S3 arbitraire
     * via le proxy.
     */
    private static final Pattern DOCUMENT_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.pdf$");

    private final StorageService storageService;

    public DocumentController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/{documentId}")
    public Mono<Void> getDocument(@PathVariable String documentId, ServerHttpResponse response) {
        if (!DOCUMENT_ID_PATTERN.matcher(documentId).matches()) {
            return Mono.error(new IllegalArgumentException("Invalid documentId format"));
        }

        return storageService.fetchFromS3(documentId)
                .flatMap(bytes -> writeAndDelete(documentId, bytes, response))
                .onErrorResume(NoSuchKeyException.class, e -> {
                    log.info("Document gone documentId={}", documentId);
                    response.setStatusCode(HttpStatus.GONE);
                    return Mono.empty();
                });
    }

    /**
     * Suppression explicite du PDF composite finalisé.
     *
     * <p>Idempotent : S3 {@code DeleteObject} réussit que la clé existe ou non, donc
     * l'endpoint répond {@code 204 No Content} dans les deux cas. On ne renvoie pas de
     * 404/410 sur document absent : cela exigerait un aller-retour d'existence en amont
     * et casserait l'idempotence attendue d'un DELETE. Même garde-fou de format que le
     * GET pour empêcher la suppression d'une clé S3 arbitraire via le proxy.</p>
     */
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDocument(@PathVariable String documentId) {
        if (!DOCUMENT_ID_PATTERN.matcher(documentId).matches()) {
            return Mono.error(new IllegalArgumentException("Invalid documentId format"));
        }

        return storageService.deleteFromS3(documentId)
                .doOnSuccess(unused -> log.info("Document deleted documentId={}", documentId));
    }

    private Mono<Void> writeAndDelete(String documentId, byte[] bytes, ServerHttpResponse response) {
        response.getHeaders().setContentType(MediaType.APPLICATION_PDF);
        response.getHeaders().setContentLength(bytes.length);
        response.getHeaders().setContentDisposition(
                ContentDisposition.attachment().filename("document.pdf").build());

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer))
                .then(storageService.deleteFromS3(documentId))
                .doOnSuccess(unused -> log.info("Document delivered and deleted documentId={} bytes={}",
                        documentId, bytes.length));
    }
}

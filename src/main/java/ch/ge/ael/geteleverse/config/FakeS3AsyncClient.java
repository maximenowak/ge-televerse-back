package ch.ge.ael.geteleverse.config;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Implémentation minimaliste de {@link S3AsyncClient} pour le profil dev :
 * stocke les objets sur le système de fichiers local. Seules les méthodes
 * réellement utilisées par {@link ch.ge.ael.geteleverse.service.StorageService}
 * sont implémentées (get, put, delete) ; les autres lèvent
 * {@link UnsupportedOperationException} via les implémentations par défaut de
 * l'interface SDK — toute nouvelle opération employée par StorageService doit
 * donc être ajoutée ici, sans quoi elle ne casse qu'en profil dev.
 */
@Slf4j
public class FakeS3AsyncClient implements S3AsyncClient {

    private final Path root;

    public FakeS3AsyncClient(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String serviceName() {
        return "s3";
    }

    @Override
    public void close() {
        // rien à libérer
    }

    @Override
    public CompletableFuture<PutObjectResponse> putObject(PutObjectRequest req, AsyncRequestBody body) {
        var path = pathFor(req.key());
        return Flux.from(body)
                .reduce(new ByteArrayOutputStream(), (baos, bb) -> {
                    var arr = new byte[bb.remaining()];
                    bb.get(arr);
                    try {
                        baos.write(arr);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return baos;
                })
                .flatMap(baos -> Mono.fromCallable(() -> {
                    Files.createDirectories(path.getParent());
                    Files.write(path, baos.toByteArray());
                    log.debug("FakeS3 PUT key={} bytes={}", req.key(), baos.size());
                    return PutObjectResponse.builder().eTag("\"fake\"").build();
                }).subscribeOn(Schedulers.boundedElastic()))
                .toFuture();
    }

    @Override
    public <T> CompletableFuture<T> getObject(GetObjectRequest req,
                                              AsyncResponseTransformer<GetObjectResponse, T> transformer) {
        var future = transformer.prepare();
        var path = pathFor(req.key());
        if (!Files.exists(path)) {
            transformer.exceptionOccurred(NoSuchKeyException.builder()
                    .message("Objet introuvable: " + req.key())
                    .build());
            return future;
        }
        try {
            var bytes = Files.readAllBytes(path);
            transformer.onResponse(GetObjectResponse.builder()
                    .contentLength((long) bytes.length)
                    .build());
            transformer.onStream(SdkPublisher.adapt(Flux.just(ByteBuffer.wrap(bytes))));
            log.debug("FakeS3 GET key={} bytes={}", req.key(), bytes.length);
        } catch (IOException e) {
            transformer.exceptionOccurred(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<DeleteObjectResponse> deleteObject(DeleteObjectRequest req) {
        var path = pathFor(req.key());
        try {
            var existed = Files.deleteIfExists(path);
            log.debug("FakeS3 DELETE key={} existed={}", req.key(), existed);
            return CompletableFuture.completedFuture(DeleteObjectResponse.builder().build());
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Path pathFor(String key) {
        var resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Clé S3 hors du répertoire racine: " + key);
        }
        return resolved;
    }
}

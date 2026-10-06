package ch.ge.ael.geteleverse.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le client S3 du profil dev tient le contrat S3 des trois opérations qu'emploie
 * {@code StorageService} : les éditions écrivent chaque version sous une clé neuve,
 * et une version refusée ou supprimée doit se lire comme absente
 * ({@link NoSuchKeyException}), comme sur le vrai bucket.
 */
class FakeS3AsyncClientTest {

    @TempDir
    Path root;

    private FakeS3AsyncClient client;

    @BeforeEach
    void setUp() {
        client = new FakeS3AsyncClient(root);
    }

    @Test
    void putThenGetReturnsTheStoredContent_andPutOverwrites() {
        put("tok/page-001.pdf", "v1");
        put("tok/page-001.pdf", "v2");

        assertThat(get("tok/page-001.pdf")).isEqualTo("v2");
    }

    @Test
    void getOfAMissingKeyFailsWithNoSuchKey() {
        assertThatThrownBy(() -> get("tok/absent.pdf"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void deleteRemovesTheObject_andIsIdempotent() {
        put("tok/page-001-3f2a9c1e.pdf", "version");

        delete("tok/page-001-3f2a9c1e.pdf");

        assertThatThrownBy(() -> get("tok/page-001-3f2a9c1e.pdf"))
                .hasCauseInstanceOf(NoSuchKeyException.class);
        assertThatCode(() -> delete("tok/page-001-3f2a9c1e.pdf")).doesNotThrowAnyException();
    }

    private void put(String key, String content) {
        client.putObject(PutObjectRequest.builder().bucket("bucket").key(key).build(),
                AsyncRequestBody.fromString(content)).join();
    }

    private String get(String key) {
        return client.getObject(GetObjectRequest.builder().bucket("bucket").key(key).build(),
                        AsyncResponseTransformer.toBytes())
                .join()
                .asString(StandardCharsets.UTF_8);
    }

    private void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket("bucket").key(key).build()).join();
    }
}

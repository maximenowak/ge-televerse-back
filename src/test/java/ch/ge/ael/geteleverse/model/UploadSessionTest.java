package ch.ge.ael.geteleverse.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UploadSessionTest {

    private static final SizeProfile PROFILE = SizeProfile.S;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void create_generatesUniqueTokens() {
        var s1 = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var s2 = UploadSession.create(PROFILE, Duration.ofMinutes(15));

        assertThat(s1.token()).isNotEqualTo(s2.token());
    }

    @Test
    void create_drawsADocumentIdIndependentOfTheToken() {
        // Le documentId circule chez l'application hôte, le token dans chaque URL :
        // l'un ne doit pas donner l'autre.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));

        assertThat(session.documentId())
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf$")
                .doesNotContain(session.token());
    }

    @Test
    void create_setsCorrectExpiry() {
        var before = Instant.now();
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var after = Instant.now();

        assertThat(session.expiresAt()).isAfter(before.plus(Duration.ofMinutes(14)));
        assertThat(session.expiresAt()).isBefore(after.plus(Duration.ofMinutes(16)));
    }

    @Test
    void create_initializesPagesEmpty() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));

        assertThat(session.pages()).isEmpty();
        assertThat(session.pageCounter()).isZero();
    }

    @Test
    void create_appliesDefaultSizeProfile_whenNull() {
        var session = UploadSession.create(null, Duration.ofMinutes(15));

        assertThat(session.sizeProfile()).isEqualTo(SizeProfile.DEFAULT);
    }

    @Test
    void isExpired_false_whenFresh() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        assertThat(session.isExpired()).isFalse();
    }

    @Test
    void isExpired_true_whenPastExpiry() {
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), List.of(), 0, false);
        assertThat(session.isExpired()).isTrue();
    }

    @Test
    void withRenewedExpiry_extendsExpiration() {
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now().minusSeconds(600), Instant.now().plusSeconds(300), List.of(), 0, false);
        var originalExpiry = session.expiresAt();

        var renewed = session.withRenewedExpiry(Duration.ofMinutes(15));

        assertThat(renewed.token()).isEqualTo(session.token());
        assertThat(renewed.documentId()).isEqualTo(session.documentId());
        assertThat(renewed.sizeProfile()).isEqualTo(session.sizeProfile());
        assertThat(renewed.createdAt()).isEqualTo(session.createdAt());
        assertThat(renewed.expiresAt()).isAfter(originalExpiry);
    }

    @Test
    void withRenewedExpiry_preservesPagesAndCounter() {
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "scan.jpg", "image/jpeg", 1024, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(300), List.of(page), 1, false);

        var renewed = session.withRenewedExpiry(Duration.ofMinutes(15));

        assertThat(renewed.pages()).hasSize(1);
        assertThat(renewed.pages().getFirst().pageId()).isEqualTo("page-001");
        assertThat(renewed.pageCounter()).isEqualTo(1);
    }

    // ===== Helpers S3 =====

    @Test
    void pagePrefix_returnsCorrectFormat() {
        var session = new UploadSession("my-token", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(300), List.of(), 0, false);

        assertThat(session.pagePrefix()).isEqualTo("my-token/");
    }

    @Test
    void nextPageId_startsAtPageOne() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));

        assertThat(session.nextPageId()).isEqualTo("page-001");
    }

    @Test
    void nextPageId_incrementsAfterAddedPage() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var page = new PageInfo("page-001", "key", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var updated = session.withAddedPage(page);

        assertThat(updated.nextPageId()).isEqualTo("page-002");
    }

    @Test
    void nextPageKey_combinesPrefixAndPageId() {
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(300), List.of(), 0, false);

        assertThat(session.nextPageKey()).isEqualTo("tok/page-001.pdf");
    }

    @Test
    void nextThumbnailKey_combinesPrefixAndPageIdWithThumbSuffix() {
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(300), List.of(), 0, false);

        assertThat(session.nextThumbnailKey()).isEqualTo("tok/page-001-thumb.jpg");
    }

    @Test
    void nextThumbnailKey_incrementsWithPageCounter() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var page = new PageInfo("page-001", "key", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var updated = session.withAddedPage(page);

        assertThat(updated.nextThumbnailKey()).contains("page-002-thumb.jpg");
    }

    // ===== Mutations immutables =====

    @Test
    void withAddedPage_addsPageAndIncrementsCounter() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));
        var page = new PageInfo("page-001", "key", "f.jpg", "image/jpeg", 100, Instant.now(), null);

        var updated = session.withAddedPage(page);

        assertThat(updated.pages()).hasSize(1);
        assertThat(updated.pages().getFirst()).isEqualTo(page);
        assertThat(updated.pageCounter()).isEqualTo(1);
    }

    @Test
    void withBumpedCounter_incrementsCounterWithoutAddingPage() {
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15));

        var updated = session.withBumpedCounter();

        assertThat(updated.pages()).isEmpty();
        assertThat(updated.pageCounter()).isEqualTo(1);
        assertThat(updated.nextPageId()).isEqualTo("page-002");
    }

    @Test
    void withAttachedPage_addsPageWithoutBumpingCounter() {
        // Le compteur est censé avoir déjà été incrémenté à la réservation —
        // l'attachement n'y touche pas, sinon withBumpedCounter + withAttachedPage
        // ferait monter le compteur de 2.
        var session = UploadSession.create(PROFILE, Duration.ofMinutes(15)).withBumpedCounter();
        var page = new PageInfo("page-001", "key", "f.jpg", "image/jpeg", 100, Instant.now(), null);

        var updated = session.withAttachedPage(page);

        assertThat(updated.pages()).hasSize(1);
        assertThat(updated.pages().getFirst()).isEqualTo(page);
        assertThat(updated.pageCounter()).isEqualTo(1);
    }

    @Test
    void withRemovedPage_removesCorrectPage() {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(300), List.of(page1, page2), 2, false);

        var updated = session.withRemovedPage("page-001");

        assertThat(updated.pages()).hasSize(1);
        assertThat(updated.pages().getFirst().pageId()).isEqualTo("page-002");
        assertThat(updated.pageCounter()).isEqualTo(2); // compteur inchangé
    }

    @Test
    void withReorderedPages_reordersCorrectly() {
        var page1 = new PageInfo("page-001", "k1", "f1.jpg", "image/jpeg", 100, Instant.now(), null);
        var page2 = new PageInfo("page-002", "k2", "f2.jpg", "image/jpeg", 200, Instant.now(), null);
        var page3 = new PageInfo("page-003", "k3", "f3.jpg", "image/jpeg", 300, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(300), List.of(page1, page2, page3), 3, false);

        var updated = session.withReorderedPages(List.of("page-003", "page-001", "page-002"));

        assertThat(updated.pages()).extracting(PageInfo::pageId)
                .containsExactly("page-003", "page-001", "page-002");
    }

    // ===== Jackson roundtrip =====

    @Test
    void jacksonRoundtrip_preservesAllFields() throws Exception {
        var page = new PageInfo("page-001", "u/f/t/page-001.pdf", "scan.jpg", "image/jpeg", 1024, Instant.now(), "u/f/t/page-001-thumb.jpg");
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);

        var json = MAPPER.writeValueAsString(session);
        var deserialized = MAPPER.readValue(json, UploadSession.class);

        assertThat(deserialized.token()).isEqualTo("tok");
        assertThat(deserialized.sizeProfile()).isEqualTo(PROFILE);
        assertThat(deserialized.pages()).hasSize(1);
        assertThat(deserialized.pages().getFirst().pageId()).isEqualTo("page-001");
        assertThat(deserialized.pages().getFirst().thumbnailKey()).isEqualTo("u/f/t/page-001-thumb.jpg");
        assertThat(deserialized.pageCounter()).isEqualTo(1);
    }

    @Test
    void jacksonRoundtrip_handlesMissingOptionalFields() throws Exception {
        // Session minimale : pas de pages, pas de pageCounter, pas de sizeProfile
        var json = """
                {"token":"tok","documentId":"doc.pdf",
                 "createdAt":0,"expiresAt":999999999999}
                """;

        var deserialized = MAPPER.readValue(json, UploadSession.class);

        assertThat(deserialized.sizeProfile()).isEqualTo(SizeProfile.DEFAULT);
        assertThat(deserialized.pages()).isEmpty();
        assertThat(deserialized.pageCounter()).isZero();
    }

    @Test
    void jacksonRoundtrip_handlesNullThumbnailKey() throws Exception {
        // PageInfo sans thumbnailKey (rétrocompatibilité)
        var page = new PageInfo("page-001", "key", "f.jpg", "image/jpeg", 100, Instant.now(), null);
        var session = new UploadSession("tok", "doc.pdf", PROFILE,
                Instant.now(), Instant.now().plusSeconds(900), List.of(page), 1, false);

        var json = MAPPER.writeValueAsString(session);
        var deserialized = MAPPER.readValue(json, UploadSession.class);

        assertThat(deserialized.pages().getFirst().thumbnailKey()).isNull();
    }
}

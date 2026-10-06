package ch.ge.ael.geteleverse.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UploadEventTest {

    @Test
    void completed_setsCorrectFields() {
        var event = UploadEvent.completed("doc-1", "scan.pdf", "application/pdf", 2048L, "page-001", 0, 1, 2048L, 100_000L);

        assertThat(event.type()).isEqualTo("upload-complete");
        assertThat(event.documentId()).isEqualTo("doc-1");
        assertThat(event.fileName()).isEqualTo("scan.pdf");
        assertThat(event.contentType()).isEqualTo("application/pdf");
        assertThat(event.fileSize()).isEqualTo(2048);
        assertThat(event.pageId()).isEqualTo("page-001");
        assertThat(event.pageIndex()).isZero();
        assertThat(event.totalPages()).isEqualTo(1);
        assertThat(event.totalBytes()).isEqualTo(2048L);
        assertThat(event.maxTotalBytes()).isEqualTo(100_000L);
        assertThat(event.uploadedAt()).isNotNull();
    }

    @Test
    void pageDeleted_setsCorrectFields() {
        var event = UploadEvent.pageDeleted("doc-1", "page-002", 2, 4096L, 100_000L);

        assertThat(event.type()).isEqualTo("page-deleted");
        assertThat(event.documentId()).isEqualTo("doc-1");
        assertThat(event.pageId()).isEqualTo("page-002");
        assertThat(event.totalPages()).isEqualTo(2);
        assertThat(event.totalBytes()).isEqualTo(4096L);
        assertThat(event.maxTotalBytes()).isEqualTo(100_000L);
        assertThat(event.fileName()).isNull();
        assertThat(event.uploadedAt()).isNotNull();
    }

    @Test
    void pagesReordered_setsCorrectFields() {
        var event = UploadEvent.pagesReordered("doc-1", 3, 6144L, 100_000L);

        assertThat(event.type()).isEqualTo("pages-reordered");
        assertThat(event.documentId()).isEqualTo("doc-1");
        assertThat(event.totalPages()).isEqualTo(3);
        assertThat(event.totalBytes()).isEqualTo(6144L);
        assertThat(event.maxTotalBytes()).isEqualTo(100_000L);
        assertThat(event.pageId()).isNull();
        assertThat(event.uploadedAt()).isNotNull();
    }

    @Test
    void pageEdited_setsCorrectFields() {
        var event = UploadEvent.pageEdited("doc-1", "page-003", 3, 4096L, 100_000L);

        assertThat(event.type()).isEqualTo("page-edited");
        assertThat(event.documentId()).isEqualTo("doc-1");
        assertThat(event.pageId()).isEqualTo("page-003");
        assertThat(event.totalPages()).isEqualTo(3);
        assertThat(event.totalBytes()).isEqualTo(4096L);
        assertThat(event.maxTotalBytes()).isEqualTo(100_000L);
        assertThat(event.fileName()).isNull();
        assertThat(event.uploadedAt()).isNotNull();
    }

    @Test
    void sessionComplete_setsCorrectFields() {
        var event = UploadEvent.sessionComplete("doc-1", 5);

        assertThat(event.type()).isEqualTo("session-complete");
        assertThat(event.documentId()).isEqualTo("doc-1");
        assertThat(event.totalPages()).isEqualTo(5);
        assertThat(event.fileName()).isNull();
        assertThat(event.contentType()).isNull();
        assertThat(event.uploadedAt()).isNotNull();
    }
}

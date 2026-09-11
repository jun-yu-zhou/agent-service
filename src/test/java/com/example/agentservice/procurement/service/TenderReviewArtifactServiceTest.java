package com.example.agentservice.procurement.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.domain.TenderReviewStatus;
import com.example.agentservice.procurement.persistence.TenderDocumentEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenderReviewArtifactServiceTest {

    @Test
    void shouldRenderCompletedReview() throws Exception {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        Docx4jMarkdownDocxRenderer renderer = mock(Docx4jMarkdownDocxRenderer.class);
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document(TenderReviewStatus.COMPLETED)));
        when(renderer.render("# 审核报告")).thenReturn(new byte[] {1, 2, 3});

        var document = new TenderReviewArtifactService(store, renderer)
                .export("task-1", "version-1").orElseThrow();

        assertEquals("招标文件审核报告_V2.docx", document.filename());
        assertArrayEquals(new byte[] {1, 2, 3}, document.content());
        verify(renderer).render("# 审核报告");
    }

    @Test
    void shouldRejectUnfinishedReview() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document(TenderReviewStatus.REVIEWING)));

        assertThrows(IllegalStateException.class,
                () -> new TenderReviewArtifactService(store, mock(Docx4jMarkdownDocxRenderer.class))
                        .export("task-1", "version-1"));
    }

    private TenderDocumentEntity document(TenderReviewStatus status) {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId("version-1");
        document.setTaskId("task-1");
        document.setContentRevision(2);
        document.setReviewStatus(status.name());
        document.setReviewReport("# 审核报告");
        return document;
    }
}

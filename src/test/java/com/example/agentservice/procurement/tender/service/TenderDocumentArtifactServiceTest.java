package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.procurement.common.docx.Docx4jMarkdownDocxRenderer;
import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenderDocumentArtifactServiceTest {

    private static final String TASK_ID = "task-1";
    private static final String VERSION_ID = "version-1";

    @Test
    void shouldRenderFinalizedVersionInMemory() throws Exception {
        byte[] expected = "docx-content".getBytes();
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        Docx4jMarkdownDocxRenderer renderer = mock(Docx4jMarkdownDocxRenderer.class);
        when(store.findByTaskId(TASK_ID)).thenReturn(Optional.of(document(true)));
        when(renderer.render("# 招标文件")).thenReturn(expected);

        TenderDocumentArtifactService.ExportedDocument document =
                new TenderDocumentArtifactService(store, renderer)
                        .exportDocument(TASK_ID, VERSION_ID).orElseThrow();

        assertEquals("招标文件.docx", document.filename());
        assertArrayEquals(expected, document.content());
        verify(renderer).render("# 招标文件");
    }

    @Test
    void shouldRejectUnfinalizedVersion() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        when(store.findByTaskId(TASK_ID)).thenReturn(Optional.of(document(false)));

        assertThrows(IllegalStateException.class,
                () -> new TenderDocumentArtifactService(store, mock(Docx4jMarkdownDocxRenderer.class))
                        .exportDocument(TASK_ID, VERSION_ID));
    }

    @Test
    void shouldRenderCompletedReview() throws Exception {
        byte[] expected = "review-content".getBytes();
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        Docx4jMarkdownDocxRenderer renderer = mock(Docx4jMarkdownDocxRenderer.class);
        TenderDocumentEntity document = document(true);
        document.setReviewStatus(TenderReviewStatus.COMPLETED.name());
        document.setReviewReport("# 审核报告");
        when(store.findByTaskId(TASK_ID)).thenReturn(Optional.of(document));
        when(renderer.render("# 审核报告")).thenReturn(expected);

        var exported = new TenderDocumentArtifactService(store, renderer)
                .exportReview(TASK_ID, VERSION_ID).orElseThrow();

        assertEquals("招标文件审核报告.docx", exported.filename());
        assertArrayEquals(expected, exported.content());
        verify(renderer).render("# 审核报告");
    }

    @Test
    void shouldRejectUnfinishedReview() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderDocumentEntity document = document(true);
        document.setReviewStatus(TenderReviewStatus.REVIEWING.name());
        when(store.findByTaskId(TASK_ID)).thenReturn(Optional.of(document));

        assertThrows(IllegalStateException.class,
                () -> new TenderDocumentArtifactService(store, mock(Docx4jMarkdownDocxRenderer.class))
                        .exportReview(TASK_ID, VERSION_ID));
    }

    private TenderDocumentEntity document(boolean finalized) {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId(VERSION_ID);
        document.setTaskId(TASK_ID);
        document.setContentRevision(2);
        document.setFinalized(finalized);
        document.setDocumentMarkdown("# 招标文件");
        return document;
    }
}

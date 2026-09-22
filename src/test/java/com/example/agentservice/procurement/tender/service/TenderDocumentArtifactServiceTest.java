package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenderDocumentArtifactServiceTest {

    private static final String TASK_ID = "task-1";
    private static final String VERSION_ID = "version-1";

    @Test
    void shouldReadFinalDocumentFromOss() throws Exception {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderArtifactStorage storage = mock(TenderArtifactStorage.class);
        TenderDocumentEntity entity = document(true);
        entity.setFinalDocumentObjectKey("final.docx");
        when(store.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
        when(storage.read("final.docx")).thenReturn(new byte[] {1});

        var document = new TenderDocumentArtifactService(store, storage)
                .exportDocument(TASK_ID, VERSION_ID).orElseThrow();

        assertEquals("招标文件.docx", document.filename());
        assertArrayEquals(new byte[] {1}, document.content());
        verify(storage).read("final.docx");
    }

    @Test
    void shouldReadCompletedReviewFromOss() throws Exception {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderArtifactStorage storage = mock(TenderArtifactStorage.class);
        TenderDocumentEntity entity = document(true);
        entity.setReviewStatus(TenderReviewStatus.COMPLETED.name());
        entity.setReviewReportObjectKey("review.docx");
        when(store.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
        when(storage.read("review.docx")).thenReturn(new byte[] {2});

        var document = new TenderDocumentArtifactService(store, storage)
                .exportReview(TASK_ID, VERSION_ID).orElseThrow();

        assertEquals("招标文件审核报告.docx", document.filename());
        assertArrayEquals(new byte[] {2}, document.content());
    }

    @Test
    void shouldRejectMissingGeneratedArtifact() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        when(store.findByTaskId(TASK_ID)).thenReturn(Optional.of(document(true)));

        assertThrows(IllegalStateException.class,
                () -> new TenderDocumentArtifactService(store, mock(TenderArtifactStorage.class))
                        .exportDocument(TASK_ID, VERSION_ID));
    }

    private TenderDocumentEntity document(boolean finalized) {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId(VERSION_ID);
        document.setTaskId(TASK_ID);
        document.setContentRevision(2);
        document.setFinalized(finalized);
        return document;
    }
}

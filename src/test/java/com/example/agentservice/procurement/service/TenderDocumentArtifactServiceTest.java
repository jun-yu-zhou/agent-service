package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.DocumentVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        Docx4jMarkdownDocxRenderer renderer = mock(Docx4jMarkdownDocxRenderer.class);
        when(store.findVersion(TASK_ID, VERSION_ID)).thenReturn(Optional.of(snapshot(true)));
        when(renderer.render("# 招标文件")).thenReturn(expected);

        TenderDocumentArtifactService.ExportedDocument document =
                new TenderDocumentArtifactService(store, renderer)
                        .export(TASK_ID, VERSION_ID).orElseThrow();

        assertEquals("招标文件_V2.docx", document.filename());
        assertArrayEquals(expected, document.content());
        verify(renderer).render("# 招标文件");
    }

    @Test
    void shouldRejectUnfinalizedVersion() {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        when(store.findVersion(TASK_ID, VERSION_ID)).thenReturn(Optional.of(snapshot(false)));

        assertThrows(IllegalStateException.class,
                () -> new TenderDocumentArtifactService(store, mock(Docx4jMarkdownDocxRenderer.class))
                        .export(TASK_ID, VERSION_ID));
    }

    private ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot(boolean finalized) {
        DocumentVersion version = new DocumentVersion(
                VERSION_ID, TASK_ID, null, 2, "MANUAL_EDIT", "tester", Instant.now(), finalized, List.of());
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(version, "# 招标文件");
    }
}

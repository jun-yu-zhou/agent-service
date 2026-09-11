package com.example.agentservice.procurement.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.TenderReviewSnapshot;
import com.example.agentservice.procurement.domain.TenderReviewStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenderReviewArtifactServiceTest {

    @Test
    void shouldRenderCompletedReview() throws Exception {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        Docx4jMarkdownDocxRenderer renderer = mock(Docx4jMarkdownDocxRenderer.class);
        when(store.findReview("task-1", "version-1")).thenReturn(Optional.of(review(TenderReviewStatus.COMPLETED)));
        when(store.findVersion("task-1", "version-1")).thenReturn(Optional.of(version()));
        when(renderer.render("# 审核报告")).thenReturn(new byte[] {1, 2, 3});

        var document = new TenderReviewArtifactService(store, renderer)
                .export("task-1", "version-1").orElseThrow();

        assertEquals("招标文件审核报告_V2.docx", document.filename());
        assertArrayEquals(new byte[] {1, 2, 3}, document.content());
        verify(renderer).render("# 审核报告");
    }

    @Test
    void shouldRejectUnfinishedReview() {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        when(store.findReview("task-1", "version-1")).thenReturn(Optional.of(review(TenderReviewStatus.REVIEWING)));
        when(store.findVersion("task-1", "version-1")).thenReturn(Optional.of(version()));

        assertThrows(IllegalStateException.class,
                () -> new TenderReviewArtifactService(store, mock(Docx4jMarkdownDocxRenderer.class))
                        .export("task-1", "version-1"));
    }

    private TenderReviewSnapshot review(TenderReviewStatus status) {
        Instant now = Instant.now();
        return new TenderReviewSnapshot(
                "task-1", "version-1", status, "审核报告已生成",
                "# 审核报告", null, now, now);
    }

    private ProcurementTaskRedisStore.DocumentVersionSnapshot version() {
        DocumentVersion version = new DocumentVersion(
                "version-1", "task-1", null, 2, "MANUAL_EDIT", "tester",
                Instant.now(), true, List.of());
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(version, "# 招标文件");
    }
}

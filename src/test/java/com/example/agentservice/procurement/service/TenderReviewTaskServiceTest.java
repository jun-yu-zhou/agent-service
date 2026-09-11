package com.example.agentservice.procurement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.TenderReviewSnapshot;
import com.example.agentservice.procurement.domain.TenderReviewStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenderReviewTaskServiceTest {

    @Test
    void shouldCompleteQueuedReview() throws Exception {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        TenderDocumentReviewService reviewer = mock(TenderDocumentReviewService.class);
        ExecutorService executor = mock(ExecutorService.class);
        var version = new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                new DocumentVersion("version-1", "task-1", null, 1,
                        "AI_GENERATED", "system", Instant.now(), true, List.of()),
                "# 招标文件");
        var source = new ProcurementTaskRedisStore.TenderReviewSource(
                "<h1>招标文件</h1>", new ObjectMapper().readTree("{\"projectName\":\"测试项目\"}"));
        when(store.findVersion("task-1", "version-1")).thenReturn(Optional.of(version));
        when(store.findReview("task-1", "version-1")).thenReturn(Optional.empty());
        when(store.findReviewSource("task-1")).thenReturn(Optional.of(source));
        when(reviewer.review(source.templateHtml(), source.projectData(), version.markdown()))
                .thenReturn("# 招标文件一致性与质量审核报告");
        TenderReviewTaskService service = new TenderReviewTaskService(store, reviewer, executor);

        TenderReviewSnapshot pending = service.start("task-1", "version-1").orElseThrow();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        ArgumentCaptor<TenderReviewSnapshot> reviewCaptor = ArgumentCaptor.forClass(TenderReviewSnapshot.class);
        verify(store, atLeast(3)).saveReview(reviewCaptor.capture());
        assertEquals(TenderReviewStatus.PENDING, pending.status());
        assertEquals(List.of(TenderReviewStatus.PENDING, TenderReviewStatus.REVIEWING, TenderReviewStatus.COMPLETED),
                reviewCaptor.getAllValues().stream().map(TenderReviewSnapshot::status).toList());
        assertEquals("# 招标文件一致性与质量审核报告",
                reviewCaptor.getAllValues().get(2).reportMarkdown());
    }
}

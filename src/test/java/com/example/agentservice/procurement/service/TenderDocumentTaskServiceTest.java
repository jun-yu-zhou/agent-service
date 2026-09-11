package com.example.agentservice.procurement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentType;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenderDocumentTaskServiceTest {

    @Test
    void shouldSaveReviewSourceWhenCreatingTask() throws Exception {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        ExecutorService executor = mock(ExecutorService.class);
        TenderDocumentTaskService service = new TenderDocumentTaskService(
                mock(TenderDocumentGenerationService.class), mock(TenderProjectDataService.class), store,
                mock(TenderReviewTaskService.class), executor);
        JsonNode projectData = new ObjectMapper().readTree("{\"projectName\":\"测试项目\"}");

        var task = service.submitTemplate("<h1>招标文件</h1>", projectData);

        ArgumentCaptor<ProcurementTaskRedisStore.TenderReviewSource> sourceCaptor =
                ArgumentCaptor.forClass(ProcurementTaskRedisStore.TenderReviewSource.class);
        verify(store).saveReviewSource(org.mockito.ArgumentMatchers.eq(task.taskId()), sourceCaptor.capture());
        assertEquals("<h1>招标文件</h1>", sourceCaptor.getValue().templateHtml());
        assertEquals(projectData, sourceCaptor.getValue().projectData());
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void shouldStartReviewAfterFinalizingVersion() {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        TenderReviewTaskService reviewTaskService = mock(TenderReviewTaskService.class);
        TenderDocumentTaskService service = new TenderDocumentTaskService(
                mock(TenderDocumentGenerationService.class), mock(TenderProjectDataService.class), store,
                reviewTaskService, mock(ExecutorService.class));
        Instant now = Instant.now();
        var task = new DocumentGenerationTask(
                "task-1", DocumentType.TENDER, GenerationTaskStatus.COMPLETED,
                "初稿生成完成", "version-1", null, now, now);
        var version = new DocumentVersion(
                "version-1", "task-1", null, 1, "AI_GENERATED", "system", now, false, List.of());
        var snapshot = new ProcurementTaskRedisStore.DocumentVersionSnapshot(version, "# 招标文件");
        when(store.findTender("task-1"))
                .thenReturn(Optional.of(new ProcurementTaskRedisStore.TenderTaskState(task, snapshot.markdown())));
        when(store.findVersion("task-1", "version-1")).thenReturn(Optional.of(snapshot));
        when(store.findVersions("task-1")).thenReturn(List.of(snapshot));

        DocumentVersion finalized = service.finalizeVersion("task-1", "version-1").orElseThrow();

        assertTrue(finalized.finalized());
        verify(reviewTaskService).start("task-1", "version-1");
    }
}

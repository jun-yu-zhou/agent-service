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
import com.example.agentservice.procurement.persistence.TenderDocumentEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenderDocumentTaskServiceTest {

    @Test
    void shouldFinalizeCurrentDatabaseDocument() {
        TenderDocumentStore documentStore = mock(TenderDocumentStore.class);
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId("document-1");
        document.setTaskId("task-1");
        document.setDocumentMarkdown("# 招标文件");
        document.setContentRevision(2);
        document.setFinalized(true);
        document.setCreatedAt(LocalDateTime.now());
        when(documentStore.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(documentStore.finalizeDocument("task-1")).thenReturn(Optional.of(document));
        TenderDocumentTaskService service = new TenderDocumentTaskService(
                mock(TenderDocumentGenerationService.class), mock(TenderProjectDataService.class), documentStore,
                mock(ProcurementTaskRedisStore.class), mock(TenderReviewTaskService.class),
                mock(ExecutorService.class));

        DocumentVersion version = service.finalizeVersion("task-1", "document-1").orElseThrow();

        assertTrue(version.finalized());
        verify(documentStore).finalizeDocument("task-1");
    }

    @Test
    void shouldReadTaskAndDraftFromDatabase() {
        TenderDocumentStore documentStore = mock(TenderDocumentStore.class);
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId("document-1");
        document.setTaskId("task-1");
        document.setGenerationStatus("COMPLETED");
        document.setGenerationStage("初稿生成完成");
        document.setDocumentMarkdown("# 招标文件");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        when(documentStore.findByTaskId("task-1")).thenReturn(Optional.of(document));
        TenderDocumentTaskService service = new TenderDocumentTaskService(
                mock(TenderDocumentGenerationService.class), mock(TenderProjectDataService.class), documentStore,
                mock(ProcurementTaskRedisStore.class), mock(TenderReviewTaskService.class),
                mock(ExecutorService.class));

        TenderDocumentTaskService.TaskSnapshot snapshot = service.findSnapshot("task-1").orElseThrow();

        assertEquals(GenerationTaskStatus.COMPLETED, snapshot.task().status());
        assertEquals("document-1", snapshot.task().currentVersionId());
        assertEquals("# 招标文件", snapshot.draft());

        TenderDocumentTaskService.DocumentVersionSnapshot version = service.findVersions("task-1")
                .orElseThrow()
                .get(0);
        assertEquals("document-1", version.version().versionId());
        assertEquals("# 招标文件", version.markdown());
    }

    @Test
    void shouldCreateDatabaseTaskForProject() throws Exception {
        TenderProjectDataService projectService = mock(TenderProjectDataService.class);
        TenderDocumentStore documentStore = mock(TenderDocumentStore.class);
        ExecutorService executor = mock(ExecutorService.class);
        JsonNode projectData = new ObjectMapper().readTree("{\"projectName\":\"测试项目\"}");
        when(projectService.load("project-1")).thenReturn(new TenderProjectDataService.GenerationInput(
                "template-1", "<h1>招标文件</h1>", projectData));
        TenderDocumentTaskService service = new TenderDocumentTaskService(
                mock(TenderDocumentGenerationService.class), projectService, documentStore,
                mock(ProcurementTaskRedisStore.class), mock(TenderReviewTaskService.class), executor);

        DocumentGenerationTask task = service.submitProject("project-1");

        verify(documentStore).create(task.taskId(), "project-1", "template-1", projectData);
        verify(executor).execute(any(Runnable.class));
        assertEquals(GenerationTaskStatus.PENDING, task.status());
    }

    @Test
    void shouldSaveReviewSourceWhenCreatingTask() throws Exception {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        ExecutorService executor = mock(ExecutorService.class);
        TenderDocumentTaskService service = new TenderDocumentTaskService(
                mock(TenderDocumentGenerationService.class), mock(TenderProjectDataService.class),
                mock(TenderDocumentStore.class), store,
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
        TenderDocumentStore documentStore = mock(TenderDocumentStore.class);
        TenderReviewTaskService reviewTaskService = mock(TenderReviewTaskService.class);
        TenderDocumentTaskService service = new TenderDocumentTaskService(
                mock(TenderDocumentGenerationService.class), mock(TenderProjectDataService.class),
                documentStore, mock(ProcurementTaskRedisStore.class),
                reviewTaskService, mock(ExecutorService.class));
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId("version-1");
        document.setTaskId("task-1");
        document.setDocumentMarkdown("# 招标文件");
        document.setContentRevision(1);
        document.setFinalized(true);
        document.setCreatedAt(LocalDateTime.now());
        when(documentStore.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(documentStore.finalizeDocument("task-1")).thenReturn(Optional.of(document));

        DocumentVersion finalized = service.finalizeVersion("task-1", "version-1").orElseThrow();

        assertTrue(finalized.finalized());
        verify(reviewTaskService).start("task-1", "version-1");
    }
}

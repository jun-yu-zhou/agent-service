package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.tender.persistence.TenderProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenderReviewTaskServiceTest {

    @Test
    void shouldCompleteQueuedReview() throws Exception {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderProjectMapper projectMapper = mock(TenderProjectMapper.class);
        TenderDocumentAiService reviewer = mock(TenderDocumentAiService.class);
        ExecutorService executor = mock(ExecutorService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TenderDocumentEntity document = document();
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(store.beginReview("task-1", 1, TenderReviewStatus.PENDING)).thenReturn(true);
        when(projectMapper.selectTemplateHtml("template-1")).thenReturn("<h1>招标文件</h1>");
        when(reviewer.review(
                org.mockito.ArgumentMatchers.eq("<h1>招标文件</h1>"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("# 招标文件")))
                .thenReturn("# 审核报告");
        TenderReviewTaskService service =
                new TenderReviewTaskService(store, projectMapper, reviewer, objectMapper, executor);

        assertEquals(TenderReviewStatus.PENDING,
                service.start("task-1", "version-1").orElseThrow().status());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(store).completeReview("task-1", 1, "# 审核报告");
    }

    @Test
    void shouldCloseClaimWhenReviewVersionBecomesInvalid() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderDocumentAiService reviewer = mock(TenderDocumentAiService.class);
        ExecutorService executor = mock(ExecutorService.class);
        TenderDocumentEntity pending = document();
        TenderDocumentEntity invalid = document();
        invalid.setFinalized(false);
        invalid.setReviewStatus(TenderReviewStatus.REVIEWING.name());
        when(store.findByTaskId("task-1")).thenReturn(
                Optional.of(pending), Optional.of(pending), Optional.of(invalid));
        when(store.beginReview("task-1", 1, TenderReviewStatus.PENDING)).thenReturn(true);
        TenderReviewTaskService service = new TenderReviewTaskService(
                store, mock(TenderProjectMapper.class), reviewer, new ObjectMapper(), executor);

        service.start("task-1", "version-1");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(store).invalidateReview("task-1", 1, "审核对应的正文版本已经失效");
        verify(reviewer, never()).review(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private TenderDocumentEntity document() {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId("version-1");
        document.setTaskId("task-1");
        document.setTemplateId("template-1");
        document.setProjectData("{\"projectName\":\"测试项目\"}");
        document.setDocumentMarkdown("# 招标文件");
        document.setContentRevision(1);
        document.setFinalized(true);
        document.setReviewRevision(1);
        document.setReviewStatus("PENDING");
        document.setReviewStage("等待审核");
        document.setFinalizedAt(LocalDateTime.now());
        return document;
    }
}

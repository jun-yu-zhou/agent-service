package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenderReviewTaskServiceTest {

    @Test
    void shouldCompleteQueuedReviewWithSameSession() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderDocumentAiService agent = mock(TenderDocumentAiService.class);
        ExecutorService executor = mock(ExecutorService.class);
        TenderDocumentEntity document = document();
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(store.beginReview("task-1", 1, TenderReviewStatus.PENDING)).thenReturn(true);
        when(agent.reviewFinalizedDocument("session-1", "# 招标文件"))
                .thenReturn("# 审核报告");
        TenderReviewTaskService service = new TenderReviewTaskService(store, agent, executor);

        assertEquals(TenderReviewStatus.PENDING,
                service.start("task-1", "version-1").orElseThrow().status());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verify(store).beginReview("task-1", 1, TenderReviewStatus.PENDING);
        task.getValue().run();

        verify(store).completeReview("task-1", 1, "# 审核报告");
    }

    @Test
    void shouldCloseClaimWhenReviewVersionBecomesInvalid() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderDocumentAiService agent = mock(TenderDocumentAiService.class);
        ExecutorService executor = mock(ExecutorService.class);
        TenderDocumentEntity pending = document();
        TenderDocumentEntity invalid = document();
        invalid.setFinalized(false);
        invalid.setReviewStatus(TenderReviewStatus.REVIEWING.name());
        when(store.findByTaskId("task-1")).thenReturn(
                Optional.of(pending), Optional.of(pending), Optional.of(invalid));
        when(store.beginReview("task-1", 1, TenderReviewStatus.PENDING)).thenReturn(true);
        TenderReviewTaskService service = new TenderReviewTaskService(store, agent, executor);

        service.start("task-1", "version-1");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(store).invalidateReview("task-1", 1, "审核对应的正文版本已经失效");
        verify(agent, never()).reviewFinalizedDocument("session-1", "# 招标文件");
    }

    @Test
    void shouldFailPendingReviewWhenExecutorRejectsTask() {
        TenderDocumentStore store = mock(TenderDocumentStore.class);
        TenderDocumentAiService agent = mock(TenderDocumentAiService.class);
        ExecutorService executor = mock(ExecutorService.class);
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document()));
        when(store.beginReview("task-1", 1, TenderReviewStatus.PENDING)).thenReturn(true);
        doThrow(new RejectedExecutionException("审核队列已满"))
                .when(executor).execute(any(Runnable.class));
        TenderReviewTaskService service = new TenderReviewTaskService(store, agent, executor);

        service.start("task-1", "version-1");

        verify(store).failReview("task-1", 1, "审核队列已满");
        verify(agent, never()).reviewFinalizedDocument("session-1", "# 招标文件");
    }

    private TenderDocumentEntity document() {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId("version-1");
        document.setTaskId("task-1");
        document.setSessionId("session-1");
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

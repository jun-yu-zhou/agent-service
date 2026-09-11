package com.example.agentservice.procurement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.domain.TenderReviewStatus;
import com.example.agentservice.procurement.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.persistence.TenderProjectMapper;
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
        TenderDocumentReviewService reviewer = mock(TenderDocumentReviewService.class);
        ExecutorService executor = mock(ExecutorService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TenderDocumentEntity document = document();
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
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

        verify(store).updateReview("task-1", "COMPLETED", "审核报告已生成", "# 审核报告", null);
    }

    private TenderDocumentEntity document() {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId("version-1");
        document.setTaskId("task-1");
        document.setTemplateId("template-1");
        document.setProjectData("{\"projectName\":\"测试项目\"}");
        document.setDocumentMarkdown("# 招标文件");
        document.setFinalized(true);
        document.setReviewStatus("PENDING");
        document.setReviewStage("等待审核");
        document.setFinalizedAt(LocalDateTime.now());
        return document;
    }
}

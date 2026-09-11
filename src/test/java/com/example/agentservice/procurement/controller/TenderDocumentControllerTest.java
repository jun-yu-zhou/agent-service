package com.example.agentservice.procurement.controller;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentType;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.domain.TenderReviewSnapshot;
import com.example.agentservice.procurement.domain.TenderReviewStatus;
import com.example.agentservice.procurement.request.TenderProjectTaskRequest;
import com.example.agentservice.procurement.service.TenderDocumentArtifactService;
import com.example.agentservice.procurement.service.TenderDocumentTaskService;
import com.example.agentservice.procurement.service.TenderReviewArtifactService;
import com.example.agentservice.procurement.service.TenderReviewTaskService;
import com.example.agentservice.procurement.service.TenderTemplateUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenderDocumentControllerTest {

    @Test
    void shouldCreateTaskByLegacyProjectId() {
        TenderDocumentTaskService taskService = mock(TenderDocumentTaskService.class);
        DocumentGenerationTask task = new DocumentGenerationTask(
                "task-1", DocumentType.TENDER, GenerationTaskStatus.PENDING,
                "等待生成", null, null, Instant.now(), Instant.now());
        when(taskService.submitProject("project-1")).thenReturn(task);
        TenderDocumentController controller = new TenderDocumentController(
                taskService, mock(TenderDocumentArtifactService.class), mock(TenderReviewTaskService.class),
                mock(TenderReviewArtifactService.class), mock(TenderTemplateUploadService.class));

        ResponseEntity<DocumentGenerationTask> response =
                controller.createTask(new TenderProjectTaskRequest("project-1"));

        assertEquals(202, response.getStatusCode().value());
        assertEquals(task, response.getBody());
    }

    @Test
    void shouldReturnDocxAsSafeAttachment() throws Exception {
        byte[] content = "docx-content".getBytes();
        TenderDocumentArtifactService artifactService = mock(TenderDocumentArtifactService.class);
        when(artifactService.export("task-1", "version-1"))
                .thenReturn(Optional.of(new TenderDocumentArtifactService.ExportedDocument(
                        "招标文件_V2.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        content)));
        TenderDocumentController controller = new TenderDocumentController(
                mock(TenderDocumentTaskService.class), artifactService, mock(TenderReviewTaskService.class),
                mock(TenderReviewArtifactService.class), mock(TenderTemplateUploadService.class));

        ResponseEntity<byte[]> response = controller.exportArtifacts("task-1", "version-1");

        assertEquals(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                response.getHeaders().getContentType());
        assertEquals(content.length, response.getHeaders().getContentLength());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment;"));
        assertArrayEquals(content, response.getBody());
    }

    @Test
    void shouldReturnReviewStatus() {
        TenderReviewTaskService reviewService = mock(TenderReviewTaskService.class);
        TenderReviewSnapshot review = new TenderReviewSnapshot(
                "task-1", "version-1", TenderReviewStatus.REVIEWING,
                "正在生成审核报告", null, null, Instant.now(), Instant.now());
        when(reviewService.find("task-1", "version-1")).thenReturn(Optional.of(review));
        TenderDocumentController controller = new TenderDocumentController(
                mock(TenderDocumentTaskService.class), mock(TenderDocumentArtifactService.class), reviewService,
                mock(TenderReviewArtifactService.class), mock(TenderTemplateUploadService.class));

        ResponseEntity<TenderReviewSnapshot> response = controller.getReview("task-1", "version-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(review, response.getBody());
    }

    @Test
    void shouldReturnReviewDocxAsSafeAttachment() throws Exception {
        byte[] content = "review-docx".getBytes();
        TenderReviewArtifactService artifactService = mock(TenderReviewArtifactService.class);
        when(artifactService.export("task-1", "version-1"))
                .thenReturn(Optional.of(new TenderReviewArtifactService.ExportedReview(
                        "招标文件审核报告_V1.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        content)));
        TenderDocumentController controller = new TenderDocumentController(
                mock(TenderDocumentTaskService.class), mock(TenderDocumentArtifactService.class),
                mock(TenderReviewTaskService.class), artifactService, mock(TenderTemplateUploadService.class));

        ResponseEntity<byte[]> response = controller.exportReview("task-1", "version-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment;"));
        assertArrayEquals(content, response.getBody());
    }
}

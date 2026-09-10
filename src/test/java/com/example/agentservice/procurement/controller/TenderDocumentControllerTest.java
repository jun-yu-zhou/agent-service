package com.example.agentservice.procurement.controller;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentType;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.request.TenderProjectTaskRequest;
import com.example.agentservice.procurement.service.TenderDocumentArtifactService;
import com.example.agentservice.procurement.service.TenderDocumentTaskService;
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
                taskService, mock(TenderDocumentArtifactService.class), mock(TenderTemplateUploadService.class));

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
                mock(TenderDocumentTaskService.class), artifactService, mock(TenderTemplateUploadService.class));

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
}

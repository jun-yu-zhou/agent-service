package com.example.agentservice.procurement.controller;

import com.example.agentservice.procurement.domain.ArtifactType;
import com.example.agentservice.procurement.service.TenderDocumentArtifactService;
import com.example.agentservice.procurement.service.TenderDocumentGenerationService;
import com.example.agentservice.procurement.service.TenderDocumentTaskService;
import com.example.agentservice.procurement.service.TenderOutlineUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenderDocumentControllerTest {

    @Test
    void shouldReturnSafeAttachmentHeadersForArtifactDownloads() {
        TenderDocumentArtifactService artifactService = mock(TenderDocumentArtifactService.class);
        StreamingResponseBody body = output -> { };
        when(artifactService.download("task-1", "version-1", ArtifactType.PDF))
                .thenReturn(Optional.of(new TenderDocumentArtifactService.ArtifactDownload(
                        "招标文件_V2.pdf", "application/pdf", 128, body)));
        TenderDocumentController controller = new TenderDocumentController(
                mock(TenderDocumentGenerationService.class), mock(TenderDocumentTaskService.class),
                artifactService, mock(TenderOutlineUploadService.class));

        ResponseEntity<StreamingResponseBody> response =
                controller.downloadArtifact("task-1", "version-1", ArtifactType.PDF);

        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals(128, response.getHeaders().getContentLength());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment;"));
        assertSame(body, response.getBody());
    }
}

package com.example.agentservice.procurement.bid.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.bid.persistence.BidDocumentEntity;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.example.agentservice.procurement.bid.domain.BidConsistencyReview;
import com.example.agentservice.procurement.bid.request.BidDocumentCreateRequest;
import com.example.agentservice.procurement.bid.service.BidDocumentTaskService;
import com.example.agentservice.procurement.bid.service.BidDocumentArtifactService;
import com.example.agentservice.procurement.bid.request.BidDocumentEditRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BidDocumentControllerTest {

    private final BidDocumentTaskService taskService = mock(BidDocumentTaskService.class);
    private final BidDocumentArtifactService artifactService = mock(BidDocumentArtifactService.class);
    private final BidDocumentController controller = new BidDocumentController(taskService, artifactService);

    @Test
    void shouldReturnAcceptedTask() {
        BidDocumentCreateRequest request = new BidDocumentCreateRequest(
                "招标文件.pdf", "https://example.com/file.pdf", new ObjectMapper().createObjectNode());
        when(taskService.create(request)).thenReturn(task("task-1"));

        var response = controller.create(request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("task-1", response.getBody().taskId());
    }

    @Test
    void shouldReturnNotFoundForUnknownTask() {
        when(taskService.find("missing")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.find("missing").getStatusCode());
    }

    @Test
    void shouldExposeOutlineForEditing() {
        BidTechnicalOutline outline = new BidTechnicalOutline(
                "技术方案", List.of(new BidTechnicalOutline.Section(
                        "one", "实施方案", null, List.of(), List.of())));
        when(taskService.findOutline("task-1")).thenReturn(Optional.of(outline));

        var response = controller.findOutline("task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("技术方案", response.getBody().title());
    }

    @Test
    void shouldReturnAcceptedAfterOutlineConfirmation() {
        BidDocumentEntity document = task("task-1");
        document.setStage("CONTENT_GENERATING");
        document.setOutlineConfirmed(true);
        when(taskService.confirmOutline("task-1")).thenReturn(Optional.of(document));

        var response = controller.confirmOutline("task-1");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("CONTENT_GENERATING", response.getBody().stage());
    }

    @Test
    void shouldReturnGeneratedDocumentAndReview() {
        var result = new BidDocumentTaskService.Result(
                "# 技术方案\n\n正文",
                new BidConsistencyReview("PASS", "响应完整", List.of()));
        when(taskService.findResult("task-1")).thenReturn(Optional.of(result));

        var response = controller.findResult("task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("# 技术方案\n\n正文", response.getBody().documentMarkdown());
        assertEquals("PASS", response.getBody().consistencyReview().conclusion());
    }

    @Test
    void shouldDownloadCompletedWordWithSafeHeaders() throws Exception {
        byte[] content = {1, 2, 3};
        when(artifactService.export("task-1")).thenReturn(Optional.of(content));

        var response = controller.downloadDocument("task-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(3, response.getBody().length);
    }

    @Test
    void shouldReturnNotFoundWhenDownloadingUnknownTask() throws Exception {
        when(artifactService.export("missing")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.downloadDocument("missing").getStatusCode());
    }

    @Test
    void shouldAcceptManualDocumentSave() {
        BidDocumentEntity document = task("task-1");
        document.setStage("CONSISTENCY_REVIEWING");
        when(taskService.saveDocument("task-1", "# 修改后的方案"))
                .thenReturn(Optional.of(document));

        var response = controller.saveDocument(
                "task-1", new BidDocumentEditRequest("# 修改后的方案"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("CONSISTENCY_REVIEWING", response.getBody().stage());
    }

    private BidDocumentEntity task(String taskId) {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setTaskId(taskId);
        document.setSourceFileName("招标文件.pdf");
        document.setStage("EXTRACTING");
        document.setOutlineConfirmed(false);
        return document;
    }
}

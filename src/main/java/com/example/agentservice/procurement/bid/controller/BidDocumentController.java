package com.example.agentservice.procurement.bid.controller;

import com.example.agentservice.procurement.bid.persistence.BidDocumentEntity;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.example.agentservice.procurement.bid.request.BidDocumentCreateRequest;
import com.example.agentservice.procurement.bid.request.BidDocumentEditRequest;
import com.example.agentservice.procurement.bid.service.BidDocumentTaskService;
import com.example.agentservice.procurement.bid.service.BidDocumentArtifactService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 投标技术方案生成接口。 */
@RestController
@RequestMapping("/api/procurement/bid-documents")
public class BidDocumentController {

    private final BidDocumentTaskService taskService;
    private final BidDocumentArtifactService artifactService;

    public BidDocumentController(BidDocumentTaskService taskService,
            BidDocumentArtifactService artifactService) {
        this.taskService = taskService;
        this.artifactService = artifactService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskResponse> create(@RequestBody BidDocumentCreateRequest request) {
        return ResponseEntity.accepted().body(TaskResponse.from(taskService.create(request)));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskResponse> find(@PathVariable String taskId) {
        return taskService.find(taskId)
                .map(TaskResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/outline")
    public ResponseEntity<BidTechnicalOutline> findOutline(@PathVariable String taskId) {
        return taskService.findOutline(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/tasks/{taskId}/outline")
    public ResponseEntity<BidTechnicalOutline> saveOutline(
            @PathVariable String taskId, @RequestBody BidTechnicalOutline outline) {
        return taskService.saveOutline(taskId, outline)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/outline/confirm")
    public ResponseEntity<TaskResponse> confirmOutline(@PathVariable String taskId) {
        return taskService.confirmOutline(taskId)
                .map(TaskResponse::from)
                .map(response -> ResponseEntity.accepted().body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/result")
    public ResponseEntity<BidDocumentTaskService.Result> findResult(@PathVariable String taskId) {
        return taskService.findResult(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/document/download")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable String taskId) throws IOException {
        return artifactService.export(taskId)
                .map(content -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                        .contentLength(content.length)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename("投标技术方案.docx", StandardCharsets.UTF_8).build().toString())
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(content))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/tasks/{taskId}/document")
    public ResponseEntity<TaskResponse> saveDocument(
            @PathVariable String taskId, @RequestBody BidDocumentEditRequest request) {
        return taskService.saveDocument(taskId, request.documentMarkdown())
                .map(TaskResponse::from)
                .map(response -> ResponseEntity.accepted().body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 前端轮询所需的精简任务快照。 */
    public record TaskResponse(
            String taskId,
            String sourceFileName,
            String stage,
            boolean outlineConfirmed,
            String errorMessage) {

        private static TaskResponse from(BidDocumentEntity document) {
            return new TaskResponse(
                    document.getTaskId(),
                    document.getSourceFileName(),
                    document.getStage(),
                    Boolean.TRUE.equals(document.getOutlineConfirmed()),
                    document.getErrorMessage());
        }
    }
}

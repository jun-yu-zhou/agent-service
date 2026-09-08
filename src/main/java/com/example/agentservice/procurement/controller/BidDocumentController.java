package com.example.agentservice.procurement.controller;

import com.example.agentservice.procurement.request.BidDraftPreviewRequest;
import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.service.BidDocumentGenerationService;
import com.example.agentservice.procurement.service.BidDocumentTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供投标文件初稿预览接口，不保存文档或创建后台任务。 */
@RestController
@RequestMapping("/api/procurement/bid-drafts")
@Tag(name = "投标文件", description = "投标文件初稿生成与审查")
public class BidDocumentController {

    private final BidDocumentGenerationService generationService;
    private final BidDocumentTaskService taskService;

    public BidDocumentController(
            BidDocumentGenerationService generationService, BidDocumentTaskService taskService) {
        this.generationService = generationService;
        this.taskService = taskService;
    }

    @PostMapping("/preview")
    @Operation(summary = "预览投标初稿", description = "根据招标正文和供应商资料生成 Markdown 初稿；不保存任务或文件。")
    public ResponseEntity<BidDraftPreviewResponse> preview(@RequestBody BidDraftPreviewRequest request) {
        String draft = generationService.generateDraft(request.tenderText(), request.supplierText());
        return ResponseEntity.ok(new BidDraftPreviewResponse(draft));
    }

    @PostMapping("/tasks")
    @Operation(summary = "创建投标初稿生成任务", description = "异步生成投标初稿，立即返回任务状态。任务仅保存在当前进程内。")
    public ResponseEntity<DocumentGenerationTask> createTask(@RequestBody BidDraftPreviewRequest request) {
        return ResponseEntity.accepted().body(taskService.submit(request.tenderText(), request.supplierText()));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询投标生成任务状态", description = "查询当前进程内的异步投标初稿生成任务。")
    public ResponseEntity<DocumentGenerationTask> getTask(@PathVariable String taskId) {
        return taskService.findTask(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/result")
    @Operation(summary = "获取投标生成任务结果", description = "任务完成后返回初稿；尚未完成时返回当前任务快照。")
    public ResponseEntity<BidTaskResultResponse> getTaskResult(@PathVariable String taskId) {
        return taskService.findTask(taskId)
                .map(task -> taskService.findResult(taskId)
                        .map(draft -> ResponseEntity.ok(new BidTaskResultResponse(task, draft)))
                        .orElseGet(() -> ResponseEntity.status(
                                        task.status() == GenerationTaskStatus.FAILED
                                                ? HttpStatus.INTERNAL_SERVER_ERROR
                                                : HttpStatus.ACCEPTED)
                                .body(new BidTaskResultResponse(task, null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record BidDraftPreviewResponse(@Schema(description = "生成的 Markdown 投标文件初稿") String draft) {
    }

    public record BidTaskResultResponse(
            @Schema(description = "任务当前快照") DocumentGenerationTask task,
            @Schema(description = "任务完成后生成的 Markdown 初稿") String draft
    ) {
    }
}

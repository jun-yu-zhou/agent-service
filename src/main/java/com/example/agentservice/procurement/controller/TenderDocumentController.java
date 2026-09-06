package com.example.agentservice.procurement.controller;

import com.example.agentservice.procurement.request.TenderDraftPreviewRequest;
import com.example.agentservice.procurement.request.TenderDocumentOssTaskRequest;
import com.example.agentservice.procurement.request.TenderManualVersionRequest;
import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.service.TenderDocumentGenerationService;
import com.example.agentservice.procurement.service.TenderDocumentTaskService;
import com.example.agentservice.procurement.service.TenderDocumentArtifactService;
import com.example.agentservice.procurement.service.TenderDraftConsistencyChecker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP preview endpoint; it does not persist documents or create background tasks. */
@RestController
@RequestMapping("/api/procurement/tender-drafts")
@Tag(name = "招标文件", description = "招标文件初稿生成与审查")
public class TenderDocumentController {

    private final TenderDocumentGenerationService generationService;
    private final TenderDocumentTaskService taskService;
    private final TenderDocumentArtifactService artifactService;

    public TenderDocumentController(
            TenderDocumentGenerationService generationService, TenderDocumentTaskService taskService,
            TenderDocumentArtifactService artifactService) {
        this.generationService = generationService;
        this.taskService = taskService;
        this.artifactService = artifactService;
    }

    @PostMapping("/preview")
    @Operation(summary = "预览招标初稿", description = "根据来源正文生成 Markdown 初稿并返回确定性遗漏检查结果；不保存任务或文件。")
    public ResponseEntity<TenderDraftPreviewResponse> preview(@RequestBody TenderDraftPreviewRequest request) {
        TenderDocumentGenerationService.DraftGenerationResult result =
                generationService.generateDraftWithCheck(request.sourceText());
        return ResponseEntity.ok(new TenderDraftPreviewResponse(result.draft(), result.consistency()));
    }

    @PostMapping("/tasks")
    @Operation(summary = "创建招标初稿生成任务", description = "异步生成初稿和确定性检查结果，立即返回任务状态。任务结果临时保存在 Redis。")
    public ResponseEntity<DocumentGenerationTask> createTask(@RequestBody TenderDraftPreviewRequest request) {
        return ResponseEntity.accepted().body(taskService.submit(request.sourceText()));
    }

    @PostMapping("/tasks/from-oss")
    @Operation(summary = "从 OSS 文件创建招标初稿生成任务", description = "使用 IMM 提取招标文件正文后异步生成初稿和确定性检查。")
    public ResponseEntity<DocumentGenerationTask> createTaskFromOss(
            @RequestBody TenderDocumentOssTaskRequest request) {
        return ResponseEntity.accepted().body(taskService.submitDocument(request.documentOssUrl()));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询招标生成任务状态", description = "查询 Redis 中暂存的异步招标初稿生成任务状态。")
    public ResponseEntity<DocumentGenerationTask> getTask(@PathVariable String taskId) {
        return taskService.findTask(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/review")
    @Operation(summary = "审查已生成的招标初稿", description = "异步执行语义一致性审查；任务须先完成初稿生成。")
    public ResponseEntity<DocumentGenerationTask> reviewTask(@PathVariable String taskId) {
        return taskService.review(taskId)
                .map(task -> ResponseEntity.accepted().body(task))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/auto-revise")
    @Operation(summary = "自动修订已审查的招标初稿", description = "仅可执行一次：依据首次审查结果修订初稿，并自动执行确定性检查和二次语义审查。")
    public ResponseEntity<DocumentGenerationTask> autoReviseTask(@PathVariable String taskId) {
        return taskService.autoRevise(taskId)
                .map(task -> ResponseEntity.accepted().body(task))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/versions")
    @Operation(summary = "查询招标文件版本列表", description = "返回初稿、自动修订和人工保存的版本元数据及 Markdown 内容。")
    public ResponseEntity<?> getVersions(@PathVariable String taskId) {
        return taskService.findVersions(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/versions")
    @Operation(summary = "保存人工编辑版本", description = "将完整 Markdown 保存为当前版本的子版本；不会自动调用模型。")
    public ResponseEntity<DocumentVersion> saveManualVersion(
            @PathVariable String taskId, @RequestBody TenderManualVersionRequest request) {
        return taskService.saveManualVersion(taskId, request.markdown(), request.changedBy())
                .map(version -> ResponseEntity.status(HttpStatus.CREATED).body(version))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/versions/{versionId}/finalize")
    @Operation(summary = "确认招标文件定稿", description = "将指定版本标记为唯一已定稿版本，并切换任务当前版本。")
    public ResponseEntity<DocumentVersion> finalizeVersion(
            @PathVariable String taskId, @PathVariable String versionId) {
        return taskService.finalizeVersion(taskId, versionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/versions/{versionId}/artifacts/export")
    @Operation(summary = "导出定稿 DOCX 和 PDF", description = "将已确认定稿版本渲染为 DOCX/PDF，上传 OSS 并返回临时下载地址。")
    public ResponseEntity<?> exportArtifacts(@PathVariable String taskId, @PathVariable String versionId) throws Exception {
        return artifactService.export(taskId, versionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/result")
    @Operation(summary = "获取招标生成任务结果", description = "任务完成后返回初稿与确定性检查；尚未完成时返回当前任务快照。")
    public ResponseEntity<TenderTaskResultResponse> getTaskResult(@PathVariable String taskId) {
        return taskService.findSnapshot(taskId)
                .map(snapshot -> snapshot.result() == null
                        ? ResponseEntity.status(snapshot.task().status() == GenerationTaskStatus.FAILED
                                        ? HttpStatus.INTERNAL_SERVER_ERROR
                                        : HttpStatus.ACCEPTED)
                                .body(new TenderTaskResultResponse(snapshot.task(), null, null, null, null, null, null, false))
                        : ResponseEntity.ok(new TenderTaskResultResponse(
                                snapshot.task(),
                                snapshot.result().draft(),
                                snapshot.result().consistency(),
                                snapshot.review(),
                                snapshot.originalDraft(),
                                snapshot.originalConsistency(),
                                snapshot.originalReview(),
                                snapshot.autoRevisionApplied())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record TenderDraftPreviewResponse(
            @Schema(description = "生成的 Markdown 招标文件初稿") String draft,
            @Schema(description = "来源正文与初稿的确定性检查结果") TenderDraftConsistencyChecker.ConsistencyResult consistency
    ) {
    }

    public record TenderTaskResultResponse(
            @Schema(description = "任务当前快照") DocumentGenerationTask task,
            @Schema(description = "任务完成后生成的 Markdown 初稿") String draft,
            @Schema(description = "任务完成后的确定性检查结果") TenderDraftConsistencyChecker.ConsistencyResult consistency,
            @Schema(description = "当前版本的语义一致性审查结果") String review,
            @Schema(description = "自动修订后保留的初始 Markdown 初稿") String originalDraft,
            @Schema(description = "自动修订后保留的初始确定性检查结果") TenderDraftConsistencyChecker.ConsistencyResult originalConsistency,
            @Schema(description = "自动修订后保留的首次语义审查结果") String originalReview,
            @Schema(description = "是否已执行过一次自动修订") boolean autoRevisionApplied
    ) {
    }
}

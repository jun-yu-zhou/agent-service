package com.example.agentservice.procurement.controller;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.request.TenderManualVersionRequest;
import com.example.agentservice.procurement.service.TenderDocumentArtifactService;
import com.example.agentservice.procurement.service.TenderDocumentTaskService;
import com.example.agentservice.procurement.service.TenderTemplateUploadService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 根据上传 HTML 模板和结构化项目数据生成招标文件初稿并管理人工编辑版本。 */
@RestController
@RequestMapping("/api/procurement/tender-drafts")
@Tag(name = "招标文件", description = "招标文件初稿生成与人工定稿")
public class TenderDocumentController {

    private final TenderDocumentTaskService taskService;
    private final TenderDocumentArtifactService artifactService;
    private final TenderTemplateUploadService templateUploadService;

    public TenderDocumentController(
            TenderDocumentTaskService taskService, TenderDocumentArtifactService artifactService,
            TenderTemplateUploadService templateUploadService) {
        this.taskService = taskService;
        this.artifactService = artifactService;
        this.templateUploadService = templateUploadService;
    }

    @PostMapping(value = "/tasks/upload", consumes = "multipart/form-data")
    @Operation(summary = "上传 HTML 模板并创建招标初稿任务", description = "上传 html、htm 招标文件模板和可选项目数据，异步生成初稿。")
    public ResponseEntity<DocumentGenerationTask> uploadTemplate(
            @RequestParam("file") MultipartFile file,
            @RequestPart(value = "projectData", required = false) JsonNode projectData) throws Exception {
        return ResponseEntity.accepted().body(
                taskService.submitTemplate(templateUploadService.readTemplate(file), projectData));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询招标生成任务状态", description = "查询当前招标文件初稿的生成状态。")
    public ResponseEntity<DocumentGenerationTask> getTask(@PathVariable String taskId) {
        return taskService.findTask(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/versions")
    @Operation(summary = "查询招标文件版本列表", description = "返回初稿和人工保存的版本元数据及 Markdown 内容。")
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
    @Operation(summary = "导出定稿 DOCX", description = "在内存中生成已确认定稿版本，并直接作为附件返回。")
    public ResponseEntity<byte[]> exportArtifacts(
            @PathVariable String taskId, @PathVariable String versionId) throws Exception {
        return artifactService.export(taskId, versionId)
                .map(document -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(document.contentType()))
                        .contentLength(document.content().length)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(document.filename(), StandardCharsets.UTF_8).build().toString())
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(document.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/result")
    @Operation(summary = "获取招标生成任务结果", description = "任务完成后返回初稿；尚未完成时返回当前任务快照。")
    public ResponseEntity<TenderTaskResultResponse> getTaskResult(@PathVariable String taskId) {
        return taskService.findSnapshot(taskId)
                .map(snapshot -> snapshot.draft() == null
                        ? ResponseEntity.status(snapshot.task().status() == GenerationTaskStatus.FAILED
                                        ? HttpStatus.INTERNAL_SERVER_ERROR
                                        : HttpStatus.ACCEPTED)
                                .body(new TenderTaskResultResponse(snapshot.task(), null))
                        : ResponseEntity.ok(new TenderTaskResultResponse(snapshot.task(), snapshot.draft())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record TenderTaskResultResponse(
            @Schema(description = "任务当前快照") DocumentGenerationTask task,
            @Schema(description = "任务完成后生成的 Markdown 招标文件初稿") String draft
    ) {
    }
}

package com.example.agentservice.procurement.tender.controller;

import com.example.agentservice.pojo.base.response.R;
import com.example.agentservice.procurement.tender.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.tender.domain.DocumentVersion;
import com.example.agentservice.procurement.tender.domain.TenderReviewSnapshot;
import com.example.agentservice.procurement.tender.request.TenderManualVersionRequest;
import com.example.agentservice.procurement.tender.request.TenderProjectTaskRequest;
import com.example.agentservice.procurement.tender.service.TenderDocumentArtifactService;
import com.example.agentservice.procurement.tender.service.TenderDocumentTaskService;
import com.example.agentservice.procurement.tender.service.TenderReviewTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
    private final TenderReviewTaskService reviewTaskService;

    public TenderDocumentController(
            TenderDocumentTaskService taskService, TenderDocumentArtifactService artifactService,
            TenderReviewTaskService reviewTaskService) {
        this.taskService = taskService;
        this.artifactService = artifactService;
        this.reviewTaskService = reviewTaskService;
    }

    /** 根据业务项目创建异步招标文件生成任务。 */
    @PostMapping("/tasks")
    @Operation(summary = "根据项目创建招标初稿任务", description = "按业务项目 ID 读取项目资料和 HTML 模板，异步生成初稿。")
    public R<DocumentGenerationTask> createTask(@RequestBody TenderProjectTaskRequest request) {
        return R.success(taskService.submitProject(request.id()));
    }

    /** 通过用户上传的模板文件创建异步招标文件生成任务。 */
    @PostMapping(value = "/tasks/with-template", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "根据项目和上传模板创建招标初稿任务", description = "不校验模板格式，直接交由 Managed Agent 使用。")
    public R<DocumentGenerationTask> createTaskWithTemplate(
            @RequestParam("projectId") String projectId,
            @RequestPart("templateFile") MultipartFile templateFile) throws IOException {
        return R.success(taskService.submitProjectWithTemplate(
                projectId, templateFile.getBytes(), templateFile.getOriginalFilename(), templateFile.getContentType()));
    }

    /** 查询招标文件生成任务的当前状态。 */
    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询招标生成任务状态", description = "查询当前招标文件初稿的生成状态。")
    public R<DocumentGenerationTask> getTask(@PathVariable String taskId) {
        return taskService.findTask(taskId)
                .map(R::success)
                .orElseGet(() -> R.error(404, "招标任务不存在"));
    }

    /** 查询当前招标文件正文及其修订信息。 */
    @GetMapping("/tasks/{taskId}/versions")
    @Operation(summary = "查询当前招标文件", description = "返回当前正文的修订信息及 Markdown 内容。")
    public R<?> getVersions(@PathVariable String taskId) {
        return taskService.findVersions(taskId)
                .map(R::success)
                .orElseGet(() -> R.error(404, "招标任务不存在"));
    }

    /** 保存用户人工编辑后的完整招标文件正文。 */
    @PostMapping("/tasks/{taskId}/versions")
    @Operation(summary = "保存人工编辑内容", description = "用完整 Markdown 覆盖当前正文。")
    public R<DocumentVersion> saveManualVersion(
            @PathVariable String taskId, @RequestBody TenderManualVersionRequest request) {
        return taskService.saveManualVersion(taskId, request.markdown())
                .map(R::success)
                .orElseGet(() -> R.error(404, "招标任务不存在"));
    }

    /** 确认指定正文版本为招标文件定稿并启动审核。 */
    @PostMapping("/tasks/{taskId}/versions/{versionId}/finalize")
    @Operation(summary = "确认招标文件定稿", description = "将指定版本标记为唯一已定稿版本，并切换任务当前版本。")
    public R<DocumentVersion> finalizeVersion(
            @PathVariable String taskId, @PathVariable String versionId) {
        return taskService.finalizeVersion(taskId, versionId)
                .map(R::success)
                .orElseGet(() -> R.error(404, "招标任务版本不存在"));
    }

    /** 查询指定定稿版本的审核进度与审核结果。 */
    @GetMapping("/tasks/{taskId}/versions/{versionId}/review")
    @Operation(summary = "查询定稿审核状态", description = "返回指定定稿版本的审核进度、报告或失败原因。")
    public R<TenderReviewSnapshot> getReview(
            @PathVariable String taskId, @PathVariable String versionId) {
        return reviewTaskService.find(taskId, versionId)
                .map(R::success)
                .orElseGet(() -> R.error(404, "审核记录不存在"));
    }

    /** 重新执行指定定稿版本的失败审核任务。 */
    @PostMapping("/tasks/{taskId}/versions/{versionId}/review/retry")
    @Operation(summary = "重试定稿审核", description = "重新执行审核失败的定稿版本。")
    public R<TenderReviewSnapshot> retryReview(
            @PathVariable String taskId, @PathVariable String versionId) {
        return reviewTaskService.retry(taskId, versionId)
                .map(R::success)
                .orElseGet(() -> R.error(404, "审核记录不存在"));
    }

    /** 将 Markdown 审核报告转换为 Word 文件并下载。 */
    @GetMapping("/tasks/{taskId}/versions/{versionId}/review/export")
    @Operation(summary = "导出定稿审核报告", description = "将审核报告转换为 Word 附件并直接返回。")
    public ResponseEntity<byte[]> exportReview(
            @PathVariable String taskId, @PathVariable String versionId) throws Exception {
        return artifactService.exportReview(taskId, versionId)
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

    /** 将数据库中的人工定稿转换为 Word 招标文件并下载。 */
    @PostMapping("/tasks/{taskId}/versions/{versionId}/artifacts/export")
    @Operation(summary = "导出最终版招标文件", description = "将人工确认稿转换为 Word 附件并直接返回。")
    public ResponseEntity<byte[]> exportArtifacts(
            @PathVariable String taskId, @PathVariable String versionId) throws Exception {
        return artifactService.exportDocument(taskId, versionId)
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

    /** 查询任务快照以及生成完成后的招标文件初稿。 */
    @GetMapping("/tasks/{taskId}/result")
    @Operation(summary = "获取招标生成任务结果", description = "任务完成后返回初稿；尚未完成时返回当前任务快照。")
    public R<TenderTaskResultResponse> getTaskResult(@PathVariable String taskId) {
        return taskService.findSnapshot(taskId)
                .map(snapshot -> R.success(new TenderTaskResultResponse(snapshot.task(), snapshot.draft())))
                .orElseGet(() -> R.error(404, "招标任务不存在"));
    }

    public record TenderTaskResultResponse(
            @Schema(description = "任务当前快照") DocumentGenerationTask task,
            @Schema(description = "任务完成后生成的 Markdown 招标文件初稿") String draft
    ) {
    }
}

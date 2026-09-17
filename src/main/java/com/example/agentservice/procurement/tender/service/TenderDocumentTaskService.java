package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.procurement.tender.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.tender.domain.DocumentVersion;
import com.example.agentservice.procurement.tender.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 使用数据库管理招标文件初稿生成、人工编辑和定稿。 */
@Service
public class TenderDocumentTaskService {

    private final TenderDocumentGenerationService generationService;
    private final TenderProjectDataService projectDataService;
    private final TenderDocumentStore documentStore;
    private final TenderReviewTaskService reviewTaskService;
    private final ExecutorService executor;

    public TenderDocumentTaskService(
            TenderDocumentGenerationService generationService,
            TenderProjectDataService projectDataService,
            TenderDocumentStore documentStore,
            TenderReviewTaskService reviewTaskService,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.generationService = generationService;
        this.projectDataService = projectDataService;
        this.documentStore = documentStore;
        this.reviewTaskService = reviewTaskService;
        this.executor = executor;
    }

    /** 根据旧业务项目 ID 自动读取模板和项目资料后创建生成任务。 */
    public DocumentGenerationTask submitProject(String projectId) {
        // 从数据库读取项目资料和模板 ID
        TenderProjectDataService.GenerationInput input = projectDataService.load(projectId);
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DocumentGenerationTask task = snapshot(
                taskId, GenerationTaskStatus.PENDING, "等待生成", null, now, now);
        // 创建数据库记录
        documentStore.create(taskId, projectId, input.templateId(), input.projectData());
        executor.execute(() -> generateDatabase(taskId, input.templateHtml(), input.projectData()));
        return task;
    }

    /** 从数据库查询任务状态，供前端轮询生成进度。 */
    public Optional<DocumentGenerationTask> findTask(String taskId) {
        return documentStore.findTaskState(taskId).map(this::taskSnapshot);
    }

    /** 从数据库的同一条记录读取任务状态和当前最新正文。 */
    public Optional<TaskSnapshot> findSnapshot(String taskId) {
        return documentStore.findDocumentContent(taskId)
                .map(document -> new TaskSnapshot(taskSnapshot(document), document.getDocumentMarkdown()));
    }

    /** 返回数据库中的当前正文，保持前端原有版本列表响应结构。 */
    public Optional<List<DocumentVersionSnapshot>> findVersions(String taskId) {
        return documentStore.findDocumentContent(taskId).map(document -> List.of(new DocumentVersionSnapshot(
                currentVersion(document), document.getDocumentMarkdown())));
    }

    /** 直接覆盖当前 Markdown，不再为每次人工保存创建历史版本。 */
    public Optional<DocumentVersion> saveManualVersion(String taskId, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("人工编辑后的招标文件不能为空");
        }
        synchronized (lock(taskId)) {
            Optional<TenderDocumentEntity> optional = documentStore.findDocumentContent(taskId);
            if (optional.isEmpty()) return Optional.empty();
            TenderDocumentEntity document = optional.get();
            if (document.getDocumentMarkdown() == null || document.getDocumentMarkdown().isBlank()) {
                throw new IllegalStateException("初稿尚未生成完成，不能保存人工版本");
            }
            if (!GenerationTaskStatus.COMPLETED.name().equals(document.getGenerationStatus())) {
                throw new IllegalStateException("当前任务状态不能保存人工版本: " + document.getGenerationStatus());
            }
            return documentStore.saveMarkdown(taskId, markdown.trim()).map(saved -> new DocumentVersion(
                    saved.getId(), saved.getTaskId(), saved.getContentRevision(), "MANUAL_EDIT", false));
        }
    }

    /** 将指定的已保存版本确认为当前任务的定稿版本。 */
    public Optional<DocumentVersion> finalizeVersion(String taskId, String versionId) {
        synchronized (lock(taskId)) {
            Optional<TenderDocumentEntity> optional = documentStore.findDocumentContent(taskId);
            if (optional.isEmpty() || !versionId.equals(optional.get().getId())) return Optional.empty();
            TenderDocumentEntity document = optional.get();
            if (document.getDocumentMarkdown() == null || document.getDocumentMarkdown().isBlank()) {
                throw new IllegalStateException("招标文件正文为空，不能确认定稿");
            }
            return documentStore.finalizeDocument(taskId).map(saved -> {
                reviewTaskService.start(taskId, versionId);
                return currentVersion(saved);
            });
        }
    }

    /** 旧项目入口生成的正文和状态直接写入数据库。 */
    private void generateDatabase(String taskId, String templateHtml, JsonNode projectData) {
        try {
            documentStore.markGenerating(taskId);
            documentStore.completeGeneration(taskId, generationService.generateDraft(templateHtml, projectData));
        } catch (Exception exception) {
            documentStore.failGeneration(taskId, exception.getMessage());
        }
    }

    /** 构造新建任务时的快照，此时还没有当前版本。 */
    private DocumentGenerationTask snapshot(
            String taskId, GenerationTaskStatus status, String stage, String errorMessage, Instant createdAt, Instant updatedAt) {
        return new DocumentGenerationTask(taskId, status, stage, null, errorMessage, createdAt, updatedAt);
    }

    /** 将数据库记录转换为现有 REST 接口使用的任务快照。 */
    private DocumentGenerationTask taskSnapshot(TenderDocumentEntity document) {
        Instant createdAt = toInstant(document.getCreatedAt());
        Instant updatedAt = toInstant(document.getUpdatedAt());
        return new DocumentGenerationTask(
                document.getTaskId(),
                GenerationTaskStatus.valueOf(document.getGenerationStatus()),
                document.getGenerationStage(),
                document.getId(),
                document.getGenerationError(),
                createdAt,
                updatedAt == null ? createdAt : updatedAt);
    }

    private DocumentVersion currentVersion(TenderDocumentEntity document) {
        int revision = document.getContentRevision() == null ? 0 : document.getContentRevision();
        return new DocumentVersion(
                document.getId(),
                document.getTaskId(),
                revision,
                revision > 1 ? "MANUAL_EDIT" : "AI_GENERATED",
                Boolean.TRUE.equals(document.getFinalized()));
    }

    private Instant toInstant(java.time.LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 同一任务的状态读写串行化。
     *
     * <p>字符串驻留让相同 taskId 取得同一个锁对象；这是进程内互斥，多实例部署时需要换成分布式锁。</p>
     */
    private Object lock(String taskId) {
        return ("procurement:tender:" + taskId).intern();
    }

    /** 任务状态与初稿正文的组合视图，供接口一次返回。 */
    public record TaskSnapshot(
            DocumentGenerationTask task,
            String draft
    ) {
    }

    /** 当前正文及其兼容版本元数据。 */
    public record DocumentVersionSnapshot(
            /** 当前正文的版本元数据。 */
            DocumentVersion version,

            /** 用户当前编辑的完整 Markdown 正文。 */
            String markdown
    ) {
    }
}

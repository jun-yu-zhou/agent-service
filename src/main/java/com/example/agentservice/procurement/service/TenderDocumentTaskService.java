package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentType;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** 基于 Redis 管理招标文件初稿异步生成任务。 */
@Service
public class TenderDocumentTaskService {

    private final TenderDocumentGenerationService generationService;
    private final TenderDocumentDraftWorkflow documentWorkflow;
    private final ProcurementTaskRedisStore taskStore;
    private final ExecutorService executor;

    public TenderDocumentTaskService(
            TenderDocumentGenerationService generationService,
            TenderDocumentDraftWorkflow documentWorkflow,
            ProcurementTaskRedisStore taskStore,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.generationService = generationService;
        this.documentWorkflow = documentWorkflow;
        this.taskStore = taskStore;
        this.executor = executor;
    }

    public DocumentGenerationTask submit(String sourceText) {
        return submit(sourceText, null);
    }

    public DocumentGenerationTask submit(String sourceText, JsonNode projectData) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("招标来源正文不能为空");
        }
        return submit(() -> new TaskRunResult(generationService.generateDraft(sourceText, projectData)));
    }

    public DocumentGenerationTask submitDocument(String documentOssUrl) {
        return submitDocument(documentOssUrl, null);
    }

    public DocumentGenerationTask submitDocument(String documentOssUrl, JsonNode projectData) {
        if (documentOssUrl == null || documentOssUrl.isBlank()) {
            throw new IllegalArgumentException("招标来源文件地址不能为空");
        }
        return submit(() -> {
            return new TaskRunResult(documentWorkflow.generateDraft(documentOssUrl, projectData));
        });
    }

    private DocumentGenerationTask submit(TaskOperation operation) {
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DocumentGenerationTask task = snapshot(taskId, GenerationTaskStatus.PENDING, "等待生成", null, now, now);
        taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(task, null));
        executor.execute(() -> generate(taskId, operation));
        return task;
    }

    public Optional<DocumentGenerationTask> findTask(String taskId) {
        return taskStore.findTender(taskId).map(ProcurementTaskRedisStore.TenderTaskState::task);
    }

    /** 从同一个 Redis 值中读取任务状态及生成结果。 */
    public Optional<TaskSnapshot> findSnapshot(String taskId) {
        return taskStore.findTender(taskId).map(state -> new TaskSnapshot(state.task(), state.draft()));
    }

    /** 返回全部已保存的 Markdown 版本，并按创建顺序排列。 */
    public Optional<List<ProcurementTaskRedisStore.DocumentVersionSnapshot>> findVersions(String taskId) {
        return taskStore.findTender(taskId).map(ignored -> taskStore.findVersions(taskId));
    }

    /** 将人工编辑后的 Markdown 保存为当前版本的子版本。 */
    public Optional<DocumentVersion> saveManualVersion(String taskId, String markdown, String changedBy) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("人工编辑后的招标文件不能为空");
        }
        synchronized (lock(taskId)) {
            Optional<ProcurementTaskRedisStore.TenderTaskState> optionalState = taskStore.findTender(taskId);
            if (optionalState.isEmpty()) {
                return Optional.empty();
            }
            ProcurementTaskRedisStore.TenderTaskState state = optionalState.get();
            if (state.draft() == null || state.draft().isBlank()) {
                throw new IllegalStateException("初稿尚未生成完成，不能保存人工版本");
            }
            if (state.task().status() != GenerationTaskStatus.COMPLETED) {
                throw new IllegalStateException("当前任务状态不能保存人工版本: " + state.task().status());
            }
            state = ensureCurrentVersion(state);
            List<ProcurementTaskRedisStore.DocumentVersionSnapshot> versions = taskStore.findVersions(taskId);
            String versionId = UUID.randomUUID().toString();
            String draft = markdown.trim();
            DocumentGenerationTask updatedTask = updateVersion(
                    state.task(), GenerationTaskStatus.COMPLETED, "人工编辑版本已保存", null, versionId);
            taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(updatedTask, draft));
            ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot = versionSnapshot(
                    updatedTask, versionId, state.task().currentVersionId(), versions.size() + 1,
                    "MANUAL_EDIT", normalizeEditor(changedBy), draft, false);
            taskStore.saveVersion(snapshot);
            return Optional.of(snapshot.version());
        }
    }

    /** 将指定的已保存版本确认为当前任务的定稿版本。 */
    public Optional<DocumentVersion> finalizeVersion(String taskId, String versionId) {
        synchronized (lock(taskId)) {
            Optional<ProcurementTaskRedisStore.TenderTaskState> optionalState = taskStore.findTender(taskId);
            Optional<ProcurementTaskRedisStore.DocumentVersionSnapshot> optionalVersion = taskStore.findVersion(taskId, versionId);
            if (optionalState.isEmpty() || optionalVersion.isEmpty()) {
                return Optional.empty();
            }
            ProcurementTaskRedisStore.TenderTaskState state = optionalState.get();
            ProcurementTaskRedisStore.DocumentVersionSnapshot selected = optionalVersion.get();
            for (ProcurementTaskRedisStore.DocumentVersionSnapshot version : taskStore.findVersions(taskId)) {
                if (version.version().finalized()) {
                    taskStore.saveVersion(withFinalized(version, false));
                }
            }
            ProcurementTaskRedisStore.DocumentVersionSnapshot finalized = withFinalized(selected, true);
            taskStore.saveVersion(finalized);
            DocumentGenerationTask completedTask = updateVersion(
                    state.task(), GenerationTaskStatus.COMPLETED, "已确认定稿", null, versionId);
            taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(completedTask, finalized.markdown()));
            return Optional.of(finalized.version());
        }
    }

    private void generate(String taskId, TaskOperation operation) {
        try {
            updateTask(taskId, GenerationTaskStatus.GENERATING, "正在生成初稿", null);
            TaskRunResult taskRunResult = operation.run();
            synchronized (lock(taskId)) {
                taskStore.findTender(taskId).ifPresent(state -> {
                    String versionId = UUID.randomUUID().toString();
                    DocumentGenerationTask completedTask = updateVersion(
                            state.task(), GenerationTaskStatus.COMPLETED, "初稿生成完成", null, versionId);
                    taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                            completedTask, taskRunResult.draft()));
                    taskStore.saveVersion(versionSnapshot(
                            completedTask, versionId, null, 1, "AI_GENERATED", "system",
                            taskRunResult.draft(), false));
                });
            }
        } catch (Exception exception) {
            updateTask(taskId, GenerationTaskStatus.FAILED, "生成失败", exception.getMessage());
        }
    }

    private void updateTask(String taskId, GenerationTaskStatus status, String stage, String errorMessage) {
        synchronized (lock(taskId)) {
            taskStore.findTender(taskId).ifPresent(state -> taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                    update(state.task(), status, stage, errorMessage), state.draft())));
        }
    }

    private DocumentGenerationTask snapshot(
            String taskId, GenerationTaskStatus status, String stage, String errorMessage, Instant createdAt, Instant updatedAt) {
        return new DocumentGenerationTask(taskId, DocumentType.TENDER, status, stage, null, errorMessage, createdAt, updatedAt);
    }

    private DocumentGenerationTask update(
            DocumentGenerationTask task, GenerationTaskStatus status, String stage, String errorMessage) {
        return new DocumentGenerationTask(task.taskId(), task.documentType(), status, stage,
                task.currentVersionId(), errorMessage, task.createdAt(), Instant.now());
    }

    private DocumentGenerationTask updateVersion(
            DocumentGenerationTask task, GenerationTaskStatus status, String stage, String errorMessage, String versionId) {
        return new DocumentGenerationTask(task.taskId(), task.documentType(), status, stage,
                versionId, errorMessage, task.createdAt(), Instant.now());
    }

    private Object lock(String taskId) {
        return ("procurement:tender:" + taskId).intern();
    }

    private ProcurementTaskRedisStore.TenderTaskState ensureCurrentVersion(
            ProcurementTaskRedisStore.TenderTaskState state) {
        if (!taskStore.findVersions(state.task().taskId()).isEmpty()) {
            return state;
        }
        String versionId = state.task().currentVersionId() == null ? UUID.randomUUID().toString() : state.task().currentVersionId();
        DocumentGenerationTask task = updateVersion(
                state.task(), state.task().status(), state.task().currentStage(), state.task().errorMessage(), versionId);
        ProcurementTaskRedisStore.TenderTaskState versionedState = new ProcurementTaskRedisStore.TenderTaskState(
                task, state.draft());
        taskStore.saveTender(versionedState);
        taskStore.saveVersion(versionSnapshot(task, versionId, null, 1, "AI_GENERATED", "system",
                state.draft(), false));
        return versionedState;
    }

    private ProcurementTaskRedisStore.DocumentVersionSnapshot versionSnapshot(
            DocumentGenerationTask task, String versionId, String parentVersionId, int versionNumber,
            String changeSource, String changedBy, String markdown,
            boolean finalized) {
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                new DocumentVersion(versionId, task.taskId(), parentVersionId, versionNumber, changeSource,
                        changedBy, Instant.now(), finalized, List.of()),
                markdown);
    }

    private ProcurementTaskRedisStore.DocumentVersionSnapshot withFinalized(
            ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot, boolean finalized) {
        DocumentVersion version = snapshot.version();
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                new DocumentVersion(version.versionId(), version.taskId(), version.parentVersionId(), version.versionNumber(),
                        version.changeSource(), version.changedBy(), version.createdAt(), finalized, version.artifacts()),
                snapshot.markdown());
    }

    private String normalizeEditor(String changedBy) {
        return changedBy == null || changedBy.isBlank() ? "operator" : changedBy.trim();
    }

    @FunctionalInterface
    private interface TaskOperation {
        TaskRunResult run() throws Exception;
    }

    private record TaskRunResult(String draft) {
    }

    public record TaskSnapshot(
            DocumentGenerationTask task,
            String draft
    ) {
    }
}

package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentType;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Redis-backed asynchronous task runner for tender draft generation. */
@Service
public class TenderDocumentTaskService {

    private final TenderDocumentGenerationService generationService;
    private final TenderDocumentDraftWorkflow documentWorkflow;
    private final TenderDraftConsistencyChecker consistencyChecker;
    private final ProcurementTaskRedisStore taskStore;
    private final ExecutorService executor;

    public TenderDocumentTaskService(
            TenderDocumentGenerationService generationService,
            TenderDocumentDraftWorkflow documentWorkflow,
            TenderDraftConsistencyChecker consistencyChecker,
            ProcurementTaskRedisStore taskStore,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.generationService = generationService;
        this.documentWorkflow = documentWorkflow;
        this.consistencyChecker = consistencyChecker;
        this.taskStore = taskStore;
        this.executor = executor;
    }

    public DocumentGenerationTask submit(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("招标来源正文不能为空");
        }
        return submit(() -> new TaskRunResult(generationService.generateDraftWithCheck(sourceText), sourceText));
    }

    public DocumentGenerationTask submitDocument(String documentOssUrl) {
        if (documentOssUrl == null || documentOssUrl.isBlank()) {
            throw new IllegalArgumentException("招标来源文件地址不能为空");
        }
        return submit(() -> {
            String sourceText = documentWorkflow.extractSourceText(documentOssUrl);
            return new TaskRunResult(generationService.generateDraftWithCheck(sourceText), sourceText);
        });
    }

    private DocumentGenerationTask submit(TaskOperation operation) {
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DocumentGenerationTask task = snapshot(taskId, GenerationTaskStatus.PENDING, "等待生成", null, now, now);
        taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(task, null, null, null, null, null, null, false));
        executor.execute(() -> generate(taskId, operation));
        return task;
    }

    public Optional<DocumentGenerationTask> findTask(String taskId) {
        return taskStore.findTender(taskId).map(ProcurementTaskRedisStore.TenderTaskState::task);
    }

    public Optional<TenderDocumentGenerationService.DraftGenerationResult> findResult(String taskId) {
        return taskStore.findTender(taskId).map(ProcurementTaskRedisStore.TenderTaskState::result);
    }

    public Optional<String> findReview(String taskId) {
        return taskStore.findTender(taskId).map(ProcurementTaskRedisStore.TenderTaskState::review);
    }

    /** Reads task state and its outputs from one Redis value. */
    public Optional<TaskSnapshot> findSnapshot(String taskId) {
        return taskStore.findTender(taskId)
                .map(state -> new TaskSnapshot(
                        state.task(), state.result(), state.review(), state.originalDraft(),
                        state.originalConsistency(), state.originalReview(), state.autoRevisionApplied()));
    }

    /** Returns all persisted Markdown versions, ordered from oldest to newest. */
    public Optional<List<ProcurementTaskRedisStore.DocumentVersionSnapshot>> findVersions(String taskId) {
        return taskStore.findTender(taskId).map(ignored -> taskStore.findVersions(taskId));
    }

    /** Saves an operator-edited Markdown draft as a child of the current version. */
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
            if (state.result() == null || state.sourceText() == null || state.sourceText().isBlank()) {
                throw new IllegalStateException("初稿尚未生成完成，不能保存人工版本");
            }
            if (state.task().status() != GenerationTaskStatus.COMPLETED) {
                throw new IllegalStateException("当前任务状态不能保存人工版本: " + state.task().status());
            }
            state = ensureCurrentVersion(state);
            List<ProcurementTaskRedisStore.DocumentVersionSnapshot> versions = taskStore.findVersions(taskId);
            String versionId = UUID.randomUUID().toString();
            TenderDocumentGenerationService.DraftGenerationResult result =
                    new TenderDocumentGenerationService.DraftGenerationResult(markdown.trim(), consistencyChecker.check(state.sourceText(), markdown));
            DocumentGenerationTask updatedTask = updateVersion(
                    state.task(), GenerationTaskStatus.COMPLETED, "人工编辑版本已保存", null, versionId);
            taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                    updatedTask, result, null, state.sourceText(), state.originalDraft(), state.originalConsistency(),
                    state.originalReview(), state.autoRevisionApplied()));
            ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot = versionSnapshot(
                    updatedTask, versionId, state.task().currentVersionId(), versions.size() + 1,
                    "MANUAL_EDIT", normalizeEditor(changedBy), result, null, false);
            taskStore.saveVersion(snapshot);
            return Optional.of(snapshot.version());
        }
    }

    /** Confirms one saved version as the final version for this task. */
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
            TenderDocumentGenerationService.DraftGenerationResult result = finalized.consistencyResult() == null
                    ? new TenderDocumentGenerationService.DraftGenerationResult(
                            finalized.markdown(), consistencyChecker.check(state.sourceText(), finalized.markdown()))
                    : finalized.consistencyResult();
            DocumentGenerationTask completedTask = updateVersion(
                    state.task(), GenerationTaskStatus.COMPLETED, "已确认定稿", null, versionId);
            taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                    completedTask, result, finalized.review(), state.sourceText(), state.originalDraft(),
                    state.originalConsistency(), state.originalReview(), state.autoRevisionApplied()));
            return Optional.of(finalized.version());
        }
    }

    /** Schedules one semantic review for a completed draft task. */
    public Optional<DocumentGenerationTask> review(String taskId) {
        synchronized (lock(taskId)) {
            Optional<ProcurementTaskRedisStore.TenderTaskState> optionalState = taskStore.findTender(taskId);
            if (optionalState.isEmpty()) {
                return Optional.empty();
            }
            ProcurementTaskRedisStore.TenderTaskState state = optionalState.get();
            if (state.result() == null) {
                throw new IllegalStateException("初稿尚未生成完成，不能发起审查");
            }
            if (state.task().status() == GenerationTaskStatus.REVIEWING) {
                return Optional.of(state.task());
            }
            if (state.task().status() != GenerationTaskStatus.COMPLETED) {
                throw new IllegalStateException("当前任务状态不能发起审查: " + state.task().status());
            }
            DocumentGenerationTask reviewingTask = update(state.task(), GenerationTaskStatus.REVIEWING, "正在审查初稿", null);
            taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                    reviewingTask, state.result(), null, state.sourceText(), state.originalDraft(),
                    state.originalConsistency(), state.originalReview(), state.autoRevisionApplied()));
            executor.execute(() -> executeReview(taskId));
            return Optional.of(reviewingTask);
        }
    }

    /** Revises a reviewed draft once, then runs deterministic and semantic checks on the revision. */
    public Optional<DocumentGenerationTask> autoRevise(String taskId) {
        synchronized (lock(taskId)) {
            Optional<ProcurementTaskRedisStore.TenderTaskState> optionalState = taskStore.findTender(taskId);
            if (optionalState.isEmpty()) {
                return Optional.empty();
            }
            ProcurementTaskRedisStore.TenderTaskState state = optionalState.get();
            if (state.task().status() == GenerationTaskStatus.REVISING) {
                return Optional.of(state.task());
            }
            if (state.autoRevisionApplied()) {
                return Optional.of(state.task());
            }
            if (state.result() == null || state.review() == null || state.review().isBlank()) {
                throw new IllegalStateException("请先完成初稿审查后再自动修订");
            }
            if (state.task().status() != GenerationTaskStatus.COMPLETED) {
                throw new IllegalStateException("当前任务状态不能自动修订: " + state.task().status());
            }
            DocumentGenerationTask revisingTask = update(state.task(), GenerationTaskStatus.REVISING, "正在自动修订初稿", null);
            taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                    revisingTask, state.result(), state.review(), state.sourceText(), state.originalDraft(),
                    state.originalConsistency(), state.originalReview(), false));
            executor.execute(() -> executeAutoRevision(taskId));
            return Optional.of(revisingTask);
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
                            completedTask, taskRunResult.result(), null, taskRunResult.sourceText(), null, null, null, false));
                    taskStore.saveVersion(versionSnapshot(
                            completedTask, versionId, null, 1, "AI_GENERATED", "system",
                            taskRunResult.result(), null, false));
                });
            }
        } catch (Exception exception) {
            updateTask(taskId, GenerationTaskStatus.FAILED, "生成失败", exception.getMessage());
        }
    }

    private void executeReview(String taskId) {
        try {
            ProcurementTaskRedisStore.TenderTaskState state = taskStore.findTender(taskId)
                    .orElseThrow(() -> new IllegalStateException("审查任务不存在或已过期"));
            if (state.result() == null || state.sourceText() == null || state.sourceText().isBlank()) {
                throw new IllegalStateException("审查所需的初稿或来源正文不存在");
            }
            String review = generationService.reviewConsistency(state.sourceText(), state.result().draft());
            synchronized (lock(taskId)) {
                taskStore.findTender(taskId).ifPresent(current -> taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                        update(current.task(), GenerationTaskStatus.COMPLETED, "初稿审查完成", null),
                        current.result(), review, current.sourceText(), current.originalDraft(),
                        current.originalConsistency(), current.originalReview(), current.autoRevisionApplied())));
            }
        } catch (Exception exception) {
            updateTask(taskId, GenerationTaskStatus.FAILED, "审查失败", exception.getMessage());
        }
    }

    private void executeAutoRevision(String taskId) {
        try {
            ProcurementTaskRedisStore.TenderTaskState state = taskStore.findTender(taskId)
                    .orElseThrow(() -> new IllegalStateException("自动修订任务不存在或已过期"));
            String revisedDraft = generationService.reviseDraft(state.sourceText(), state.result().draft(), state.review());
            TenderDocumentGenerationService.DraftGenerationResult revisedResult =
                    new TenderDocumentGenerationService.DraftGenerationResult(
                            revisedDraft, consistencyChecker.check(state.sourceText(), revisedDraft));
            String revisedReview = generationService.reviewConsistency(state.sourceText(), revisedDraft);
            synchronized (lock(taskId)) {
                taskStore.findTender(taskId).ifPresent(current -> {
                    String versionId = UUID.randomUUID().toString();
                    DocumentGenerationTask completedTask = updateVersion(current.task(), GenerationTaskStatus.COMPLETED,
                            "初稿自动修订及复审完成", null, versionId);
                    taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                            completedTask, revisedResult, revisedReview, current.sourceText(),
                            current.originalDraft() == null ? current.result().draft() : current.originalDraft(),
                            current.originalConsistency() == null ? current.result().consistency() : current.originalConsistency(),
                            current.originalReview() == null ? current.review() : current.originalReview(), true));
                    taskStore.saveVersion(versionSnapshot(completedTask, versionId, current.task().currentVersionId(),
                            taskStore.findVersions(taskId).size() + 1, "AI_AUTO_REVISED", "system",
                            revisedResult, revisedReview, false));
                });
            }
        } catch (Exception exception) {
            updateTask(taskId, GenerationTaskStatus.FAILED, "自动修订失败", exception.getMessage());
        }
    }

    private void updateTask(String taskId, GenerationTaskStatus status, String stage, String errorMessage) {
        synchronized (lock(taskId)) {
            taskStore.findTender(taskId).ifPresent(state -> taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                    update(state.task(), status, stage, errorMessage), state.result(), state.review(), state.sourceText(),
                    state.originalDraft(), state.originalConsistency(), state.originalReview(), state.autoRevisionApplied())));
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
                task, state.result(), state.review(), state.sourceText(), state.originalDraft(), state.originalConsistency(),
                state.originalReview(), state.autoRevisionApplied());
        taskStore.saveTender(versionedState);
        taskStore.saveVersion(versionSnapshot(task, versionId, null, 1, "AI_GENERATED", "system",
                state.result(), state.review(), false));
        return versionedState;
    }

    private ProcurementTaskRedisStore.DocumentVersionSnapshot versionSnapshot(
            DocumentGenerationTask task, String versionId, String parentVersionId, int versionNumber,
            String changeSource, String changedBy, TenderDocumentGenerationService.DraftGenerationResult result,
            String review, boolean finalized) {
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                new DocumentVersion(versionId, task.taskId(), parentVersionId, versionNumber, changeSource,
                        changedBy, Instant.now(), finalized, List.of()),
                result.draft(), result, review);
    }

    private ProcurementTaskRedisStore.DocumentVersionSnapshot withFinalized(
            ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot, boolean finalized) {
        DocumentVersion version = snapshot.version();
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                new DocumentVersion(version.versionId(), version.taskId(), version.parentVersionId(), version.versionNumber(),
                        version.changeSource(), version.changedBy(), version.createdAt(), finalized, version.artifacts()),
                snapshot.markdown(), snapshot.consistencyResult(), snapshot.review());
    }

    private String normalizeEditor(String changedBy) {
        return changedBy == null || changedBy.isBlank() ? "operator" : changedBy.trim();
    }

    @FunctionalInterface
    private interface TaskOperation {
        TaskRunResult run() throws Exception;
    }

    private record TaskRunResult(TenderDocumentGenerationService.DraftGenerationResult result, String sourceText) {
    }

    public record TaskSnapshot(
            DocumentGenerationTask task,
            TenderDocumentGenerationService.DraftGenerationResult result,
            String review,
            String originalDraft,
            TenderDraftConsistencyChecker.ConsistencyResult originalConsistency,
            String originalReview,
            boolean autoRevisionApplied
    ) {
    }
}

package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentType;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 管理招标文件初稿异步生成任务。
 *
 * <p>任务快照（状态、阶段、当前版本、初稿正文）与版本快照都存放在 Redis。提交流程只写入 PENDING 快照后交给
 * 线程池，生成线程完成后回写 COMPLETED 并把初稿落为 AI_GENERATED 版本；人工编辑保存为 MANUAL_EDIT 子版本；
 * 定稿时把选中版本标记为 finalized，并把任务当前版本指向该版本。</p>
 */
@Service
public class TenderDocumentTaskService {

    private final TenderDocumentGenerationService generationService;
    private final TenderProjectDataService projectDataService;
    private final ProcurementTaskRedisStore taskStore;
    private final ExecutorService executor;

    public TenderDocumentTaskService(
            TenderDocumentGenerationService generationService,
            TenderProjectDataService projectDataService,
            ProcurementTaskRedisStore taskStore,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.generationService = generationService;
        this.projectDataService = projectDataService;
        this.taskStore = taskStore;
        this.executor = executor;
    }

    /** 根据旧业务项目 ID 自动读取模板和项目资料后创建生成任务。 */
    public DocumentGenerationTask submitProject(String projectId) {
        TenderProjectDataService.GenerationInput input = projectDataService.load(projectId);
        return submitTemplate(input.templateHtml(), input.projectData());
    }

    /** 创建生成任务：先写入等待中的任务快照，再交由线程池异步生成。 */
    public DocumentGenerationTask submitTemplate(String templateHtml, JsonNode projectData) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new IllegalArgumentException("招标文件 HTML 模板不能为空");
        }
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DocumentGenerationTask task = snapshot(taskId, GenerationTaskStatus.PENDING, "等待生成", null, now, now);
        taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(task, null));
        executor.execute(() -> generate(taskId, templateHtml, projectData));
        return task;
    }

    /** 查询任务快照，任务不存在时返回空。 */
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
            // 首次人工保存时补建初稿版本，保证版本链从第 1 版开始。
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
            // 定稿是互斥状态：先清除其余版本的定稿标记，保证同一任务只有一个定稿版本。
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

    /** 生成线程入口：模型调用失败时把任务置为失败并记录原因。 */
    private void generate(String taskId, String templateHtml, JsonNode projectData) {
        try {
            updateTask(taskId, GenerationTaskStatus.GENERATING, "正在生成初稿", null);
            String draft = generationService.generateDraft(templateHtml, projectData);
            synchronized (lock(taskId)) {
                taskStore.findTender(taskId).ifPresent(state -> {
                    String versionId = UUID.randomUUID().toString();
                    DocumentGenerationTask completedTask = updateVersion(
                            state.task(), GenerationTaskStatus.COMPLETED, "初稿生成完成", null, versionId);
                    taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(completedTask, draft));
                    taskStore.saveVersion(versionSnapshot(
                            completedTask, versionId, null, 1, "AI_GENERATED", "system", draft, false));
                });
            }
        } catch (Exception exception) {
            updateTask(taskId, GenerationTaskStatus.FAILED, "生成失败", exception.getMessage());
        }
    }

    /** 只更新状态与阶段，保留已写入的初稿正文。 */
    private void updateTask(String taskId, GenerationTaskStatus status, String stage, String errorMessage) {
        synchronized (lock(taskId)) {
            taskStore.findTender(taskId).ifPresent(state -> taskStore.saveTender(new ProcurementTaskRedisStore.TenderTaskState(
                    update(state.task(), status, stage, errorMessage), state.draft())));
        }
    }

    /** 构造新建任务时的快照，此时还没有当前版本。 */
    private DocumentGenerationTask snapshot(
            String taskId, GenerationTaskStatus status, String stage, String errorMessage, Instant createdAt, Instant updatedAt) {
        return new DocumentGenerationTask(taskId, DocumentType.TENDER, status, stage, null, errorMessage, createdAt, updatedAt);
    }

    /** 保留原有当前版本的状态更新。 */
    private DocumentGenerationTask update(
            DocumentGenerationTask task, GenerationTaskStatus status, String stage, String errorMessage) {
        return new DocumentGenerationTask(task.taskId(), task.documentType(), status, stage,
                task.currentVersionId(), errorMessage, task.createdAt(), Instant.now());
    }

    /** 更新状态并把当前版本指向新的版本号。 */
    private DocumentGenerationTask updateVersion(
            DocumentGenerationTask task, GenerationTaskStatus status, String stage, String errorMessage, String versionId) {
        return new DocumentGenerationTask(task.taskId(), task.documentType(), status, stage,
                versionId, errorMessage, task.createdAt(), Instant.now());
    }

    /**
     * 同一任务的状态读写串行化。
     *
     * <p>字符串驻留让相同 taskId 取得同一个锁对象；这是进程内互斥，多实例部署时需要换成分布式锁。</p>
     */
    private Object lock(String taskId) {
        return ("procurement:tender:" + taskId).intern();
    }

    /** 老任务可能只有初稿没有版本记录，首次保存人工版本时按当前初稿补建第 1 版。 */
    private ProcurementTaskRedisStore.TenderTaskState ensureCurrentVersion(
            ProcurementTaskRedisStore.TenderTaskState state) {
        if (!taskStore.findVersions(state.task().taskId()).isEmpty()) {
            return state;
        }
        String versionId = state.task().currentVersionId() == null ? UUID.randomUUID().toString() : state.task().currentVersionId();
        DocumentGenerationTask task = updateVersion(
                state.task(), state.task().status(), state.task().currentStage(), state.task().errorMessage(), versionId);
        ProcurementTaskRedisStore.TenderTaskState versionedState = new ProcurementTaskRedisStore.TenderTaskState(task, state.draft());
        taskStore.saveTender(versionedState);
        taskStore.saveVersion(versionSnapshot(task, versionId, null, 1, "AI_GENERATED", "system", state.draft(), false));
        return versionedState;
    }

    /** 组装版本快照，创建时间取当前时间。 */
    private ProcurementTaskRedisStore.DocumentVersionSnapshot versionSnapshot(
            DocumentGenerationTask task, String versionId, String parentVersionId, int versionNumber,
            String changeSource, String changedBy, String markdown, boolean finalized) {
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                new DocumentVersion(versionId, task.taskId(), parentVersionId, versionNumber, changeSource,
                        changedBy, Instant.now(), finalized, List.of()),
                markdown);
    }

    /** 只改写定稿标记，其余版本元数据与正文保持不变。 */
    private ProcurementTaskRedisStore.DocumentVersionSnapshot withFinalized(
            ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot, boolean finalized) {
        DocumentVersion version = snapshot.version();
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                new DocumentVersion(version.versionId(), version.taskId(), version.parentVersionId(), version.versionNumber(),
                        version.changeSource(), version.changedBy(), version.createdAt(), finalized, version.artifacts()),
                snapshot.markdown());
    }

    /** 未提供编辑人时记为 operator。 */
    private String normalizeEditor(String changedBy) {
        return changedBy == null || changedBy.isBlank() ? "operator" : changedBy.trim();
    }

    /** 任务状态与初稿正文的组合视图，供接口一次返回。 */
    public record TaskSnapshot(
            DocumentGenerationTask task,
            String draft
    ) {
    }
}

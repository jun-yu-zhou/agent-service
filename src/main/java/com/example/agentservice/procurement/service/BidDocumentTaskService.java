package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentType;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Redis-backed asynchronous task runner for bid draft generation. */
@Service
public class BidDocumentTaskService {

    private final BidDocumentGenerationService generationService;
    private final ProcurementTaskRedisStore taskStore;
    private final ExecutorService executor;

    public BidDocumentTaskService(
            BidDocumentGenerationService generationService,
            ProcurementTaskRedisStore taskStore,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.generationService = generationService;
        this.taskStore = taskStore;
        this.executor = executor;
    }

    public DocumentGenerationTask submit(String tenderText, String supplierText) {
        if (tenderText == null || tenderText.isBlank()) {
            throw new IllegalArgumentException("招标文件正文不能为空");
        }
        if (supplierText == null || supplierText.isBlank()) {
            throw new IllegalArgumentException("供应商资料不能为空");
        }
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DocumentGenerationTask task = snapshot(taskId, GenerationTaskStatus.PENDING, "等待生成", null, now, now);
        taskStore.saveBid(new ProcurementTaskRedisStore.BidTaskState(task, null));
        executor.execute(() -> generate(taskId, tenderText, supplierText));
        return task;
    }

    public Optional<DocumentGenerationTask> findTask(String taskId) {
        return taskStore.findBid(taskId).map(ProcurementTaskRedisStore.BidTaskState::task);
    }

    public Optional<String> findResult(String taskId) {
        return taskStore.findBid(taskId).map(ProcurementTaskRedisStore.BidTaskState::draft);
    }

    private void generate(String taskId, String tenderText, String supplierText) {
        try {
            updateTask(taskId, GenerationTaskStatus.GENERATING, "正在生成初稿", null);
            String draft = generationService.generateDraft(tenderText, supplierText);
            synchronized (lock(taskId)) {
                taskStore.findBid(taskId).ifPresent(state -> taskStore.saveBid(new ProcurementTaskRedisStore.BidTaskState(
                        update(state.task(), GenerationTaskStatus.COMPLETED, "初稿生成完成", null), draft)));
            }
        } catch (Exception exception) {
            updateTask(taskId, GenerationTaskStatus.FAILED, "生成失败", exception.getMessage());
        }
    }

    private void updateTask(String taskId, GenerationTaskStatus status, String stage, String errorMessage) {
        synchronized (lock(taskId)) {
            taskStore.findBid(taskId).ifPresent(state -> taskStore.saveBid(new ProcurementTaskRedisStore.BidTaskState(
                    update(state.task(), status, stage, errorMessage), state.draft())));
        }
    }

    private DocumentGenerationTask snapshot(
            String taskId, GenerationTaskStatus status, String stage, String errorMessage, Instant createdAt, Instant updatedAt) {
        return new DocumentGenerationTask(taskId, DocumentType.BID, status, stage, null, errorMessage, createdAt, updatedAt);
    }

    private DocumentGenerationTask update(
            DocumentGenerationTask task, GenerationTaskStatus status, String stage, String errorMessage) {
        return snapshot(task.taskId(), status, stage, errorMessage, task.createdAt(), Instant.now());
    }

    private Object lock(String taskId) {
        return ("procurement:bid:" + taskId).intern();
    }
}

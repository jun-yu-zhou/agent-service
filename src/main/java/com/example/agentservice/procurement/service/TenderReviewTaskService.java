package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.TenderReviewSnapshot;
import com.example.agentservice.procurement.domain.TenderReviewStatus;
import com.example.agentservice.procurement.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.persistence.TenderProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 使用数据库管理定稿审核任务及审核报告。 */
@Service
public class TenderReviewTaskService {

    private final TenderDocumentStore documentStore;
    private final TenderProjectMapper projectMapper;
    private final TenderDocumentReviewService reviewService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public TenderReviewTaskService(
            TenderDocumentStore documentStore,
            TenderProjectMapper projectMapper,
            TenderDocumentReviewService reviewService,
            ObjectMapper objectMapper,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.documentStore = documentStore;
        this.projectMapper = projectMapper;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /** 定稿后异步启动审核；正在审核或已经完成时不重复提交。 */
    public Optional<TenderReviewSnapshot> start(String taskId, String versionId) {
        synchronized (lock(taskId, versionId)) {
            var optional = current(taskId, versionId);
            if (optional.isEmpty()) return Optional.empty();
            TenderDocumentEntity document = optional.get();
            if (!Boolean.TRUE.equals(document.getFinalized())) {
                throw new IllegalStateException("仅已确认定稿版本可以生成审核报告");
            }
            if (TenderReviewStatus.REVIEWING.name().equals(document.getReviewStatus())
                    || TenderReviewStatus.COMPLETED.name().equals(document.getReviewStatus())) {
                return Optional.of(snapshot(document));
            }
            documentStore.updateReview(taskId, TenderReviewStatus.PENDING.name(), "等待审核", null, null);
            executor.execute(() -> review(taskId));
            return documentStore.findByTaskId(taskId).map(this::snapshot);
        }
    }

    public Optional<TenderReviewSnapshot> find(String taskId, String versionId) {
        return current(taskId, versionId).map(this::snapshot);
    }

    /** 失败的审核任务重新进入队列。 */
    public Optional<TenderReviewSnapshot> retry(String taskId, String versionId) {
        var optional = current(taskId, versionId);
        if (optional.isEmpty()) return Optional.empty();
        if (!TenderReviewStatus.FAILED.name().equals(optional.get().getReviewStatus())) {
            throw new IllegalStateException("仅审核失败的报告可以重试");
        }
        documentStore.updateReview(taskId, TenderReviewStatus.PENDING.name(), "等待重新审核", null, null);
        executor.execute(() -> review(taskId));
        return documentStore.findByTaskId(taskId).map(this::snapshot);
    }

    private void review(String taskId) {
        try {
            documentStore.updateReview(taskId, TenderReviewStatus.REVIEWING.name(), "正在生成审核报告", null, null);
            TenderDocumentEntity document = documentStore.findByTaskId(taskId).orElseThrow();
            String templateHtml = projectMapper.selectTemplateHtml(document.getTemplateId());
            String report = reviewService.review(
                    templateHtml, objectMapper.readTree(document.getProjectData()), document.getDocumentMarkdown());
            documentStore.updateReview(taskId, TenderReviewStatus.COMPLETED.name(), "审核报告已生成", report, null);
        } catch (Exception exception) {
            documentStore.updateReview(taskId, TenderReviewStatus.FAILED.name(), "审核失败", null, exception.getMessage());
        }
    }

    private Optional<TenderDocumentEntity> current(String taskId, String versionId) {
        return documentStore.findByTaskId(taskId).filter(document -> versionId.equals(document.getId()));
    }

    private TenderReviewSnapshot snapshot(TenderDocumentEntity document) {
        Instant createdAt = toInstant(document.getFinalizedAt());
        Instant updatedAt = toInstant(document.getReviewedAt());
        return new TenderReviewSnapshot(
                document.getTaskId(), document.getId(), TenderReviewStatus.valueOf(document.getReviewStatus()),
                document.getReviewStage(), document.getReviewReport(), document.getReviewError(),
                createdAt, updatedAt == null ? createdAt : updatedAt);
    }

    private Instant toInstant(java.time.LocalDateTime value) {
        return value == null ? Instant.now() : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Object lock(String taskId, String versionId) {
        return ("procurement:tender-review:" + taskId + ":" + versionId).intern();
    }
}

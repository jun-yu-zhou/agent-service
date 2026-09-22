package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.procurement.tender.domain.TenderReviewSnapshot;
import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 使用数据库管理定稿审核任务及审核报告。 */
@Service
@Slf4j
public class TenderReviewTaskService {

    private final TenderDocumentStore documentStore;
    private final TenderDocumentAiService aiService;
    private final TenderArtifactStorage artifactStorage;
    private final ExecutorService executor;

    public TenderReviewTaskService(
            TenderDocumentStore documentStore,
            TenderDocumentAiService aiService,
            TenderArtifactStorage artifactStorage,
            @Qualifier("tenderReviewExecutor") ExecutorService executor) {
        this.documentStore = documentStore;
        this.aiService = aiService;
        this.artifactStorage = artifactStorage;
        this.executor = executor;
    }

    /** 定稿后异步启动审核；正在审核或已经完成时不重复提交。 */
    public Optional<TenderReviewSnapshot> start(String taskId, String versionId) {
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
        int revision = currentRevision(document);
        if (documentStore.beginReview(taskId, revision, TenderReviewStatus.PENDING)) {
            try {
                executor.execute(() -> review(taskId, revision));
                log.info("招标文件审核任务已提交，taskId={}，revision={}", taskId, revision);
            }
            catch (RuntimeException exception) {
                documentStore.failReview(taskId, revision, errorMessage(exception));
                log.error("招标文件审核任务提交失败，taskId={}，revision={}", taskId, revision, exception);
            }
        }
        return documentStore.findByTaskId(taskId).map(this::snapshot);
    }

    public Optional<TenderReviewSnapshot> find(String taskId, String versionId) {
        return current(taskId, versionId).map(this::snapshot);
    }

    private void review(String taskId, int revision) {
        try {
            log.info("招标文件审核线程开始执行，taskId={}，revision={}", taskId, revision);
            TenderDocumentEntity document = documentStore.findByTaskId(taskId).orElseThrow();
            if (!Boolean.TRUE.equals(document.getFinalized())
                    || document.getContentRevision() == null
                    || document.getContentRevision() != revision
                    || document.getReviewRevision() == null
                    || document.getReviewRevision() != revision) {
                // 只收口仍属于本线程审核版本的声明；人工编辑已清空审核版本时不会被旧线程覆盖。
                documentStore.invalidateReview(taskId, revision, "审核对应的正文版本已经失效");
                return;
            }
            if (document.getSessionId() == null || document.getSessionId().isBlank()) {
                throw new IllegalStateException("招标文件缺少 Managed Agent 会话，无法生成定稿产物");
            }
            log.info("准备向 Managed Agent 原会话发送定稿审核请求，taskId={}，revision={}，sessionId={}",
                    taskId, revision, document.getSessionId());
            TenderDocumentAiService.FinalizedArtifacts artifacts = aiService.reviewFinalizedDocument(
                    document.getSessionId(), document.getDocumentMarkdown());
            TenderArtifactStorage.StoredArtifacts stored =
                    artifactStorage.store(taskId, revision, artifacts);
            documentStore.completeReview(
                    taskId, revision, stored.documentObjectKey(), stored.reviewObjectKey());
            log.info("招标文件定稿和审核报告生成完成，taskId={}，revision={}，sessionId={}",
                    taskId, revision, document.getSessionId());
        } catch (Exception exception) {
            documentStore.failReview(taskId, revision, errorMessage(exception));
            log.error("招标文件审核执行失败，taskId={}，revision={}", taskId, revision, exception);
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
                document.getReviewStage(), TenderReviewStatus.COMPLETED.name().equals(document.getReviewStatus()),
                document.getReviewError(),
                createdAt, updatedAt == null ? createdAt : updatedAt);
    }

    private Instant toInstant(java.time.LocalDateTime value) {
        return value == null ? Instant.now() : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private int currentRevision(TenderDocumentEntity document) {
        return document.getContentRevision() == null ? 0 : document.getContentRevision();
    }

    private String errorMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

}

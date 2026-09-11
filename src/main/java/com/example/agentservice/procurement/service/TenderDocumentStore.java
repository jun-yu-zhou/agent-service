package com.example.agentservice.procurement.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.agentservice.procurement.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.persistence.TenderDocumentMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 使用数据库持久化 AI 招标文件当前正文及处理状态。 */
@DS("master")
@Repository
public class TenderDocumentStore {

    private static final String REVIEW_NOT_STARTED = "NOT_STARTED";

    private final TenderDocumentMapper mapper;

    public TenderDocumentStore(TenderDocumentMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<TenderDocumentEntity> findByTaskId(String taskId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<TenderDocumentEntity>lambdaQuery()
                .eq(TenderDocumentEntity::getTaskId, taskId)));
    }

    /** 创建任务主记录，正文由后台生成完成后写入。 */
    public TenderDocumentEntity create(
            String taskId, String projectId, String templateId, JsonNode projectData) {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId(shortUuid());
        document.setTaskId(taskId);
        document.setProjectId(projectId);
        document.setTemplateId(templateId);
        document.setProjectData(projectData == null || projectData.isNull() ? null : projectData.toString());
        document.setGenerationStatus(GenerationTaskStatus.PENDING.name());
        document.setGenerationStage("等待生成");
        document.setFinalized(false);
        document.setContentRevision(0);
        document.setReviewStatus(REVIEW_NOT_STARTED);
        mapper.insert(document);
        return document;
    }

    public void markGenerating(String taskId) {
        updateGeneration(taskId, GenerationTaskStatus.GENERATING, "正在生成初稿", null, null);
    }

    public void completeGeneration(String taskId, String markdown) {
        updateGeneration(taskId, GenerationTaskStatus.COMPLETED, "初稿生成完成", null, markdown);
    }

    public void failGeneration(String taskId, String errorMessage) {
        updateGeneration(taskId, GenerationTaskStatus.FAILED, "生成失败", errorMessage, null);
    }

    /** 覆盖当前正文，并使旧定稿和旧审核报告立即失效。 */
    public Optional<TenderDocumentEntity> saveMarkdown(String taskId, String markdown) {
        Optional<TenderDocumentEntity> optional = findByTaskId(taskId);
        if (optional.isEmpty()) return Optional.empty();

        TenderDocumentEntity document = optional.get();
        int revision = document.getContentRevision() == null ? 1 : document.getContentRevision() + 1;
        mapper.update(null, Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .set(TenderDocumentEntity::getDocumentMarkdown, markdown)
                .set(TenderDocumentEntity::getContentRevision, revision)
                .set(TenderDocumentEntity::getGenerationStage, "人工编辑内容已保存")
                .set(TenderDocumentEntity::getFinalized, false)
                .set(TenderDocumentEntity::getFinalizedAt, null)
                .set(TenderDocumentEntity::getReviewRevision, null)
                .set(TenderDocumentEntity::getReviewStatus, REVIEW_NOT_STARTED)
                .set(TenderDocumentEntity::getReviewStage, null)
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, null));
        document.setDocumentMarkdown(markdown);
        document.setContentRevision(revision);
        document.setGenerationStage("人工编辑内容已保存");
        document.setFinalized(false);
        document.setFinalizedAt(null);
        document.setReviewRevision(null);
        document.setReviewStatus(REVIEW_NOT_STARTED);
        document.setReviewStage(null);
        document.setReviewReport(null);
        document.setReviewError(null);
        document.setReviewedAt(null);
        document.setUpdatedAt(LocalDateTime.now());
        return Optional.of(document);
    }

    /** 将当前正文确认为定稿，并为同一正文版本初始化审核状态。 */
    public Optional<TenderDocumentEntity> finalizeDocument(String taskId) {
        Optional<TenderDocumentEntity> optional = findByTaskId(taskId);
        if (optional.isEmpty()) return Optional.empty();

        TenderDocumentEntity document = optional.get();
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .set(TenderDocumentEntity::getFinalized, true)
                .set(TenderDocumentEntity::getFinalizedAt, now)
                .set(TenderDocumentEntity::getGenerationStage, "已确认定稿")
                .set(TenderDocumentEntity::getReviewRevision, document.getContentRevision())
                .set(TenderDocumentEntity::getReviewStatus, "PENDING")
                .set(TenderDocumentEntity::getReviewStage, "等待审核")
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, null));
        document.setFinalized(true);
        document.setFinalizedAt(now);
        document.setGenerationStage("已确认定稿");
        document.setReviewRevision(document.getContentRevision());
        document.setReviewStatus("PENDING");
        document.setReviewStage("等待审核");
        document.setReviewReport(null);
        document.setReviewError(null);
        document.setReviewedAt(null);
        document.setUpdatedAt(now);
        return Optional.of(document);
    }

    /** 更新审核进度及结果。 */
    public void updateReview(String taskId, String status, String stage, String report, String error) {
        var update = Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .set(TenderDocumentEntity::getReviewStatus, status)
                .set(TenderDocumentEntity::getReviewStage, stage)
                .set(TenderDocumentEntity::getReviewReport, report)
                .set(TenderDocumentEntity::getReviewError, error);
        if ("COMPLETED".equals(status)) update.set(TenderDocumentEntity::getReviewedAt, LocalDateTime.now());
        mapper.update(null, update);
    }

    private void updateGeneration(
            String taskId,
            GenerationTaskStatus status,
            String stage,
            String error,
            String markdown) {
        var update = Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .set(TenderDocumentEntity::getGenerationStatus, status.name())
                .set(TenderDocumentEntity::getGenerationStage, stage)
                .set(TenderDocumentEntity::getGenerationError, error);
        if (markdown != null) {
            update.set(TenderDocumentEntity::getDocumentMarkdown, markdown)
                    .set(TenderDocumentEntity::getContentRevision, 1);
        }
        mapper.update(null, update);
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

package com.example.agentservice.procurement.tender.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.agentservice.procurement.tender.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentMapper;
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
    public Optional<TenderDocumentEntity> saveMarkdown(
            String taskId, int expectedRevision, boolean expectedFinalized, String markdown) {
        int updated = mapper.update(null, Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .eq(TenderDocumentEntity::getContentRevision, expectedRevision)
                .eq(TenderDocumentEntity::getFinalized, expectedFinalized)
                .eq(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.COMPLETED.name())
                .set(TenderDocumentEntity::getDocumentMarkdown, markdown)
                .set(TenderDocumentEntity::getContentRevision, expectedRevision + 1)
                .set(TenderDocumentEntity::getGenerationStage, "人工编辑内容已保存")
                .set(TenderDocumentEntity::getFinalized, false)
                .set(TenderDocumentEntity::getFinalizedAt, null)
                .set(TenderDocumentEntity::getReviewRevision, null)
                .set(TenderDocumentEntity::getReviewStatus, REVIEW_NOT_STARTED)
                .set(TenderDocumentEntity::getReviewStage, null)
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, null));
        return updated == 1 ? findByTaskId(taskId) : Optional.empty();
    }

    /** 将当前正文确认为定稿，并为同一正文版本初始化审核状态。 */
    public Optional<TenderDocumentEntity> finalizeDocument(String taskId, int expectedRevision) {
        LocalDateTime now = LocalDateTime.now();
        int updated = mapper.update(null, Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .eq(TenderDocumentEntity::getContentRevision, expectedRevision)
                .eq(TenderDocumentEntity::getFinalized, false)
                .set(TenderDocumentEntity::getFinalized, true)
                .set(TenderDocumentEntity::getFinalizedAt, now)
                .set(TenderDocumentEntity::getGenerationStage, "已确认定稿")
                .set(TenderDocumentEntity::getReviewRevision, expectedRevision)
                .set(TenderDocumentEntity::getReviewStatus, "PENDING")
                .set(TenderDocumentEntity::getReviewStage, "等待审核")
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, null));
        return updated == 1 ? findByTaskId(taskId) : Optional.empty();
    }

    /** 原子抢占待执行或失败的审核任务，避免同一版本被重复提交。 */
    public boolean beginReview(String taskId, int revision, String expectedStatus) {
        return mapper.update(null, reviewUpdate(taskId, revision)
                .eq(TenderDocumentEntity::getReviewStatus, expectedStatus)
                .set(TenderDocumentEntity::getReviewStatus, "REVIEWING")
                .set(TenderDocumentEntity::getReviewStage, "正在生成审核报告")
                .set(TenderDocumentEntity::getReviewError, null)) == 1;
    }

    /** 仅允许当前定稿版本的审核任务写入结果，旧任务完成后会被自动丢弃。 */
    public boolean completeReview(String taskId, int revision, String report) {
        return mapper.update(null, reviewUpdate(taskId, revision)
                .eq(TenderDocumentEntity::getReviewStatus, "REVIEWING")
                .set(TenderDocumentEntity::getReviewStatus, "COMPLETED")
                .set(TenderDocumentEntity::getReviewStage, "审核报告已生成")
                .set(TenderDocumentEntity::getReviewReport, report)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, LocalDateTime.now())) == 1;
    }

    /** 审核失败状态同样绑定正文版本，不能覆盖后续人工编辑产生的新版本。 */
    public boolean failReview(String taskId, int revision, String error) {
        return mapper.update(null, reviewUpdate(taskId, revision)
                .eq(TenderDocumentEntity::getReviewStatus, "REVIEWING")
                .set(TenderDocumentEntity::getReviewStatus, "FAILED")
                .set(TenderDocumentEntity::getReviewStage, "审核失败")
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getReviewError, error)) == 1;
    }

    private LambdaUpdateWrapper<TenderDocumentEntity> reviewUpdate(
            String taskId, int revision) {
        return Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .eq(TenderDocumentEntity::getContentRevision, revision)
                .eq(TenderDocumentEntity::getReviewRevision, revision)
                .eq(TenderDocumentEntity::getFinalized, true);
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

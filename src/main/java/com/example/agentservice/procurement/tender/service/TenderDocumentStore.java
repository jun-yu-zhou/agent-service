package com.example.agentservice.procurement.tender.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.agentservice.procurement.tender.domain.GenerationTaskStatus;
import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 使用数据库持久化 AI 招标文件当前正文及处理状态。 */
@DS("master")
@Repository
public class TenderDocumentStore {

    private final TenderDocumentMapper mapper;

    public TenderDocumentStore(TenderDocumentMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<TenderDocumentEntity> findByTaskId(String taskId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<TenderDocumentEntity>lambdaQuery()
                .eq(TenderDocumentEntity::getTaskId, taskId)));
    }

    /** 创建任务主记录，正文由后台生成完成后写入。 */
    public TenderDocumentEntity create(String taskId, String projectId, String templateId) {
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setId(shortUuid());
        document.setTaskId(taskId);
        document.setProjectId(projectId);
        document.setTemplateId(templateId);
        document.setGenerationStatus(GenerationTaskStatus.PENDING.name());
        document.setGenerationStage("等待生成");
        document.setFinalized(false);
        document.setContentRevision(0);
        document.setReviewStatus(TenderReviewStatus.NOT_STARTED.name());
        mapper.insert(document);
        return document;
    }

    /**
     * 原子抢占一个待生成任务。
     *
     * <p>正文版本和定稿状态共同作为乐观锁，避免迟到或重复执行的后台线程重新生成已经被人工处理的文件。</p>
     */
    public boolean markGenerating(String taskId) {
        return mapper.update(null, initialGenerationUpdate(taskId)
                .eq(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.PENDING.name())
                .set(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.GENERATING.name())
                .set(TenderDocumentEntity::getGenerationStage, "正在生成初稿")
                .set(TenderDocumentEntity::getGenerationError, null)) == 1;
    }

    /** 仅允许仍在生成的初始版本写入正文，旧线程不能覆盖人工编辑或定稿内容。 */
    public boolean completeGeneration(String taskId, String sessionId, String markdown) {
        return mapper.update(null, initialGenerationUpdate(taskId)
                .eq(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.GENERATING.name())
                .set(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.COMPLETED.name())
                .set(TenderDocumentEntity::getGenerationStage, "初稿生成完成")
                .set(TenderDocumentEntity::getGenerationError, null)
                .set(TenderDocumentEntity::getSessionId, sessionId)
                .set(TenderDocumentEntity::getDocumentMarkdown, markdown)
                .set(TenderDocumentEntity::getContentRevision, 1)) == 1;
    }

    /**
     * 会话创建成功即保存会话标识，避免首轮事件发送失败后无法回到百炼控制台追踪该会话。
     *
     * <p>仍限定为正在生成的初始版本，过期后台线程不能向已完成任务写入会话。</p>
     */
    public boolean saveGenerationSession(String taskId, String sessionId) {
        return mapper.update(null, initialGenerationUpdate(taskId)
                .eq(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.GENERATING.name())
                .set(TenderDocumentEntity::getSessionId, sessionId)
                .set(TenderDocumentEntity::getGenerationStage, "Managed Agent 会话已创建")) == 1;
    }

    /** 生成失败只能结束预期的生成状态，不能把已完成任务回退为失败。 */
    public boolean failGeneration(
            String taskId, GenerationTaskStatus expectedStatus, String errorMessage) {
        return mapper.update(null, initialGenerationUpdate(taskId)
                .eq(TenderDocumentEntity::getGenerationStatus, expectedStatus.name())
                .set(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.FAILED.name())
                .set(TenderDocumentEntity::getGenerationStage, "生成失败")
                .set(TenderDocumentEntity::getGenerationError, errorMessage)) == 1;
    }

    /**
     * 覆盖当前正文，并使旧定稿和旧审核报告立即失效。
     *
     * <p>同时比较正文版本与调用方读取到的定稿状态，让并发的编辑和定稿请求最多只有一个成功。</p>
     */
    public boolean saveMarkdown(
            String taskId, int expectedRevision, boolean expectedFinalized, String markdown) {
        return mapper.update(null, Wrappers.<TenderDocumentEntity>lambdaUpdate()
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
                .set(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.NOT_STARTED.name())
                .set(TenderDocumentEntity::getReviewStage, null)
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getFinalDocumentObjectKey, null)
                .set(TenderDocumentEntity::getReviewReportObjectKey, null)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, null)) == 1;
    }

    /** 将生成完成的当前正文确认为定稿，并将审核任务绑定到同一正文版本。 */
    public boolean finalizeDocument(String taskId, int expectedRevision) {
        return mapper.update(null, Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .eq(TenderDocumentEntity::getContentRevision, expectedRevision)
                .eq(TenderDocumentEntity::getFinalized, false)
                .eq(TenderDocumentEntity::getGenerationStatus, GenerationTaskStatus.COMPLETED.name())
                .set(TenderDocumentEntity::getFinalized, true)
                .set(TenderDocumentEntity::getFinalizedAt, LocalDateTime.now())
                .set(TenderDocumentEntity::getGenerationStage, "已确认定稿")
                .set(TenderDocumentEntity::getReviewRevision, expectedRevision)
                .set(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.PENDING.name())
                .set(TenderDocumentEntity::getReviewStage, "等待审核")
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getFinalDocumentObjectKey, null)
                .set(TenderDocumentEntity::getReviewReportObjectKey, null)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, null)) == 1;
    }

    /** 原子抢占待执行或失败的审核任务，避免同一版本被重复提交。 */
    public boolean beginReview(String taskId, int revision, TenderReviewStatus expectedStatus) {
        return mapper.update(null, currentReviewUpdate(taskId, revision)
                .eq(TenderDocumentEntity::getReviewStatus, expectedStatus.name())
                .set(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.REVIEWING.name())
                .set(TenderDocumentEntity::getReviewStage, "正在生成审核报告")
                .set(TenderDocumentEntity::getReviewError, null)) == 1;
    }

    /** 仅允许当前定稿版本的审核任务写入结果，旧任务完成后会被自动丢弃。 */
    public boolean completeReview(
            String taskId, int revision, String documentObjectKey, String reviewObjectKey) {
        return mapper.update(null, currentReviewUpdate(taskId, revision)
                .eq(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.REVIEWING.name())
                .set(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.COMPLETED.name())
                .set(TenderDocumentEntity::getReviewStage, "定稿和审核报告已生成")
                .set(TenderDocumentEntity::getFinalDocumentObjectKey, documentObjectKey)
                .set(TenderDocumentEntity::getReviewReportObjectKey, reviewObjectKey)
                .set(TenderDocumentEntity::getReviewError, null)
                .set(TenderDocumentEntity::getReviewedAt, LocalDateTime.now())) == 1;
    }

    /** 审核失败状态同样绑定正文版本，不能覆盖后续人工编辑产生的新版本。 */
    public boolean failReview(String taskId, int revision, String error) {
        return mapper.update(null, currentReviewUpdate(taskId, revision)
                .eq(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.REVIEWING.name())
                .set(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.FAILED.name())
                .set(TenderDocumentEntity::getReviewStage, "审核失败")
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getFinalDocumentObjectKey, null)
                .set(TenderDocumentEntity::getReviewReportObjectKey, null)
                .set(TenderDocumentEntity::getReviewError, error)) == 1;
    }

    /**
     * 收口已被当前线程抢占、但执行前发现失效的审核任务。
     *
     * <p>这里只校验审核版本令牌；人工编辑会清空审核版本，因此旧线程无法误伤新审核任务。</p>
     */
    public boolean invalidateReview(String taskId, int revision, String error) {
        return mapper.update(null, Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .eq(TenderDocumentEntity::getReviewRevision, revision)
                .eq(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.REVIEWING.name())
                .set(TenderDocumentEntity::getReviewStatus, TenderReviewStatus.FAILED.name())
                .set(TenderDocumentEntity::getReviewStage, "审核任务已失效")
                .set(TenderDocumentEntity::getReviewReport, null)
                .set(TenderDocumentEntity::getFinalDocumentObjectKey, null)
                .set(TenderDocumentEntity::getReviewReportObjectKey, null)
                .set(TenderDocumentEntity::getReviewError, error)) == 1;
    }

    private LambdaUpdateWrapper<TenderDocumentEntity> currentReviewUpdate(
            String taskId, int revision) {
        return Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .eq(TenderDocumentEntity::getContentRevision, revision)
                .eq(TenderDocumentEntity::getReviewRevision, revision)
                .eq(TenderDocumentEntity::getFinalized, true);
    }

    /** 所有初稿生成迁移都限定在尚未产生正文的初始版本。 */
    private LambdaUpdateWrapper<TenderDocumentEntity> initialGenerationUpdate(String taskId) {
        return Wrappers.<TenderDocumentEntity>lambdaUpdate()
                .eq(TenderDocumentEntity::getTaskId, taskId)
                .eq(TenderDocumentEntity::getContentRevision, 0)
                .eq(TenderDocumentEntity::getFinalized, false);
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

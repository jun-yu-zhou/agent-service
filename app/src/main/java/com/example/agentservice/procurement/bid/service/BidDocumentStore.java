package com.example.agentservice.procurement.bid.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.agentservice.procurement.bid.domain.BidDocumentStage;
import com.example.agentservice.procurement.bid.persistence.BidDocumentEntity;
import com.example.agentservice.procurement.bid.persistence.BidDocumentMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 使用数据库保存投标文件生成任务的当前状态。 */
@DS("master")
@Repository
public class BidDocumentStore {

    private final BidDocumentMapper mapper;

    public BidDocumentStore(BidDocumentMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<BidDocumentEntity> findByTaskId(String taskId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<BidDocumentEntity>lambdaQuery()
                .eq(BidDocumentEntity::getTaskId, taskId)));
    }

    /** 创建任务记录，OSS 文件由前端上传，因此对象路径允许为空。 */
    public BidDocumentEntity create(String fileName, String sourceUrl, String supplierFacts) {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        document.setTaskId(UUID.randomUUID().toString());
        document.setSourceFileName(fileName);
        document.setSourceUrl(sourceUrl);
        document.setSupplierFacts(supplierFacts);
        document.setStage(BidDocumentStage.EXTRACTING.name());
        document.setOutlineConfirmed(false);
        mapper.insert(document);
        return document;
    }

    /** 保存抽取结果，并把任务交给后续目录生成阶段。 */
    public void completeExtraction(String taskId, String tenderFacts) {
        mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .set(BidDocumentEntity::getTenderFacts, tenderFacts)
                .set(BidDocumentEntity::getStage, BidDocumentStage.OUTLINE_GENERATING.name())
                .set(BidDocumentEntity::getErrorMessage, null));
    }

    /** 保存目录，等待用户调整并确认后再生成正文。 */
    public void completeOutline(String taskId, String outlineJson) {
        mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .set(BidDocumentEntity::getOutlineJson, outlineJson)
                .set(BidDocumentEntity::getStage,
                        BidDocumentStage.WAITING_OUTLINE_CONFIRMATION.name())
                .set(BidDocumentEntity::getOutlineConfirmed, false)
                .set(BidDocumentEntity::getErrorMessage, null));
    }

    /** 在等待确认阶段覆盖用户调整后的目录。 */
    public boolean saveOutline(String taskId, String outlineJson) {
        return mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .eq(BidDocumentEntity::getStage,
                        BidDocumentStage.WAITING_OUTLINE_CONFIRMATION.name())
                .eq(BidDocumentEntity::getOutlineConfirmed, false)
                .set(BidDocumentEntity::getOutlineJson, outlineJson)) == 1;
    }

    /** 原子确认目录，避免重复点击触发多次正文生成。 */
    public boolean confirmOutline(String taskId) {
        return mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .eq(BidDocumentEntity::getStage,
                        BidDocumentStage.WAITING_OUTLINE_CONFIRMATION.name())
                .eq(BidDocumentEntity::getOutlineConfirmed, false)
                .set(BidDocumentEntity::getOutlineConfirmed, true)
                .set(BidDocumentEntity::getStage, BidDocumentStage.CONTENT_GENERATING.name())) == 1;
    }

    /** 保存技术方案；含人工章节时先等待用户补充，否则直接进入一致性检查。 */
    public void completeContent(String taskId, String markdown, boolean requiresManualCompletion) {
        mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .set(BidDocumentEntity::getDocumentMarkdown, markdown)
                .set(BidDocumentEntity::getStage, requiresManualCompletion
                        ? BidDocumentStage.WAITING_MANUAL_COMPLETION.name()
                        : BidDocumentStage.CONSISTENCY_REVIEWING.name())
                .set(BidDocumentEntity::getErrorMessage, null));
    }

    /** 保存一致性检查结果并结束生成流程。 */
    public void completeReview(String taskId, String consistencyReview) {
        mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .set(BidDocumentEntity::getConsistencyReview, consistencyReview)
                .set(BidDocumentEntity::getStage, BidDocumentStage.COMPLETED.name())
                .set(BidDocumentEntity::getErrorMessage, null));
    }

    /** 保存人工修改正文，并使上一份一致性检查结果失效。 */
    public boolean saveDocumentForReview(String taskId, String markdown) {
        return mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .in(BidDocumentEntity::getStage,
                        BidDocumentStage.WAITING_MANUAL_COMPLETION.name(),
                        BidDocumentStage.COMPLETED.name())
                .set(BidDocumentEntity::getDocumentMarkdown, markdown)
                .set(BidDocumentEntity::getConsistencyReview, null)
                .set(BidDocumentEntity::getStage,
                        BidDocumentStage.CONSISTENCY_REVIEWING.name())
                .set(BidDocumentEntity::getErrorMessage, null)) == 1;
    }

    public void fail(String taskId, String errorMessage) {
        mapper.update(null, Wrappers.<BidDocumentEntity>lambdaUpdate()
                .eq(BidDocumentEntity::getTaskId, taskId)
                .set(BidDocumentEntity::getStage, BidDocumentStage.FAILED.name())
                .set(BidDocumentEntity::getErrorMessage, errorMessage));
    }
}

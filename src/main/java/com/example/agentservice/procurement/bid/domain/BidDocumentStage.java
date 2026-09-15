package com.example.agentservice.procurement.bid.domain;

/** 投标文件生成任务当前所处阶段。 */
public enum BidDocumentStage {
    /** 正在从招标文件中抽取项目要求和评分要点。 */
    EXTRACTING,

    /** 正在根据招标要求和投标企业资料生成技术方案目录。 */
    OUTLINE_GENERATING,

    /** 目录已生成，等待用户编辑并确认。 */
    WAITING_OUTLINE_CONFIRMATION,

    /** 正在按照已确认目录生成各章节正文。 */
    CONTENT_GENERATING,

    /** 正在检查技术方案与招标要求的一致性。 */
    CONSISTENCY_REVIEWING,

    /** 技术方案及其一致性检查已经完成。 */
    COMPLETED,

    /** 任务执行失败，具体原因记录在错误信息中。 */
    FAILED
}

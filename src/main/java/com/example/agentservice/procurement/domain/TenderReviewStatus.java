package com.example.agentservice.procurement.domain;

/** 定稿版本审核报告的异步处理状态。 */
public enum TenderReviewStatus {
    /** 已创建审核任务，等待线程执行。 */
    PENDING,

    /** 模型正在生成审核报告。 */
    REVIEWING,

    /** 审核报告已经生成，可以导出。 */
    COMPLETED,

    /** 审核失败，可以由用户重新发起。 */
    FAILED
}

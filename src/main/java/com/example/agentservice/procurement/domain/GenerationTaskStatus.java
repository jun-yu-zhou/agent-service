package com.example.agentservice.procurement.domain;

/** 招标文件与投标文件生成任务共用的生命周期状态。 */
public enum GenerationTaskStatus {
    /** 任务已经创建，等待线程执行。 */
    PENDING,

    /** 模型正在生成文档。 */
    GENERATING,

    /** 文档生成成功。 */
    COMPLETED,

    /** 文档生成失败。 */
    FAILED
}

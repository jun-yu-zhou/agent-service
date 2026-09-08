package com.example.agentservice.procurement.domain;

/** 招标文件与投标文件生成任务共用的生命周期状态。 */
public enum GenerationTaskStatus {
    PENDING,
    GENERATING,
    COMPLETED,
    FAILED
}

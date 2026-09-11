package com.example.agentservice.procurement.domain;

import java.time.Instant;

/** 异步 REST 接口返回的稳定任务快照。 */
public record DocumentGenerationTask(
        /** 文档生成任务的唯一标识。 */
        String taskId,

        /** 当前任务生成的采购文档类型。 */
        DocumentType documentType,

        /** 文档生成任务当前状态。 */
        GenerationTaskStatus status,

        /** 供页面展示的当前处理阶段。 */
        String currentStage,

        /** 当前正在编辑或已经定稿的版本 ID。 */
        String currentVersionId,

        /** 任务失败原因，任务成功时为空。 */
        String errorMessage,

        /** 任务创建时间。 */
        Instant createdAt,

        /** 任务状态最后更新时间。 */
        Instant updatedAt) {
}

package com.example.agentservice.procurement.domain;

import java.time.Instant;

/** 异步 REST 接口返回的稳定任务快照。 */
public record DocumentGenerationTask(
        String taskId,
        DocumentType documentType,
        GenerationTaskStatus status,
        String currentStage,
        String currentVersionId,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {
}

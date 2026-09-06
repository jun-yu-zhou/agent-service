package com.example.agentservice.procurement.domain;

import java.time.Instant;

/** Stable task snapshot returned by future asynchronous REST endpoints. */
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

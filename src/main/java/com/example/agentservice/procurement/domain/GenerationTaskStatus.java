package com.example.agentservice.procurement.domain;

/** Lifecycle shared by tender and bid document generation tasks. */
public enum GenerationTaskStatus {
    PENDING,
    PARSING,
    GENERATING,
    REVIEWING,
    REVISING,
    RENDERING,
    COMPLETED,
    FAILED
}

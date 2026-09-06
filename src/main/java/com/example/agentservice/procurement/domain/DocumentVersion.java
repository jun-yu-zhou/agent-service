package com.example.agentservice.procurement.domain;

import java.time.Instant;
import java.util.List;

/** Version metadata used by generation, human editing, review and finalization. */
public record DocumentVersion(
        String versionId,
        String taskId,
        String parentVersionId,
        int versionNumber,
        String changeSource,
        String changedBy,
        Instant createdAt,
        boolean finalized,
        List<DocumentArtifact> artifacts) {

    public DocumentVersion {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}

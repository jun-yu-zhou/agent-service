package com.example.agentservice.procurement.domain;

import java.time.Instant;
import java.util.List;

/** 文档生成、人工编辑和定稿流程使用的版本元数据。 */
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

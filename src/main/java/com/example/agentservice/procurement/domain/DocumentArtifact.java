package com.example.agentservice.procurement.domain;

import java.time.Instant;

/** 文档版本上传后形成的不可变导出产物。 */
public record DocumentArtifact(
        ArtifactType type,
        String objectKey,
        String downloadUrl,
        long size,
        Instant createdAt) {
}

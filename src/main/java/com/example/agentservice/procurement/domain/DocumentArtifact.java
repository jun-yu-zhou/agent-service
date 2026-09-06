package com.example.agentservice.procurement.domain;

import java.time.Instant;

/** One immutable output uploaded for a document version. */
public record DocumentArtifact(
        ArtifactType type,
        String objectKey,
        String downloadUrl,
        long size,
        Instant createdAt) {
}

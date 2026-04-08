package com.scaffoldops.generatorworker.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GenerationArtifact(
        UUID requestId,
        String artifactType,
        String artifactReference,
        OffsetDateTime createdAt
) {
}

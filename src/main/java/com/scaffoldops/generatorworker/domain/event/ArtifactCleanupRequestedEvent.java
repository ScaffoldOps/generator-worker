package com.scaffoldops.generatorworker.domain.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ArtifactCleanupRequestedEvent(
        UUID requestId,
        String name,
        String artifactRef,
        String imageRef,
        String deploymentNamespace,
        OffsetDateTime deletedAt
) {
    public ArtifactCleanupRequestedEvent(UUID requestId, String name, OffsetDateTime deletedAt) {
        this(requestId, name, null, null, null, deletedAt);
    }
}

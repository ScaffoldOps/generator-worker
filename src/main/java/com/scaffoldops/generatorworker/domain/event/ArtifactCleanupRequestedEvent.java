package com.scaffoldops.generatorworker.domain.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ArtifactCleanupRequestedEvent(
        UUID requestId,
        String name,
        OffsetDateTime deletedAt
) {
}

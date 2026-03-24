package com.scaffoldops.generatorworker.domain.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GenerationRequestedEvent(
        UUID requestId,
        String name,
        String template,
        boolean database,
        boolean restApi,
        boolean security,
        boolean messaging,
        String deploymentTarget,
        String status,
        OffsetDateTime createdAt
) {
}

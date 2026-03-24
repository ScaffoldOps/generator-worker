package com.scaffoldops.generatorworker.domain.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GenerationRequestedEvent(
        UUID requestId,
        String name,
        String template,
        Boolean database,
        Boolean restApi,
        Boolean security,
        Boolean messaging,
        String deploymentTarget,
        String status,
        OffsetDateTime createdAt
) {
}

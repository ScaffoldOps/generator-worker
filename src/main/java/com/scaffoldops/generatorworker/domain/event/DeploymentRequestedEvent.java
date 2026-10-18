package com.scaffoldops.generatorworker.domain.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeploymentRequestedEvent(
        UUID requestId,
        String name,
        String deploymentTarget,
        String artifactReference,
        OffsetDateTime createdAt
) {
}

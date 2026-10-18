package com.scaffoldops.generatorworker.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GenerationRequest(
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

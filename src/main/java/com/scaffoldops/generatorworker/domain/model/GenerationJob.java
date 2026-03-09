package com.scaffoldops.generatorworker.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GenerationJob(
        UUID requestId,
        String name,
        String template,
        boolean database,
        boolean restApi,
        boolean security,
        boolean messaging,
        String deploymentTarget,
        OffsetDateTime createdAt
) {
}

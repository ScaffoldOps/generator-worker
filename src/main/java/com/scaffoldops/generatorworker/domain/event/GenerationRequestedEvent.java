package com.scaffoldops.generatorworker.domain.event;

import com.fasterxml.jackson.annotation.JsonAlias;

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
        @JsonAlias("status") String generationStatus,
        OffsetDateTime createdAt
) {
}

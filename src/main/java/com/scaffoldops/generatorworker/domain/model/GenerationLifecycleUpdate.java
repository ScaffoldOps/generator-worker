package com.scaffoldops.generatorworker.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GenerationLifecycleUpdate(
        UUID requestId,
        String status,
        String detail,
        OffsetDateTime occurredAt
) {
}

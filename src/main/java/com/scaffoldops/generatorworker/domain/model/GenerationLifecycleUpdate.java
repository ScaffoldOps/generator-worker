package com.scaffoldops.generatorworker.domain.model;

import java.util.UUID;

public record GenerationLifecycleUpdate(
        UUID requestId,
        String status,
        String message,
        String artifactRef,
        String imageRef
) {
}

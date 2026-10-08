package com.scaffoldops.generatorworker.domain.model;

import java.util.UUID;

public record GenerationLifecycleUpdate(UUID requestId, String status, String message,
        String artifactRef, String imageRef, String failureStage, int retryCount) {
    public GenerationLifecycleUpdate(UUID requestId, String status, String message,
            String artifactRef, String imageRef) {
        this(requestId, status, message, artifactRef, imageRef, null, 0);
    }
}

package com.scaffoldops.generatorworker.application.port.in;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface CleanupGeneratedArtifactUseCase {

    void cleanup(Command command);

    record Command(
            UUID requestId,
            String name,
            String artifactRef,
            String imageRef,
            String deploymentNamespace,
            OffsetDateTime deletedAt
    ) {
        public Command(UUID requestId, String name, OffsetDateTime deletedAt) {
            this(requestId, name, null, null, null, deletedAt);
        }
    }
}

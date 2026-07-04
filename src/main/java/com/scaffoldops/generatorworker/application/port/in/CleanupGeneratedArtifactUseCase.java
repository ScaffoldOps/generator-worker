package com.scaffoldops.generatorworker.application.port.in;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface CleanupGeneratedArtifactUseCase {

    void cleanup(Command command);

    record Command(
            UUID requestId,
            String name,
            OffsetDateTime deletedAt
    ) {
    }
}

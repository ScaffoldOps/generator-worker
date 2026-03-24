package com.scaffoldops.generatorworker.application.port.in;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ProcessGenerationRequestUseCase {

    void process(Command command);

    record Command(
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
}

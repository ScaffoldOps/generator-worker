package com.scaffoldops.generatorworker.infrastructure.generation;

import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NoOpProjectGenerationAdapter implements ProjectGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpProjectGenerationAdapter.class);

    @Override
    public void generate(GenerationRequest request) {
        log.info(
                "Placeholder generation flow invoked requestId={} serviceName={} template={} workerService=generator-worker",
                request.requestId(),
                request.name(),
                request.template()
        );
    }
}

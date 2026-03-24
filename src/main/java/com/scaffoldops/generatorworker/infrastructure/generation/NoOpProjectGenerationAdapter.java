package com.scaffoldops.generatorworker.infrastructure.generation;

import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NoOpProjectGenerationAdapter implements ProjectGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpProjectGenerationAdapter.class);

    @Override
    public void generate(GenerationJob job) {
        log.info(
                "Placeholder generation flow invoked for requestId={}, serviceName={}, template={}",
                job.requestId(),
                job.name(),
                job.template()
        );
    }
}

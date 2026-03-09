package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationJobUseCase;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationJob;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class GenerationWorkerService implements ProcessGenerationJobUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerationWorkerService.class);

    private final GenerationLifecyclePort generationLifecyclePort;
    private final ProjectGenerationPort projectGenerationPort;

    public GenerationWorkerService(
            GenerationLifecyclePort generationLifecyclePort,
            ProjectGenerationPort projectGenerationPort
    ) {
        this.generationLifecyclePort = generationLifecyclePort;
        this.projectGenerationPort = projectGenerationPort;
    }

    @Override
    public void process(Command command) {
        GenerationJob job = new GenerationJob(
                command.requestId(),
                command.name(),
                command.template(),
                command.database(),
                command.restApi(),
                command.security(),
                command.messaging(),
                command.deploymentTarget(),
                command.createdAt()
        );

        generationLifecyclePort.updateStatus(new GenerationLifecycleUpdate(
                job.requestId(),
                "GENERATING",
                "generator-worker accepted the generation job",
                OffsetDateTime.now()
        ));

        try {
            projectGenerationPort.generate(job);

            generationLifecyclePort.updateStatus(new GenerationLifecycleUpdate(
                    job.requestId(),
                    "GENERATED",
                    "generator-worker completed placeholder generation flow",
                    OffsetDateTime.now()
            ));
        } catch (RuntimeException exception) {
            generationLifecyclePort.updateStatus(new GenerationLifecycleUpdate(
                    job.requestId(),
                    "FAILED",
                    "generator-worker placeholder flow failed: " + exception.getMessage(),
                    OffsetDateTime.now()
            ));
            log.error("Generation job {} failed in placeholder workflow", job.requestId(), exception);
            throw exception;
        }
    }
}

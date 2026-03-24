package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class ProcessGenerationRequestService implements ProcessGenerationRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessGenerationRequestService.class);

    private final GenerationLifecyclePort generationLifecyclePort;
    private final ProjectGenerationPort projectGenerationPort;

    public ProcessGenerationRequestService(
            GenerationLifecyclePort generationLifecyclePort,
            ProjectGenerationPort projectGenerationPort
    ) {
        this.generationLifecyclePort = generationLifecyclePort;
        this.projectGenerationPort = projectGenerationPort;
    }

    @Override
    public void process(Command command) {
        GenerationRequest request = new GenerationRequest(
                command.requestId(),
                command.name(),
                command.template(),
                command.database(),
                command.restApi(),
                command.security(),
                command.messaging(),
                command.deploymentTarget(),
                command.status(),
                command.createdAt()
        );

        log.info(
                "Processing generation request requestId={} serviceName={} template={} deploymentTarget={} workerService=generator-worker",
                request.requestId(),
                request.name(),
                request.template(),
                request.deploymentTarget()
        );

        transition(request, "RECEIVED", "generator-worker received the generation request from Kafka");
        transition(request, "GENERATING", "generator-worker started placeholder generation processing");

        try {
            // TODO(scaffoldops): replace placeholder ports with real generator-api request-state persistence/API integration.
            projectGenerationPort.generate(request);
            transition(request, "GENERATED", "generator-worker completed placeholder generation flow");
        } catch (RuntimeException exception) {
            transition(request, "FAILED", "generator-worker placeholder flow failed: " + exception.getMessage());
            log.error(
                    "Generation request failed requestId={} serviceName={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    exception
            );
            throw exception;
        }
    }

    private void transition(GenerationRequest request, String status, String detail) {
        generationLifecyclePort.updateStatus(new GenerationLifecycleUpdate(
                request.requestId(),
                status,
                detail,
                OffsetDateTime.now()
        ));
    }
}

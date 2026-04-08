package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.application.port.out.DeploymentRequestedPublisherPort;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.event.DeploymentRequestedEvent;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class ProcessGenerationRequestService implements ProcessGenerationRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessGenerationRequestService.class);

    private final DeploymentRequestedPublisherPort deploymentRequestedPublisherPort;
    private final GenerationLifecyclePort generationLifecyclePort;
    private final ProjectGenerationPort projectGenerationPort;

    public ProcessGenerationRequestService(
            DeploymentRequestedPublisherPort deploymentRequestedPublisherPort,
            GenerationLifecyclePort generationLifecyclePort,
            ProjectGenerationPort projectGenerationPort
    ) {
        this.deploymentRequestedPublisherPort = deploymentRequestedPublisherPort;
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
        transition(request, "GENERATING", "generator-worker started generation processing");

        GenerationArtifact artifact;
        try {
            artifact = projectGenerationPort.generate(request);
        } catch (RuntimeException exception) {
            transition(request, "FAILED", "generator-worker generation stage failed: " + exception.getMessage());
            log.error(
                    "Generation stage failed requestId={} serviceName={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    exception
            );
            throw exception;
        }

        transition(request, "GENERATED", "generator-worker created durable generation artifact: " + artifact.artifactReference());
        publishDeploymentRequested(request, artifact);
    }

    private void publishDeploymentRequested(GenerationRequest request, GenerationArtifact artifact) {
        try {
            deploymentRequestedPublisherPort.publish(new DeploymentRequestedEvent(
                    request.requestId(),
                    request.name(),
                    request.deploymentTarget(),
                    artifact.artifactReference(),
                    OffsetDateTime.now()
            ));
            transition(request, "DEPLOYMENT_REQUESTED", "generator-worker published deployment-requested for artifact: " + artifact.artifactReference());
        } catch (RuntimeException exception) {
            transition(request, "FAILED", "generator-worker deployment-request publication failed: " + exception.getMessage());
            log.error(
                    "Deployment-request publication failed requestId={} serviceName={} workerService=generator-worker",
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

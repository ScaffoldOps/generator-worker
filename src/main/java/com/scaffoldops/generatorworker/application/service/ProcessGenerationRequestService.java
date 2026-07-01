package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.application.port.out.DeploymentRequestedPublisherPort;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ImageBuilderPort;
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
    private final ImageBuilderPort imageBuilderPort;
    private final ProjectGenerationPort projectGenerationPort;

    public ProcessGenerationRequestService(
            DeploymentRequestedPublisherPort deploymentRequestedPublisherPort,
            GenerationLifecyclePort generationLifecyclePort,
            ImageBuilderPort imageBuilderPort,
            ProjectGenerationPort projectGenerationPort
    ) {
        this.deploymentRequestedPublisherPort = deploymentRequestedPublisherPort;
        this.generationLifecyclePort = generationLifecyclePort;
        this.imageBuilderPort = imageBuilderPort;
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

        try {
            process(request);
        } catch (RuntimeException exception) {
            log.error(
                    "Generation request processing failed requestId={} serviceName={} template={} deploymentTarget={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    request.template(),
                    request.deploymentTarget(),
                    exception
            );
            throw exception;
        }
    }

    private void process(GenerationRequest request) {
        log.info(
                "Processing generation request requestId={} serviceName={} template={} deploymentTarget={} workerService=generator-worker",
                request.requestId(),
                request.name(),
                request.template(),
                request.deploymentTarget()
        );

        transition(request, "RECEIVED", "generator-worker received the generation request from Kafka", null, null);
        transition(request, "GENERATING", "generator-worker started generation processing", null, null);

        GenerationArtifact artifact;
        try {
            artifact = projectGenerationPort.generate(request);
        } catch (RuntimeException exception) {
            transition(
                    request,
                    "FAILED",
                    "generator-worker generation stage failed: " + exception.getMessage(),
                    null,
                    null
            );
            log.error(
                    "Generation stage failed requestId={} serviceName={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    exception
            );
            throw exception;
        }

        String imageRef = buildImage(request, artifact);
        String generatedMessage = imageRef == null
                ? "generator-worker generated the project and skipped Docker image build"
                : "generator-worker generated the project and built the Docker image";
        transition(
                request,
                "GENERATED",
                generatedMessage,
                artifact.artifactReference(),
                imageRef
        );
        publishDeploymentRequested(request, artifact, imageRef);
    }

    private String buildImage(GenerationRequest request, GenerationArtifact artifact) {
        try {
            return imageBuilderPort.build(artifact);
        } catch (RuntimeException exception) {
            transition(
                    request,
                    "FAILED",
                    "generator-worker image build stage failed: " + exception.getMessage(),
                    artifact.artifactReference(),
                    null
            );
            log.error(
                    "Image build stage failed requestId={} serviceName={} imageName={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    artifact.imageName(),
                    exception
            );
            throw exception;
        }
    }

    private void publishDeploymentRequested(GenerationRequest request, GenerationArtifact artifact, String imageRef) {
        if (imageRef == null) {
            log.info(
                    "Skipping deployment-requested publication because no image was built requestId={} serviceName={} artifactReference={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    artifact.artifactReference()
            );
            return;
        }

        try {
            deploymentRequestedPublisherPort.publish(new DeploymentRequestedEvent(
                    request.requestId(),
                    request.name(),
                    request.deploymentTarget(),
                    artifact.artifactReference(),
                    OffsetDateTime.now()
            ));
            transition(
                    request,
                    "DEPLOYMENT_REQUESTED",
                    "generator-worker published deployment-requested for artifact: " + artifact.artifactReference(),
                    artifact.artifactReference(),
                    imageRef
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Deployment-request publication failed requestId={} serviceName={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    exception
            );
            throw exception;
        }
    }

    private void transition(
            GenerationRequest request,
            String status,
            String message,
            String artifactRef,
            String imageRef
    ) {
        generationLifecyclePort.updateStatus(new GenerationLifecycleUpdate(
                request.requestId(),
                status,
                message,
                artifactRef,
                imageRef
        ));
    }
}

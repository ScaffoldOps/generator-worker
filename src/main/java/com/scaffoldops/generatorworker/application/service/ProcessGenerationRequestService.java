package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.application.port.out.ArtifactPublisher;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ImageBuilderPort;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;
import com.scaffoldops.generatorworker.domain.model.GenerationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.scaffoldops.generatorworker.domain.model.ImageGenerationException;


@Service
public class ProcessGenerationRequestService implements ProcessGenerationRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessGenerationRequestService.class);

    private final ArtifactPublisher artifactPublisher;
    private final GenerationLifecyclePort generationLifecyclePort;
    private final ImageBuilderPort imageBuilderPort;
    private final ProjectGenerationPort projectGenerationPort;

    private final int maxAttempts;
    private final long backoffMillis;

    public ProcessGenerationRequestService(ArtifactPublisher publisher, GenerationLifecyclePort lifecycle,
            ImageBuilderPort builder, ProjectGenerationPort generator) {
        this(publisher, lifecycle, builder, generator, 3, 0);
    }

    @Autowired
    public ProcessGenerationRequestService(
            ArtifactPublisher artifactPublisher,
            GenerationLifecyclePort generationLifecyclePort,
            ImageBuilderPort imageBuilderPort,
            ProjectGenerationPort projectGenerationPort,
            @Value("${app.image-builder.max-attempts:3}") int maxAttempts,
            @Value("${app.image-builder.retry-backoff-ms:1000}") long backoffMillis
    ) {
        this.artifactPublisher = artifactPublisher;
        this.generationLifecyclePort = generationLifecyclePort;
        this.imageBuilderPort = imageBuilderPort;
        this.projectGenerationPort = projectGenerationPort;
        if (maxAttempts < 1 || backoffMillis < 0) {
            throw new IllegalArgumentException("Image retry max attempts must be positive and backoff nonnegative");
        }
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
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

    public void recover(GenerationRequest request, GenerationArtifact artifact, String artifactRef, int retryAttempt) {
        process(request, artifact, artifactRef, retryAttempt);
    }

    private void process(GenerationRequest request) {
        process(request, null, null, 0);
    }

    private void process(GenerationRequest request, GenerationArtifact existingArtifact, String existingRef, int retryAttempt) {
        log.info(
                "Processing generation request requestId={} serviceName={} template={} deploymentTarget={} workerService=generator-worker",
                request.requestId(),
                request.name(),
                request.template(),
                request.deploymentTarget()
        );

        String stage = "CALLBACK";
        String artifactRef = existingRef;
        String imageRef = null;
        int retryCount = retryAttempt;
        try {
            if (existingArtifact == null) transition(request, GenerationStatus.GENERATING, "generator-worker started generation processing", null, null, null, 0);
            stage = "ARTIFACT_GENERATION";
            GenerationArtifact artifact = existingArtifact != null ? existingArtifact : projectGenerationPort.generate(request);
            stage = "ARTIFACT_UPLOAD";
            if (existingArtifact == null) artifactRef = artifactPublisher.publish(artifact, imageBuilderPort.intendedImageReference(artifact));
            if (artifactRef == null || artifactRef.isBlank()) {
                artifactRef = null;
                throw new IllegalStateException("Artifact publishing returned a blank artifactRef");
            }
            stage = "IMAGE_BUILD";
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                retryCount = existingArtifact == null ? attempt - 1 : retryAttempt;
                try {
                    imageRef = imageBuilderPort.build(artifact);
                    if (imageRef == null || imageRef.isBlank()) {
                        throw new ImageGenerationException("IMAGE_BUILD",
                                "Docker image build returned a blank imageRef; cannot complete generation", null, false);
                    }
                    break;
                } catch (RuntimeException exception) {
                    stage = exception instanceof ImageGenerationException imageFailure
                            ? imageFailure.failureStage() : "IMAGE_BUILD";
                    boolean retryable = !(exception instanceof ImageGenerationException imageFailure)
                            || imageFailure.retryable();
                    if (!retryable || attempt == maxAttempts || Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Docker image generation failed after " + attempt
                                + " attempt(s): " + exception.getMessage(), exception);
                    }
                    try {
                        Thread.sleep(backoffMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Docker image retry interrupted", interrupted);
                    }
                }
            }
            stage = "CALLBACK";
            transition(request, GenerationStatus.GENERATED, "Generation completed successfully", artifactRef, imageRef, null, retryCount);
        } catch (RuntimeException exception) {
            if (existingArtifact != null && "CALLBACK".equals(stage)) {
                throw exception;
            }
            try {
                transition(request, GenerationStatus.GENERATION_FAILED, exception.getMessage(), artifactRef, null, stage, retryCount);
            } catch (RuntimeException callbackFailure) {
                exception.addSuppressed(callbackFailure);
            }
            if (existingArtifact != null && exception.getSuppressed().length == 0) {
                return; // API scheduler owns subsequent technical recovery attempts.
            }
            throw exception;
        }
    }

    private void transition(GenerationRequest request, GenerationStatus status, String message,
            String artifactRef, String imageRef, String failureStage, int retryCount) {
        generationLifecyclePort.updateStatus(new GenerationLifecycleUpdate(request.requestId(),
                status.name(), message, artifactRef, imageRef, failureStage, retryCount));
    }
}

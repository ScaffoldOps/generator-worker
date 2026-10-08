package com.scaffoldops.generatorworker.infrastructure.image;

import com.scaffoldops.generatorworker.application.port.out.ImageBuilderPort;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class DockerImageBuilderAdapter implements ImageBuilderPort {

    private static final Logger log = LoggerFactory.getLogger(DockerImageBuilderAdapter.class);

    private final boolean buildEnabled;
    private final String dockerCommand;

    private final boolean pushEnabled;
    private final String registry;
    private final String repositoryPrefix;

    public DockerImageBuilderAdapter(boolean buildEnabled, String dockerCommand) {
        this(buildEnabled, dockerCommand, false, "", "scaffoldops");
    }

    @Autowired
    public DockerImageBuilderAdapter(
            @Value("${app.image-builder.build-enabled:true}") boolean buildEnabled,
            @Value("${app.image-builder.docker-command:docker}") String dockerCommand,
            @Value("${app.image-builder.push-enabled:false}") boolean pushEnabled,
            @Value("${app.image-builder.registry:}") String registry,
            @Value("${app.image-builder.repository-prefix:scaffoldops}") String repositoryPrefix
    ) {
        this.buildEnabled = buildEnabled;
        this.dockerCommand = dockerCommand;
        this.pushEnabled = pushEnabled;
        this.registry = registry.replaceAll("/+$", "");
        this.repositoryPrefix = repositoryPrefix.replaceAll("^/+|/+$", "");
        if (pushEnabled && (!buildEnabled || this.registry.isBlank())) {
            throw new IllegalArgumentException("Docker push requires build enabled and an image registry");
        }
    }

    @Override
    public String intendedImageReference(GenerationArtifact artifact) {
        if (!buildEnabled) {
            return null;
        }
        String repository = repositoryPrefix.isBlank() ? artifact.serviceName()
                : repositoryPrefix + "/" + artifact.serviceName();
        return (registry.isBlank() ? "" : registry + "/") + repository + ":" + artifact.requestId();
    }

    @Override
    public String build(GenerationArtifact artifact) {
        Path projectDirectory = projectDirectory(artifact);
        if (!buildEnabled) {
            log.info(
                    "Skipping generated Docker image build because image builder is disabled requestId={} imageName={} projectDirectory={} workerService=generator-worker",
                    artifact.requestId(),
                    artifact.imageName(),
                    projectDirectory
            );
            return null;
        }

        String imageRef = intendedImageReference(artifact);
        List<String> command = List.of(
                dockerCommand,
                "build",
                "--tag",
                imageRef,
                projectDirectory.toString()
        );

        log.info(
                "Building generated Docker image requestId={} imageName={} projectDirectory={} workerService=generator-worker",
                artifact.requestId(),
                artifact.imageName(),
                projectDirectory
        );

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to start docker build for image=" + artifact.imageName(),
                    exception
            );
        }

        try {
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "docker build failed for image=" + artifact.imageName()
                                + " exitCode=" + exitCode
                                + " output=" + output.trim()
                );
            }
            log.info(
                    "Built generated Docker image requestId={} imageName={} workerService=generator-worker",
                    artifact.requestId(),
                    artifact.imageName()
            );
            if (pushEnabled) {
                push(imageRef);
            }
            return imageRef;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "docker build interrupted for image=" + artifact.imageName(),
                    exception
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to read docker build output for image=" + artifact.imageName(),
                    exception
            );
        }
    }

    private void push(String imageRef) {
        try {
            Process process = new ProcessBuilder(dockerCommand, "push", imageRef)
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("docker push failed for image=" + imageRef
                        + " exitCode=" + exitCode + " output=" + output.trim());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker push interrupted for image=" + imageRef, exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to execute docker push for image=" + imageRef, exception);
        }
    }

    private Path projectDirectory(GenerationArtifact artifact) {
        URI artifactUri;
        try {
            artifactUri = URI.create(artifact.artifactReference());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid project artifact reference: " + artifact.artifactReference(), exception);
        }

        if (!"file".equalsIgnoreCase(artifactUri.getScheme())) {
            throw new IllegalStateException("docker image build requires a file artifact reference");
        }

        Path projectDirectory = Path.of(artifactUri);
        if (!Files.isDirectory(projectDirectory)) {
            throw new IllegalStateException("generated project directory does not exist: " + projectDirectory);
        }
        if (!Files.isRegularFile(projectDirectory.resolve("Dockerfile"))) {
            throw new IllegalStateException("generated project Dockerfile does not exist: " + projectDirectory);
        }
        return projectDirectory;
    }
}

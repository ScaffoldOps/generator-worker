package com.scaffoldops.generatorworker.infrastructure.image;

import com.scaffoldops.generatorworker.application.port.out.ImageBuilderPort;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    public DockerImageBuilderAdapter(
            @Value("${app.image-builder.build-enabled:true}") boolean buildEnabled,
            @Value("${app.image-builder.docker-command:docker}") String dockerCommand
    ) {
        this.buildEnabled = buildEnabled;
        this.dockerCommand = dockerCommand;
    }

    @Override
    public void build(GenerationArtifact artifact) {
        Path projectDirectory = projectDirectory(artifact);
        if (!buildEnabled) {
            log.info(
                    "Skipping generated Docker image build because image builder is disabled requestId={} imageName={} projectDirectory={} workerService=generator-worker",
                    artifact.requestId(),
                    artifact.imageName(),
                    projectDirectory
            );
            return;
        }

        List<String> command = List.of(
                dockerCommand,
                "build",
                "--tag",
                artifact.imageName(),
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

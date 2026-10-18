package com.scaffoldops.generatorworker.infrastructure.image;

import com.scaffoldops.generatorworker.application.port.out.ImageBuilderPort;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import com.scaffoldops.generatorworker.domain.model.ImageGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private final String dockerUsername;
    private final String dockerPassword;

    public DockerImageBuilderAdapter(boolean buildEnabled, String dockerCommand, boolean pushEnabled,
            String registry, String repositoryPrefix) {
        this(buildEnabled, dockerCommand, pushEnabled, registry, repositoryPrefix, "", "");
    }

    @Autowired
    public DockerImageBuilderAdapter(
            @Value("${app.image-builder.build-enabled:true}") boolean buildEnabled,
            @Value("${app.image-builder.docker-command:docker}") String dockerCommand,
            @Value("${app.image-builder.push-enabled:true}") boolean pushEnabled,
            @Value("${app.image-builder.registry:docker.io}") String registry,
            @Value("${app.image-builder.repository-prefix:victodomvar/scaffoldops-generated}") String repositoryPrefix,
            @Value("${app.image-builder.username:}") String dockerUsername,
            @Value("${app.image-builder.password:}") String dockerPassword
    ) {
        this.dockerUsername = dockerUsername;
        this.dockerPassword = dockerPassword;
        this.buildEnabled = buildEnabled;
        this.dockerCommand = dockerCommand;
        this.pushEnabled = pushEnabled;
        this.registry = registry.replaceAll("/+$", "");
        this.repositoryPrefix = repositoryPrefix.replaceAll("^/+|/+$", "");
        if (pushEnabled && this.registry.isBlank()) {
            throw new IllegalArgumentException("Docker image push requires a configured registry; cannot complete generation");
        }
    }

    @Override
    public String intendedImageReference(GenerationArtifact artifact) {
        if (!buildEnabled) {
            return null;
        }
        String repository = repositoryPrefix.isBlank() ? artifact.serviceName()
                : repositoryPrefix;
        return (registry.isBlank() ? "" : registry + "/") + repository + ":" + artifact.serviceName() + "-" + artifact.requestId();
    }

    @Override
    public String build(GenerationArtifact artifact) {
        if (!buildEnabled) {
            throw new ImageGenerationException("IMAGE_BUILD",
                    "Docker image build is disabled; cannot complete generation", null, false);
        }
        if (!pushEnabled) {
            throw new ImageGenerationException("IMAGE_PUSH",
                    "Docker image push is disabled; cannot complete generation", null, false);
        }
        if (dockerUsername == null || dockerUsername.isBlank() || dockerPassword == null || dockerPassword.isBlank()) {
            throw new ImageGenerationException("IMAGE_PUSH",
                    "Docker push requires DOCKER_USERNAME and DOCKER_PASSWORD (Docker Hub access token)", null, false);
        }
        try {
            return buildAndPush(artifact);
        } catch (ImageGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ImageGenerationException("IMAGE_BUILD", exception.getMessage(), exception, true);
        }
    }

    private String buildAndPush(GenerationArtifact artifact) {
        Path projectDirectory = projectDirectory(artifact);
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
                                + " output=" + redact(output.trim())
                );
            }
            log.info(
                    "Built generated Docker image requestId={} imageName={} workerService=generator-worker",
                    artifact.requestId(),
                    artifact.imageName()
            );
            try {
                login();
                push(imageRef);
            } catch (RuntimeException exception) {
                throw new ImageGenerationException("IMAGE_PUSH", exception.getMessage(), exception, true);
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

    private void login() {
        try {
            Process process = new ProcessBuilder(dockerCommand, "login", registry,
                    "--username", dockerUsername, "--password-stdin")
                    .redirectErrorStream(true).start();
            try (var input = process.getOutputStream()) {
                input.write((dockerPassword + "\n").getBytes(StandardCharsets.UTF_8));
            }
            // Login output is intentionally excluded from errors and logs.
            process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("docker login failed for registry=" + registry + " exitCode=" + exitCode);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker login interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to execute docker login", exception);
        }
    }

    private String redact(String output) {
        return dockerPassword == null || dockerPassword.isEmpty() ? output : output.replace(dockerPassword, "[REDACTED]");
    }

    private void push(String imageRef) {
        try {
            Process process = new ProcessBuilder(dockerCommand, "push", imageRef)
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("docker push failed for image=" + imageRef
                        + " exitCode=" + exitCode + " output=" + redact(output.trim()));
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

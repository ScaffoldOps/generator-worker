package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.CleanupGeneratedArtifactUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class CleanupGeneratedArtifactService implements CleanupGeneratedArtifactUseCase {

    private static final Logger log = LoggerFactory.getLogger(CleanupGeneratedArtifactService.class);
    private static final Pattern INVALID_SERVICE_NAME_CHARACTERS = Pattern.compile("[^a-z0-9-]");

    private final Path manifestOutputDirectory;

    public CleanupGeneratedArtifactService(
            @Value("${app.generation.manifest-output-dir:${java.io.tmpdir}/generator-worker/manifests}")
            String manifestOutputDirectory
    ) {
        this.manifestOutputDirectory = Path.of(manifestOutputDirectory);
    }

    @Override
    public void cleanup(Command command) {
        if (command.requestId() == null) {
            throw new IllegalArgumentException("artifact-cleanup-requested event missing required field: requestId");
        }
        if (!StringUtils.hasText(command.name())) {
            throw new IllegalArgumentException("artifact-cleanup-requested event missing required field: name");
        }

        Path artifactDirectory = artifactDirectory(command);
        if (!Files.exists(artifactDirectory)) {
            log.info(
                    "Generated artifact directory already absent requestId={} serviceName={} artifactDirectory={} workerService=generator-worker",
                    command.requestId(),
                    command.name(),
                    artifactDirectory
            );
            return;
        }
        if (!Files.isDirectory(artifactDirectory)) {
            throw new IllegalStateException("generated artifact path is not a directory: " + artifactDirectory);
        }

        try (Stream<Path> paths = Files.walk(artifactDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
            log.info(
                    "Deleted generated artifact directory requestId={} serviceName={} artifactDirectory={} workerService=generator-worker",
                    command.requestId(),
                    command.name(),
                    artifactDirectory
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete generated artifact directory: " + artifactDirectory, exception);
        }
    }

    private Path artifactDirectory(Command command) {
        String serviceName = sanitizedServiceName(command.name());
        Path root = manifestOutputDirectory.toAbsolutePath().normalize();
        Path artifactDirectory = root.resolve(serviceName + "-" + command.requestId()).normalize();
        if (!artifactDirectory.startsWith(root)) {
            throw new IllegalArgumentException("generated artifact cleanup path escapes manifest output directory");
        }
        return artifactDirectory;
    }

    private String sanitizedServiceName(String name) {
        String serviceName = INVALID_SERVICE_NAME_CHARACTERS
                .matcher(name.toLowerCase(Locale.ROOT))
                .replaceAll("-");
        serviceName = serviceName.replaceAll("-+", "-").replaceAll("(^-|-$)", "");
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("service name must contain at least one alphanumeric character");
        }
        return serviceName;
    }

    private void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete generated artifact path: " + path, exception);
        }
    }
}

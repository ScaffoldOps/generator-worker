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
    private final com.scaffoldops.generatorworker.application.port.out.ExternalArtifactCleanup externalCleanup;

    @org.springframework.beans.factory.annotation.Autowired
    public CleanupGeneratedArtifactService(
            @Value("${app.generation.manifest-output-dir:${java.io.tmpdir}/generator-worker/manifests}")
            String manifestOutputDirectory,
            com.scaffoldops.generatorworker.application.port.out.ExternalArtifactCleanup externalCleanup
    ) {
        this.manifestOutputDirectory = Path.of(manifestOutputDirectory);
        this.externalCleanup = externalCleanup;
    }

    public CleanupGeneratedArtifactService(String manifestOutputDirectory) {
        this(manifestOutputDirectory, new com.scaffoldops.generatorworker.application.port.out.ExternalArtifactCleanup() {
            public void deleteArtifact(String ref) { throw new IllegalStateException("external cleanup unavailable"); }
            public void deleteImage(String ref) { throw new IllegalStateException("external cleanup unavailable"); }
        });
    }

    @Override
    public void cleanup(Command command) {
        if (command.requestId() == null) {
            throw new IllegalArgumentException("artifact-cleanup-requested event missing required field: requestId");
        }
        if (!StringUtils.hasText(command.name())) {
            throw new IllegalArgumentException("artifact-cleanup-requested event missing required field: name");
        }

        Path workspace = artifactDirectory(command);
        attempt(command, "workspace", () -> deleteSafePath(workspace));
        if (StringUtils.hasText(command.artifactRef())) {
            attempt(command, "artifact", () -> {
                java.net.URI uri = java.net.URI.create(command.artifactRef());
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    deleteSafePath(Path.of(uri));
                } else {
                    externalCleanup.deleteArtifact(command.artifactRef());
                }
            });
        }
        if (StringUtils.hasText(command.imageRef())) {
            attempt(command, "image", () -> externalCleanup.deleteImage(command.imageRef()));
        }
    }

    private void attempt(Command command, String target, CleanupAction action) {
        try {
            action.run();
            log.info("Cleanup completed target={} requestId={}", target, command.requestId());
        } catch (Exception exception) {
            // Do not log external exception messages/responses: they can contain credentials.
            log.warn("Cleanup failed or rejected target={} requestId={} errorType={}; manual reconciliation may be required",
                    target, command.requestId(), exception.getClass().getSimpleName());
        }
    }

    private void deleteSafePath(Path path) throws IOException {
        Path root = manifestOutputDirectory.toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("cleanup path escapes manifest output directory or targets its root");
        }
        // Reject symlink ancestors (including the configured root). Files.walk never follows
        // descendant symlinks; deleting those removes the link, never its external target.
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("cleanup path contains a symbolic link");
            }
        }
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            log.info("Cleanup path already absent");
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    @FunctionalInterface
    private interface CleanupAction { void run() throws Exception; }

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

}

package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.CleanupGeneratedArtifactUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CleanupGeneratedArtifactServiceTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void shouldDeleteGeneratedArtifactDirectoryWhenItExists() throws Exception {
        UUID requestId = UUID.randomUUID();
        Path manifestDirectory = tempDirectory.resolve("manifests");
        Path artifactDirectory = manifestDirectory.resolve("billing-service-" + requestId);
        Files.createDirectories(artifactDirectory.resolve("k8s"));
        Files.writeString(artifactDirectory.resolve("pom.xml"), "<project/>");
        Files.writeString(artifactDirectory.resolve("k8s/deployment.yaml"), "apiVersion: apps/v1");
        CleanupGeneratedArtifactService service = new CleanupGeneratedArtifactService(manifestDirectory.toString());

        service.cleanup(command(requestId, "billing-service"));

        assertThat(artifactDirectory).doesNotExist();
        assertThat(manifestDirectory).exists();
    }

    @Test
    void shouldTreatMissingGeneratedArtifactDirectoryAsSuccessful() {
        UUID requestId = UUID.randomUUID();
        Path manifestDirectory = tempDirectory.resolve("manifests");
        CleanupGeneratedArtifactService service = new CleanupGeneratedArtifactService(manifestDirectory.toString());

        service.cleanup(command(requestId, "billing-service"));

        assertThat(manifestDirectory.resolve("billing-service-" + requestId)).doesNotExist();
    }

    @Test
    void shouldNotDeleteOutsideManifestOutputDirectoryWhenServiceNameContainsTraversal() throws Exception {
        UUID requestId = UUID.randomUUID();
        Path manifestDirectory = tempDirectory.resolve("manifests");
        Path outsideDirectory = tempDirectory.resolve("outside-" + requestId);
        Files.createDirectories(outsideDirectory);
        Files.writeString(outsideDirectory.resolve("keep.txt"), "do not delete");
        CleanupGeneratedArtifactService service = new CleanupGeneratedArtifactService(manifestDirectory.toString());

        service.cleanup(command(requestId, "../../outside"));

        assertThat(outsideDirectory.resolve("keep.txt")).exists();
        assertThat(manifestDirectory.resolve("outside-" + requestId)).doesNotExist();
    }

    @Test
    void shouldRejectServiceNameWithoutAlphanumericCharacters() {
        CleanupGeneratedArtifactService service = new CleanupGeneratedArtifactService(
                tempDirectory.resolve("manifests").toString()
        );

        assertThatThrownBy(() -> service.cleanup(command(UUID.randomUUID(), "../---")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service name must contain at least one alphanumeric character");
    }

    private CleanupGeneratedArtifactUseCase.Command command(UUID requestId, String name) {
        return new CleanupGeneratedArtifactUseCase.Command(
                requestId,
                name,
                OffsetDateTime.parse("2026-03-07T10:15:30Z")
        );
    }
}

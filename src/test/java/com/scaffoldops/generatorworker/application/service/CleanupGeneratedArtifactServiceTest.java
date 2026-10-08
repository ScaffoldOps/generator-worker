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

    @Test
    void shouldDeleteSafeFileArtifactEvenWhenWorkspaceIsMissing() throws Exception {
        Path artifact = Files.createDirectories(tempDirectory.resolve("legacy/project"));
        Files.writeString(artifact.resolve("file"), "content");
        var service = new CleanupGeneratedArtifactService(tempDirectory.toString());
        var command = new CleanupGeneratedArtifactUseCase.Command(UUID.randomUUID(), "billing",
                artifact.toUri().toString(), null, null, OffsetDateTime.now());
        service.cleanup(command);
        service.cleanup(command);
        assertThat(artifact).doesNotExist();
    }

    @Test
    void shouldRejectOutsideAndRootFileArtifactsAndStillDeleteImage() throws Exception {
        Path root = Files.createDirectories(tempDirectory.resolve("root"));
        Path outside = Files.writeString(tempDirectory.resolve("keep"), "keep");
        var external = org.mockito.Mockito.mock(com.scaffoldops.generatorworker.application.port.out.ExternalArtifactCleanup.class);
        var service = new CleanupGeneratedArtifactService(root.toString(), external);
        for (Path unsafe : java.util.List.of(outside, root, root.resolve("../keep"))) {
            service.cleanup(new CleanupGeneratedArtifactUseCase.Command(UUID.randomUUID(), "billing",
                    unsafe.toUri().toString(), "docker.io/owner/repo:tag", null, OffsetDateTime.now()));
        }
        assertThat(outside).exists();
        assertThat(root).exists();
        org.mockito.Mockito.verify(external, org.mockito.Mockito.times(3)).deleteImage("docker.io/owner/repo:tag");
    }

    @Test
    void shouldRejectSymlinkAncestorAndNeverFollowDescendantSymlinks() throws Exception {
        Path root = Files.createDirectories(tempDirectory.resolve("root"));
        Path outside = Files.createDirectories(tempDirectory.resolve("outside"));
        Path keep = Files.writeString(outside.resolve("keep"), "keep");
        Path link = Files.createSymbolicLink(root.resolve("link"), outside);
        var service = new CleanupGeneratedArtifactService(root.toString());
        service.cleanup(new CleanupGeneratedArtifactUseCase.Command(UUID.randomUUID(), "billing",
                link.resolve("keep").toUri().toString(), null, null, OffsetDateTime.now()));
        assertThat(keep).exists();
        Path artifact = Files.createDirectories(root.resolve("artifact"));
        Files.createSymbolicLink(artifact.resolve("link"), outside);
        service.cleanup(new CleanupGeneratedArtifactUseCase.Command(UUID.randomUUID(), "billing",
                artifact.toUri().toString(), null, null, OffsetDateTime.now()));
        assertThat(artifact).doesNotExist();
        assertThat(keep).exists();
    }

    @Test
    void shouldContinueImageCleanupAfterArtifactFailure() throws Exception {
        var external = org.mockito.Mockito.mock(com.scaffoldops.generatorworker.application.port.out.ExternalArtifactCleanup.class);
        org.mockito.Mockito.doThrow(new IllegalStateException()).when(external).deleteArtifact("s3://bucket/key");
        new CleanupGeneratedArtifactService(tempDirectory.toString(), external).cleanup(
                new CleanupGeneratedArtifactUseCase.Command(UUID.randomUUID(), "billing", "s3://bucket/key",
                        "docker.io/owner/repo:tag", null, OffsetDateTime.now()));
        org.mockito.Mockito.verify(external).deleteImage("docker.io/owner/repo:tag");
    }

    private CleanupGeneratedArtifactUseCase.Command command(UUID requestId, String name) {
        return new CleanupGeneratedArtifactUseCase.Command(
                requestId,
                name,
                OffsetDateTime.parse("2026-03-07T10:15:30Z")
        );
    }
}

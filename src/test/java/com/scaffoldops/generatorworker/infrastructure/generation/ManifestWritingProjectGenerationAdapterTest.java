package com.scaffoldops.generatorworker.infrastructure.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestWritingProjectGenerationAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteDeterministicManifestAndReturnDurableReference() throws Exception {
        ManifestWritingProjectGenerationAdapter adapter =
                new ManifestWritingProjectGenerationAdapter(new ObjectMapper(), tempDir.toString());

        UUID requestId = UUID.randomUUID();
        GenerationRequest request = new GenerationRequest(
                requestId,
                "billing-service",
                "spring-boot-hexagonal",
                true,
                true,
                true,
                false,
                "kubernetes",
                "REQUESTED",
                OffsetDateTime.parse("2026-03-24T12:00:00Z")
        );

        GenerationArtifact artifact = adapter.generate(request);

        assertThat(artifact.requestId()).isEqualTo(requestId);
        assertThat(artifact.artifactType()).isEqualTo("generation-manifest");

        Path manifestPath = Path.of(URI.create(artifact.artifactReference()));
        assertThat(manifestPath.getFileName().toString()).isEqualTo("manifest-" + requestId + ".json");
        assertThat(Files.exists(manifestPath)).isTrue();

        Map<?, ?> manifest = new ObjectMapper().readValue(manifestPath.toFile(), Map.class);
        assertThat(manifest.get("requestId")).isEqualTo(requestId.toString());
        assertThat(manifest.get("name")).isEqualTo("billing-service");
        assertThat(manifest.get("deploymentTarget")).isEqualTo("kubernetes");
    }

    @Test
    void shouldReuseExistingManifestForSameRequestId() throws Exception {
        ManifestWritingProjectGenerationAdapter adapter =
                new ManifestWritingProjectGenerationAdapter(new ObjectMapper(), tempDir.toString());

        UUID requestId = UUID.randomUUID();
        GenerationRequest request = new GenerationRequest(
                requestId,
                "billing-service",
                "spring-boot-hexagonal",
                true,
                true,
                true,
                false,
                "kubernetes",
                "REQUESTED",
                OffsetDateTime.parse("2026-03-24T12:00:00Z")
        );

        GenerationArtifact firstArtifact = adapter.generate(request);
        GenerationArtifact secondArtifact = adapter.generate(request);

        assertThat(secondArtifact.artifactReference()).isEqualTo(firstArtifact.artifactReference());
        assertThat(Files.list(tempDir)).hasSize(1);
    }
}

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
    void shouldGenerateHelloWorldSpringBootProjectAndReturnDurableReference() throws Exception {
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
        assertThat(artifact.artifactType()).isEqualTo("spring-boot-project");
        assertThat(artifact.serviceName()).isEqualTo("billing-service");
        assertThat(artifact.imageName()).isEqualTo("scaffoldops/billing-service:" + requestId);

        Path projectDirectory = Path.of(URI.create(artifact.artifactReference()));
        Path javaDirectory = projectDirectory.resolve(
                "src/main/java/com/scaffoldops/generated/billingservice"
        );
        Path manifestPath = projectDirectory.resolve("generation-manifest.json");

        assertThat(projectDirectory.getFileName().toString()).isEqualTo("billing-service-" + requestId);
        assertThat(projectDirectory.resolve("pom.xml")).exists();
        assertThat(javaDirectory.resolve("HelloApplication.java")).exists();
        assertThat(javaDirectory.resolve("HelloController.java")).exists();
        assertThat(projectDirectory.resolve("Dockerfile")).exists();
        assertThat(projectDirectory.resolve("k8s/deployment.yaml")).exists();
        assertThat(projectDirectory.resolve("k8s/service.yaml")).exists();
        assertThat(manifestPath).exists();

        assertThat(Files.readString(projectDirectory.resolve("pom.xml")))
                .contains("<artifactId>billing-service</artifactId>")
                .contains("<generated.request-id>" + requestId + "</generated.request-id>")
                .contains("<generated.image-name>scaffoldops/billing-service:" + requestId + "</generated.image-name>");
        assertThat(Files.readString(javaDirectory.resolve("HelloApplication.java")))
                .contains("package com.scaffoldops.generated.billingservice;")
                .contains("class HelloApplication");
        assertThat(Files.readString(javaDirectory.resolve("HelloController.java")))
                .contains("@GetMapping(\"/hello\")")
                .contains("Hello from billing-service");
        assertThat(Files.readString(projectDirectory.resolve("Dockerfile")))
                .contains("FROM maven:3.9.9-eclipse-temurin-17 AS build")
                .contains("COPY --from=build /workspace/target/app.jar app.jar");
        assertThat(Files.readString(projectDirectory.resolve("k8s/deployment.yaml")))
                .contains("image: scaffoldops/billing-service:" + requestId)
                .contains("scaffoldops.io/request-id: \"" + requestId + "\"");
        assertThat(Files.readString(projectDirectory.resolve("k8s/service.yaml")))
                .contains("name: billing-service")
                .contains("targetPort: 8080");

        Map<?, ?> manifest = new ObjectMapper().readValue(manifestPath.toFile(), Map.class);
        assertThat(manifest.get("requestId")).isEqualTo(requestId.toString());
        assertThat(manifest.get("serviceName")).isEqualTo("billing-service");
        assertThat(manifest.get("packageName")).isEqualTo("com.scaffoldops.generated.billingservice");
        assertThat(manifest.get("imageName")).isEqualTo("scaffoldops/billing-service:" + requestId);
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

    @Test
    void shouldNormalizeTemplateVariablesFromServiceName() throws Exception {
        ManifestWritingProjectGenerationAdapter adapter =
                new ManifestWritingProjectGenerationAdapter(new ObjectMapper(), tempDir.toString());

        UUID requestId = UUID.randomUUID();
        GenerationRequest request = new GenerationRequest(
                requestId,
                "Payments API",
                "hello-world",
                false,
                true,
                false,
                false,
                "kubernetes",
                "REQUESTED",
                OffsetDateTime.parse("2026-03-24T12:00:00Z")
        );

        Path projectDirectory = Path.of(URI.create(adapter.generate(request).artifactReference()));

        assertThat(projectDirectory.getFileName().toString()).isEqualTo("payments-api-" + requestId);
        assertThat(projectDirectory.resolve(
                "src/main/java/com/scaffoldops/generated/paymentsapi/HelloController.java"
        )).exists();
        assertThat(Files.readString(projectDirectory.resolve("k8s/deployment.yaml")))
                .contains("image: scaffoldops/payments-api:" + requestId);
    }
}

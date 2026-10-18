package com.scaffoldops.generatorworker.infrastructure.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtifactPublisherTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldKeepFilesystemReferenceAndFinalizeManifest() throws Exception {
        GenerationArtifact artifact = artifact();
        String reference = new FilesystemArtifactPublisher(mapper).publish(artifact, artifact.imageName());
        assertThat(reference).isEqualTo(directory.toUri().toString()).startsWith("file://");
        var manifest = mapper.readTree(directory.resolve("generation-manifest.json").toFile());
        assertThat(manifest.get("artifactRef").asText()).isEqualTo(reference);
        assertThat(manifest.get("imageRef").asText()).isEqualTo(artifact.imageName());
        assertThat(manifest.get("template").asText()).isEqualTo("spring-boot-hello-world");
    }

    @Test
    void shouldUploadZipContainingProjectAndFinalManifest() throws Exception {
        GenerationArtifact artifact = artifact();
        MinioClient client = mock(MinioClient.class);
        String reference = "s3://scaffoldops-artifacts/" + artifact.requestId() + "/project.zip";
        Path[] temporaryZip = new Path[1];
        doAnswer(invocation -> {
            UploadObjectArgs args = invocation.getArgument(0);
            assertThat(args.bucket()).isEqualTo("scaffoldops-artifacts");
            assertThat(args.object()).isEqualTo(artifact.requestId() + "/project.zip");
            temporaryZip[0] = Path.of(args.filename());
            try (ZipFile zip = new ZipFile(args.filename())) {
                assertThat(zip.getEntry("pom.xml")).isNotNull();
                var manifest = mapper.readTree(zip.getInputStream(zip.getEntry("generation-manifest.json")));
                assertThat(manifest.get("artifactRef").asText()).isEqualTo(reference);
                assertThat(manifest.get("imageRef").asText()).isEqualTo(artifact.imageName());
            }
            return null;
        }).when(client).uploadObject(any(UploadObjectArgs.class));
        assertThat(new MinioArtifactPublisher(client, mapper, "scaffoldops-artifacts")
                .publish(artifact, artifact.imageName())).isEqualTo(reference);
        assertThat(temporaryZip[0]).doesNotExist();
        verify(client).uploadObject(any(UploadObjectArgs.class));
    }

    @Test
    void shouldPropagateUploadFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.uploadObject(any())).thenThrow(new java.io.IOException("unavailable"));
        GenerationArtifact artifact = artifact();
        assertThatThrownBy(() -> new MinioArtifactPublisher(client, mapper, "scaffoldops-artifacts")
                .publish(artifact, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to publish artifact").hasRootCauseMessage("unavailable");
    }

    private GenerationArtifact artifact() throws Exception {
        Files.writeString(directory.resolve("generation-manifest.json"),
                "{\"template\":\"spring-boot-hello-world\",\"requestId\":\"test\"}");
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        return new GenerationArtifact(UUID.randomUUID(), "spring-boot-project", directory.toUri().toString(),
                "billing", "registry.local/generated/billing:test", OffsetDateTime.now());
    }
}

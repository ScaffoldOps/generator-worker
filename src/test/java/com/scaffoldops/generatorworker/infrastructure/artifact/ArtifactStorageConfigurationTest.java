package com.scaffoldops.generatorworker.infrastructure.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.application.port.out.ArtifactPublisher;
import com.scaffoldops.generatorworker.infrastructure.config.MinioConfiguration;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStorageConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MinioConfiguration.class, FilesystemArtifactPublisher.class, MinioArtifactPublisher.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void shouldDefaultToFilesystemWithoutMinioClient() {
        context.run(application -> {
            assertThat(application).hasSingleBean(ArtifactPublisher.class).doesNotHaveBean(MinioClient.class);
            assertThat(application.getBean(ArtifactPublisher.class)).isInstanceOf(FilesystemArtifactPublisher.class);
        });
    }

    @Test
    void shouldSelectMinioWithConfiguredHttpsEndpoint() {
        context.withPropertyValues("app.artifact.storage-type=minio",
                "app.artifact.minio.endpoint=https://localhost:9000",
                "app.artifact.minio.access-key=test-key", "app.artifact.minio.secret-key=test-secret")
                .run(application -> {
                    assertThat(application).hasSingleBean(ArtifactPublisher.class).hasSingleBean(MinioClient.class);
                    assertThat(application.getBean(ArtifactPublisher.class)).isInstanceOf(MinioArtifactPublisher.class);
                });
    }
}
